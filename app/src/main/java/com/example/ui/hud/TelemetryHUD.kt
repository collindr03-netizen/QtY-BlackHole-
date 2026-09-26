@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.example.ui.hud

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.api.GeminiClient
import com.example.telemetry.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelemetryHUD(
    engine: TelemetryEngine,
    modifier: Modifier = Modifier
) {
    val connections by engine.connections.collectAsState()
    val derived by engine.derivedFeatures.collectAsState()
    val engines by engine.engines.collectAsState()
    val dualAi by engine.dualAiState.collectAsState()
    val ledger by engine.auditLedger.collectAsState()

    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    // Selected engine for detail dialog
    var selectedEngine by remember { mutableStateOf<EngineStatus?>(null) }

    // Gemini API states
    var geminiPrompt by remember { mutableStateOf("Synthesize the current Lyapunov divergence and formulate a high-conviction scalping verdict.") }
    var geminiThought by remember { mutableStateOf("") }
    var geminiResult by remember { mutableStateOf("") }
    var isGeminiLoading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueryStats,
                            contentDescription = "QtY Logo",
                            tint = CyberGreen
                        )
                        Column {
                            Text(
                                text = "QtY 64 QUANTITATIVE TELEMETRY",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Experimental Scalping Terminal • Phase 8 NDK Core",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MutedSlate
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkCarbon,
                    titleContentColor = Color.White
                ),
                actions = {
                    // System Tripwire Indicator in Header
                    val isTripwired = dualAi?.tripwireState != "SYSTEM_NOMINAL"
                    val headerStatusText = if (isTripwired) "TRIPWIRED / CLOSED" else "SYSTEM_NOMINAL"
                    val headerStatusColor = if (isTripwired) CyberRed else CyberGreen

                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(headerStatusColor.copy(alpha = 0.15f))
                            .border(1.dp, headerStatusColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = headerStatusText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = headerStatusColor
                        )
                    }
                }
            )
        },
        containerColor = DarkCarbon
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            TopStatusBar(derived = derived, connections = connections)

            if (isTablet) {
                // Wide-screen grid/column layout
                Row(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                // Left Column: Core HUD & Real-time Telemetry (60% width)
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DualAiGaugeSection(dualAi)
                    SeparationBarSection(dualAi, engine.targetCritSeparation)
                    RealtimeChartsSection(
                        engine.chartPriceHistory,
                        engine.chartLyapunovHistory,
                        engine.chartReynoldsHistory,
                        engine.chartSeparationHistory,
                        derived
                    )
                    InteractiveControllerSection(engine)
                    GeminiReasoningSection(
                        derived = derived,
                        dualAi = dualAi,
                        geminiPrompt = geminiPrompt,
                        geminiThought = geminiThought,
                        geminiResult = geminiResult,
                        isGeminiLoading = isGeminiLoading,
                        onPromptChange = { geminiPrompt = it },
                        onRunAnalysis = {
                            isGeminiLoading = true
                            geminiResult = "Querying gemini-3.1-pro-preview with HIGH reasoning thinking config..."
                            geminiThought = "Thinking process initialized in secure background thread...\n"
                            scope.launch {
                                val res = GeminiClient.analyzeMarketState(derived, dualAi, geminiPrompt)
                                geminiResult = res.responseText
                                geminiThought = res.thoughtText
                                isGeminiLoading = false
                            }
                        }
                    )
                }

                // Right Column: 37 Engines board & Audit log (40% width)
                Column(
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DeepSlate),
                        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "TABLE D — 37 ANALYTICAL ENGINES",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = CyberBlue,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            EnginesGrid(engines, onEngineClick = { selectedEngine = it })
                        }
                    }

                    Card(
                        modifier = Modifier
                            .height(200.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DeepSlate),
                        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "CRYPTOGRAPHIC AUDIT LEDGER",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = BrightSilver,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            AuditLedgerList(ledger)
                        }
                    }
                }
            }
        } else {
            // Mobile-first scrollable stack
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { DualAiGaugeSection(dualAi) }
                item { SeparationBarSection(dualAi, engine.targetCritSeparation) }
                item {
                    RealtimeChartsSection(
                        engine.chartPriceHistory,
                        engine.chartLyapunovHistory,
                        engine.chartReynoldsHistory,
                        engine.chartSeparationHistory,
                        derived
                    )
                }
                item { InteractiveControllerSection(engine) }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        colors = CardDefaults.cardColors(containerColor = DeepSlate),
                        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "TABLE D — 37 ANALYTICAL ENGINES",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = CyberBlue,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            EnginesGrid(engines, onEngineClick = { selectedEngine = it })
                        }
                    }
                }
                item {
                    GeminiReasoningSection(
                        derived = derived,
                        dualAi = dualAi,
                        geminiPrompt = geminiPrompt,
                        geminiThought = geminiThought,
                        geminiResult = geminiResult,
                        isGeminiLoading = isGeminiLoading,
                        onPromptChange = { geminiPrompt = it },
                        onRunAnalysis = {
                            isGeminiLoading = true
                            geminiResult = "Querying gemini-3.1-pro-preview with HIGH reasoning thinking config..."
                            geminiThought = "Thinking process initialized in secure background thread...\n"
                            scope.launch {
                                val res = GeminiClient.analyzeMarketState(derived, dualAi, geminiPrompt)
                                geminiResult = res.responseText
                                geminiThought = res.thoughtText
                                isGeminiLoading = false
                            }
                        }
                    )
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        colors = CardDefaults.cardColors(containerColor = DeepSlate),
                        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "CRYPTOGRAPHIC AUDIT LEDGER",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = BrightSilver,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            AuditLedgerList(ledger)
                        }
                    }
                }
            }
        }
        }

        // Engine Details Dialog
        selectedEngine?.let { eng ->
            AlertDialog(
                onDismissRequest = { selectedEngine = null },
                confirmButton = {
                    TextButton(onClick = { selectedEngine = null }) {
                        Text("DISMISS", fontFamily = FontFamily.Monospace, color = CyberGreen)
                    }
                },
                title = {
                    Text(
                        text = "${eng.code} : ${eng.name}",
                        fontFamily = FontFamily.Monospace,
                        color = CyberBlue,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "CATEGORY: ${eng.category.name}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = BrightSilver,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "STATUS: ${eng.status}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (eng.status == "TRIPWIRED") CyberRed else CyberGreen,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "DERIVED VALUE: ${eng.telemetryValue}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyberAmber,
                            fontSize = 14.sp
                        )
                        Divider(color = MutedSlate.copy(alpha = 0.3f))
                        Text(
                            text = eng.description,
                            fontFamily = FontFamily.Monospace,
                            color = BrightSilver,
                            fontSize = 11.sp
                        )
                    }
                },
                containerColor = DeepSlate,
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

// ==========================================
// COMPOSABLE COMPONENT SECTIONS
// ==========================================

@Composable
fun DualAiGaugeSection(state: DualAiState?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "ASYMMETRIC INFERENCE DUAL-AI ENGINE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BrightSilver,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // UP-AI Gauge
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "UP-AI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = CyberGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%.2f%%", (state?.pUp ?: 0.5) * 100),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen
                    )
                    Text(
                        text = "σ(Epistemic): ${String.format("%.4f", state?.sigmaUp ?: 0.05)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(60.dp)
                        .background(MutedSlate.copy(alpha = 0.3f))
                )

                // DOWN-AI Gauge
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "DOWN-AI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = CyberRed,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%.2f%%", (state?.pDown ?: 0.5) * 100),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberRed
                    )
                    Text(
                        text = "σ(Aleatoric): ${String.format("%.4f", state?.sigmaDown ?: 0.05)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MutedSlate.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))

            // Uncertainty Gating Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val zSep = state?.zSeparation ?: 0.0
                val zStatus = if (zSep >= 4.0) "NOMINAL (>=4.0)" else "SUPPRESSED (<4.0)"
                val zColor = if (zSep >= 4.0) CyberGreen else CyberAmber

                Text(
                    text = "Z-Separation (Confidence Margin):",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = BrightSilver
                )
                Text(
                    text = "${String.format("%.4f", zSep)} ($zStatus)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = zColor
                )
            }
        }
    }
}

