import math
import time
from typing import Dict, Any, Tuple, Optional
from dataclasses import dataclass

# ==========================================
# Gating & Evaluation Output Records
# ==========================================

@dataclass(frozen=True)
class AIModelOutput:
    probability: float
    epistemic_uncertainty: float  # Model unfamiliarity / ensemble variance
    aleatoric_uncertainty: float  # Microstructural order-book noise
    
    @property
    def total_variance(self) -> float:
        return self.epistemic_uncertainty + self.aleatoric_uncertainty


@dataclass(frozen=True)
class GateDecision:
    timestamp_ns: int
    p_up: float
    p_down: float
    directional_separation: float  # D(t)
    confidence_ratio: float        # Z_sep(t)
    decision_state: str            # "BUY", "SELL", or "NO-TRADE"
    erosion_factor: float          # Remaining signal strength (0.0 to 1.0)
    tripwire_active: bool


# ==========================================
# ASYMMETRIC DECOUPLED AI ESTIMATORS
# ==========================================

class AsymmetricAIEstimator:
    """
    Simulates high-precision, decoupled deep learning estimators (UP_AI / DOWN_AI).
    These models act as asymmetric binary probability engines rather than mirrored classifiers.
    """

    def __init__(self, mode: str):
        assert mode in ["UP", "DOWN"]
        self.mode = mode

    def infer(self, feature_tensor: Dict[str, float]) -> AIModelOutput:
        """
        Processes normalized evidence tensors.
        Returns posterior target boundary probabilities and separate uncertainties.
        """
        # Read parameters from Table C Feature / Evidence results
        ofi = feature_tensor.get("order_flow_imbalance", 0.0)
        depth_imb = feature_tensor.get("depth_imbalance", 0.0)
        z_score = feature_tensor.get("z_score", 0.0)
        vol = feature_tensor.get("garman_klass_vol", 0.0)

        # Microstructural pressure calculations (distinct paths for asymmetric weights)
        if self.mode == "UP":
            # UP_AI targets +6 bps profit target before -3 bps stop
            base_score = 2.5 * (0.6 * ofi + 0.4 * depth_imb + 0.1 * z_score)
            prob = 1.0 / (1.0 + math.exp(-base_score)) # Logistic Sigmoid Mapping
            
            # Epistemic: rises if price standard dev (z_score) or volatility behaves oddly
            epistemic = max(0.001, 0.005 * abs(z_score))
            # Aleatoric: intrinsic noise associated with thin depth levels and spread
            aleatoric = max(0.001, 0.05 * vol)
            
        else: # DOWN_AI
            # DOWN_AI targets -6 bps profit target before +3 bps stop
            # Note: negative OFI/Depth implies down pressure
            base_score = 2.5 * (-0.6 * ofi - 0.4 * depth_imb - 0.1 * z_score)
            prob = 1.0 / (1.0 + math.exp(-base_score))
            
            epistemic = max(0.001, 0.005 * abs(z_score))
            aleatoric = max(0.001, 0.05 * vol)

        return AIModelOutput(
            probability=prob,
            epistemic_uncertainty=epistemic,
            aleatoric_uncertainty=aleatoric
        )


# ==========================================
# DUAL-AI DIRECTIONAL GATE & DECISION EROSION
# ==========================================

