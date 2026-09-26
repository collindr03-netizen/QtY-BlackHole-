package com.example.telemetry

import org.junit.Assert.*
import org.junit.Test

class BacktestEngineTest {

    @Test
    fun testFrictionReducesGrossReturns() {
        // 1. Configure Engine with low friction (maker rebate + low taker fee + low slippage)
        val lowFrictionEngine = BacktestEngine(
            makerFeeBps = -0.10, // Maker rebate
            takerFeeBps = 0.40,  // Taker fee
            slippageBps = 0.15,  // Taker slippage
            profitTargetBps = 6.0,
            stopLossBps = -3.0
        )

        // 2. Configure Engine with high friction (high maker + very high taker + high slippage)
        val highFrictionEngine = BacktestEngine(
            makerFeeBps = 0.50,  // High maker fee (no rebate)
            takerFeeBps = 2.00,  // Very high taker fee
            slippageBps = 1.50,  // High slippage
            profitTargetBps = 6.0,
            stopLossBps = -3.0
        )

        // Create base tick and exit tick where bid-ask is wider
        val tickEntry = L2Tick(
            timestampMs = 1000L,
            bestBid = 10000.0,
            bestAsk = 10010.0,
            bidSize = 10.0,
            askSize = 10.0,
            lastTradePrice = 10005.0,
            lastTradeSize = 1.0,
            lastTradeSide = "BUY"
        )

        val tickExit = L2Tick(
            timestampMs = 5000L,
            bestBid = 10100.0,
            bestAsk = 10110.0,
            bidSize = 5.0,
            askSize = 5.0,
            lastTradePrice = 10105.0,
            lastTradeSize = 1.0,
            lastTradeSide = "BUY"
        )

        // Low friction position entry at 10000.0 (maker order)
        val posLow = BacktestEngine.ActivePosition(
            type = "BUY-LONG",
            entryPrice = 10000.0,
            entryTimestampMs = 1000L,
            entryFilled = true,
            queueAhead = 0.0,
            isLimitOrder = true
        )

        // High friction position entry at 10000.0 (maker order)
        val posHigh = BacktestEngine.ActivePosition(
            type = "BUY-LONG",
            entryPrice = 10000.0,
            entryTimestampMs = 1000L,
            entryFilled = true,
            queueAhead = 0.0,
            isLimitOrder = true
        )

        val lowResult = lowFrictionEngine.forceExitPosition(posLow, tickExit, "PROFIT_TARGET")
        val highResult = highFrictionEngine.forceExitPosition(posHigh, tickExit, "PROFIT_TARGET")

        // Gross return should be identical because it only depends on exit execution levels
        // exitPrice_low = 10100 - (10100 * 0.15/10000) = 10100 - 1.515 = 10098.485
        // exitPrice_high = 10100 - (10100 * 1.50/10000) = 10100 - 15.15 = 10084.85
        // So higher slippage directly reduces exitPrice, which reduces gross return!
        assertTrue("Higher slippage must reduce exit price", lowResult.exitPrice > highResult.exitPrice)
        assertTrue("Higher slippage must reduce gross return", lowResult.grossReturnBps > highResult.grossReturnBps)

        // Net Return = Gross - Fees
        // lowResult.netReturnBps = gross_low - (-0.1 + 0.4) = gross_low - 0.3
        // highResult.netReturnBps = gross_high - (0.5 + 2.0) = gross_high - 2.5
        assertTrue("High friction must dramatically lower Net Return compared to Low friction", lowResult.netReturnBps > highResult.netReturnBps)

        // Verify the math matches exactly
        assertEquals(lowResult.grossReturnBps - lowResult.feesPaidBps, lowResult.netReturnBps, 1e-6)
        assertEquals(highResult.grossReturnBps - highResult.feesPaidBps, highResult.netReturnBps, 1e-6)
    }

    @Test
    fun testQueueSimulationL2OrderExecution() {
        val engine = BacktestEngine()

        // Create tick where entry is initialized
        val tickEntry = L2Tick(
            timestampMs = 1000L,
            bestBid = 10000.0,
            bestAsk = 10010.0,
            bidSize = 5.0, // 5.0 units ahead of us
            askSize = 5.0,
            lastTradePrice = 10005.0,
            lastTradeSize = 1.0,
            lastTradeSide = "BUY"
        )

        // Initialize position (should set queueAhead to 5.0)
        val pos = engine.initializePosition("BUY-LONG", tickEntry)
        assertEquals(5.0, pos.queueAhead, 1e-6)
        assertFalse("Position cannot be filled instantly at mid price", pos.entryFilled)

        // 1st subsequent trade: aggressive sell of 2.0 units at entry price
        val tickTrade1 = L2Tick(
            timestampMs = 2000L,
            bestBid = 10000.0,
            bestAsk = 10010.0,
            bidSize = 3.0,
            askSize = 5.0,
            lastTradePrice = 10000.0,
            lastTradeSize = 2.0,
            lastTradeSide = "SELL" // aggressive seller hits bid
        )

        // In a real backtest, checkPositionExit is called on each tick
        // Since checkPositionExit evaluates queueAhead internally, let's trace:
        // After trade of 2.0, queueAhead should decrease to 3.0
        // Let's call checkPositionExit to verify it processes the trade correctly
        val res1 = invokeCheckPositionExit(engine, pos, tickTrade1)
        assertNull("Position should not be filled yet", res1)
        assertEquals(3.0, pos.queueAhead, 1e-6)
        assertFalse(pos.entryFilled)

        // 2nd subsequent trade: aggressive sell of 4.0 units at entry price
        val tickTrade2 = L2Tick(
            timestampMs = 3000L,
            bestBid = 10000.0,
            bestAsk = 10010.0,
            bidSize = 0.0,
            askSize = 5.0,
            lastTradePrice = 10000.0,
            lastTradeSize = 4.0,
            lastTradeSide = "SELL"
        )

        val res2 = invokeCheckPositionExit(engine, pos, tickTrade2)
        assertNull("Position is filled but not exited yet", res2)
        assertTrue("Position should now be filled after queue depletion", pos.entryFilled)
        assertTrue("Queue ahead must be consumed fully", pos.queueAhead <= 0)
    }

    private fun invokeCheckPositionExit(
        engine: BacktestEngine,
        pos: BacktestEngine.ActivePosition,
        tick: L2Tick
    ): BacktestTrade? {
        // Reflection helper to invoke the package-private/internal method if needed
        // Or since checkPositionExit is private, let's make it internal in BacktestEngine so we can test directly!
        // Wait, yes, let's check checkPositionExit visibility. We had it as private. Let's make it internal!
        return engine.checkPositionExit(pos, tick)
    }
}
