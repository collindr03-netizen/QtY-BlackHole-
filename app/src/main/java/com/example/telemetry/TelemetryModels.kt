package com.example.telemetry

// ==========================================
// TABLE A: Connection Registry (Socks & Latency)
// ==========================================
data class ConnectionState(
    val name: String,
    val protocol: String, // WebSocket, FIX, REST
    val endpoint: String,
    val latencyMs: Long,
    val packetCount: Long,
    val status: ConnectionStatus
)

enum class ConnectionStatus {
    CONNECTED, DISCONNECTED, DEGRADED
}

// ==========================================
// TABLE B: Raw Payloads (Atomic Wire Feed)
// ==========================================
data class RawTickPayload(
    val timestamp: Long,
    val tradePrice: Double,
    val tradeSize: Double,
    val side: String, // "BUY" or "SELL"
    val bids: DoubleArray, // volumes of L1-L20
    val asks: DoubleArray, // volumes of L1-L20
    val clockDriftMs: Long
)

// ==========================================
// TABLE C: Derived Features (Log-Returns, OFI, Entropy)
// ==========================================
data class DerivedFeatures(
    val microPrice: Double,
    val logReturn: Double,
    val orderFlowImbalance: Double, // OFI
    val depthImbalance: Double,
    val parkinsonVolatility: Double,
    val zScore: Double,
    val lyapunovExponent: Double, // Stability/Chaos indicator
    val reynoldsNumber: Double,   // Laminar vs Turbulent index
    val shannonEntropy: Double,   // Order book noise
    val rk4SmoothPrice: Double    // Smoothed continuous value
)

// ==========================================
// TABLE D: Engines (E01 - E37 Specifications)
// ==========================================
enum class EngineCategory {
    FOUNDATION,    // E01-E04
    EVIDENCE,      // E05-E17
    INFERENCE,     // E18-E24
    PREDICTION,    // E25-E29
    VALIDATION,    // E30-E34
    GOVERNANCE     // E35-E37
}

data class EngineStatus(
    val code: String, // E01-E37
    val name: String,
    val category: EngineCategory,
    val status: String, // "ACTIVE", "BYPASS", "TRIPWIRED", "STANDBY"
    val description: String,
    val telemetryValue: String = "0.0"
)

// ==========================================
// DUAL-AI GATE & DECISION METRICS
// ==========================================
data class DualAiState(
    val pUp: Double,              // Probability of hitting Tau+ before Tau-
    val pDown: Double,            // Probability of hitting Tau- before Tau+
    val sigmaUp: Double,          // Epistemic Uncertainty (Up-AI variance)
    val sigmaDown: Double,        // Aleatoric Noise (Down-AI variance)
    val directionalSeparation: Double, // D(t) = pUp - pDown
    val zSeparation: Double,      // Z_sep ratio
    val decisionErosion: Double,  // E(t) decay coefficient
    val expectedGrossReturn: Double,
    val expectedNetReturn: Double,
    val edgeToCostRatio: Double,   // ECR
    val isLaminar: Boolean,       // From Reynolds and Lyapunov Gating
    val hasDirectionalAsymmetry: Boolean, // D(t) > Threshold
    val decisionState: String,    // "NO-TRADE", "BUY-LONG", "SELL-SHORT"
    val tripwireState: String,    // "SYSTEM_NOMINAL", "DATA_INTEGRITY_FAIL_CLOSED", "FRICTION_GATED"
    val latencyDriftCritical: Boolean
)

// ==========================================
// CRYPTOGRAPHIC LEDGER / RECORD
// ==========================================
data class AuditRecord(
    val timestamp: Long,
    val stepIndex: Long,
    val stateHash: String, // SHA-256 hash of entire mathematical state
    val decision: String,
    val directionalSeparation: Double,
    val activeTripwires: List<String>
)