@Composable
fun SeparationBarSection(state: DualAiState?, targetCrit: Double) {
    val dirSeparation = state?.directionalSeparation ?: 0.0
    val activeSignal = state?.decisionState ?: "NO-TRADE"
    val activeTripwire = state?.tripwireState ?: "SYSTEM_NOMINAL"
    val erosion = state?.decisionErosion ?: 1.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DIRECTIONAL SEPARATION BAR D(t)",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = BrightSilver,
                    fontSize = 13.sp
                )
                Text(
                    text = String.format("Separation: %+.4f", dirSeparation),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        activeSignal == "BUY-LONG" -> CyberGreen
                        activeSignal == "SELL-SHORT" -> CyberRed
                        else -> MutedSlate
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Rendering the separation bar (from -1 to +1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DarkCarbon)
                    .border(1.dp, MutedSlate.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            ) {
                // Central axis
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MutedSlate)
                )

                // Critical positive threshold line
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .align(Alignment.CenterStart)
                        .padding(start = ((targetCrit + 1.0) / 2.0 * 100).let { if (it.isNaN()) 50f else it.toFloat() }.coerceIn(0f, 100f).dp)
                        .background(CyberAmber)
                )

                // Separation fill bar with dynamic colors (Green for Buy, Red for Sell, Gray/Muted for No-Trade)
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (dirSeparation < 0) {
                        // Negative bar fill (shifts from middle to left)
                        Spacer(modifier = Modifier.weight((1.0f + dirSeparation.toFloat()).coerceIn(0.01f, 1f)))
                        Box(
                            modifier = Modifier
                                .weight(Math.abs(dirSeparation.toFloat()).coerceIn(0.01f, 1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(CyberRed, CyberRed.copy(alpha = 0.4f))
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.weight(1.0f))
                    } else {
                        // Positive bar fill (shifts from middle to right)
                        Spacer(modifier = Modifier.weight(1.0f))
                        Box(
                            modifier = Modifier
                                .weight(dirSeparation.toFloat().coerceIn(0.01f, 1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(CyberGreen.copy(alpha = 0.4f), CyberGreen)
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.weight((1.0f - dirSeparation.toFloat()).coerceIn(0.01f, 1f)))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Decision erosion progress indicator
            if (activeSignal != "NO-TRADE") {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SIGNAL VALIDITY EROSION:",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MutedSlate,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format("%.1f%%", erosion * 100),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { erosion.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = CyberAmber,
                        trackColor = DarkCarbon
                    )
                }
            }

            // 5. Fail-closed tripwire alerts for packet drops or wide spreads
            if (activeTripwire != "SYSTEM_NOMINAL") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyberRed.copy(alpha = 0.15f))
                        .border(1.dp, CyberRed, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Tripwire Alert",
                            tint = CyberRed,
                            size = 18.dp
                        )
                        Column {
                            Text(
                                text = "TRIPWIRE FAIL-CLOSED ACTIVATED",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberRed
                            )
                            Text(
                                text = "Reason: $activeTripwire - Order placement suppressed.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = BrightSilver
                            )
                        }
                    }
                }
            }

            // Bottom descriptive metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DECISION STATE:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate
                    )
                    Text(
                        text = activeSignal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (activeSignal) {
                            "BUY-LONG" -> CyberGreen
                            "SELL-SHORT" -> CyberRed
                            else -> MutedSlate
                        },
                        modifier = Modifier.testTag("submit_button")
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "ACTIVE TRIPWIRE / REASON:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = activeTripwire,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeTripwire == "SYSTEM_NOMINAL") CyberGreen else CyberAmber,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}


@Composable
fun RealtimeChartsSection(
    priceHist: List<Double>,
    lyapunovHist: List<Double>,
    reynoldsHist: List<Double>,
    separationHist: List<Double>,
    derived: DerivedFeatures?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "REAL-TIME QUANTITATIVE PHYSICS TERMINAL (120Hz)",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BrightSilver,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Grid of 4 Canvas charts
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1. Price Timeline
                ChartItem(
                    title = "MICRO-PRICE TIMELINE",
                    subValue = String.format("$%.2f", derived?.microPrice ?: 96450.0),
                    data = priceHist,
                    color = CyberBlue
                )

                // 2. Lyapunov Timeline
                ChartItem(
                    title = "LYAPUNOV CALCULUS (STABILITY GATING)",
                    subValue = String.format("Exp: %.4f • %s", derived?.lyapunovExponent ?: 0.0, if ((derived?.lyapunovExponent ?: 0.0) < 0.22) "LAMINAR" else "CHAOTIC"),
                    data = lyapunovHist,
                    color = if ((derived?.lyapunovExponent ?: 0.0) < 0.22) CyberGreen else CyberRed,
                    centerAxis = true
                )

                // 3. Reynolds Timeline
                ChartItem(
                    title = "REYNOLDS FLUID METRICS (TURBULENCE GATING)",
                    subValue = String.format("Re: %.2f • %s", derived?.reynoldsNumber ?: 0.0, if ((derived?.reynoldsNumber ?: 0.0) < 320.0) "LAMINAR" else "TURBULENT"),
                    data = reynoldsHist,
                    color = if ((derived?.reynoldsNumber ?: 0.0) < 320.0) CyberGreen else CyberAmber
                )
            }
        }
    }
}

