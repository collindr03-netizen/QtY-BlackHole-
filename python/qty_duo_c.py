import math
from typing import List, Tuple, Dict, Any, Optional
from dataclasses import dataclass
from qty_foundation import RawTradeEvent, RawBookSnapshot
from qty_features import TableCFeatureCalculator

# ==========================================
# Immutable Evidence Record Output
# ==========================================

@dataclass(frozen=True)
class EvidenceRecord:
    __slots__ = [
        'timestamp_ns',
        'ofi_contribution',
        'depth_contribution',
        'blended_raw_score',
        'absorption_flag',
        'persistence_duration_ms'
    ]
    timestamp_ns: int
    ofi_contribution: float       # FEAT-03 (OFI)
    depth_contribution: float     # FEAT-04 (Depth Imbalance)
    blended_raw_score: float      # Blended score S_micro bounded inside [-1.0, +1.0]
    absorption_flag: bool         # High passive limit absorption indicator
    persistence_duration_ms: int  # Lifetime expectation of evidence


# ==========================================
# DUO C MICROSTRUCTURAL EVIDENCE GENERATOR
# ==========================================

class DuoCEvidenceEngine:
    """
    Duo C: Order Flow Imbalance (OFI) + Order Book Depth Imbalance.
    Primary microstructural evidence generator.
    """

    def __init__(
        self,
        absorption_volume_threshold: float = 3.5,  # Large aggressor trade size limit
        absorption_price_tolerance: float = 0.15,  # Max micro-price shift allowed
        persistence_ms: int = 1800                 # Microstructural evidence decay horizon
    ):
        self.absorption_volume_threshold = absorption_volume_threshold
        self.absorption_price_tolerance = absorption_price_tolerance
        self.persistence_ms = persistence_ms

    def evaluate_evidence(
        self,
        current_time_ns: int,
        book: RawBookSnapshot,
        trades_5s: List[RawTradeEvent],
        price_history: List[Tuple[int, float]] # List of (timestamp_ns, microprice)
    ) -> EvidenceRecord:
        
        # 1. Compute Individual Core Features (Table C Consumptions)
        ofi_5s = TableCFeatureCalculator.calculate_order_flow_imbalance(
            current_time_ns=current_time_ns,
            trades=trades_5s,
            window_ms=5000.0
        )

        depth_imb = TableCFeatureCalculator.calculate_depth_imbalance(
            book=book,
            alpha=0.4
        )

        # 2. Multi-Scale Confirmation Check
        # Fast 1-second OFI
        ofi_1s = TableCFeatureCalculator.calculate_order_flow_imbalance(
            current_time_ns=current_time_ns,
            trades=trades_5s,
            window_ms=1000.0
        )

        # If they conflict in sign, we damp the evidence score by 50%
        sign_agreement = (ofi_1s * ofi_5s) >= 0.0
        damping_factor = 1.0 if sign_agreement else 0.5

        # Blended raw microstructural pressure score (equal 50/50 baseline weighting)
        blended = (0.5 * ofi_5s + 0.5 * depth_imb) * damping_factor
        blended_clamped = max(-1.0, min(1.0, blended))

        # 3. Localized Passive Liquidity Absorption Detection
        # Large aggressor volume failing to move the microprice
        absorption_flag = False
        
        # Check trades in the last 200ms
        cutoff_ns = current_time_ns - int(200 * 1_000_000)
        recent_trades = [t for t in trades_5s if cutoff_ns <= t.timestamp_ns <= current_time_ns]
        
        if recent_trades:
            # High-volume trade events
            total_recent_volume = sum(t.size for t in recent_trades)
            if total_recent_volume >= self.absorption_volume_threshold:
                # Find price shift over this 200ms window
                start_price = None
                for ts, p in price_history:
                    if ts >= cutoff_ns:
                        start_price = p
                        break
                
                if start_price is not None and len(price_history) > 0:
                    current_price = price_history[-1][1]
                    price_shift = abs(current_price - start_price)
                    
                    # Large volume + very low price change = institutional passive absorption
                    if price_shift <= self.absorption_price_tolerance:
                        absorption_flag = True

        return EvidenceRecord(
            timestamp_ns=current_time_ns,
            ofi_contribution=ofi_5s,
            depth_contribution=depth_imb,
            blended_raw_score=blended_clamped,
            absorption_flag=absorption_flag,
            persistence_duration_ms=self.persistence_ms
        )
