import asyncio
import time
import json
import logging
from dataclasses import dataclass
from typing import List, Tuple, Optional, Dict, Any

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] (%(threadName)s) %(message)s")
logger = logging.getLogger("QtY_Foundation")

# ==========================================
# TABLE B: Raw Ingestion Payloads (Strict Immutable Data Classes)
# ==========================================

@dataclass(frozen=True)
class RawTradeEvent:
    __slots__ = ['timestamp_ns', 'price', 'size', 'side', 'venue']
    timestamp_ns: int   # Nanosecond precision monotonic time
    price: float
    size: float
    side: str           # "BUY" or "SELL"
    venue: str          # "COINBASE" or "KRAKEN"

@dataclass(frozen=True)
class RawBookSnapshot:
    __slots__ = ['timestamp_ns', 'bids', 'asks', 'venue']
    timestamp_ns: int
    bids: Tuple[Tuple[float, float], ...] # Top 20 bids (price, quantity)
    asks: Tuple[Tuple[float, float], ...] # Top 20 asks (price, quantity)
    venue: str


# ==========================================
# TABLE A: Connection Registry & State Watchdog
# ==========================================

@dataclass
class ConnectionState:
    venue: str
    endpoint: str
    protocol: str       # "WebSocket" or "FIX"
    subscribed_symbols: List[str]
    latency_ms: float
    packet_count: int
    connected: bool


# ==========================================
# Pre-allocated, Garbage-Collection Free Circular Ring Buffer
# ==========================================
class PreallocatedRingBuffer:
    def __init__(self, capacity: int = 131072):
        # Ensure capacity is a power of 2 for fast masking
        if capacity & (capacity - 1) != 0:
            raise ValueError("Buffer capacity must be a power of 2.")
        
        self.capacity = capacity
        self.mask = capacity - 1
        
        # Pre-allocate array slots to avoid dynamic allocation and GC penalties during fast ingestion
        self.buffer = [None] * capacity
        self.write_idx = 0
        self.read_idx = 0

    def push(self, event) -> bool:
        """Producer appends item to buffer. Return False if full."""
        if self.write_idx - self.read_idx >= self.capacity:
            return False  # Buffer Overflow!
        
        self.buffer[self.write_idx & self.mask] = event
        self.write_idx += 1
        return True

    def pop(self) -> Optional[Any]:
        """Consumer reads item from buffer. Return None if empty."""
        if self.read_idx == self.write_idx:
            return None  # Empty
        
        event = self.buffer[self.read_idx & self.mask]
        # Release the reference immediately to keep memory usage flat without GC wait times
        self.buffer[self.read_idx & self.mask] = None
        self.read_idx += 1
        return event

    def size(self) -> int:
        return self.write_idx - self.read_idx


# ==========================================
# ENGINE E02: Data Integrity Gating
# ==========================================
class DataIntegrityEngine:
    def __init__(self, max_clock_drift_ms: float = 25.0):
        self.max_drift_ns = int(max_clock_drift_ms * 1_000_000)
        self.tripwire_flag = False  # false = nominal, true = DATA_INTEGRITY_FAIL_CLOSED
        self.expected_sequence: Dict[str, int] = {}
        self.last_venue_time_ns: Dict[str, int] = {}

    def process_packet(self, venue: str, seq: int, venue_timestamp_ns: int, local_timestamp_ns: int):
        if self.tripwire_flag:
            return # Locked in closed state

        # 1. Clock Drift Evaluation
        drift = abs(local_timestamp_ns - venue_timestamp_ns)
        if drift > self.max_drift_ns:
            logger.error(f"[DATA INTEGRITY TRIP] Clock drift exceeded! Drift: {drift / 1_000_000:.2f}ms. Threshold: {self.max_drift_ns / 1_000_000}ms.")
            self.tripwire_flag = True

        # 2. Sequence Gap Evaluation (Packet drop checks)
        expected = self.expected_sequence.get(venue, 0)
        if expected != 0 and seq != expected:
            logger.error(f"[DATA INTEGRITY TRIP] Packet sequence gap detected on {venue}! Expected: {expected}, Got: {seq}.")
            self.tripwire_flag = True
        self.expected_sequence[venue] = seq + 1

        # 3. Packet Ordering Monotonic Check
        last_time = self.last_venue_time_ns.get(venue, 0)
        if venue_timestamp_ns < last_time:
            logger.error(f"[DATA INTEGRITY TRIP] Out-of-order packet timestamps received on {venue}! Last: {last_time}, Received: {venue_timestamp_ns}.")
            self.tripwire_flag = True
        self.last_venue_time_ns[venue] = venue_timestamp_ns