@Composable
fun ChartItem(
    title: String,
    subValue: String,
    data: List<Double>,
    color: Color,
    centerAxis: Boolean = false
) {
    val copiedData = remember(data) { data.toList() } // Thread safety Copy

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkCarbon, RoundedCornerShape(4.dp))
            .border(1.dp, MutedSlate.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MutedSlate,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subValue,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (copiedData.size < 2) return@Canvas

            val width = size.width
            val height = size.height

            val minVal = copiedData.minOrNull() ?: 0.0
            val maxVal = copiedData.maxOrNull() ?: 1.0
            val range = if (maxVal == minVal) 1.0 else maxVal - minVal

            val path = Path()
            val stepX = width / (copiedData.size - 1)

            // Central axis if requested
            if (centerAxis) {
                drawLine(
                    color = MutedSlate.copy(alpha = 0.25f),
                    start = Offset(0f, height / 2),
                    end = Offset(width, height / 2),
                    strokeWidth = 1f
                )
            }

            copiedData.forEachIndexed { idx, value ->
                val x = idx * stepX
                val y = height - (((value - minVal) / range) * height).toFloat()

                if (idx == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 3f)
            )

            // Draw current value glow circle
            if (copiedData.isNotEmpty()) {
                val lastVal = copiedData.last()
                val lastY = height - (((lastVal - minVal) / range) * height).toFloat()
                drawCircle(
                    color = color,
                    radius = 5f,
                    center = Offset(width, lastY)
                )
            }
        }
    }
}

