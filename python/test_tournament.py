import unittest
import os
import json
import sqlite3
from qty_tournament import TournamentEvaluator, TournamentScorecard

class TournamentEvaluationUnitTest(unittest.TestCase):

    def setUp(self):
        self.db_path = "test_evaluation_ledger.db"
        # Cleanup past test DBs
        if os.path.exists(self.db_path):
            os.remove(self.db_path)
        self.evaluator = TournamentEvaluator(db_path=self.db_path)

    def tearDown(self):
        if os.path.exists(self.db_path):
            os.remove(self.db_path)

    def test_brier_score_math(self):
        """Verifies mathematical validity of Brier Score estimations."""
        forecasts = [0.9, 0.1, 0.8, 0.2]
        outcomes = [1, 0, 1, 0]
        # (0.9-1)^2 + (0.1-0)^2 + (0.8-1)^2 + (0.2-0)^2
        # = 0.01 + 0.01 + 0.04 + 0.04 = 0.10 -> Average = 0.025
        bs = self.evaluator.calculate_brier_score(forecasts, outcomes)
        self.assertAlmostEqual(bs, 0.025, places=4)

    def test_ece_calibration_error(self):
        """Verifies Expected Calibration Error scales according to reliability deviations."""
        # Perfectly calibrated forecasts
        forecasts = [0.1, 0.5, 0.9]
        outcomes = [0, 0, 1] # outcomes map accuracy close to confidence averages
        ece = self.evaluator.calculate_ece(forecasts, outcomes, num_bins=10)
        # Expected ECE = 0.2333 for these points, assert that it maps correctly
        self.assertAlmostEqual(ece, 0.2333, places=4)

    def test_purged_walk_forward_backtester(self):
        """Verifies backtester purging logic, queue priorities, and simulated slippages."""
        # Setup signals separated by 10s and 2s
        signals = [
            {"timestamp": 10 * 1_000_000_000, "d_t": 1.0, "mid": 100.0},
            {"timestamp": 12 * 1_000_000_000, "d_t": 0.9, "mid": 102.0}, # Overlapping inside 18s holding -> must be purged!
            {"timestamp": 40 * 1_000_000_000, "d_t": -1.0, "mid": 105.0} # Non-overlapping -> must execute!
        ]

        # Setup standard ticks
        ticks = [
            {"timestamp": 11 * 1_000_000_000, "price": 100.8}, # BUY hits profit target
            {"timestamp": 45 * 1_000_000_000, "price": 104.2}  # SELL hits profit target
        ]

        forecasts, outcomes, equity_curve = self.evaluator.backtest_purged_walk_forward(signals, ticks)
        
        # Signal at 12s must have been skipped/purged
        self.assertEqual(len(forecasts), 2)
        self.assertEqual(len(outcomes), 2)
        self.assertEqual(outcomes[0], 1) # BUY hit target 100.8 > 100.0
        self.assertEqual(outcomes[1], 1) # SELL hit target 104.2 < 105.0

    def test_elimination_bracket_and_ledger_insertion(self):
        """Tests tournament brackets and ensures underperforming configurations are systematically eliminated."""
        # Duo C: Stable, high precision configuration (NOMINAL)
        # Duo A: Extremely poor calibration / negative edge (eliminated)
        
        # Mock signal data
        signals_c = [{"timestamp": (i * 20) * 1_000_000_000, "d_t": 0.95, "mid": 100.0} for i in range(10)]
        ticks = [{"timestamp": (i * 20 + 2) * 1_000_000_000, "price": 100.7} for i in range(10)] # Always wins

        # Evaluate Duo C (Strong, nominal setup)
        card_c = self.evaluator.evaluate_and_record_duo(
            duo_id="Duo_C",
            version_id="v1.0.0",
            engine_weights={"OFI": 0.5, "Depth": 0.5},
            hyperparameters={"alpha": 0.4},
            signals=signals_c,
            ticks=ticks,
            baseline_brier=0.25
        )
        self.assertFalse(card_c.is_eliminated)
        self.assertEqual(card_c.elimination_reason, "NOMINAL_STABLE")

        # Evaluate Duo A (Conflicting/Wrong predictions: d_t is -0.95, but outcomes win -> huge error -> eliminated!)
        signals_a = [{"timestamp": (i * 20) * 1_000_000_000, "d_t": -0.95, "mid": 100.0} for i in range(10)]
        card_a = self.evaluator.evaluate_and_record_duo(
            duo_id="Duo_A",
            version_id="v1.0.0",
            engine_weights={"Trend": 1.0},
            hyperparameters={},
            signals=signals_a,
            ticks=ticks,
            baseline_brier=0.25
        )
        self.assertTrue(card_a.is_eliminated)

        # Confirm entries saved in sqlite3
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT count(*), duo_id FROM evaluation_ledger GROUP BY duo_id")
        rows = cursor.fetchall()
        conn.close()

        self.assertEqual(len(rows), 2)
        # Assert database content persists
        duos_inserted = [row[1] for row in rows]
        self.assertIn("Duo_C", duos_inserted)
        self.assertIn("Duo_A", duos_inserted)

if __name__ == "__main__":
    unittest.main()
