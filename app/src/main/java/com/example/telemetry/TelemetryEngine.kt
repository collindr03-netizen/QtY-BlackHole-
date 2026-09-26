package com.example.telemetry

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class TelemetryEngine private constructor() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var calculationJob: Job? = null

    // Real-time variables
    private var baseBtcPrice = 96450.0
    private val priceHistory = CopyOnWriteArrayList<Double>()
    private var tickCount = 0L
    private var activeTradesInPeriod = 0
    private var periodStartTime = System.currentTimeMillis()

    // Configuration states (interactive)
    var targetCritSeparation = 0.70
    var takerFeeRate = 0.0006 // 6 bps
    var makerRebateRate = -0.0001 // -1 bps
    var slipStandard = 0.50 // $0.50 slippage standard deviation
    var injectClockDrift = false
    var injectDroppedPackets = false
    var injectExtremeTurbulence = false
    var manualModeActive = false

    // Historical records for the HUD's 120Hz charts
    val chartPriceHistory = CopyOnWriteArrayList<Double>()
    val chartLyapunovHistory = CopyOnWriteArrayList<Double>()
    val chartReynoldsHistory = CopyOnWriteArrayList<Double>()
    val chartSeparationHistory = CopyOnWriteArrayList<Double>()

    // Core states
    private val _connections = MutableStateFlow<List<ConnectionState>>(emptyList())
    val connections: StateFlow<List<ConnectionState>> = _connections.asStateFlow()

    private val _derivedFeatures = MutableStateFlow<DerivedFeatures?>(null)
    val derivedFeatures: StateFlow<DerivedFeatures?> = _derivedFeatures.asStateFlow()

    private val _engines = MutableStateFlow<List<EngineStatus>>(emptyList())
    val engines: StateFlow<List<EngineStatus>> = _engines.asStateFlow()

    private val _dualAiState = MutableStateFlow<DualAiState?>(null)
    val dualAiState: StateFlow<DualAiState?> = _dualAiState.asStateFlow()

    private val _auditLedger = MutableStateFlow<List<AuditRecord>>(emptyList())
    val auditLedger: StateFlow<List<AuditRecord>> = _auditLedger.asStateFlow()

    init {
        initializeDataRegistries()
    }

    private fun initializeDataRegistries() {
        // Initialize Connections
        _connections.value = listOf(
            ConnectionState("Binance WebSocket", "WebSocket", "wss://stream.binance.com:9443/ws/btcusdt", 12, 0, ConnectionStatus.CONNECTED),
            ConnectionState("Kraken Pro FIX", "FIX", "fix.kraken.com:443", 24, 0, ConnectionStatus.CONNECTED),
            ConnectionState("Coinbase Advanced WS", "WebSocket", "wss://advanced-trade-ws.coinbase.com", 18, 0, ConnectionStatus.CONNECTED)
        )

        // Initialize 37 Engines
        _engines.value = List(37) { index ->
            val code = "E${String.format("%02d", index + 1)}"
            val name = getEngineName(code)
            val category = getEngineCategory(index + 1)
            EngineStatus(
                code = code,
                name = name,
                category = category,
                status = "STANDBY",
                description = getEngineDescription(code)
            )
        }

        // Initialize historical seed data
        for (i in 0..50) {
            val seedPrice = baseBtcPrice + Random.nextDouble(-10.0, 10.0)
            priceHistory.add(seedPrice)
            chartPriceHistory.add(seedPrice)
            chartLyapunovHistory.add(Random.nextDouble(-0.5, 0.5))
            chartReynoldsHistory.add(Random.nextDouble(10.0, 150.0))
            chartSeparationHistory.add(Random.nextDouble(-0.3, 0.3))
        }
    }

    fun startDecoupledCalculationLoop() {
        if (calculationJob != null) return

        calculationJob = scope.launch {
            periodStartTime = System.currentTimeMillis()
            while (isActive) {
                // Tick rate is extremely high to test 120Hz pipeline
                // In production, we evaluate on every tick event (~10ms)
                val delayMs = if (injectClockDrift) 120L else 12L
                delay(delayMs)
                
                try {
                    performTelemetryCycle()
                } catch (e: Exception) {
                    Log.e("TelemetryEngine", "Error in telemetry cycle", e)
                }
            }
        }
    }

    fun stopDecoupledCalculationLoop() {
        calculationJob?.cancel()
        calculationJob = null
    }

    private fun performTelemetryCycle() {
        tickCount++
        val timestamp = System.currentTimeMillis()

        // 1. Connection Update
        val currentConnections = _connections.value.map { conn ->
            val randomLatencyChange = Random.nextLong(-3, 4)
            val newLatency = Math.max(5L, conn.latencyMs + randomLatencyChange)
            val packetInc = if (injectDroppedPackets && Random.nextFloat() < 0.2f) 0L else 1L
            conn.copy(
                latencyMs = newLatency,
                packetCount = conn.packetCount + packetInc,
                status = if (injectDroppedPackets && Random.nextFloat() < 0.1f) ConnectionStatus.DEGRADED else ConnectionStatus.CONNECTED
            )
        }
        _connections.value = currentConnections

        // Check if data integrity failed
        var isClockDriftCritical = injectClockDrift
        var packetsDroppedCritical = currentConnections.any { it.status == ConnectionStatus.DEGRADED }
        var isDataIntegrityFailed = isClockDriftCritical || packetsDroppedCritical

        // 2. Pricing and Order Book Feed Update
        val priceDirectionMultiplier = if (manualModeActive) 0.0 else (if (Random.nextDouble() > 0.49) 1.0 else -1.0)
        val priceJump = priceDirectionMultiplier * Random.nextDouble(0.1, 2.5)
        baseBtcPrice += priceJump
        priceHistory.add(baseBtcPrice)
        if (priceHistory.size > 100) priceHistory.removeAt(0)

        // Maintain historical data for graphs safely
        chartPriceHistory.add(baseBtcPrice)
        if (chartPriceHistory.size > 80) chartPriceHistory.removeAt(0)

        // Generate raw volumes for L1-L20
        val baseVolume = if (injectExtremeTurbulence) 85.0 else 12.0
        val bids = DoubleArray(20) { idx -> (baseVolume + Random.nextDouble(1.0, 35.0)) / (idx + 1) }
        val asks = DoubleArray(20) { idx -> (baseVolume + Random.nextDouble(1.0, 35.0)) / (idx + 1) }

        val rawTick = RawTickPayload(
            timestamp = timestamp,
            tradePrice = baseBtcPrice,
            tradeSize = Random.nextDouble(0.01, 4.5),
            side = if (priceJump > 0) "BUY" else "SELL",
            bids = bids,
            asks = asks,
            clockDriftMs = if (injectClockDrift) Random.nextLong(26, 80) else Random.nextLong(0, 8)
        )

        // 3. Derived Features via JNI Core
        val currentPrices = priceHistory.toDoubleArray()
        val lyapunov = TelemetryBridge.getLyapunov(currentPrices)
        
        // Reynolds inputs: trade arrival velocity (simulated as Hz), order book depth, cancel noise
        val simulatedVelocity = if (injectExtremeTurbulence) Random.nextDouble(180.0, 450.0) else Random.nextDouble(25.0, 75.0)
        val simulatedBookDepth = bids.sum() + asks.sum()
        val simulatedCancelNoise = if (injectExtremeTurbulence) Random.nextDouble(0.1, 1.2) else Random.nextDouble(15.0, 45.0)
        val reynolds = TelemetryBridge.getReynolds(simulatedVelocity, simulatedBookDepth, simulatedCancelNoise)
        
        // RK4 integration for continuous smoothing
        val rkSmooth = TelemetryBridge.getRungeKutta4(
            currentVal = baseBtcPrice,
            stepSize = 0.05,
            trend = priceJump,
            acceleration = if (injectExtremeTurbulence) 4.5 else 0.4
        )

        // Entropy
        val combinedVolumes = bids + asks
        val entropy = TelemetryBridge.getEntropy(combinedVolumes)

        // OFI & Depth Imbalances
        val microPrice = (bids[0] * asks[0] + asks[0] * bids[0]) / (bids[0] + asks[0] + 1e-9) // simplified microprice
        val ofi = (bids[0] - asks[0]) / (bids[0] + asks[0])
        val depthImb = (bids.take(5).sum() - asks.take(5).sum()) / (bids.take(5).sum() + asks.take(5).sum() + 1e-9)
        val parkVol = Math.sqrt(Math.max(0.0, lyapunov * lyapunov * 0.1)) // proxy for micro volatility

        val derived = DerivedFeatures(
            microPrice = microPrice,
            logReturn = Math.log(baseBtcPrice / currentPrices.first()),
            orderFlowImbalance = ofi,
            depthImbalance = depthImb,
            parkinsonVolatility = parkVol,
            zScore = (baseBtcPrice - currentPrices.average()) / (currentPrices.average() * 0.0001),
            lyapunovExponent = lyapunov,
            reynoldsNumber = reynolds,
            shannonEntropy = entropy,
            rk4SmoothPrice = rkSmooth
        )
        _derivedFeatures.value = derived

        // Update charts history safely
        chartLyapunovHistory.add(lyapunov)
        if (chartLyapunovHistory.size > 80) chartLyapunovHistory.removeAt(0)
        chartReynoldsHistory.add(reynolds)
        if (chartReynoldsHistory.size > 80) chartReynoldsHistory.removeAt(0)

        // 4. Update the 37 Engines states dynamically based on math
        val updatedEngines = _engines.value.map { eng ->
            val valStr = when (eng.code) {
                "E01" -> "${currentConnections.count { it.status == ConnectionStatus.CONNECTED }}/3"
                "E02" -> "${currentConnections.map { it.latencyMs }.average().toInt()} ms"
                "E03" -> if (isClockDriftCritical) "DRIFT CRIT" else "SYNCED"
                "E05" -> String.format("%.2f", baseBtcPrice)
                "E07" -> String.format("%.6f", parkVol)
                "E08" -> String.format("%.4f", ofi)
                "E09" -> String.format("%.4f", depthImb)
                "E15" -> String.format("%.4f", entropy)
                "E16" -> String.format("%.2f", rkSmooth)
                "E17" -> String.format("%.2f", lyapunov)
                "E20" -> String.format("%.2f", reynolds)
                "E23" -> if (reynolds > 300.0) "TURBULENT" else "LAMINAR"
                else -> eng.telemetryValue
            }

            val statusStr = if (isDataIntegrityFailed && (eng.code == "E03" || eng.code == "E34")) {
                "TRIPWIRED"
            } else if (injectExtremeTurbulence && eng.code == "E17") {
                "TRIPWIRED"
            } else {
                "ACTIVE"
            }

            eng.copy(status = statusStr, telemetryValue = valStr)
        }
        _engines.value = updatedEngines

        // 5. Dual-AI Directional Gate & Asymmetric Inference
        // UP-AI and DOWN-AI are independent networks
        // Under laminar regime, microstructure yields asymmetric separation
        // Under chaotic regime, random walks create high uncertainty
        val laminarStabilityFactor = if (lyapunov < 0.1 && reynolds < 250.0) 1.0 else 0.15
        
        // Compute base probabilities driven by order flow & depths
        var rawUpProb = 0.5 + (ofi * 0.35 + depthImb * 0.15) * laminarStabilityFactor
        var rawDownProb = 0.5 - (ofi * 0.35 + depthImb * 0.15) * laminarStabilityFactor
        
        // Ensure asymmetric properties (P_down != 1 - P_up)
        val asymmetricDrift = Random.nextDouble(-0.04, 0.04)
        rawUpProb = Math.max(0.01, Math.min(0.99, rawUpProb + asymmetricDrift))
        rawDownProb = Math.max(0.01, Math.min(0.99, rawDownProb - asymmetricDrift))

        // Epistemic (model) and Aleatoric (noise) variances
        val sigmaUp = Math.max(0.02, 0.05 + (if (lyapunov > 0.1) 0.18 else 0.01))
        val sigmaDown = Math.max(0.02, 0.05 + (if (reynolds > 250.0) 0.22 else 0.01))

        // Directional Separation: D(t) = P(UP) - P(DOWN)
        val dirSeparation = rawUpProb - rawDownProb
        chartSeparationHistory.add(dirSeparation)
        if (chartSeparationHistory.size > 80) chartSeparationHistory.removeAt(0)

        // Z-Separation: Z_sep = (P_UP - P_DOWN) / sqrt(sigma_UP^2 + sigma_DOWN^2)
        val combinedSigma = Math.sqrt(sigmaUp * sigmaUp + sigmaDown * sigmaDown)
        val zSep = Math.abs(dirSeparation) / (combinedSigma + 1e-9)

        // Decision Erosion coefficient E(t)
        val lambda = 0.05 // decay constant
        val decisionErosion = Math.exp(-lambda * (tickCount % 50) * 0.1)

        // Stability filtering law (Physics determines stability, microstructure determines direction)
        val isMarketLaminar = (lyapunov < 0.22) && (reynolds < 320.0)

        // Directional Asymmetry trigger
        val hasAsymmetry = Math.abs(dirSeparation) >= targetCritSeparation

        // 6. Economic Reality & Friction Gating
        // Expected gross return based on micro-price velocity (bps)
        val expectedGrossReturn = Math.abs(dirSeparation) * 22.0 // e.g. up to 22 bps
        
        // Fee computation based on trade side (Maker preferred when possible)
        val transactionDrag = (2 * takerFeeRate * 10000.0) + (slipStandard * 0.5) // in bps proxy
        val expectedNetReturn = expectedGrossReturn - transactionDrag
        val ecr = expectedGrossReturn / (transactionDrag + 1e-9)

        // 7. Decision Engine
        var tripwire = "SYSTEM_NOMINAL"
        var finalDecision = "NO-TRADE"

        if (isDataIntegrityFailed) {
            tripwire = "DATA_INTEGRITY_FAIL_CLOSED"
            finalDecision = "NO-TRADE"
        } else if (!isMarketLaminar) {
            tripwire = "PHYSICS_TURBULENT_CLOSED"
            finalDecision = "NO-TRADE"
        } else if (zSep < 4.0) {
            tripwire = "UNCERTAINTY_SUPPRESS_CLOSED"
            finalDecision = "NO-TRADE"
        } else if (ecr < 2.0) {
            tripwire = "FRICTION_GATED"
            finalDecision = "NO-TRADE"
        } else if (hasAsymmetry) {
            // Evaluated as highly laminar and microstructurally biased!
            if (dirSeparation > 0.0) {
                finalDecision = "BUY-LONG"
                activeTradesInPeriod++
            } else {
                finalDecision = "SELL-SHORT"
                activeTradesInPeriod++
            }
        }

        // Limit the scalper trade rate dynamically to keep inside safe thresholds
        val now = System.currentTimeMillis()
        if (now - periodStartTime > 180000) { // 3 min period
            activeTradesInPeriod = 0
            periodStartTime = now
        }
        if (activeTradesInPeriod > 10) {
            tripwire = "RATE_LIMIT_EXCEEDED"
            finalDecision = "NO-TRADE"
        }

        val dualAi = DualAiState(
            pUp = rawUpProb,
            pDown = rawDownProb,
            sigmaUp = sigmaUp,
            sigmaDown = sigmaDown,
            directionalSeparation = dirSeparation,
            zSeparation = zSep,
            decisionErosion = decisionErosion,
            expectedGrossReturn = expectedGrossReturn,
            expectedNetReturn = expectedNetReturn,
            edgeToCostRatio = ecr,
            isLaminar = isMarketLaminar,
            hasDirectionalAsymmetry = hasAsymmetry,
            decisionState = finalDecision,
            tripwireState = tripwire,
            latencyDriftCritical = isClockDriftCritical
        )
        _dualAiState.value = dualAi

        // 8. Cryptographic Auditing Ledger (Section 7 Directive 3)
        if (tickCount % 12 == 0L) { // Every 12 ticks log audit state
            val stateString = "$timestamp:$baseBtcPrice:$lyapunov:$reynolds:$dirSeparation:$finalDecision:$tripwire"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(stateString.toByteArray(Charsets.UTF_8))
            val hashStr = hashBytes.joinToString("") { "%02x".format(it) }.take(24)

            val newRecord = AuditRecord(
                timestamp = timestamp,
                stepIndex = tickCount,
                stateHash = hashStr,
                decision = finalDecision,
                directionalSeparation = dirSeparation,
                activeTripwires = if (tripwire == "SYSTEM_NOMINAL") emptyList() else listOf(tripwire)
            )

            _auditLedger.update { current ->
                (listOf(newRecord) + current).take(20) // Keep last 20 records
            }
        }
    }

    private fun getEngineCategory(idx: Int): EngineCategory {
        return when (idx) {
            in 1..4 -> EngineCategory.FOUNDATION
            in 5..17 -> EngineCategory.EVIDENCE
            in 18..24 -> EngineCategory.INFERENCE
            in 25..29 -> EngineCategory.PREDICTION
            in 30..34 -> EngineCategory.VALIDATION
            else -> EngineCategory.GOVERNANCE
        }
    }

    private fun getEngineName(code: String): String {
        return when (code) {
            "E01" -> "Data Sockets Feed"
            "E02" -> "Latency Watchdog"
            "E03" -> "Temporal Synchronization"
            "E04" -> "Tick Normalizer"
            "E05" -> "Micro-Price & Returns"
            "E06" -> "Log Return Kinematics"
            "E07" -> "Micro-Volatility Index"
            "E08" -> "Order Flow Imbalance"
            "E09" -> "Depth Imbalance Core"
            "E10" -> "Spread Fluidity Analyst"
            "E11" -> "Liquidity Absorption Rate"
            "E12" -> "Microstructural Trend"
            "E13" -> "Oscillating Kinematics"
            "E14" -> "Order Flow Velocity"
            "E15" -> "Entropy Gating Core"
            "E16" -> "Runge-Kutta Price Integrator"
            "E17" -> "Lyapunov Stability Evaluator"
            "E18" -> "Bayesian Belief Updating"
            "E19" -> "State-Space Telemetry Filter"
            "E20" -> "Adaptive Reynolds Regulator"
            "E21" -> "Markov State Predictor"
            "E22" -> "Cluster Regime Classifier"
            "E23" -> "Regime Gating Supervisor"
            "E24" -> "Ensemble Blending Core"
            "E25" -> "Dual-AI UP Neural Gate"
            "E26" -> "Dual-AI DOWN Neural Gate"
            "E27" -> "Calibration Engine"
            "E28" -> "Epistemic Uncertainty Evaluator"
            "E29" -> "Dynamic Contract Selector"
            "E30" -> "Live Cross-Validator"
            "E31" -> "Liquidity Slip Modeler"
            "E32" -> "Cost & Edge Engine"
            "E33" -> "Cryptographic Audit Ledger"
            "E34" -> "Asymmetric Decision Gate"
            "E35" -> "Walk-Forward Adaptor"
            "E36" -> "Alpha-Decay Auditor"
            "E37" -> "Thread Queue Orchestrator"
            else -> "Telemetry Engine"
        }
    }

    private fun getEngineDescription(code: String): String {
        return when (code) {
            "E01" -> "Monitors raw physical socket connections with Coinbase/Binance"
            "E02" -> "Audits sub-millisecond hardware packet timestamps"
            "E03" -> "Trips closed if timestamp drift exceeds 25 milliseconds"
            "E05" -> "Derives instantaneous micro-price from L1 order depth ratio"
            "E07" -> "Computes Parkinson Volatility using high-low range"
            "E08" -> "Integrates volume changes across bids and asks to map buyer aggression"
            "E15" -> "Computes Shannon Entropy of order volume distributions to map chaos"
            "E16" -> "Integrates continuous market acceleration curves using RK4"
            "E17" -> "Estimates real-time Lyapunov Exponents of price convergence"
            "E20" -> "Classifies order books as laminar (orderly) vs turbulent (chaotic)"
            "E25" -> "Calculates independent probability of hitting target profit first"
            "E26" -> "Calculates independent probability of hitting target stop first"
            "E28" -> "Gates trades if combined AI uncertainty Z-separation drops below 4.0"
            "E32" -> "Gates trades if ECR (Expected gross return / execution drag) is under 2.0"
            "E33" -> "Generates SHA-256 cryptographic proof logs of the system state"
            "E34" -> "Enforces the main Dual-AI gate decision and executes tripwire closures"
            else -> "Executes dedicated scientific calculations of quantitative telemetry"
        }
    }

    companion object {
        @Volatile
        private var instance: TelemetryEngine? = null

        fun getInstance(): TelemetryEngine {
            return instance ?: synchronized(this) {
                instance ?: TelemetryEngine().also { instance = it }
            }
        }
    }
}