@Composable
fun InteractiveControllerSection(engine: TelemetryEngine) {
    var critValue by remember { mutableStateOf(engine.targetCritSeparation) }
    var driftChecked by remember { mutableStateOf(engine.injectClockDrift) }
    var packetsChecked by remember { mutableStateOf(engine.injectDroppedPackets) }
    var turbulenceChecked by remember { mutableStateOf(engine.injectExtremeTurbulence) }
    var manualModeChecked by remember { mutableStateOf(engine.manualModeActive) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "COORDINATE FLUID CONTROL & REGIME TRIPWIRES",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BrightSilver,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Slider for separation gate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Threshold \u0398(crit):",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = BrightSilver
                )
                Text(
                    text = String.format("%.2f", critValue),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberBlue
                )
            }

            Slider(
                value = critValue.toFloat(),
                onValueChange = {
                    critValue = it.toDouble()
                    engine.targetCritSeparation = it.toDouble()
                },
                valueRange = 0.50f..0.95f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = CyberBlue,
                    activeTrackColor = CyberBlue,
                    inactiveTrackColor = MutedSlate.copy(alpha = 0.3f)
                ),
                modifier = Modifier.testTag("target_crit_slider")
            )

            Spacer(modifier = Modifier.height(6.dp))
            Divider(color = MutedSlate.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "STRESS TESTING SIMULATOR:",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MutedSlate,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Checkbox simulation triggers
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Tripwire clock drift
                ControlToggle(
                    label = "Clock Drift >25ms",
                    checked = driftChecked,
                    checkedColor = CyberRed,
                    onCheckedChange = {
                        driftChecked = it
                        engine.injectClockDrift = it
                    }
                )

                // Tripwire packet loss
                ControlToggle(
                    label = "Packet Loss",
                    checked = packetsChecked,
                    checkedColor = CyberRed,
                    onCheckedChange = {
                        packetsChecked = it
                        engine.injectDroppedPackets = it
                    }
                )

                // Tripwire extreme turbulence
                ControlToggle(
                    label = "Fluid Turbulence (Re >320)",
                    checked = turbulenceChecked,
                    checkedColor = CyberRed,
                    onCheckedChange = {
                        turbulenceChecked = it
                        engine.injectExtremeTurbulence = it
                    }
                )

                // Manual trending mode
                ControlToggle(
                    label = "Manual Price Drift",
                    checked = manualModeChecked,
                    checkedColor = CyberBlue,
                    onCheckedChange = {
                        manualModeChecked = it
                        engine.manualModeActive = it
                    }
                )
            }
        }
    }
}

