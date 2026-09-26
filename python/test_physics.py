import unittest
import math
from qty_physics import PhysicsRegimeFilter, StabilityGate, PhysicsState

class PhysicsTelemetryUnitTest(unittest.TestCase):

    def setUp(self):
        self.gate = StabilityGate(re_crit=120.0, max_entropy=0.85)

    def test_reynolds_number_laminar_vs_turbulent(self):
        """Verifies Reynolds engine under fluid laminar patterns and turbulent spike conditions."""
        # 1. Laminar State (low displacement, thick books)
        # Re = (rho * v * L) / mu = (1.5 * 0.05 * 0.2) / 100.0 = 0.00015 << 120.0
        re_lam, is_turb_lam = PhysicsRegimeFilter.calculate_reynolds_number(
            trade_volume_rate=1.5,
            price_displacement=0.05,
            bid_ask_spread=0.2,
            resting_depth_top5=100.0
        )
        self.assertFalse(is_turb_lam)
        self.assertLess(re_lam, 1.0)

        # 2. Turbulent State (violent trading, wide spreads, paper-thin liquidity)
        # Re = (150.0 * 25.0 * 8.5) / 0.02 = 31875000.0 / 0.02 = 1,593,750,000 >> 120.0
        re_turb, is_turb_turb = PhysicsRegimeFilter.calculate_reynolds_number(
            trade_volume_rate=150.0,
            price_displacement=25.0,
            bid_ask_spread=8.5,
            resting_depth_top5=0.02
        )
        self.assertTrue(is_turb_turb)
        self.assertTrue(re_turb > 120.0)

    def test_ftle_stable_vs_chaotic_divergence(self):
        """Verifies FTLE evaluates predictable trajectory sequences vs divergent chaos."""
        # 1. Perfectly stable orbits (No deviation, identical parameters)
        stable_trajectory = [
            [100.0, 0.0, 0.0, 0.0, 0.5],
            [100.0, 0.0, 0.0, 0.0, 0.5],
            [100.0, 0.0, 0.0, 0.0, 0.5],
            [100.0, 0.0, 0.0, 0.0, 0.5],
            [100.0, 0.0, 0.0, 0.0, 0.5]
        ]
        lambda_stable, is_chaotic_stable = PhysicsRegimeFilter.calculate_ftle(stable_trajectory)
        # Orbits with exact same values shouldn't exhibit divergence
        self.assertFalse(is_chaotic_stable)
        self.assertLessEqual(lambda_stable, 0.001)

        # 2. Divergent trajectory (Accelerating drift)
        divergent_trajectory = [
            [100.0, 0.1, 0.01, -0.1, 0.2],
            [101.5, 0.5, 0.08, -0.2, 0.1],
            [104.2, 1.8, 0.25, -0.4, -0.1],
            [109.8, 4.2, 0.88, -0.8, -0.4],
            [118.5, 9.5, 2.12, -1.0, -0.9]
        ]
        lambda_div, is_chaotic_div = PhysicsRegimeFilter.calculate_ftle(divergent_trajectory)
        self.assertTrue(lambda_div > 0.0, f"Divergent system missed positive Lyapunov: {lambda_div}")

    def test_permutation_entropy_ordinal_complexity(self):
        """Verifies permutation entropy calculations for ordinal sequences."""
        # 1. Perfectly predictable monotonic series (Always climbing)
        # All subsets are ranked monotonically [0, 1, 2, 3] -> only 1 pattern -> 0 entropy
        returns_monotonic = [float(i) for i in range(15)]
        entropy_mono, is_noisy_mono = PhysicsRegimeFilter.calculate_permutation_entropy(returns_monotonic)
        self.assertEqual(entropy_mono, 0.0)
        self.assertFalse(is_noisy_mono)

        # 2. Highly noisy complex random walk
        returns_noisy = [1.2, -0.8, 2.5, -1.9, 0.4, 3.1, -2.4, 1.1, -1.1, 2.2, -0.3, 1.9, -1.5, 0.8, -2.0]
        entropy_noisy, is_noisy_noisy = PhysicsRegimeFilter.calculate_permutation_entropy(returns_noisy)
        self.assertTrue(entropy_noisy > 0.60)

    def test_stability_gate_trade_permission(self):
        """Verifies that StabilityGate gives trading permission only under absolute laminar nominal regimes."""
        # Setup inputs for Laminar nominal state
        lam_trajectory = [[100.0, 0.0, 0.0, 0.0, 0.0] for _ in range(10)]
        returns_nominal = [float(i) for i in range(15)] # predictable monotonic returns

        state_laminar = self.gate.evaluate_market_state(
            trade_volume_rate=1.0,
            price_displacement=0.01,
            bid_ask_spread=0.1,
            resting_depth_top5=100.0,
            phase_space_history=lam_trajectory,
            returns_history=returns_nominal
        )
        self.assertTrue(state_laminar.actionable_gate, "StabilityGate failed to authorize trading under nominal laminar regimes!")
        self.assertEqual(state_laminar.regime, "LAMINAR")

        # Stress test 1: Sudden turbulent Reynolds breach (>120.0)
        state_turbulent = self.gate.evaluate_market_state(
            trade_volume_rate=150.0, # massive flow volume
            price_displacement=15.0, # large price drift
            bid_ask_spread=5.0,      # wide spread
            resting_depth_top5=0.1,  # thin depth/viscosity
            phase_space_history=lam_trajectory,
            returns_history=returns_nominal
        )
        self.assertFalse(state_turbulent.actionable_gate, "Turbulent Reynolds breach failed to trigger fail-closed NO-TRADE state!")
        self.assertEqual(state_turbulent.regime, "TURBULENT_OR_CHAOTIC")

        # Stress test 2: Chaotic Lyapunov state
        chaotic_trajectory = [
            [100.0, 0.1, 0.01, -0.1, 0.2],
            [101.5, 0.5, 0.08, -0.2, 0.1],
            [104.2, 1.8, 0.25, -0.4, -0.1],
            [109.8, 4.2, 0.88, -0.8, -0.4],
            [118.5, 9.5, 2.12, -1.0, -0.9]
        ]
        state_chaotic = self.gate.evaluate_market_state(
            trade_volume_rate=1.0,
            price_displacement=0.01,
            bid_ask_spread=0.1,
            resting_depth_top5=100.0,
            phase_space_history=chaotic_trajectory,
            returns_history=returns_nominal
        )
        self.assertFalse(state_chaotic.actionable_gate, "Chaotic Lyapunov breach failed to trigger fail-closed NO-TRADE state!")
        self.assertEqual(state_chaotic.regime, "TURBULENT_OR_CHAOTIC")

if __name__ == "__main__":
    unittest.main()
