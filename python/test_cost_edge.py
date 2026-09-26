import unittest
import math
from qty_foundation import RawBookSnapshot, RawTradeEvent
from qty_cost_edge import CostAndEdgeEngine, EconomicEvaluation

class CostAndEdgeEngineUnitTest(unittest.TestCase):

    def setUp(self):
        # Create standard engine
        self.engine = CostAndEdgeEngine(
            taker_fee_rate=0.0004,     # +0.04% taker fee (4 bps)
            maker_fee_rate=-0.00005,   # -0.005% maker rebate (-0.5 bps)
            slippage_slope=0.00002
        )

    def test_nominal_spread_economic_viability(self):
        """Verifies trade permission under nominal small spreads and high directional separation."""
        now_ns = 1000
        # Price = 100.0, Spread = 0.01 (1 bp spread)
        # Taker fee friction: 2 * 4 bps + 1 bp spread + 0 slippage = 9 bps friction
        # Directional separation = 1.0, Profit target = 30 bps -> expected gross = 30 bps
        # Net Return = 30 - 9 = 21 bps >= 2.5 bps
        # ECR = 30 / 9 = 3.33 >= 2.0
        book = RawBookSnapshot(
            timestamp_ns=now_ns,
            bids=((99.995, 10.0),),
            asks=((100.005, 10.0),),
            venue="COINBASE"
        )
        
        evaluation = self.engine.evaluate_economic_viability(
            current_time_ns=now_ns,
            book=book,
            trades_5s=[],
            directional_separation=1.0,
            target_bps=30.0, # Large target
            trade_size=1.0
        )
        
        self.assertTrue(evaluation.is_economically_viable)
        self.assertEqual(evaluation.tripwire_reason, "NOMINAL")
        self.assertTrue(evaluation.edge_to_cost_ratio >= 2.0)

    def test_wide_spread_suppresses_trades_regardless_of_directional_strength(self):
        """Verifies that wide spreads ($0.50+) automatically suppress trades, forcing NO-TRADE (NEGATIVE_EDGE)."""
        now_ns = 1000
        # Price = 100.0, Spread = 0.50 (50 bps spread!)
        # Even with max separation (1.0) and profit target of 10.0 bps,
        # Friction = 2 * 4 bps + 50 bps spread = 58 bps friction.
        # Net Return = 10.0 - 58.0 = -48.0 bps (NEGATIVE!)
        # ECR = 10 / 58 = 0.172 << 2.0 -> must suppress!
        wide_spread_book = RawBookSnapshot(
            timestamp_ns=now_ns,
            bids=((99.75, 10.0),),
            asks=((100.25, 10.0),),
            venue="COINBASE"
        )

        evaluation = self.engine.evaluate_economic_viability(
            current_time_ns=now_ns,
            book=wide_spread_book,
            trades_5s=[],
            directional_separation=1.0, # PERFECT directional separation!
            target_bps=10.0,
            trade_size=1.0
        )

        self.assertFalse(evaluation.is_economically_viable)
        self.assertEqual(evaluation.tripwire_reason, "NEGATIVE_EDGE_LOW_ECR")
        self.assertTrue(evaluation.expected_net_return_bps < 0.0)

    def test_queue_passive_fill_probability(self):
        """Verifies that queue passive fill probability scales dynamically with recent transaction velocity."""
        now_ns = 10 * 1_000_000_000 # 10 seconds
        
        # Book with L1 depth of 10.0
        book = RawBookSnapshot(
            timestamp_ns=now_ns,
            bids=((100.0, 10.0),),
            asks=((101.0, 10.0),),
            venue="COINBASE"
        )

        # Case A: No trades in window -> 0% fill probability
        prob_empty = self.engine.estimate_passive_fill_probability(book, [], horizon_sec=3.0)
        self.assertEqual(prob_empty, 0.0)

        # Case B: Some trades but below L1 depth queue size
        trades_low = [
            RawTradeEvent(timestamp_ns=now_ns - 1_000_000_000, price=100.0, size=2.0, side="BUY", venue="COINBASE")
        ]
        prob_low = self.engine.estimate_passive_fill_probability(book, trades_low, horizon_sec=3.0)
        self.assertTrue(0.0 < prob_low < 0.3)

        # Case C: Extreme transaction velocity (exceeds queue size) -> High fill probability
        trades_high = [
            RawTradeEvent(timestamp_ns=now_ns - 1_000_000_000, price=100.0, size=25.0, side="BUY", venue="COINBASE")
        ]
        prob_high = self.engine.estimate_passive_fill_probability(book, trades_high, horizon_sec=3.0)
        self.assertTrue(prob_high > 0.90)

if __name__ == "__main__":
    unittest.main()