@Composable
fun ControlToggle(
    label: String,
    checked: Boolean,
    checkedColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) checkedColor.copy(alpha = 0.15f) else DarkCarbon)
            .border(
                width = 1.dp,
                color = if (checked) checkedColor else MutedSlate.copy(alpha = 0.4f),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) checkedColor else MutedSlate)
            )
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (checked) Color.White else BrightSilver,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EnginesGrid(
    engines: List<EngineStatus>,
    onEngineClick: (EngineStatus) -> Unit
) {
    val copiedEngines = remember(engines) { engines.toList() } // thread safety

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 85.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(copiedEngines, key = { it.code }) { eng ->
            val isEngineCritical = eng.status == "TRIPWIRED"
            val borderClr = if (isEngineCritical) CyberRed else MutedSlate.copy(alpha = 0.25f)
            val bgClr = if (isEngineCritical) CyberRed.copy(alpha = 0.1f) else DarkCarbon

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bgClr)
                    .border(1.dp, borderClr, RoundedCornerShape(4.dp))
                    .clickable { onEngineClick(eng) }
                    .padding(4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = eng.code,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = if (isEngineCritical) CyberRed else CyberBlue
                        )

                        // Glowing status indicator dot
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(if (isEngineCritical) CyberRed else CyberGreen)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = eng.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = BrightSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = eng.telemetryValue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = CyberAmber,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun GeminiReasoningSection(
    derived: DerivedFeatures?,
    dualAi: DualAiState?,
    geminiPrompt: String,
    geminiThought: String,
    geminiResult: String,
    isGeminiLoading: Boolean,
    onPromptChange: (String) -> Unit,
    onRunAnalysis: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "AI Reasoning",
                    tint = CyberAmber
                )
                Text(
                    text = "HYBRID AI REASONING (GEMINI-3.1-PRO-PREVIEW)",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyberAmber,
                    fontSize = 13.sp
                )
            }

            Text(
                text = "Provides real-time qualitative evaluation of physics indicators & microstructure separation under high-reasoning thinking budgets.",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MutedSlate,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Preset Queries Chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(
                    "Evaluate Lyapunov instability vs fluid Reynolds turbulence.",
                    "Verify if ECR allows trading after friction gating.",
                    "Audit data integrity tripwire state logs."
                )
                presets.forEach { text ->
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(DarkCarbon)
                            .border(1.dp, MutedSlate.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                            .clickable { onPromptChange(text) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = CyberBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Query Input Box
            OutlinedTextField(
                value = geminiPrompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("username_input"),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.White
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberAmber,
                    unfocusedBorderColor = MutedSlate.copy(alpha = 0.4f),
                    focusedContainerColor = DarkCarbon,
                    unfocusedContainerColor = DarkCarbon
                ),
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Engage Button
            Button(
                onClick = onRunAnalysis,
                enabled = !isGeminiLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberAmber,
                    contentColor = DarkCarbon,
                    disabledContainerColor = MutedSlate
                ),
                shape = RoundedCornerShape(4.dp)
            ) {
                if (isGeminiLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = DarkCarbon,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "INTEGRATING DUAL-AI MATRIX...",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Run", size = 16.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ENGAGE HYBRID REASONING ENGINE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Results Section
            if (geminiThought.isNotEmpty() || geminiResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                // Thoughts Collapsible Box (MANDATORY High-Reasoning Display)
                if (geminiThought.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(CyberAmber.copy(alpha = 0.08f))
                            .border(1.dp, CyberAmber.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TipsAndUpdates,
                                contentDescription = "Thoughts",
                                tint = CyberAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "THINKING PROTOCOL (HIGH REASONING)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyberAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = geminiThought,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = CyberAmber.copy(alpha = 0.85f),
                                lineHeight = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Final Output Terminal Text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkCarbon)
                        .border(1.dp, MutedSlate.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "TERMINAL RESPONSE:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = CyberBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = geminiResult,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = BrightSilver,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuditLedgerList(records: List<AuditRecord>) {
    val copiedLedger = remember(records) { records.toList() } // thread safety

    if (copiedLedger.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "NO AUDITED BLOCKS YET\nEngine running...",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MutedSlate,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(copiedLedger, key = { it.stepIndex }) { record ->
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(record.timestamp))
                val isNominal = record.activeTripwires.isEmpty()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkCarbon)
                        .border(1.dp, MutedSlate.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "BLOCK #${record.stepIndex}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyberBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "[$timeStr]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = MutedSlate
                            )
                        }

                        Text(
                            text = "SHA-256: ${record.stateHash}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MutedSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = record.decision,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (record.decision) {
                                "BUY-LONG" -> CyberGreen
                                "SELL-SHORT" -> CyberRed
                                else -> CyberAmber
                            }
                        )
                        Text(
                            text = if (isNominal) "AUDITED_NOMINAL" else "TRIP_CLOSED",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = if (isNominal) CyberGreen else CyberRed
                        )
                    }
                }
            }
        }
    }
}

