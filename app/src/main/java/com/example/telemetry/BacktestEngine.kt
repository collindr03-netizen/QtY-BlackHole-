package com.example.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

// ==========================================
// BACKTESTING DATA STRUCTURES
// ==========================================

data class BacktestTrade(
    val timestampMs: Long,
    val type: String, // "BUY-LONG" or "SELL-SHORT"
    val entryPrice: Double,
    val exitPrice: Double,
    val holdingPeriodSec: Double,
    val grossReturnBps: Double,
    val feesPaidBps: Double,
    val slippageBps: Double,
    val netReturnBps: Double,
    val exitReason: String // "PROFIT_TARGET", "STOP_LOSS", "TIME_OUT"
)

data class BacktestRunResult(
    val tradeLog: List<BacktestTrade>,
    val cumulativeNetPnL: Double, // in basis points
    val maxDrawdown: Double,      // in basis points
    val winRate: Double,          // 0.0 to 1.0
    val totalTrades: Int
)

data class L2Tick(
    val timestampMs: Long,
    val bestBid: Double,
    val bestAsk: Double,
    val bidSize: Double,
    val askSize: Double,
    val lastTradePrice: Double,
    val lastTradeSize: Double,
    val lastTradeSide: String // "BUY" (aggressive buyer, hits ask) or "SELL" (aggressive seller, hits bid)
)

// ==========================================
// HIGH-PERFORMANCE EVENT-DRIVEN ENGINE
// ==========================================

