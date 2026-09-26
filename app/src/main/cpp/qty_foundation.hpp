#pragma once

#include <atomic>
#include <vector>
#include <chrono>
#include <string>
#include <array>
#include <cstdint>
#include <algorithm>

namespace qty {

// ==========================================
// TABLE B: Immutable Data Structure Definitions
// ==========================================

struct RawTradeEvent {
    uint64_t timestamp_ns; // Nanosecond monotonic timestamp
    double price;
    double size;
    char side[8];          // "BUY" or "SELL"
    char venue[16];        // "COINBASE" or "KRAKEN"
};

struct BookLevel {
    double price;
    double quantity;
};

struct RawBookSnapshot {
    uint64_t timestamp_ns;
    BookLevel bids[20];    // Top 20 levels
    BookLevel asks[20];    // Top 20 levels
    char venue[16];
};

// ==========================================
// Thread-Safe, Lock-Free Circular Ring Buffer
// SPSC (Single Producer Single Consumer) with Pre-allocated Memory
// ==========================================
template <typename T, size_t Capacity>
class LockFreeRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2 to allow fast bitwise masking");

private:
    std::vector<T> buffer_;
    alignas(64) std::atomic<size_t> write_index_{0};
    alignas(64) std::atomic<size_t> read_index_{0};
    const size_t mask_ = Capacity - 1;

public:
    LockFreeRingBuffer() : buffer_(Capacity) {
        // Pre-allocate complete vector buffer to prevent runtime heap allocation or garbage collection interference
        std::fill(buffer_.begin(), buffer_.end(), T{});
    }

    // Producer Ingests Ticks
    bool push(const T& item) {
        size_t const write_idx = write_index_.load(std::memory_order_relaxed);
        size_t const read_idx = read_index_.load(std::memory_order_acquire);
        
        if (write_idx - read_idx >= Capacity) {
            return false; // Buffer overflow (Safety Fallback)
        }
        
        buffer_[write_idx & mask_] = item;
        write_index_.store(write_idx + 1, std::memory_order_release);
        return true;
    }

    // Consumer Pops Ticks
    bool pop(T& item) {
        size_t const read_idx = read_index_.load(std::memory_order_relaxed);
        size_t const write_idx = write_index_.load(std::memory_order_acquire);
        
        if (read_idx == write_idx) {
            return false; // Buffer empty (Wait state)
        }
        
        item = buffer_[read_idx & mask_];
        read_index_.store(read_idx + 1, std::memory_order_release);
        return true;
    }

    size_t size() const {
        size_t const write_idx = write_index_.load(std::memory_order_relaxed);
        size_t const read_idx = read_index_.load(std::memory_order_relaxed);
        return write_idx - read_idx;
    }

    size_t capacity() const {
        return Capacity;
    }
};

// ==========================================
// ENGINE E02: Low-Latency Data Integrity Engine
// ==========================================
class DataIntegrityEngine {
private:
    std::atomic<bool> tripwire_flag_{false}; // false = Nominal, true = DATA_INTEGRITY_FAIL_CLOSED
    std::atomic<uint64_t> expected_sequence_{0};
    std::atomic<uint64_t> last_timestamp_ns_{0};

public:
    DataIntegrityEngine() = default;

    /**
     * Assesses packets for clock drift, sequence IDs, and chronological monotonic order.
     * @param packet_seq Received index count on connection
     * @param venue_timestamp_ns Clock time embedded by the broker/venue
     * @param local_timestamp_ns Local precision monotonic computer clock
     */
    void process_packet(uint64_t packet_seq, uint64_t venue_timestamp_ns, uint64_t local_timestamp_ns) {
        // 1. Clock Drift Gating (25 milliseconds = 25,000,000 nanoseconds)
        uint64_t const drift = (local_timestamp_ns > venue_timestamp_ns) ? 
                               (local_timestamp_ns - venue_timestamp_ns) : 
                               (venue_timestamp_ns - local_timestamp_ns);
        
        if (drift > 25000000ULL) {
            tripwire_flag_.store(true, std::memory_order_release);
        }

        // 2. Out-of-Order / Dropped Packets check
        uint64_t const expected = expected_sequence_.load(std::memory_order_relaxed);
        if (expected != 0 && packet_seq != expected) {
            tripwire_flag_.store(true, std::memory_order_release);
        }
        expected_sequence_.store(packet_seq + 1, std::memory_order_relaxed);

        // 3. Monotonic Timestamp Gating (chronological validity)
        uint64_t const last_ts = last_timestamp_ns_.load(std::memory_order_relaxed);
        if (venue_timestamp_ns < last_ts) {
            tripwire_flag_.store(true, std::memory_order_release);
        }
        last_timestamp_ns_.store(venue_timestamp_ns, std::memory_order_relaxed);
    }

    bool is_tripwired() const {
        return tripwire_flag_.load(std::memory_order_acquire);
    }

    void reset_tripwire() {
        tripwire_flag_.store(false, std::memory_order_release);
        expected_sequence_.store(0, std::memory_order_relaxed);
        last_timestamp_ns_.store(0, std::memory_order_relaxed);
    }
};

} // namespace qty