class DualAIDirectionalGate:
    """
    Enforces asymmetric inference evaluation, directional separation,
    confidence ratio checks, and signal decision erosion over sub-18s windows.
    """

    def __init__(
        self,
        theta_crit: float = 0.70,        # Min directional separation threshold
        z_crit: float = 4.0,             # Min statistical separation Z-score
        lambda_decay: float = 0.05       # Erosion factor decay rate per tick
    ):
        self.theta_crit = theta_crit
        self.z_crit = z_crit
        self.lambda_decay = lambda_decay
        
        self.up_ai = AsymmetricAIEstimator("UP")
        self.down_ai = AsymmetricAIEstimator("DOWN")
        
        # Keep track of active trade execution context
        self.last_signal: Optional[GateDecision] = None
        self.last_signal_time_ns: int = 0
        self.active_directional_sign: int = 0 # 1 = BUY, -1 = SELL

    def evaluate_gate(
        self,
        current_time_ns: int,
        features: Dict[str, float],
        is_laminar: bool,
        is_integrity_nominal: bool = True
    ) -> GateDecision:
        """
        Enforces 4 operational gating constraints to issue actionable decisions.
        """
        # Fail-closed tripwire check (Engine E02 overrides everything)
        if not is_integrity_nominal:
            self._abort_signal()
            return self._build_no_trade(current_time_ns, 0.5, 0.5, tripwire=True)

        # 1. Infer decoupled target probability estimators
        out_up = self.up_ai.infer(features)
        out_down = self.down_ai.infer(features)

        p_up = out_up.probability
        p_down = out_down.probability

        # 2. Compute Directional Separation D(t) = P(UP) - P(DOWN)
        d_t = p_up - p_down

        # 3. Compute Confidence Ratio Z_sep(t)
        total_var_up = out_up.total_variance
        total_var_down = out_down.total_variance
        denom = math.sqrt(total_var_up + total_var_down)
        z_sep = d_t / denom if denom > 1e-9 else 0.0

        # Evaluate Signal Decision Erosion & Abort conditions
        erosion_factor = 1.0
        if self.last_signal is not None and self.last_signal.decision_state != "NO-TRADE":
            dt_sec = (current_time_ns - self.last_signal_time_ns) / 1_000_000_000.0
            
            # Apply exponential decay erosion: E(t) = exp(-lambda * dt)
            erosion_factor = math.exp(-self.lambda_decay * dt_sec)
            
            # ABORT TRIPWIRE: If tick flow flips the directional sign, abort immediately!
            current_sign = 1 if d_t > 0 else -1
            if current_sign != self.active_directional_sign:
                self._abort_signal()
                erosion_factor = 0.0

        # GATING CHECKS FOR ACTIVE DECISIONS
        decision_state = "NO-TRADE"
        
        # 1. Stability check (Laminar)
        # 2. Directional Separation check (|D(t)| >= theta_crit)
        # 3. Confidence ratio check (Z_sep >= z_crit)
        if is_laminar and erosion_factor > 0.05:
            if d_t >= self.theta_crit and z_sep >= self.z_crit:
                decision_state = "BUY"
                if self.last_signal is None or self.last_signal.decision_state == "NO-TRADE":
                    self.last_signal_time_ns = current_time_ns
                    self.active_directional_sign = 1
            elif d_t <= -self.theta_crit and z_sep <= -self.z_crit:
                decision_state = "SELL"
                if self.last_signal is None or self.last_signal.decision_state == "NO-TRADE":
                    self.last_signal_time_ns = current_time_ns
                    self.active_directional_sign = -1

        decision = GateDecision(
            timestamp_ns=current_time_ns,
            p_up=p_up,
            p_down=p_down,
            directional_separation=d_t,
            confidence_ratio=z_sep,
            decision_state=decision_state if is_laminar else "NO-TRADE",
            erosion_factor=erosion_factor,
            tripwire_active=not is_integrity_nominal
        )

        if decision.decision_state != "NO-TRADE":
            self.last_signal = decision
        else:
            self.last_signal = None

        return decision

    def _abort_signal(self):
        self.last_signal = None
        self.last_signal_time_ns = 0
        self.active_directional_sign = 0

    def _build_no_trade(self, ts: int, up: float, down: float, tripwire: bool) -> GateDecision:
        return GateDecision(
            timestamp_ns=ts,
            p_up=up,
            p_down=down,
            directional_separation=up - down,
            confidence_ratio=0.0,
            decision_state="NO-TRADE",
            erosion_factor=1.0,
            tripwire_active=tripwire
        )