# ==========================================
# ASYNC WEBSOCKET INGESTION CLIENT
# ==========================================
class LiveIngestionManager:
    def __init__(self, buffer: PreallocatedRingBuffer, integrity_engine: DataIntegrityEngine):
        self.buffer = buffer
        self.integrity_engine = integrity_engine
        self.running = False
        
        # Connection Registry (Table A)
        self.registry = {
            "COINBASE": ConnectionState(
                venue="COINBASE",
                endpoint="wss://advanced-trade-ws.coinbase.com",
                protocol="WebSocket",
                subscribed_symbols=["BTC-USD"],
                latency_ms=0.0,
                packet_count=0,
                connected=False
            ),
            "KRAKEN": ConnectionState(
                venue="KRAKEN",
                endpoint="wss://ws.kraken.com/v2",
                protocol="WebSocket",
                subscribed_symbols=["BTC/USD"],
                latency_ms=0.0,
                packet_count=0,
                connected=False
            )
        }

    async def run_coinbase_intake(self):
        """Asynchronous client for Coinbase Advanced Streaming."""
        state = self.registry["COINBASE"]
        seq = 1
        
        # In a real run, this would loop connection retries with websockets
        logger.info("Initializing Coinbase Advanced WebSocket stream client...")
        state.connected = True
        
        while self.running:
            # Simulate high-frequency trades streaming from WebSocket payload
            await asyncio.sleep(0.015) # ~66 Hz ticker
            
            local_now = time.time_ns()
            # Simulate slight latency drift (e.g., 2-8ms nominal)
            venue_now = local_now - int(3.5 * 1_000_000)
            
            state.packet_count += 1
            
            # Construct Immutable Event
            trade_ev = RawTradeEvent(
                timestamp_ns=venue_now,
                price=96450.0 + (seq % 100) * 0.1,
                size=0.05 + (seq % 5) * 0.01,
                side="BUY" if seq % 2 == 0 else "SELL",
                venue="COINBASE"
            )
            
            # Decoupled ingestion safety checks
            self.integrity_engine.process_packet("COINBASE", seq, venue_now, local_now)
            
            # Push to the pre-allocated zero-GC circular buffer
            pushed = self.buffer.push(trade_ev)
            if not pushed:
                logger.warning("Circular Ingestion Buffer overflow on Coinbase trade arrival! Ingest worker skipping tick.")
            
            seq += 1

    async def run_kraken_intake(self):
        """Asynchronous client for Kraken Pro Streaming."""
        state = self.registry["KRAKEN"]
        seq = 1
        
        logger.info("Initializing Kraken Pro WebSocket stream client...")
        state.connected = True
        
        while self.running:
            await asyncio.sleep(0.01) # ~100 Hz book events
            
            local_now = time.time_ns()
            venue_now = local_now - int(4.2 * 1_000_000)
            
            state.packet_count += 1
            
            # Pre-allocated structural content representation
            bids = tuple((96440.0 - i, 2.5 / (i + 1)) for i in range(20))
            asks = tuple((96460.0 + i, 2.5 / (i + 1)) for i in range(20))
            
            book_ev = RawBookSnapshot(
                timestamp_ns=venue_now,
                bids=bids,
                asks=asks,
                venue="KRAKEN"
            )
            
            self.integrity_engine.process_packet("KRAKEN", seq, venue_now, local_now)
            
            pushed = self.buffer.push(book_ev)
            if not pushed:
                logger.warning("Circular Ingestion Buffer overflow on Kraken snapshot arrival!")
                
            seq += 1

    async def start_ingestion_workers(self):
        self.running = True
        logger.info("Starting sub-18s high-precision ingestion loop...")
        await asyncio.gather(
            self.run_coinbase_intake(),
            self.run_kraken_intake()
        )

    def stop(self):
        self.running = False
        logger.info("Stopping ingestion workers...")
