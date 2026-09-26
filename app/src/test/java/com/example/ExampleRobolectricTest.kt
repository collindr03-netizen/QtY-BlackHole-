package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.telemetry.TelemetryBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("QtY 64", appName)
    }

    @Test
    fun `test Lyapunov exponent predictable series`() {
        // Flat line should be predictable (lambda <= 0)
        val prices = doubleArrayOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0)
        val result = TelemetryBridge.getLyapunov(prices)
        assertTrue(result <= 0.0)
    }

    @Test
    fun `test Reynolds Number calculation`() {
        // Velocity: 10, Depth: 100, Viscosity/Noise: 5 -> Reynolds = 200
        val reynolds = TelemetryBridge.getReynolds(10.0, 100.0, 5.0)
        assertEquals(200.0, reynolds, 0.001)
    }

    @Test
    fun `test Runge Kutta 4 Integration`() {
        // RK4 integrates y' = trend + accel * sin(y). If everything is 0, integration is flat
        val rkResult = TelemetryBridge.getRungeKutta4(1.0, 0.1, 0.0, 0.0)
        assertEquals(1.0, rkResult, 0.001)
    }

    @Test
    fun `test Shannon Entropy empty and balanced`() {
        // Balanced books (same bids and asks volume) -> max entropy
        val volumes = doubleArrayOf(100.0, 100.0)
        val entropy = TelemetryBridge.getEntropy(volumes)
        assertEquals(1.0, entropy, 0.001) // log2(2) = 1.0
    }
}