class BacktestEngine(
    val makerFeeBps: Double = -0.10, // Maker Rebate (negative is credit)
    val takerFeeBps: Double = 0.40,  // Taker Fee
    val slippageBps: Double = 0.15,  // Taker Slippage
    val profitTargetBps: Double = 6.0,
    val stopLossBps: Double = -3.0,
    val maxHoldingTimeMs: Long = 18000
) {
    private val _status = MutableStateFlow("IDLE") // IDLE, RUNNING, PAUSED, COMPLETED
    val status: StateFlow<String> = _status.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentResult = MutableStateFlow<BacktestRunResult?>(null)
    val currentResult: StateFlow<BacktestRunResult?> = _currentResult.asStateFlow()

    private var replaySpeed = 10 // Variable replay speed (1x to 100x)
    @Volatile
    private var isPaused = false
    private var backtestJob: Job? = null

    fun setSpeed(speed: Int) {
        replaySpeed = speed.coerceIn(1, 100)
    }

    fun pause() {
        if (_status.value == "RUNNING") {
            isPaused = true
            _status.value = "PAUSED"
        }
    }

    fun resume() {
        if (_status.value == "PAUSED") {
            isPaused = false
            _status.value = "RUNNING"
        }
    }

    fun cancel() {
        backtestJob?.cancel()
        _status.value = "IDLE"
        _progress.value = 0f
    }

    /**
     * Executes backtest in background dispatcher without blockages.
     */
    fun startBacktest(
        scope: CoroutineScope,
        strategyId: String,
        dataset: List<L2Tick>,
        onProgressUpdate: (Float) -> Unit = {}
    ) {
        if (_status.value == "RUNNING") return
        isPaused = false
        _status.value = "RUNNING"
        _progress.value = 0f

        backtestJob = scope.launch(Dispatchers.Default) {
            val trades = mutableListOf<BacktestTrade>()
            var activePosition: ActivePosition? = null

            val totalTicks = dataset.size
            var currentTickIdx = 0

            while (currentTickIdx < totalTicks && isActive) {
                // Pause handler loop
                while (isPaused && isActive) {
                    delay(100)
                }

                val tick = dataset[currentTickIdx]

                // Process Active Position if any
                if (activePosition != null) {
                    val result = checkPositionExit(activePosition, tick)
                    if (result != null) {
                        trades.add(result)
                        activePosition = null
                    }
                } else {
                    // Try to trigger entries based on Strategy Logic
                    val signal = evaluateStrategy(strategyId, tick)
                    if (signal != null) {
                        activePosition = initializePosition(signal, tick)
                    }
                }

                currentTickIdx++
                val p = currentTickIdx.toFloat() / totalTicks.toFloat()
                _progress.value = p
                onProgressUpdate(p)

                // Replay speed throttling (delay calculation)
                // 1x = 10ms per tick, 100x = 0.1ms per tick
                if (currentTickIdx % 10 == 0) {
                    val sleepMs = max(1L, (100L / replaySpeed))
                    delay(sleepMs)
                }
            }

            // Flush out remaining position at the end of the backtest
            if (activePosition != null && dataset.isNotEmpty()) {
                val lastTick = dataset.last()
                val remainingResult = forceExitPosition(activePosition, lastTick, "TIME_OUT")
                trades.add(remainingResult)
            }

            // Compute cumulative scorecard metrics
            val runResult = calculatePerformanceScorecard(trades)
            _currentResult.value = runResult
            _status.value = "COMPLETED"
        }
    }

    // ==========================================
    // MICROSTRUCTURE STRATEGY & QUEUE LOGIC
    // ==========================================

    private fun evaluateStrategy(strategyId: String, tick: L2Tick): String? {
        // High-conviction threshold evaluation following Rule 1 & Section 4
        // We simulate entries when bid/ask metrics show extreme directional asymmetry
        val midPrice = (tick.bestBid + tick.bestAsk) / 2.0
        val imbalance = (tick.bidSize - tick.askSize) / (tick.bidSize + tick.askSize + 1e-9)

        return when (strategyId) {
            "DUAL_AI_NOMINAL_DUO" -> {
                // Generates entries under highly laminar momentum
                if (imbalance > 0.72) "BUY-LONG"
                else if (imbalance < -0.72) "SELL-SHORT"
                else null
            }
            "OFI_REGIME_SURVIVAL" -> {
                if (imbalance > 0.85) "BUY-LONG"
                else if (imbalance < -0.85) "SELL-SHORT"
                else null
            }
            "LYAPUNOV_SCALPER" -> {
                if (imbalance > 0.60) "BUY-LONG"
                else if (imbalance < -0.60) "SELL-SHORT"
                else null
            }
            else -> null
        }
    }

    data class ActivePosition(
        val type: String, // "BUY-LONG", "SELL-SHORT"
        val entryPrice: Double,
        val entryTimestampMs: Long,
        var entryFilled: Boolean,
        var queueAhead: Double, // Queue priority size ahead of us
        val isLimitOrder: Boolean
    )

    internal fun initializePosition(type: String, tick: L2Tick): ActivePosition {
        // Enforces maker priority (Limit Order placement at top of book)
        val limitPrice = if (type == "BUY-LONG") tick.bestBid else tick.bestAsk
        val queueAhead = if (type == "BUY-LONG") tick.bidSize else tick.askSize
        
        return ActivePosition(
            type = type,
            entryPrice = limitPrice,
            entryTimestampMs = tick.timestampMs,
            entryFilled = false,
            queueAhead = queueAhead,
            isLimitOrder = true
        )
    }

    internal fun checkPositionExit(pos: ActivePosition, tick: L2Tick): BacktestTrade? {
        // 1. Evaluate Limit Entry Queue Priority if not filled yet
        if (!pos.entryFilled) {
            if (pos.type == "BUY-LONG") {
                // If aggressive sellers trade, they consume depth ahead of us in the queue
                if (tick.lastTradeSide == "SELL" && tick.lastTradePrice <= pos.entryPrice) {
                    pos.queueAhead -= tick.lastTradeSize
                }
                // If the bid price sweeps through or equals and queue is exhausted, we get filled
                if (pos.queueAhead <= 0 || tick.bestBid > pos.entryPrice) {
                    pos.entryFilled = true
                }
            } else { // SELL-SHORT
                // If aggressive buyers trade, they consume resting depth ahead of us
                if (tick.lastTradeSide == "BUY" && tick.lastTradePrice >= pos.entryPrice) {
                    pos.queueAhead -= tick.lastTradeSize
                }
                if (pos.queueAhead <= 0 || tick.bestAsk < pos.entryPrice) {
                    pos.entryFilled = true
                }
            }
            return null // Entry not filled yet, cannot evaluate exits
        }

        // 2. Evaluate Exit Targets: Upper profit target (+6 bps) or Stop Loss (-3 bps)
        val currentMid = (tick.bestBid + tick.bestAsk) / 2.0
        val grossReturnPct = if (pos.type == "BUY-LONG") {
            (currentMid - pos.entryPrice) / pos.entryPrice
        } else {
            (pos.entryPrice - currentMid) / pos.entryPrice
        }
        val grossReturnBps = grossReturnPct * 10000.0

        val holdingTimeMs = tick.timestampMs - pos.entryTimestampMs

        return when {
            grossReturnBps >= profitTargetBps -> {
                // Exits with Taker Market Order to capture immediate breakout
                forceExitPosition(pos, tick, "PROFIT_TARGET")
            }
            grossReturnBps <= stopLossBps -> {
                // Strict stop-loss boundary hit, urgent market exit
                forceExitPosition(pos, tick, "STOP_LOSS")
            }
            holdingTimeMs >= maxHoldingTimeMs -> {
                // 18-second holding horizon breached, auto-flush
                forceExitPosition(pos, tick, "TIME_OUT")
            }
            else -> null // Keep holding
        }
    }

    internal fun forceExitPosition(pos: ActivePosition, tick: L2Tick, reason: String): BacktestTrade {
        // Enforces realistic friction logic:
        // Entry: Limit Order (Maker) -> Pays Maker rebate
        // Exit: Market Order (Taker) -> Pays Taker fee, top-of-book slippage and bid-ask spread
        
        val exitPrice = if (pos.type == "BUY-LONG") {
            // We exit LONG by selling into the best bid (subject to taker slippage)
            tick.bestBid - (tick.bestBid * (slippageBps / 10000.0))
        } else {
            // We exit SHORT by buying back from the best ask (subject to taker slippage)
            tick.bestAsk + (tick.bestAsk * (slippageBps / 10000.0))
        }

        val grossReturnPct = if (pos.type == "BUY-LONG") {
            (exitPrice - pos.entryPrice) / pos.entryPrice
        } else {
            (pos.entryPrice - exitPrice) / pos.entryPrice
        }
        val grossReturnBps = grossReturnPct * 10000.0

        // Fees: Maker Fee on entry + Taker Fee on exit
        val feesPaidBps = makerFeeBps + takerFeeBps
        val netReturnBps = grossReturnBps - feesPaidBps

        val holdingPeriodSec = (tick.timestampMs - pos.entryTimestampMs) / 1000.0

        return BacktestTrade(
            timestampMs = tick.timestampMs,
            type = pos.type,
            entryPrice = pos.entryPrice,
            exitPrice = exitPrice,
            holdingPeriodSec = holdingPeriodSec,
            grossReturnBps = grossReturnBps,
            feesPaidBps = feesPaidBps,
            slippageBps = slippageBps,
            netReturnBps = netReturnBps,
            exitReason = reason
        )
    }

    private fun calculatePerformanceScorecard(trades: List<BacktestTrade>): BacktestRunResult {
        if (trades.isEmpty()) return BacktestRunResult(emptyList(), 0.0, 0.0, 0.0, 0)

        var cumulativeBps = 0.0
        var maxPnL = 0.0
        var maxDD = 0.0
        var wins = 0

        for (trade in trades) {
            cumulativeBps += trade.netReturnBps
            if (cumulativeBps > maxPnL) {
                maxPnL = cumulativeBps
            }
            val dd = maxPnL - cumulativeBps
            if (dd > maxDD) {
                maxDD = dd
            }
            if (trade.netReturnBps > 0.0) {
                wins++
            }
        }

        val winRate = wins.toDouble() / trades.size

        return BacktestRunResult(
            tradeLog = trades,
            cumulativeNetPnL = cumulativeBps,
            maxDrawdown = maxDD,
            winRate = winRate,
            totalTrades = trades.size
        )
    }
}

