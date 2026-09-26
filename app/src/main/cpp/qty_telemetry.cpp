#include <jni.h>
#include <vector>
#include <cmath>
#include <numeric>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "QtY_64_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Calculates the Lyapunov Exponent of a short-term price history to assess market stability.
 * Positive values indicate high divergence (chaotic), while negative or near-zero indicate predictability.
 */
JNIEXPORT jdouble JNICALL
Java_com_example_telemetry_TelemetryBridge_calculateLyapunov(
    JNIEnv *env,
    jobject thiz,
    jdoubleArray prices_array
) {
    if (!prices_array) return 0.0;
    
    jsize len = env->GetArrayLength(prices_array);
    if (len < 5) return 0.0; // Needs sufficient samples
    
    jdouble* prices = env->GetDoubleArrayElements(prices_array, nullptr);
    if (!prices) return 0.0;

    std::vector<double> log_diffs;
    log_diffs.reserve(len - 1);
    
    for (int i = 0; i < len - 1; ++i) {
        double d0 = std::abs(prices[i]);
        double d1 = std::abs(prices[i + 1]);
        if (d0 > 0.0 && d1 > 0.0) {
            log_diffs.push_back(std::log(d1 / d0));
        }
    }
    
    env->ReleaseDoubleArrayElements(prices_array, prices, JNI_ABORT);
    
    if (log_diffs.size() < 2) return 0.0;
    
    // Compute mean divergence rate (estimating the exponent lambda)
    double sum = 0.0;
    for (double val : log_diffs) {
        sum += val;
    }
    double mean = sum / log_diffs.size();
    
    double variance_sum = 0.0;
    for (double val : log_diffs) {
        variance_sum += (val - mean) * (val - mean);
    }
    double std_dev = std::sqrt(variance_sum / log_diffs.size());
    
    // Lyapunov exponent approximation
    double lyapunov = mean / (std_dev + 1e-9);
    return (jdouble)lyapunov;
}

/**
 * Calculates the Reynolds Number of order book flow to evaluate laminar vs turbulent regimes.
 * Reynolds = (Velocity * Characteristic Length) / Kinematic Viscosity
 * In order flow: Velocity = Trade arrival rate, Length = Order book depth, Viscosity = Order cancellations + volatility.
 */
JNIEXPORT jdouble JNICALL
Java_com_example_telemetry_TelemetryBridge_calculateReynolds(
    JNIEnv *env,
    jobject thiz,
    jdouble trade_velocity,       // P_trade frequency (trades / sec)
    jdouble order_book_depth,     // Bid-ask spread size or cumulative volume
    jdouble cancellation_noise    // Rate of cancellations / order updates (viscosity)
) {
    // Avoid division by zero (extreme stability/viscosity)
    double viscosity = std::max(cancellation_noise, 1e-4);
    double velocity = std::max(trade_velocity, 0.0);
    double depth = std::max(order_book_depth, 1.0);
    
    double reynolds = (velocity * depth) / viscosity;
    return (jdouble)reynolds;
}

/**
 * Performs Runge-Kutta 4th Order Numerical Integration (RK4) to integrate
 * continuous flow approximations from discrete trade arrivals.
 * dy/dt = f(t, y) where we model trend acceleration.
 */
