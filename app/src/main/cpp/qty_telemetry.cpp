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

}
