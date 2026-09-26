package com.example.api

import android.util.Log
import com.example.BuildConfig
import com.example.telemetry.DualAiState
import com.example.telemetry.DerivedFeatures
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a request to gemini-3.1-pro-preview with HIGH thinking level.
     * Extracts and separates both the AI's internal thoughts (if present) and the final response text.
     */
    suspend fun analyzeMarketState(
        derived: DerivedFeatures?,
        dualAi: DualAiState?,
        customPrompt: String
    ): GeminiAnalysisResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext GeminiAnalysisResult(
                responseText = "API Key is missing or invalid. Please configure GEMINI_API_KEY in the Secrets panel of Google AI Studio.",
                thoughtText = "Verification failed: API_KEY_ABSENT"
            )
        }

        // Build a mathematically rich telemetry dump to feed the AI
        val telemetryDump = """
            QtY 64 Telemetry State Report:
            -----------------------------
            Microprice: ${derived?.microPrice ?: "N/A"}
            Log Return: ${derived?.logReturn ?: "N/A"}
            Order Flow Imbalance (OFI): ${derived?.orderFlowImbalance ?: "N/A"}
            Depth Imbalance L1-5: ${derived?.depthImbalance ?: "N/A"}
            Volatility (Parkinson): ${derived?.parkinsonVolatility ?: "N/A"}
            
            Physics Telemetry & Stability Gating:
            - Lyapunov Exponent: ${derived?.lyapunovExponent ?: "N/A"} -> ${if (dualAi?.isLaminar == true) "LAMINAR (Orderly)" else "TURBULENT (Chaotic)"}
            - Reynolds Number: ${derived?.reynoldsNumber ?: "N/A"}
            - Shannon Entropy (Order book noise): ${derived?.shannonEntropy ?: "N/A"}
            - RK4 Smoothed continuous price: ${derived?.rk4SmoothPrice ?: "N/A"}
            
            Dual-AI Directional Gate State:
            - P(UP): ${dualAi?.pUp ?: "N/A"} (sigma: ${dualAi?.sigmaUp ?: "N/A"})
            - P(DOWN): ${dualAi?.pDown ?: "N/A"} (sigma: ${dualAi?.sigmaDown ?: "N/A"})
            - Directional Separation D(t): ${dualAi?.directionalSeparation ?: "N/A"}
            - Z-Separation Ratio: ${dualAi?.zSeparation ?: "N/A"} -> ${if ((dualAi?.zSeparation ?: 0.0) >= 4.0) "NOMINAL" else "MUTUAL_UNCERTAINTY_SUPPRESSION"}
            - Expected Gross Return (bps): ${dualAi?.expectedGrossReturn ?: "N/A"}
            - Expected Net Return after friction (bps): ${dualAi?.expectedNetReturn ?: "N/A"}
            - Edge-to-Cost Ratio (ECR): ${dualAi?.edgeToCostRatio ?: "N/A"} -> ${if ((dualAi?.edgeToCostRatio ?: 0.0) >= 2.0) "PASS" else "FRICTION_GATED"}
            
            System Decision State:
            - Final Signal: ${dualAi?.decisionState ?: "N/A"}
            - Tripwires Activated: ${dualAi?.tripwireState ?: "N/A"}
            
            User Deep-Reasoning Prompt:
            "$customPrompt"
        """.trimIndent()

        // Construct JSON using standard org.json to guarantee compilation and bypass plugin version matching
        val requestJson = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", telemetryDump)
                        })
                    })
                })
            }
            put("contents", contentsArray)

            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", "HIGH") // Use HIGH thinking mode as requested
                })
            })

            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "You are the QtY 64 Hybrid Quantitative Supervisor. Assess the telemetry dump, detail the micro-market physics, evaluate the independent UP-AI/DOWN-AI probabilities, and validate whether the NO-TRADE status or scalping trigger complies with scientific and economic friction rules. Use rigorous mathematics.")
                    })
                })
            })
        }

        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        val url = "$BASE_URL/v1beta/models/gemini-3.1-pro-preview:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Request failed: ${response.code} - $bodyStr")
                    return@withContext GeminiAnalysisResult(
                        responseText = "Error from Gemini API (${response.code}). Ensure your API key is correct and valid.",
                        thoughtText = "HTTP_ERROR_${response.code}"
                    )
                }

                val jsonResponse = JSONObject(bodyStr)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext GeminiAnalysisResult(
                        responseText = "No analysis response candidates generated by the model.",
                        thoughtText = "CANDIDATES_EMPTY"
                    )
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                if (content == null) {
                    return@withContext GeminiAnalysisResult(
                        responseText = "No content parts returned by the model.",
                        thoughtText = "CONTENT_EMPTY"
                    )
                }

                val parts = content.optJSONArray("parts") ?: JSONArray()
                var responseText = ""
                var thoughtText = ""

                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    // In modern thinking API models, thinking is returned as a part with a specific thought/thinking block
                    if (part.has("thought") || part.optBoolean("thought") || part.has("thinking")) {
                        thoughtText += part.optString("text") + "\n"
                    } else if (part.has("text")) {
                        val txt = part.optString("text")
                        // If it's a model-internal thoughts container
                        if (part.optString("mimeType") == "text/x-google-thinking-process") {
                            thoughtText += txt + "\n"
                        } else {
                            responseText += txt
                        }
                    }
                }

                if (responseText.isEmpty()) {
                    // Fallback to searching the standard text parts if no division was explicitly parsed
                    responseText = parts.optJSONObject(0)?.optString("text") ?: "No analysis text returned."
                }

                GeminiAnalysisResult(
                    responseText = responseText,
                    thoughtText = thoughtText.ifEmpty { "High-Reasoning thoughts processed in background server thread." }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini analysis", e)
            GeminiAnalysisResult(
                responseText = "Connection failed: ${e.message}. Check your internet connection and verify that your proxy allows requests to Google API services.",
                thoughtText = "EXCEPTION_THROWN: ${e.javaClass.simpleName}"
            )
        }
    }
}

data class GeminiAnalysisResult(
    val responseText: String,
    val thoughtText: String
)
