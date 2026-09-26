import math
from dataclasses import dataclass
from typing import List, Tuple, Dict, Optional, Union
from qty_foundation import RawTradeEvent, RawBookSnapshot

# ==========================================
# TABLE C: Immutable Feature Tensor Definition
# ==========================================

@dataclass(frozen=True)
class FeatureTensor:
    __slots__ = [
        'timestamp_ns',
        'micro_price',
        'log_return_500ms',
        'log_return_2s',
        'log_return_10s',
        'order_flow_imbalance',
        'depth_imbalance',
        'garman_klass_vol',
        'z_score'
    ]
    timestamp_ns: int
    micro_price: float
    log_return_500ms: float
    log_return_2s: float
    log_return_10s: float
    order_flow_imbalance: float # OFI rolling 5s
    depth_imbalance: float      # Exponentially weighted top 5
    garman_klass_vol: float     # Realized 30s volatility proxy
    z_score: float              # Standardized micro-price dev


# ==========================================
# Vectorized, Stateless Feature Calculations
# ==========================================

class TableCFeatureCalculator:
    """
    Stateless calculation engine.
    Computes features strictly backward-looking from a given snapshot.
    Ensures zero lookahead bias and robust zero-division clamping.
    """

    @staticmethod
    def calculate_micro_price(book: RawBookSnapshot) -> float:
        """
        Calculates weighted top-of-book price using top bid/ask volumes.
        If total volume at L1 is 0, falls back to mid-price.
        """
        if not book.bids or not book.asks:
            return 0.0

        bid_p, bid_q = book.bids[0]
        ask_p, ask_q = book.asks[0]

        total_volume = bid_q + ask_q
        if total_volume <= 1e-9:
            # Fallback to simple arithmetic mid-price
            return (bid_p + ask_p) * 0.5

        # Microprice = (BidPrice * AskQty + AskPrice * BidQty) / (BidQty + AskQty)
        return (bid_p * ask_q + ask_p * bid_q) / total_volume

    @staticmethod
    def calculate_log_returns(
        current_time_ns: int,
        price_history: List[Tuple[int, float]], # List of (timestamp_ns, price)
        lookback_ms: float
    ) -> float:
        """
        Calculates log-returns relative to a backward looking horizon.
        Uses exact historical timestamp search to prevent interpolation lookahead bias.
        """
        if len(price_history) < 2:
            return 0.0

        target_time_ns = current_time_ns - int(lookback_ms * 1_000_000)
        
        # Find the actual current price at current_time_ns (strictly backward-looking)
        current_price = None
        for ts, p in reversed(price_history):
            if ts <= current_time_ns:
                current_price = p
                break

        if current_price is None:
            return 0.0

        # Find closest historical point <= target_time_ns (strictly backward-looking)
        best_price = None
        for ts, p in reversed(price_history):
            if ts <= target_time_ns:
                best_price = p
                break

        if best_price is None or best_price <= 1e-9 or current_price <= 1e-9:
            return 0.0

        return math.log(current_price / best_price)

    @staticmethod
    def calculate_order_flow_imbalance(
        current_time_ns: int,
        trades: List[RawTradeEvent],
        window_ms: float = 5000.0
    ) -> float:
        """
        Ratio of net aggressive buy volume minus sell volume over total volume.
        Across a rolling window of trades, bounded strictly within [-1.0, +1.0].
        """
        cutoff_ns = current_time_ns - int(window_ms * 1_000_000)
        
        net_agg_volume = 0.0
        total_volume = 0.0

        for trade in reversed(trades):
            if trade.timestamp_ns < cutoff_ns:
                break
            
            # Filter future packets (Safety check)
            if trade.timestamp_ns > current_time_ns:
                continue

            vol = trade.size
            total_volume += vol
            if trade.side.upper() == "BUY":
                net_agg_volume += vol
            else:
                net_agg_volume -= vol

        if total_volume <= 1e-9:
            return 0.0

        # Bound strictly inside [-1.0, +1.0]
        ratio = net_agg_volume / total_volume
        return max(-1.0, min(1.0, ratio))

    @staticmethod
    def calculate_depth_imbalance(book: RawBookSnapshot, alpha: float = 0.4) -> float:
        """
        Exponentially weighted top-5 order-book depth imbalance.
        Uses top 5 bids/asks, weighting closer levels more heavily.
        Bounded strictly within [-1.0, +1.0].
        """
        if len(book.bids) < 5 or len(book.asks) < 5:
            # Fallback to available levels
            num_levels = min(len(book.bids), len(book.asks))
        else:
            num_levels = 5

        if num_levels == 0:
            return 0.0

        weighted_bids_qty = 0.0
        weighted_asks_qty = 0.0

        # Calculate decay weight: w_i = e^(-alpha * i)
        for i in range(num_levels):
            weight = math.exp(-alpha * i)
            weighted_bids_qty += book.bids[i][1] * weight
            weighted_asks_qty += book.asks[i][1] * weight

        total_weighted_qty = weighted_bids_qty + weighted_asks_qty
        if total_weighted_qty <= 1e-9:
            return 0.0

        imbalance = (weighted_bids_qty - weighted_asks_qty) / total_weighted_qty
        return max(-1.0, min(1.0, imbalance))

    @staticmethod
    def calculate_garman_klass_volatility(
        current_time_ns: int,
        price_history: List[Tuple[int, float]], # List of (timestamp_ns, price)
        window_ms: float = 30000.0
    ) -> float:
        """
        Realized realized high-frequency volatility estimate using GK formalisms.
        For continuous tick sequences, we split the 30-second window into 10 smaller intervals (e.g. 3s each)
        and compute: 0.5 * (ln(H/L))^2 - (2*ln(2) - 1) * (ln(C/O))^2.
        Clamped safely above zero.
        """
        cutoff_ns = current_time_ns - int(window_ms * 1_000_000)
        
        # Extract points in window
        window_prices = [p for ts, p in price_history if cutoff_ns <= ts <= current_time_ns]
        if len(window_prices) < 4:
            return 0.0

        # Divide into up to 5 interval chunks to evaluate OHLC attributes dynamically
        chunk_size = max(1, len(window_prices) // 5)
        chunks = [window_prices[i:i + chunk_size] for i in range(0, len(window_prices), chunk_size)]

        sum_gk = 0.0
        valid_chunks = 0

        for chunk in chunks:
            if len(chunk) < 2:
                continue
            
            o = chunk[0]
            c = chunk[-1]
            h = max(chunk)
            l = min(chunk)

            if o <= 1e-9 or c <= 1e-9 or h <= 1e-9 or l <= 1e-9:
                continue

            log_hl = math.log(h / l)
            log_co = math.log(c / o)

            term1 = 0.5 * (log_hl * log_hl)
            term2 = (2.0 * math.log(2.0) - 1.0) * (log_co * log_co)
            
            sum_gk += max(0.0, term1 - term2)
            valid_chunks += 1

        if valid_chunks == 0:
            return 0.0

        # Return root mean realized GK proxy
        return math.sqrt(sum_gk / valid_chunks)

    @staticmethod
    def calculate_z_score(
        current_val: float,
        history: List[float],
        min_variance_floor: float = 1e-6
    ) -> float:
        """
        Standardized deviations using backward-looking rolling means and standard deviations.
        Applies strict variance floor clamping to prevent zero-division infinity spikes.
        """
        if len(history) < 3:
            return 0.0

        mean = sum(history) / len(history)
        variance = sum((x - mean) ** 2 for x in history) / len(history)
        
        # Apply strict variance floor clamp
        clamped_variance = max(variance, min_variance_floor)
        std_dev = math.sqrt(clamped_variance)

        return (current_val - mean) / std_dev
