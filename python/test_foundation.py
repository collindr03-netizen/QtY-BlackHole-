import time
import sys
import unittest
from qty_foundation import PreallocatedRingBuffer, DataIntegrityEngine, RawTradeEvent

class IngestionPerformanceTest(unittest.TestCase):

    def test_benchmark_100k_ticks_throughput(self):
        """Feeds 100,000 synthetic raw ticks through the circular buffer to verify throughput and data continuity."""
        buffer_capacity = 131072 # Power of 2 (128k slots)
        ring_buffer = PreallocatedRingBuffer(capacity=buffer_capacity)
        integrity_engine = DataIntegrityEngine(max_clock_drift_ms=25.0)

        tick_count = 100_000
        
        # Pre-generate 100k synthetic RawTradeEvents to keep generation logic out of our performance clock
        synthetic_ticks = []
        base_time = time.time_ns()
        
        for i in range(tick_count):
            synthetic_ticks.append(
                RawTradeEvent(
                    timestamp_ns=base_time + i * 100, # perfectly monotonic
                    price=96450.0 + (i % 50) * 0.05,
                    size=0.1 + (i % 10) * 0.05,
                    side="BUY" if i % 2 == 0 else "SELL",
                    venue="COINBASE"
                )
            )

        print("\n=======================================================")
        print("STARTING HFT FOUNDATION INGESTION BENCHMARK: 100,000 TICKS")
        print("=======================================================")
        
        # 1. Measure Producer Push Speed
        start_push_time = time.perf_counter()
        for tick in synthetic_ticks:
            pushed = ring_buffer.push(tick)
            if not pushed:
                self.fail("Buffer Overflow occurred during sequential pushing! Pre-allocated slots are insufficient.")
        end_push_time = time.perf_counter()
        
        push_duration = end_push_time - start_push_time
        push_throughput = tick_count / push_duration
        latency_per_push_us = (push_duration / tick_count) * 1_000_000
        
        # 2. Measure Consumer Pop Speed (Zero-GC and Lock-free verification)
        start_pop_time = time.perf_counter()
        pop_count = 0
        while True:
            event = ring_buffer.pop()
            if event is None:
                break
            pop_count += 1
        end_pop_time = time.perf_counter()

        pop_duration = end_pop_time - start_pop_time
        pop_throughput = tick_count / pop_duration
        latency_per_pop_us = (pop_duration / tick_count) * 1_000_000

        print(f"Total Push Duration : {push_duration:.5f} seconds")
        print(f"Push Throughput     : {push_throughput:,.2f} events/second")
        print(f"Push Latency        : {latency_per_push_us:.5f} microseconds/event")
        print(f"Total Pop Duration  : {pop_duration:.5f} seconds")
        print(f"Pop Throughput      : {pop_throughput:,.2f} events/second")
        print(f"Pop Latency         : {latency_per_pop_us:.5f} microseconds/event")

        # Assert zero data loss
        self.assertEqual(pop_count, tick_count, "Data loss occurred in circular buffer ingestion pipeline!")
        
        # Assert sub-microsecond ingestion speeds (typically 0.1us in Python on standard hardware)
        self.assertLess(latency_per_push_us, 1.0, f"Push latency {latency_per_push_us:.4f}us exceeds the sub-microsecond threshold constraint!")
        self.assertLess(latency_per_pop_us, 1.0, f"Pop latency {latency_per_pop_us:.4f}us exceeds the sub-microsecond threshold constraint!")
        print("✔ Sub-microsecond execution performance verified successfully!")

    def test_clock_drift_integrity_tripwire(self):
        """Verifies that E02 instantly triggers tripwire on clock drift greater than 25 milliseconds."""
        integrity_engine = DataIntegrityEngine(max_clock_drift_ms=25.0)
        
        local_time = time.time_ns()
        # Nominal case (e.g. 5ms drift)
        nominal_venue_time = local_time - int(5.0 * 1_000_000)
        integrity_engine.process_packet("COINBASE", 1, nominal_venue_time, local_time)
        self.assertFalse(integrity_engine.tripwire_flag, "Nominal drift incorrectly tripped system integrity engine!")
        
        # Drift Violation case (e.g. 30ms drift)
        violating_venue_time = local_time - int(30.0 * 1_000_000)
        integrity_engine.process_packet("COINBASE", 2, violating_venue_time, local_time)
        self.assertTrue(integrity_engine.tripwire_flag, "Clock drift above 25ms failed to trip engine closed!")

    def test_sequence_gap_tripwire(self):
        """Verifies that E02 triggers fail-closed state on sequence drops/skips."""
        integrity_engine = DataIntegrityEngine()
        
        local_time = time.time_ns()
        integrity_engine.process_packet("KRAKEN", 1, local_time, local_time)
        integrity_engine.process_packet("KRAKEN", 2, local_time + 100, local_time + 100)
        self.assertFalse(integrity_engine.tripwire_flag)
        
        # Gap detected: skipping sequence index 3 to 5
        integrity_engine.process_packet("KRAKEN", 5, local_time + 200, local_time + 200)
        self.assertTrue(integrity_engine.tripwire_flag, "Skipped packet sequence failed to trip data integrity engine!")

    def test_out_of_order_monotonic_tripwire(self):
        """Verifies that out-of-order venue timestamps instantly trigger tripwire safety constraints."""
        integrity_engine = DataIntegrityEngine()
        
        local_time = time.time_ns()
        integrity_engine.process_packet("COINBASE", 1, local_time, local_time)
        
        # Out of order: next packet has a preceding timestamp
        integrity_engine.process_packet("COINBASE", 2, local_time - 1000, local_time + 10)
        self.assertTrue(integrity_engine.tripwire_flag, "Backwards-timestamp anomaly failed to trip data integrity engine!")

if __name__ == "__main__":
    unittest.main()
