import unittest
import math
from qty_dual_ai import DualAIDirectionalGate, AsymmetricAIEstimator, GateDecision

class DualAIDirectionalGateUnitTest(unittest.TestCase):

    def setUp(self):
        # Build gate with default critical thresholds (0.70 separation, 4.0 Z-score separation)
        self.gate = DualAIDirectionalGate(theta_crit=0.70, z_crit=4.0, lambda_decay=0.05)

    def test_asymmetric_decoupled_estimators(self):
        """Verifies that UP_AI and DOWN_AI function independently (P_DOWN != 1 - P_UP)."""
        up_estimator = AsymmetricAIEstimator("UP")
        down_estimator = AsymmetricAIEstimator("DOWN")

        # Mock feature tensor with dynamic microstructural imbalances
        features = {
            "order_flow_imbalance": 0.5,
            "depth_imbalance": 0.3,
            "z_score": 1.2,
            "garman_klass_vol": 0.05
        }

        out_up = up_estimator.infer(features)
        out_down = down_estimator.infer(features)

        # Independent estimators should not be trivial mirrors of each other
        self.assertNotEqual(out_down.probability, 1.0 - out_up.probability)
        self.assertTrue(0.0 < out_up.probability < 1.0)
        self.assertTrue(0.0 < out_down.probability < 1.0)

    def test_directional_gating_actionable_buy_and_sell(self):
        """Verifies BUY/SELL triggers under extreme laminar separation, and NO-TRADE under limits."""
        now_ns = 1 * 1_000_000_000

        # Case A: Strong laminar bullish momentum (OFI + Imbalance + Low Volatility/uncertainty)
        bullish_features = {
            "order_flow_imbalance": 1.0,
            "depth_imbalance": 1.0,
            "z_score": 1.5,
            "garman_klass_vol": 0.001 # extremely low volatility -> small denominators
        }
        
        decision_bull = self.gate.evaluate_gate(now_ns, bullish_features, is_laminar=True)
        self.assertEqual(decision_bull.decision_state, "BUY")
        self.assertTrue(decision_bull.directional_separation >= 0.70)
        self.assertTrue(decision_bull.confidence_ratio >= 4.0)

        # Case B: Strong laminar bearish momentum
        bearish_features = {
            "order_flow_imbalance": -1.0,
            "depth_imbalance": -1.0,
            "z_score": -1.5,
            "garman_klass_vol": 0.001
        }
        self.gate._abort_signal() # Clear active trackers
        decision_bear = self.gate.evaluate_gate(now_ns, bearish_features, is_laminar=True)
        self.assertEqual(decision_bear.decision_state, "SELL")
        self.assertTrue(decision_bear.directional_separation <= -0.70)
        self.assertTrue(decision_bear.confidence_ratio <= -4.0)

    def test_refusal_to_trade_on_conflicting_probabilities(self):
        """Verifies that the gate refuses to trade under mutual high uncertainty (P_UP and P_DOWN both high)."""
        now_ns = 1 * 1_000_000_000
        
        # Symmetrical mock features that cause high outputs on both sides (clashing signals)
        conflict_features = {
            "order_flow_imbalance": 0.0,
            "depth_imbalance": 0.0,
            "z_score": 0.0,
            "garman_klass_vol": 0.5 # High volatility increases denominators and uncertainties
        }
        
        decision_conflict = self.gate.evaluate_gate(now_ns, conflict_features, is_laminar=True)
        self.assertEqual(decision_conflict.decision_state, "NO-TRADE")
        self.assertLess(abs(decision_conflict.directional_separation), 0.70)

    def test_decision_erosion_and_directional_abort(self):
        """Verifies exponential signal erosion over time and active abort on sign-flip tick flow."""
        start_ns = 10 * 1_000_000_000
        bullish_features = {
            "order_flow_imbalance": 1.0,
            "depth_imbalance": 1.0,
            "z_score": 1.5,
            "garman_klass_vol": 0.001
        }

        # 1. Issue an active signal (BUY)
        self.gate._abort_signal()
        sig_t0 = self.gate.evaluate_gate(start_ns, bullish_features, is_laminar=True)
        self.assertEqual(sig_t0.decision_state, "BUY")
        self.assertEqual(sig_t0.erosion_factor, 1.0)

        # 2. Evaluate 5 seconds later with same bullish momentum (Erosion factor must decay)
        # E(t) = exp(-0.05 * 5) = exp(-0.25) ~ 0.7788
        sig_t5 = self.gate.evaluate_gate(start_ns + 5 * 1_000_000_000, bullish_features, is_laminar=True)
        self.assertEqual(sig_t5.decision_state, "BUY")
        self.assertAlmostEqual(sig_t5.erosion_factor, math.exp(-0.25), places=4)

        # 3. Sudden sign flip tick flow (Bearish momentum)
        # Active signal must abort immediately (NO-TRADE) and decay factor must drop to 0.0
        bearish_features = {
            "order_flow_imbalance": -1.0,
            "depth_imbalance": -1.0,
            "z_score": -1.5,
            "garman_klass_vol": 0.001
        }
        sig_flip = self.gate.evaluate_gate(start_ns + 6 * 1_000_000_000, bearish_features, is_laminar=True)
        self.assertEqual(sig_flip.decision_state, "NO-TRADE")
        self.assertEqual(sig_flip.erosion_factor, 0.0)

    def test_fail_closed_tripwire_override(self):
        """Verifies that E02 data integrity fail-closed flag blocks and aborts trading unconditionally."""
        now_ns = 5 * 1_000_000_000
        bullish_features = {
            "order_flow_imbalance": 1.0,
            "depth_imbalance": 1.0,
            "z_score": 1.5,
            "garman_klass_vol": 0.001
        }

        # Integrity Nominals (Should trigger BUY)
        decision_nominal = self.gate.evaluate_gate(now_ns, bullish_features, is_laminar=True, is_integrity_nominal=True)
        self.assertEqual(decision_nominal.decision_state, "BUY")

        # Integrity Breach (Should immediately trip closed and force NO-TRADE)
        decision_breach = self.gate.evaluate_gate(now_ns + 100, bullish_features, is_laminar=True, is_integrity_nominal=False)
        self.assertEqual(decision_breach.decision_state, "NO-TRADE")
        self.assertTrue(decision_breach.tripwire_active)

if __name__ == "__main__":
    unittest.main()
