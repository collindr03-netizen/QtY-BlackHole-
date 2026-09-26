package com.example.telemetry

import android.util.Log

object TelemetryBridge {
    private const val TAG = "TelemetryBridge"
    var isNativeLoaded = false
        private set

    init {
        try {
            System.loadLibrary("qty_telemetry_jni")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native C++ qty_telemetry_jni library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library - falling back to high-fidelity pure Kotlin math engine", e)
            isNativeLoaded = false
        }
    }

    // --- Native JNI Declarations ---
    private external fun calculateLyapunov(prices: DoubleArray): Double
    private external fun calculateReynolds(tradeVelocity: Double, orderBookDepth: Double, cancellationNoise: Double): Double
    private external fun runRungeKutta4(currentVal: Double, stepSize: Double, trend: Double, acceleration: Double): Double
    private external fun calculateEntropy(volumes: DoubleArray): Double

    // --- Safe Wrappers with High-Fidelity Pure Kotlin Fallbacks ---

    /**
     * Calculates the Lyapunov Exponent of price history (determines if regime is chaotic vs predictable).
     */
    fun getLyapunov(prices: DoubleArray): Double {
        if (isNativeLoaded) {
            try {
                return calculateLyapunov(prices)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "UnsatisfiedLinkError in native calculateLyapunov, using fallback", e)
            }
        }
        
        // Pure Kotlin Fallback:
        if (prices.size < 5) return 0.0
        val logDiffs = ArrayList<Double>()
        for (i in 0 until prices.size - 1) {
            val d0 = Math.abs(prices[i])
            val d1 = Math.abs(prices[i + 1])
            if (d0 > 0.0 && d1 > 0.0) {
                logDiffs.add(Math.log(d1 / d0))
            }
        }
        if (logDiffs.size < 2) return 0.0
        val mean = logDiffs.average()
        val variance = logDiffs.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        return mean / (stdDev + 1e-9)
    }

    /**
     * Calculates Reynolds Number of order book liquidity flow.
     */
    fun getReynolds(tradeVelocity: Double, orderBookDepth: Double, cancellationNoise: Double): Double {
        if (isNativeLoaded) {
            try {
                return calculateReynolds(tradeVelocity, orderBookDepth, cancellationNoise)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "UnsatisfiedLinkError in native calculateReynolds, using fallback", e)
            }
        }
        
        // Pure Kotlin Fallback:
        val viscosity = Math.max(cancellationNoise, 1e-4)
        val velocity = Math.max(tradeVelocity, 0.0)
        val depth = Math.max(orderBookDepth, 1.0)
        return (velocity * depth) / viscosity
    }

    /**
     * Performs Runge-Kutta 4th Order numerical integration.
     */
    fun getRungeKutta4(currentVal: Double, stepSize: Double, trend: Double, acceleration: Double): Double {
        if (isNativeLoaded) {
            try {
                return runRungeKutta4(currentVal, stepSize, trend, acceleration)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "UnsatisfiedLinkError in native runRungeKutta4, using fallback", e)
            }
        }
        
        // Pure Kotlin Fallback:
        val f = { t: Double, y: Double -> trend + acceleration * Math.sin(y) }
        val t0 = 0.0
        val y0 = currentVal
        val h = stepSize

        val k1 = h * f(t0, y0)
        val k2 = h * f(t0 + h / 2.0, y0 + k1 / 2.0)
        val k3 = h * f(t0 + h / 2.0, y0 + k2 / 2.0)
        val k4 = h * f(t0 + h, y0 + k3)

        return y0 + (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0
    }

    /**
     * Calculates Shannon Entropy of order volume distributions.
     */
    fun getEntropy(volumes: DoubleArray): Double {
        if (isNativeLoaded) {
            try {
                return calculateEntropy(volumes)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "UnsatisfiedLinkError in native calculateEntropy, using fallback", e)
            }
        }
        
        // Pure Kotlin Fallback:
        if (volumes.isEmpty()) return 0.0
        val totalVolume = volumes.map { Math.abs(it) }.sum()
        if (totalVolume < 1e-9) return 0.0
        
        var entropy = 0.0
        for (vol in volumes) {
            val p = Math.abs(vol) / totalVolume
            if (p > 1e-9) {
                entropy -= p * (Math.log(p) / Math.log(2.0))
            }
        }
        return entropy
    }
}
