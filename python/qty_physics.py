import math
from typing import List, Tuple, Dict, Any, Optional
from dataclasses import dataclass

# ==========================================
# Stability Engine Outputs & Gate State
# ==========================================

@dataclass(frozen=True)
class PhysicsState:
    reynolds_number: float
    lyapunov_exponent: float
    permutation_entropy: float
    is_turbulent: bool
    is_chaotic: bool
    is_noisy: bool
    regime: str            # "LAMINAR" or "TURBULENT"
    actionable_gate: bool  # True = Trade permitted, False = NO-TRADE (hard stop)


# ==========================================
# PHYSICALLY ENFORCED MARKET PREDICTABILITY ENGINES
# ==========================================

class PhysicsRegimeFilter:
    """
    Evaluates microstructural stability using physical analogies.
    Never calculates buy/sell directions; strictly filters predictability regimes.
    """

    @staticmethod
    def calculate_reynolds_number(
        trade_volume_rate: float,      # rho_flow (volume/sec)
        price_displacement: float,     # v_price (price drift/sec)
        bid_ask_spread: float,         # L_spread
        resting_depth_top5: float,     # mu_depth (viscosity proxy)
        re_crit: float = 120.0
    ) -> Tuple[float, bool]:
        """
        Market Reynolds Number:
        Re_mkt = (rho_flow * v_price * L_spread) / mu_depth
        Flags as TURBULENT if Re_mkt > re_crit.
        """
        viscosity = max(resting_depth_top5, 1e-4) # Zero division guard
        flow_density = max(trade_volume_rate, 0.0)
        velocity = abs(price_displacement)
        length = max(bid_ask_spread, 1e-6)

        re_mkt = (flow_density * velocity * length) / viscosity
        is_turbulent = re_mkt > re_crit
        return re_mkt, is_turbulent

    @staticmethod
    def _euclidean_norm(vector: List[float]) -> float:
        """Helper to calculate L2 norm of a vector without numpy."""
        return math.sqrt(sum(x * x for x in vector))

    @staticmethod
    def calculate_ftle(
        phase_space_history: List[List[float]], # List of [P_micro, velocity, acceleration, OFI, depth_imb]
        dt: float = 1.0,
        epsilon: float = 1e-5
    ) -> Tuple[float, bool]:
        """
        Finite-Time Lyapunov Exponent (FTLE) over 5-dimensional phase space trajectory.
        Evaluates local trajectory divergence rate over historical records.
        """
        if len(phase_space_history) < 5:
            return 0.0, False

        n = len(phase_space_history)
        divergence_rates = []

        for i in range(n - 1):
            curr_state = phase_space_history[i]
            next_state = phase_space_history[i + 1]

            # Vector separation calculations (pure math)
            perturbed_state = [x + epsilon for x in curr_state]
            step_diff = [next_state[j] - curr_state[j] for j in range(len(curr_state))]
            perturbed_next = [perturbed_state[j] + step_diff[j] for j in range(len(perturbed_state))]

            # Compute separation distances
            dist0_vec = [perturbed_state[j] - curr_state[j] for j in range(len(curr_state))]
            dist1_vec = [perturbed_next[j] - next_state[j] for j in range(len(next_state))]

            d0 = PhysicsRegimeFilter._euclidean_norm(dist0_vec) + 1e-12
            d1 = PhysicsRegimeFilter._euclidean_norm(dist1_vec) + 1e-12

            # divergence rate
            divergence_rate = math.log(d1 / d0) / dt
            divergence_rates.append(divergence_rate)

        avg_divergence = sum(divergence_rates) / len(divergence_rates) if divergence_rates else 0.0
        is_chaotic = avg_divergence > 0.0
        return avg_divergence, is_chaotic

    @staticmethod
    def _argsort(seq: List[float]) -> List[int]:
        """Returns the indices that would sort an array."""
        return [x[0] for x in sorted(enumerate(seq), key=lambda x: x[1])]

    @staticmethod
    def calculate_permutation_entropy(
        returns: List[float],
        d: int = 4,
        tau: int = 2
    ) -> Tuple[float, bool]:
        """
        Normalized Permutation Entropy of ordinal returns.
        Captures dynamic complexity over dimensions d and time delay lag tau.
        Flags as NOISY if Entropy >= 0.85.
        """
        min_required = (d - 1) * tau + 1
        if len(returns) < min_required:
            return 0.5, False # nominal fallback

        patterns = {}
        total_permutations = 0

        # Extract ordinal patterns from series (pure Python argsort)
        for i in range(len(returns) - min_required + 1):
            seq = [returns[i + j * tau] for j in range(d)]
            ranks = tuple(PhysicsRegimeFilter._argsort(seq))
            patterns[ranks] = patterns.get(ranks, 0) + 1
            total_permutations += 1

        if total_permutations == 0:
            return 0.0, False

        entropy = 0.0
        for count in patterns.values():
            p = count / total_permutations
            entropy -= p * math.log2(p)

        # Max entropy is log2(d!)
        max_entropy = math.log2(math.factorial(d))
        normalized_entropy = entropy / max_entropy if max_entropy > 0.0 else 0.0
        
        is_noisy = normalized_entropy >= 0.85
        return normalized_entropy, is_noisy


# ==========================================
# STABILITY GATE ENFORCER
# ==========================================

class StabilityGate:
    """
    Enforces unified physical filtering.
    Gives permission to trade ONLY when the market is LAMINAR, ORDERLY, and PREDICTABLE.
    """

    def __init__(self, re_crit: float = 120.0, max_entropy: float = 0.85):
        self.re_crit = re_crit
        self.max_entropy = max_entropy

    def evaluate_market_state(
        self,
        # Reynolds properties
        trade_volume_rate: float,
        price_displacement: float,
        bid_ask_spread: float,
        resting_depth_top5: float,
        # FTLE properties
        phase_space_history: List[List[float]],
        # Permutation properties
        returns_history: List[float]
    ) -> PhysicsState:
        
        # 1. Compute Reynolds
        re, is_turbulent = PhysicsRegimeFilter.calculate_reynolds_number(
            trade_volume_rate=trade_volume_rate,
            price_displacement=price_displacement,
            bid_ask_spread=bid_ask_spread,
            resting_depth_top5=resting_depth_top5,
            re_crit=self.re_crit
        )

        # 2. Compute FTLE
        lambda_ftle, is_chaotic = PhysicsRegimeFilter.calculate_ftle(
            phase_space_history=phase_space_history,
            dt=1.0
        )

        # 3. Compute Permutation Entropy
        entropy, is_noisy = PhysicsRegimeFilter.calculate_permutation_entropy(
            returns=returns_history,
            d=4,
            tau=2
        )

        # GATING LAW: Must be laminar (Re <= Re_crit) AND stable (lambda_FTLE <= 0) AND orderly (Entropy < 0.85)
        # Any turbulent or chaotic state triggers a hard NO-TRADE fail-closed state.
        actionable_gate = (not is_turbulent) and (not is_chaotic) and (not is_noisy)
        regime = "LAMINAR" if actionable_gate else "TURBULENT_OR_CHAOTIC"

        return PhysicsState(
            reynolds_number=re,
            lyapunov_exponent=lambda_ftle,
            permutation_entropy=entropy,
            is_turbulent=is_turbulent,
            is_chaotic=is_chaotic,
            is_noisy=is_noisy,
            regime=regime,
            actionable_gate=actionable_gate
        )
