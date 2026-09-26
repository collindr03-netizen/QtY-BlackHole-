import unittest
import time
from qty_foundation import RawTradeEvent, RawBookSnapshot
from qty_duo_c import DuoCEvidenceEngine, EvidenceRecord

class DuoCEvidenceEngineUnitTest(unittest.TestCase):

    def setUp(self):
        # Setup Duo C engine with custom thresholds for testing
        self.engine = DuoCEvidenceEngine(
            absorption_volume_threshold=5.0,
            absorption_price_tolerance=0.05,
            persistence_ms=1800
        )

    def test_symmetric_inputs_produce_zero_bias(self):
        """Verifies that symmetric balanced inputs result in an exact zero directional bias score."""
        # Balanced book depth
        book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=((100.0, 10.0), (99.0, 10.0)),
            asks=((101.0, 10.0), (102.0, 10.0)),
            venue="COINBASE"
        )
        # Empty trades (0.0 OFI)
        record = self.engine.evaluate_evidence(
            current_time_ns=1000,
            book=book,
            trades_5s=[],
            price_history=[(1000, 100.5)]
        )
        self.assertEqual(record.ofi_contribution, 0.0)
        self.assertEqual(record.depth_contribution, 0.0)
        self.assertEqual(record.blended_raw_score, 0.0, "Symmetric inputs produced biased non-zero score!")

    def test_quote_cancellation_depletes_depth_score(self):
        """Verifies that sudden bid quote cancellations instantly reduce the depth score and blended pressure."""
        # 1. Healthy depth book (Bid support at L1 is strong: 50.0 vs Ask 10.0)
        healthy_book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=((100.0, 50.0),),
            asks=((101.0, 10.0),),
            venue="COINBASE"
        )
        rec_healthy = self.engine.evaluate_evidence(
            current_time_ns=1000,
            book=healthy_book,
            trades_5s=[],
            price_history=[(1000, 100.5)]
        )
        self.assertTrue(rec_healthy.depth_contribution > 0.0)
        
        # 2. Sudden bid cancellation (Bid support drops from 50.0 to 1.0)
        cancelled_book = RawBookSnapshot(
            timestamp_ns=1010,
            bids=((100.0, 1.0),),
            asks=((101.0, 10.0),),
            venue="COINBASE"
        )
        rec_cancelled = self.engine.evaluate_evidence(
            current_time_ns=1010,
            book=cancelled_book,
            trades_5s=[],
            price_history=[(1000, 100.5), (1010, 100.5)]
        )
        self.assertTrue(rec_cancelled.depth_contribution < 0.0, "Quote cancellation failed to deplete depth imbalance!")
        self.assertTrue(rec_cancelled.blended_raw_score < rec_healthy.blended_raw_score)

    def test_multi_scale_sign_conflict_damping(self):
        """Verifies that sign disagreement between fast 1s OFI and slow 5s OFI damps score by 50%."""
        now_ns = 10 * 1_000_000_000
        
        # We construct a conflict: 
        # - Slow 5s OFI has overall net Buy aggression: BUY 10 size at 4s ago
        # - Fast 1s OFI has sudden net Sell aggression: SELL 2 size at 0.5s ago
        # Net 5s aggregate is positive (10 - 2 = 8 Buy), Fast 1s is negative (-2 Sell) -> sign conflict!
        trades = [
            RawTradeEvent(timestamp_ns=now_ns - 4_000_000_000, price=100.0, size=10.0, side="BUY", venue="COINBASE"),
            RawTradeEvent(timestamp_ns=now_ns - 500_000_000, price=100.1, size=2.0, side="SELL", venue="COINBASE")
        ]
        
        # Strongly bullish book depth (+1.0)
        book = RawBookSnapshot(
            timestamp_ns=now_ns,
            bids=((100.0, 50.0),),
            asks=((101.0, 0.0),),
            venue="COINBASE"
        )
        
        # Baseline check (Without sign conflict, e.g. only buy trades)
        nominal_trades = [
            RawTradeEvent(timestamp_ns=now_ns - 4_000_000_000, price=100.0, size=10.0, side="BUY", venue="COINBASE"),
            RawTradeEvent(timestamp_ns=now_ns - 500_000_000, price=100.1, size=2.0, side="BUY", venue="COINBASE")
        ]
        rec_nominal = self.engine.evaluate_evidence(now_ns, book, nominal_trades, [(now_ns, 100.5)])
        
        # Conflict check
        rec_conflict = self.engine.evaluate_evidence(now_ns, book, trades, [(now_ns, 100.5)])
        
        # The conflicting multi-scale evidence must lead to damping
        self.assertLess(abs(rec_conflict.blended_raw_score), abs(rec_nominal.blended_raw_score), "Sign mismatch failed to damp blended evidence!")

    def test_passive_institutional_absorption(self):
        """Verifies passive liquidity absorption triggers when large trades fail to move prices."""
        now_ns = 5 * 1_000_000_000
        
        # Construct large aggressive BUY orders within last 200ms (Size: 8.0, threshold is 5.0)
        trades = [
            RawTradeEvent(timestamp_ns=now_ns - 50_000_000, price=100.0, size=8.0, side="BUY", venue="COINBASE")
        ]
        
        # Case A: Massive trades but price is highly sticky (fails to advance micro-price)
        # Price 200ms ago: 100.0, current price: 100.02 -> Shift: 0.02 <= 0.05 threshold -> ABSORPTION ACTIVE
        sticky_price_history = [
            (now_ns - 200_000_000, 100.0),
            (now_ns, 100.02)
        ]
        book = RawBookSnapshot(timestamp_ns=now_ns, bids=((100.0, 10.0),), asks=((101.0, 10.0),), venue="COINBASE")
        rec_absorbed = self.engine.evaluate_evidence(now_ns, book, trades, sticky_price_history)
        self.assertTrue(rec_absorbed.absorption_flag, "Large sticky transactions missed passive institutional absorption trigger!")

        # Case B: Large trades but price advances/slips normally (no absorption)
        # Price 200ms ago: 100.0, current price: 100.8 -> Shift: 0.8 > 0.05 threshold
        volatile_price_history = [
            (now_ns - 200_000_000, 100.0),
            (now_ns, 100.8)
        ]
        rec_normal = self.engine.evaluate_evidence(now_ns, book, trades, volatile_price_history)
        self.assertFalse(rec_normal.absorption_flag, "Volatile price movements incorrectly flagged as passive limit absorption!")

if __name__ == "__main__":
    unittest.main()