JNIEXPORT jdouble JNICALL
Java_com_example_telemetry_TelemetryBridge_runRungeKutta4(
    JNIEnv *env,
    jobject thiz,
    jdouble current_val,
    jdouble step_size, // dt
    jdouble trend,     // slope factor
    jdouble acceleration // acceleration factor
) {
    // We integrate y' = trend + acceleration * sin(y) as an oscillating price momentum model
    auto f = [trend, acceleration](double t, double y) -> double {
        return trend + acceleration * std::sin(y);
    };
    
    double t0 = 0.0;
    double y0 = current_val;
    double h = step_size;
    
    double k1 = h * f(t0, y0);
    double k2 = h * f(t0 + h / 2.0, y0 + k1 / 2.0);
    double k3 = h * f(t0 + h / 2.0, y0 + k2 / 2.0);
    double k4 = h * f(t0 + h, y0 + k3);
    
    double next_val = y0 + (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0;
    return (jdouble)next_val;
}

/**
 * Calculates the Shannon Entropy of order book level volume distributions.
 * Higher entropy indicates flat, noisy, uncoordinated activity.
 * Lower entropy indicates focused liquidity blocks (predictable support/resistance).
 */
JNIEXPORT jdouble JNICALL
Java_com_example_telemetry_TelemetryBridge_calculateEntropy(
    JNIEnv *env,
    jobject thiz,
    jdoubleArray volumes_array
) {
    if (!volumes_array) return 0.0;
    
    jsize len = env->GetArrayLength(volumes_array);
    if (len == 0) return 0.0;
    
    jdouble* volumes = env->GetDoubleArrayElements(volumes_array, nullptr);
    if (!volumes) return 0.0;
    
    double total_volume = 0.0;
    for (int i = 0; i < len; ++i) {
        total_volume += std::abs(volumes[i]);
    }
    
    if (total_volume < 1e-9) {
        env->ReleaseDoubleArrayElements(volumes_array, volumes, JNI_ABORT);
        return 0.0;
    }
    
    double entropy = 0.0;
    for (int i = 0; i < len; ++i) {
        double p = std::abs(volumes[i]) / total_volume;
        if (p > 1e-9) {
            entropy -= p * std::log2(p);
        }
    }
    
    env->ReleaseDoubleArrayElements(volumes_array, volumes, JNI_ABORT);
    return (jdouble)entropy;
}

/**
 * Calculates high-performance real-time telemetry payload inside the C++ native layer
 * bypassing JNI/JVM pauses and memory fragmentation.
 */
JNIEXPORT jobject JNICALL
Java_com_example_telemetry_TelemetryBridge_getNativeTelemetryPayload(
    JNIEnv *env,
    jobject thiz,
    jdouble current_btc_price,
    jdouble ofi,
    jdouble depth_imbalance,
    jdouble skew,
    jdouble vol,
    jdouble latency_ms,
    jboolean is_laminar
) {
    // 1. Calculate Asymmetric Probabilities
    double const up_score = 2.5 * (0.6 * ofi + 0.4 * depth_imbalance + 0.1 * skew);
    double const down_score = 2.5 * (-0.6 * ofi - 0.4 * depth_imbalance - 0.1 * skew);

    double const p_up = 1.0 / (1.0 + std::exp(-up_score));
    double const p_down = 1.0 / (1.0 + std::exp(-down_score));

    // 2. Compute model and noise uncertainties
    double const epistemic = std::max(0.001, 0.005 * std::abs(skew));
    double const aleatoric = std::max(0.001, 0.05 * vol);
    double const total_variance = epistemic + aleatoric;

    // 3. Directional separation and confidence ratio
    double const dir_sep = p_up - p_down;
    double const denom = std::sqrt(total_variance * 2.0);
    double const z_sep = (denom > 1e-9) ? (std::abs(dir_sep) / denom) : 0.0;

    // 4. Decision erosion coefficient
    double const erosion = 1.0;

    // 5. Build Decision State using physical filters and economic gates
    std::string decision = "NO-TRADE";
    std::string tripwire = "SYSTEM_NOMINAL";

    if (latency_ms > 25) {
        tripwire = "DATA_INTEGRITY_FAIL_CLOSED";
    } else if (!is_laminar) {
        tripwire = "PHYSICS_TURBULENT_CLOSED";
    } else if (z_sep < 4.0) {
        tripwire = "UNCERTAINTY_SUPPRESS_CLOSED";
    } else if (std::abs(dir_sep) >= 0.70) {
        decision = (dir_sep > 0.0) ? "BUY-LONG" : "SELL-SHORT";
    }

    // 6. Instantiate and return Kotlin TelemetryPayload class
    jclass payload_class = env->FindClass("com/example/telemetry/TelemetryPayload");
    if (!payload_class) return nullptr;

    // Constructor signature
    jmethodID constructor = env->GetMethodID(
        payload_class,
        "<init>",
        "(JDDJDDDDDDDDLjava/lang/String;ZLjava/lang/String;)V"
    );
    if (!constructor) return nullptr;

    jstring j_decision = env->NewStringUTF(decision.c_str());
    jstring j_tripwire = env->NewStringUTF(tripwire.c_str());

    // Compute nano timestamps
    auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();

    jobject payload_obj = env->NewObject(
        payload_class,
        constructor,
        (jlong)now_ns,
        (jdouble)current_btc_price,
        (jdouble)1.15, // Spread Bps proxy
        (jlong)latency_ms,
        (jdouble)110.0, // Reynolds index proxy
        (jdouble)p_up,
        (jdouble)p_down,
        (jdouble)epistemic,
        (jdouble)aleatoric,
        (jdouble)dir_sep,
        (jdouble)z_sep,
        (jdouble)erosion,
        j_decision,
        (jboolean)is_laminar,
        j_tripwire
    );

    return payload_obj;
}

}

