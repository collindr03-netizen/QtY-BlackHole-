import math
from typing import List, Tuple, Dict, Any, Optional
from dataclasses import dataclass
from qty_foundation import RawBookSnapshot, RawTradeEvent

# ==========================================
# Economic Evaluation Record Output
# ==========================================

@dataclass(frozen=True)
class EconomicEvaluation:
    __slots__ = [
        'timestamp_ns',
        'friction_bps',
        'expected_net_return_bps',
        'edge_to_cost_ratio',
        'passive_fill_probability',
        'is_economically_viable',
        'tripwire_reason'
    ]
    timestamp_ns: int
    friction_bps: float            # Total transaction friction in basis points (1 bp = 1e-4)
    expected_net_return_bps: float # Expected net return in basis points
    edge_to_cost_ratio: float      # ECR = Gross Return / Friction
    passive_fill_probability: float # Probability of passive limit fill inside 3s (0.0 to 1.0)
    is_economically_viable: bool   # True if expected payoff exceeds friction bounds
    tripwire_reason: str           # Reason for suppression if any


# ==========================================
# ENGINE E32: COST AND EDGE ENGINE
# ==========================================

class CostAndEdgeEngine:
    """
    Cost and Edge Engine (E32).
    Evaluates micro-market spreads, order book queues, and fee-structures to block trades 
    that are doomed to be unprofitable due to trading friction.
    """

    def __init__(
        self,
        taker_fee_rate: float = 0.0004, # +0.04% taker fee
        maker_fee_rate: float = -0.00005, # -0.005% maker rebate
        slippage_slope: float = 0.00002 # Slippage penalty coefficient per size unit
    ):
        self.taker_fee_rate = taker_fee_rate
        self.maker_fee_rate = maker_fee_rate
        self.slippage_slope = slippage_slope

    def estimate_slippage(self, book: RawBookSnapshot, trade_size: float) -> float:
        """
        Estimates dynamic slippage based on top level order book depth.
        Slippage increases if desired trade size exceeds L1 depth.
        """
        if not book.bids or not book.asks:
            return 0.0005 # 5 bps default penalty for blank books

        # Look at opposite side depth (Asks for BUY, Bids for SELL)
        top_ask_qty = book.asks[0][1]
        top_bid_qty = book.bids[0][1]
        avg_depth = (top_ask_qty + top_bid_qty) * 0.5

        if avg_depth <= 1e-9:
            return 0.0005

        # Penalty factor: more size than depth = increased slippage
        size_ratio = trade_size / avg_depth
        slippage = size_ratio * self.slippage_slope
        # Clamp slippage to a reasonable range [0.0, 100 bps]
        return max(0.0, min(0.01, slippage))

    def estimate_passive_fill_probability(
        self,
        book: RawBookSnapshot,
        trades_5s: List[RawTradeEvent],
        horizon_sec: float = 3.0
    ) -> float:
        """
        Estimates the probability of getting filled passively at the best bid/ask
        within 3 seconds based on recent trade arrival rates and L1 depth.
        """
        if not book.bids or not book.asks:
            return 0.0

        # Sum recent trade volume inside 3s to evaluate order queue clearance velocity
        cutoff_ns = book.timestamp_ns - int(horizon_sec * 1_000_000_000)
        recent_trade_volume = sum(t.size for t in trades_5s if t.timestamp_ns >= cutoff_ns)

        # Average Level-1 depth (queue size)
        l1_depth = (book.bids[0][1] + book.asks[0][1]) * 0.5
        if l1_depth <= 1e-9:
            return 0.0

        # Probability increases if recent trade volume exceeds the L1 queue size
        # P(fill) = 1 - exp(-trade_volume / L1_queue)
        ratio = recent_trade_volume / l1_depth
        fill_prob = 1.0 - math.exp(-ratio)
        return max(0.0, min(1.0, fill_prob))

    def evaluate_economic_viability(
        self,
        current_time_ns: int,
        book: RawBookSnapshot,
        trades_5s: List[RawTradeEvent],
        directional_separation: float, # D(t) from Dual-AI
        target_bps: float = 6.0,       # Profit target (e.g., 6.0 bps)
        trade_size: float = 1.0,       # Trade size in asset base
        force_maker_execution: bool = False
    ) -> EconomicEvaluation:
        """
        Friction = 2 * Fee_rate + (Spread / Price) + Slippage_est
        ECR = (|D(t)| * Target_bps) / Friction
        Expected_Net_Return = |D(t)| * Target_bps - Friction
        """
        # Determine Mid-Price & Spread
        if not book.bids or not book.asks:
            return EconomicEvaluation(
                timestamp_ns=current_time_ns,
                friction_bps=10.0,
                expected_net_return_bps=-10.0,
                edge_to_cost_ratio=0.0,
                passive_fill_probability=0.0,
                is_economically_viable=False,
                tripwire_reason="NO_BOOK_DEPTH"
            )

        bid = book.bids[0][0]
        ask = book.asks[0][0]
        price = (bid + ask) * 0.5
        spread = ask - bid

        # 1. Determine Fee structures (favor maker rebate if forced, otherwise taker)
        fee_rate = self.maker_fee_rate if force_maker_execution else self.taker_fee_rate

        # 2. Dynamic Slippage
        slippage_est = self.estimate_slippage(book, trade_size)

        # 3. Spread component
        spread_bps = (spread / price) * 10000.0 if price > 1e-9 else 0.0

        # Total Friction in basis points
        # 2 * fee_rate + spread / price + slippage
        raw_friction = (2.0 * fee_rate) + (spread / price) + slippage_est
        friction_bps = raw_friction * 10000.0

        # 4. Expected gross payoff
        abs_d_t = abs(directional_separation)
        expected_gross_bps = abs_d_t * target_bps

        # Net Return & Edge-to-Cost Ratio (ECR)
        expected_net_bps = expected_gross_bps - friction_bps
        
        if friction_bps > 1e-9:
            ecr = expected_gross_bps / friction_bps
        else:
            ecr = 999.0 # Division zero backup

        # 5. Queue fill probability
        fill_prob = self.estimate_passive_fill_probability(book, trades_5s, horizon_sec=3.0)

        # Economic Tripwire checks:
        # Require: ECR >= 2.0 AND Expected Net Return >= 2.5 bps
        is_viable = True
        tripwire_reason = "NOMINAL"

        if ecr < 2.0:
            is_viable = False
            tripwire_reason = "NEGATIVE_EDGE_LOW_ECR"
        elif expected_net_bps < 2.5:
            is_viable = False
            tripwire_reason = "NEGATIVE_EDGE_LOW_NET_RETURN"

        return EconomicEvaluation(
            timestamp_ns=current_time_ns,
            friction_bps=friction_bps,
            expected_net_return_bps=expected_net_bps,
            edge_to_cost_ratio=ecr,
            passive_fill_probability=fill_prob,
            is_economically_viable=is_viable,
            tripwire_reason=tripwire_reason
        )