// ==========================================
// HIGH-FIDELITY SYNTHETIC DATA GENERATOR
// ==========================================

object BacktestDataGenerator {
    /**
     * Generates a realistic L2 tick log stream with 1000 ticks
     */
    fun generateL2TickStream(basePrice: Double = 96450.0, seed: Long = 42): List<L2Tick> {
        val list = mutableListOf<L2Tick>()
        val random = java.util.Random(seed)
        var currentPrice = basePrice
        var timestamp = System.currentTimeMillis() - 3600 * 1000 // starts 1 hour ago

        for (i in 1..1000) {
            timestamp += 500 + random.nextInt(1500) // ticks arrive every 0.5 to 2 seconds

            // Simple random walk with laminar drift tendencies
            val drift = if (i in 300..450) 1.5 else if (i in 600..750) -1.2 else 0.0
            val noise = (random.nextDouble() - 0.5) * 5.0
            currentPrice += drift + noise

            // Spread is around 1.0 to 1.5 Bps
            val spreadAmt = currentPrice * (0.0001) // 1.0 Bps
            val bestBid = currentPrice - (spreadAmt / 2.0)
            val bestAsk = currentPrice + (spreadAmt / 2.0)

            // Volumes at the inside levels
            val bidSize = 1.0 + random.nextDouble() * 15.0
            val askSize = 1.0 + random.nextDouble() * 15.0

            // Simulate trade executions hitting the inside levels
            val aggressiveSide = if (random.nextBoolean()) "BUY" else "SELL"
            val lastTradePrice = if (aggressiveSide == "BUY") bestAsk else bestBid
            val lastTradeSize = 0.1 + random.nextDouble() * 5.0

            list.add(
                L2Tick(
                    timestampMs = timestamp,
                    bestBid = bestBid,
                    bestAsk = bestAsk,
                    bidSize = bidSize,
                    askSize = askSize,
                    lastTradePrice = lastTradePrice,
                    lastTradeSize = lastTradeSize,
                    lastTradeSide = aggressiveSide
                )
            )
        }

        return list
    }
}