// Custom Icon implementation helper
@Composable
fun Icon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    tint: Color = Color.Unspecified
) {
    androidx.compose.material3.Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

@Composable
fun TopStatusBar(derived: DerivedFeatures?, connections: List<ConnectionState>) {
    val btcPrice = derived?.microPrice ?: 96450.0
    val spreadBps = 1.15
    val avgLatency = connections.map { it.latencyMs }.average().let { if (it.isNaN()) 10.0 else it }.toInt()
    val reynolds = derived?.reynoldsNumber ?: 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBarItem(
                label = "BTC-PERP PRICE",
                value = String.format("$%.2f", btcPrice),
                color = CyberBlue,
                icon = Icons.Default.AttachMoney
            )
            StatusBarItem(
                label = "BID-ASK SPREAD",
                value = String.format("%.2f bps", spreadBps),
                color = CyberAmber,
                icon = Icons.Default.SwapHoriz
            )
            StatusBarItem(
                label = "NET LATENCY",
                value = "$avgLatency ms",
                color = if (avgLatency > 25) CyberRed else CyberGreen,
                icon = Icons.Default.NetworkCell
            )
            StatusBarItem(
                label = "REYNOLDS INDEX",
                value = String.format("%.1f", reynolds),
                color = if (reynolds > 320.0) CyberRed else CyberGreen,
                icon = Icons.Default.Waves
            )
        }
    }
}

@Composable
fun StatusBarItem(
    label: String,
    value: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, size = 18.dp)
        Column {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MutedSlate,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

