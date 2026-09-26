import unittest
import math
import time
from qty_foundation import RawTradeEvent, RawBookSnapshot
from qty_features import TableCFeatureCalculator, FeatureTensor

class FeatureEngineeringUnitTest(unittest.TestCase):

    def setUp(self):
        self.calc = TableCFeatureCalculator()

    def test_micro_price_nominal_and_zero_volume_fallback(self):
        """Verifies micro price calculation under standard states and zero-volume conditions."""
        # Standard: Bid $100 Qty 10, Ask $102 Qty 30
        # Expected: (100 * 30 + 102 * 10) / (10 + 30) = (3000 + 1020) / 40 = 4020 / 40 = 100.5
        book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=((100.0, 10.0),),
            asks=((102.0, 30.0),),
            venue="COINBASE"
        )
        micro = self.calc.calculate_micro_price(book)
        self.assertEqual(micro, 100.5)

        # Zero volume boundary test (Ensures fallback to mid-price)
        zero_vol_book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=((100.0, 0.0),),
            asks=((102.0, 0.0),),
            venue="COINBASE"
        )
        fallback_micro = self.calc.calculate_micro_price(zero_vol_book)
        self.assertEqual(fallback_micro, 101.0, "Zero volume failed to gracefully fallback to simple mid-price!")

    def test_log_returns_without_lookahead_bias(self):
        """Verifies log returns calculations across 500ms, 2s, and 10s windows with NO lookahead bias."""
        now_ns = 20 * 1_000_000_000 # 20 seconds
        
        # Build 10s price history
        history = []
        for s in range(30):
            # Price starts at 100 and rises to 130
            history.append((s * 1_000_000_000, 100.0 + s))

        # Check 2-second return at current timestamp (20 seconds, current price: 120.0)
        # Target timestamp is 20s - 2s = 18s (price at 18s is 118.0)
        # Expected return: log(120.0 / 118.0)
        ret_2s = self.calc.calculate_log_returns(now_ns, history[:21], 2000.0)
        expected = math.log(120.0 / 118.0)
        self.assertAlmostEqual(ret_2s, expected, places=5)

        # Lookahead Bias protection verification:
        # If we supply the entire historical list including FUTURE prices (e.g. up to 25s),
        # but calculate at 20s, the calculation should remain strictly backward-looking and ignore indices > 20s.
        biased_ret_2s = self.calc.calculate_log_returns(now_ns, history, 2000.0)
        self.assertEqual(biased_ret_2s, ret_2s, "Feature calculation touched future price indices (lookahead bias detected)!")

    def test_order_flow_imbalance_bounding(self):
        """Verifies rolling 5-second OFI calculations and its strict [-1.0, 1.0] bounding limits."""
        now_ns = 10 * 1_000_000_000
        
        # Case 1: Pure Buyer aggression (Ratio must equal +1.0)
        trades_buy_only = [
            RawTradeEvent(timestamp_ns=now_ns - 2_000_000_000, price=100.0, size=5.0, side="BUY", venue="COINBASE"),
            RawTradeEvent(timestamp_ns=now_ns - 1_000_000_000, price=100.1, size=2.5, side="BUY", venue="COINBASE")
        ]
        ofi_buy = self.calc.calculate_order_flow_imbalance(now_ns, trades_buy_only, 5000.0)
        self.assertEqual(ofi_buy, 1.0)

        # Case 2: Pure Seller aggression (Ratio must equal -1.0)
        trades_sell_only = [
            RawTradeEvent(timestamp_ns=now_ns - 1_000_000_000, price=99.9, size=10.0, side="SELL", venue="COINBASE")
        ]
        ofi_sell = self.calc.calculate_order_flow_imbalance(now_ns, trades_sell_only, 5000.0)
        self.assertEqual(ofi_sell, -1.0)

        # Case 3: Balanced trade flow
        trades_mixed = [
            RawTradeEvent(timestamp_ns=now_ns - 2_000_000_000, price=100.0, size=10.0, side="BUY", venue="COINBASE"),
            RawTradeEvent(timestamp_ns=now_ns - 1_000_000_000, price=99.9, size=10.0, side="SELL", venue="COINBASE")
        ]
        ofi_mixed = self.calc.calculate_order_flow_imbalance(now_ns, trades_mixed, 5000.0)
        self.assertEqual(ofi_mixed, 0.0)

        # Case 4: No trades in rolling window (Ensure 0 is returned cleanly without exception)
        ofi_empty = self.calc.calculate_order_flow_imbalance(now_ns, [], 5000.0)
        self.assertEqual(ofi_empty, 0.0)

    def test_depth_imbalance_weighted_top_5(self):
        """Verifies exponentially decaying depth imbalance calculation on top 5 order book snapshots."""
        # Top 5 bids have massive liquidity blocks, top asks have thin volume
        bids = tuple((100.0 - i, 100.0) for i in range(5))
        asks = tuple((102.0 + i, 1.0) for i in range(5))
        
        book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=bids,
            asks=asks,
            venue="COINBASE"
        )
        
        imbalance = self.calc.calculate_depth_imbalance(book, alpha=0.4)
        # Expected: highly positive imbalance close to +1.0
        self.assertTrue(0.9 < imbalance <= 1.0)

        # Reverse: top asks have massive volume, top bids have thin volume
        reverse_book = RawBookSnapshot(
            timestamp_ns=1000,
            bids=asks, # bids are small now
            asks=bids, # asks are massive
            venue="COINBASE"
        )
        reverse_imbalance = self.calc.calculate_depth_imbalance(reverse_book, alpha=0.4)
        self.assertTrue(-1.0 <= reverse_imbalance < -0.9)

    def test_garman_klass_volatility_clamping(self):
        """Verifies Garman-Klass volatility proxy under volatile periods and clamps safely above zero."""
        now_ns = 35 * 1_000_000_000
        
        # Build price history spanning 30 seconds
        history = []
        for s in range(40):
            # Modulate prices up and down to create high/low/close bounds
            p = 100.0 + 5.0 * math.sin(s * 0.5)
            history.append((s * 1_000_000_000, p))

        vol = self.calc.calculate_garman_klass_volatility(now_ns, history, 30000.0)
        self.assertTrue(vol > 0.0, "Garman-Klass realized volatility proxy failed to generate positive scale!")

        # Edge Case: perfectly flat prices (GK must clamp to 0.0 without throwing exceptions)
        flat_history = [(s * 1_000_000_000, 100.0) for s in range(40)]
        flat_vol = self.calc.calculate_garman_klass_volatility(now_ns, flat_history, 30000.0)
        self.assertEqual(flat_vol, 0.0, "Flat price series returned non-zero or NaN volatility!")

    def test_z_score_with_variance_clamping(self):
        """Verifies standardized dev z-scores and asserts that the variance floor clamps division anomalies."""
        history = [100.0, 100.0, 100.0] # zero variance raw history
        
        # If variance floor is 1e-6 (default), the z-score of 100.0 should be 0.0
        z = self.calc.calculate_z_score(100.0, history, min_variance_floor=1e-6)
        self.assertEqual(z, 0.0)

        # Standard non-zero variance case
        active_history = [98.0, 100.0, 102.0] # mean 100.0, variance 8/3 = 2.666
        z_std = self.calc.calculate_z_score(103.0, active_history)
        self.assertTrue(z_std > 0.0)

    def test_extreme_flash_crash_and_exception_gating(self):
        """Simulates a $500 flash crash within 1 tick, verifying that no engine crashes or divides by zero."""
        now_ns = 5 * 1_000_000_000
        
        # Raw prices collapse from 96000 to 100
        crash_history = [
            (0, 96000.0),
            (1_000_000_000, 96000.0),
            (2_000_000_000, 96005.0),
            (3_000_000_000, 100.0) # $95,900 flash collapse!
        ]

        # Calculate log-return (should handle the massive negative returns safely)
        ret_500ms = self.calc.calculate_log_returns(3_000_000_000, crash_history, 500.0)
        self.assertTrue(ret_500ms < 0.0)
        self.assertTrue(math.isfinite(ret_500ms))

        # Check depth snapshot of collapsed thin book (with 0 spreads or overlap values)
        crash_book = RawBookSnapshot(
            timestamp_ns=3_000_000_000,
            bids=((100.0, 0.0001),), # extremely low bids volume
            asks=((100.0, 0.0001),), # overlapping asks volume (crossed book crash state)
            venue="COINBASE"
        )
        
        micro = self.calc.calculate_micro_price(crash_book)
        self.assertEqual(micro, 100.0) # handles overlapping crossed bids/asks safely

        imbalance = self.calc.calculate_depth_imbalance(crash_book)
        self.assertEqual(imbalance, 0.0)

if __name__ == "__main__":
    unittest.main()
