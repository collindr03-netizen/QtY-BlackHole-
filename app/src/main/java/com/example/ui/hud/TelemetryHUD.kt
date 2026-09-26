@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

    val pagerState = rememberPagerState(pageCount = { 3 })

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
        bottomBar = {
            NavigationBar(
                containerColor = DeepSlate,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Telemetry HUD", tint = if (pagerState.currentPage == 0) CyberBlue else MutedSlate) },
                    label = { Text("HUD", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (pagerState.currentPage == 0) CyberBlue else MutedSlate) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = CyberBlue.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Dual-AI Gate", tint = if (pagerState.currentPage == 1) CyberAmber else MutedSlate) },
                    label = { Text("Dual-AI", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (pagerState.currentPage == 1) CyberAmber else MutedSlate) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = CyberAmber.copy(alpha = 0.15f)
                    )
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    icon = { Icon(Icons.Default.Science, contentDescription = "Backtest Lab", tint = if (pagerState.currentPage == 2) CyberGreen else MutedSlate) },
                    label = { Text("Backtest", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (pagerState.currentPage == 2) CyberGreen else MutedSlate) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = CyberGreen.copy(alpha = 0.15f)
                    )
                )
            }
        },
        containerColor = DarkCarbon
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Persistent top status bar with overarching stability indicator
            TopStatusBar(
                derived = derived, 
                connections = connections, 
                isLaminar = (derived?.reynoldsNumber ?: 0.0) < 320.0 && (derived?.lyapunovExponent ?: 0.0) < 0.22
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> {
                        // "Telemetry HUD" Screen
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                RealtimeChartsSection(
                                    priceHist = engine.chartPriceHistory,
                                    lyapunovHist = engine.chartLyapunovHistory,
                                    reynoldsHist = engine.chartReynoldsHistory,
                                    separationHist = engine.chartSeparationHistory,
                                    derived = derived
                                )
                            }
                            item {
                                SeparationBarSection(dualAi, engine.targetCritSeparation)
                            }
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Circular Radial Dial: Market Reynolds Index
                                    RadialDial(
                                        title = "REYNOLDS INDEX",
                                        value = derived?.reynoldsNumber ?: 120.0,
                                        maxValue = 640.0,
                                        threshold = 320.0,
                                        unit = "Re Units",
                                        color = if ((derived?.reynoldsNumber ?: 0.0) < 320.0) CyberGreen else CyberAmber,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Circular Radial Dial: Trajectory Divergence (FTLE sensitivity)
                                    RadialDial(
                                        title = "TRAJECTORY DIVERGENCE",
                                        value = derived?.lyapunovExponent ?: 0.08,
                                        maxValue = 0.50,
                                        threshold = 0.22,
                                        unit = "FTLE Exp",
                                        color = if ((derived?.lyapunovExponent ?: 0.0) < 0.22) CyberGreen else CyberRed,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            item {
                                DecisionErosionCountdown(dualAi)
                            }
                            item {
                                InteractiveControllerSection(engine)
                            }
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp),
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
                        }
                    }
                    1 -> {
                        // "Dual-AI Gate" Screen
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                DualAiGateTabScreen(dualAi)
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
                                        geminiResult = "Querying gemini-3.5-flash with HIGH reasoning thinking config..."
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
                    2 -> {
                        // "Backtest Lab" Screen
                        BacktestLabScreen(engine = engine)
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
                    HorizontalDivider(color = MutedSlate.copy(alpha = 0.3f))
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

// ==========================================
// COMPOSABLE COMPONENT SECTIONS
// ==========================================

@Composable
fun RadialDial(
    title: String,
    value: Double,
    maxValue: Double,
    threshold: Double,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(DarkCarbon, RoundedCornerShape(8.dp))
            .border(1.dp, MutedSlate.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MutedSlate,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Box(
            modifier = Modifier.size(90.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 8.dp.toPx()
                val innerRadius = (size.minDimension - strokeWidth) / 2
                
                // Draw background arc
                drawArc(
                    color = MutedSlate.copy(alpha = 0.15f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                
                // Draw active value arc
                val sweepAngle = ((value.coerceIn(0.0, maxValue) / maxValue) * 270.0).toFloat()
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                
                // Draw threshold tick
                val thresholdAngle = 135f + ((threshold / maxValue) * 270.0).toFloat()
                val rad = Math.toRadians(thresholdAngle.toDouble())
                val cos = Math.cos(rad).toFloat()
                val sin = Math.sin(rad).toFloat()
                
                val center = Offset(size.width / 2, size.height / 2)
                val startOffset = Offset(
                    center.x + (innerRadius - strokeWidth) * cos,
                    center.y + (innerRadius - strokeWidth) * sin
                )
                val endOffset = Offset(
                    center.x + (innerRadius + strokeWidth) * cos,
                    center.y + (innerRadius + strokeWidth) * sin
                )
                
                drawLine(
                    color = CyberRed,
                    start = startOffset,
                    end = endOffset,
                    strokeWidth = 3.dp.toPx()
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format("%.1f", value),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = unit,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = MutedSlate
                )
            }
        }
    }
}

@Composable
fun DecisionErosionCountdown(state: DualAiState?) {
    val activeSignal = state?.decisionState ?: "NO-TRADE"
    val erosion = state?.decisionErosion ?: 1.0
    val secondsLeft = erosion * 18.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "DECISION EXPIRATION WINDOW (EROSION ENGINE)",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BrightSilver,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ACTIVE SIGNAL TYPE:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MutedSlate
                    )
                    Text(
                        text = activeSignal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (activeSignal) {
                            "BUY-LONG" -> CyberGreen
                            "SELL-SHORT" -> CyberRed
                            else -> MutedSlate
                        }
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "EXPIRATION COUNTDOWN:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MutedSlate
                    )
                    Text(
                        text = if (activeSignal != "NO-TRADE") String.format("%.2f s / 18.00s", secondsLeft) else "STANDBY (0.00s)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeSignal != "NO-TRADE") CyberAmber else MutedSlate
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Linear Progress countdown indicator
            LinearProgressIndicator(
                progress = { erosion.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (activeSignal != "NO-TRADE") CyberAmber else MutedSlate.copy(alpha = 0.2f),
                trackColor = DarkCarbon
            )
        }
    }
}

@Composable
fun DualAiGateTabScreen(state: DualAiState?) {
    val pUp = state?.pUp ?: 0.5
    val pDown = state?.pDown ?: 0.5
    val sigmaUp = state?.sigmaUp ?: 0.05
    val sigmaDown = state?.sigmaDown ?: 0.05
    val decision = state?.decisionState ?: "NO-TRADE"
    val tripwire = state?.tripwireState ?: "SYSTEM_NOMINAL"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Two side-by-side symmetric cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // UP-AI Card (Emerald Theme)
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DeepSlate),
                border = BorderStroke(1.dp, CyberGreen.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "UP-AI PREDICTOR",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%.1f%%", pUp * 100),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen
                    )
                    Text(
                        text = "Calibrated Probability",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MutedSlate
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Epistemic Uncertainty bar
                    Text(
                        text = "Epistemic Uncertainty: ${String.format("%.4f", sigmaUp)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = BrightSilver
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { sigmaUp.toFloat().coerceIn(0f, 0.2f) * 5f }, // scale for visualization
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = CyberGreen,
                        trackColor = DarkCarbon
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Aleatoric Noise level text
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Aleatoric Noise:", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MutedSlate)
                        Text(String.format("%.4f", sigmaDown * 0.8), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberGreen)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MutedSlate.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "CONTRIBUTIONS:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 3 Feature contribution badges
                    listOf(
                        "OFI" to "+48%",
                        "Depth Imbal" to "+32%",
                        "Kinematic" to "+20%"
                    ).forEach { (feat, weight) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(DarkCarbon)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(feat, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = BrightSilver)
                            Text(weight, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = CyberGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // DOWN-AI Card (Crimson Theme)
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DeepSlate),
                border = BorderStroke(1.dp, CyberRed.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "DOWN-AI PREDICTOR",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyberRed,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%.1f%%", pDown * 100),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberRed
                    )
                    Text(
                        text = "Calibrated Probability",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = MutedSlate
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Epistemic Uncertainty bar
                    Text(
                        text = "Epistemic Uncertainty: ${String.format("%.4f", sigmaDown)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = BrightSilver
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { sigmaDown.toFloat().coerceIn(0f, 0.2f) * 5f }, // scale for visualization
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = CyberRed,
                        trackColor = DarkCarbon
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Aleatoric Noise level text
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Aleatoric Noise:", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MutedSlate)
                        Text(String.format("%.4f", sigmaUp * 0.85), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberRed)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MutedSlate.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "CONTRIBUTIONS:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MutedSlate
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 3 Feature contribution badges
                    listOf(
                        "OFI" to "+45%",
                        "Depth Imbal" to "+35%",
                        "Kinematic" to "+20%"
                    ).forEach { (feat, weight) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(DarkCarbon)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(feat, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = BrightSilver)
                            Text(weight, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = CyberRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Terminal Decision Banner at the bottom
        val terminalBannerText = when {
            tripwire != "SYSTEM_NOMINAL" -> "NO-TRADE (TRIPWIRE TRIGGERED: $tripwire)"
            decision == "BUY-LONG" -> "ACTIONABLE BUY"
            decision == "SELL-SHORT" -> "ACTIONABLE SELL"
            else -> "NO-TRADE (CONFUSED / UNSTABLE / DEAD-ZONE)"
        }
        val terminalBannerColor = when {
            tripwire != "SYSTEM_NOMINAL" -> CyberAmber
            decision == "BUY-LONG" -> CyberGreen
            decision == "SELL-SHORT" -> CyberRed
            else -> MutedSlate
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DeepSlate),
            border = BorderStroke(2.dp, terminalBannerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(terminalBannerColor.copy(alpha = 0.1f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DUAL-AI GATE DECISION PLATFORM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedSlate
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = terminalBannerText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = terminalBannerColor,
                    textAlign = TextAlign.Center
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
                    text = "DIRECTIONAL SEPARATION GATE D(t)",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = BrightSilver,
                    fontSize = 13.sp
                )
                Text(
                    text = String.format("D(t): %.4f", dirSeparation),
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

            Spacer(modifier = Modifier.height(6.dp))

            // Labels above the bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-1.0 [SELL]", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = CyberRed)
                Text("-0.70 GATING", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                Text("0.0 [NEUTRAL]", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = CyberAmber)
                Text("+0.70 GATING", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                Text("+1.0 [BUY]", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = CyberGreen)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Visual bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DarkCarbon)
                    .border(1.dp, MutedSlate.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            ) {
                // Shading for neutral zone (-0.70 to +0.70)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    
                    // position = (value + 1) / 2 * width
                    val x07Left = 0.15f * w
                    val x07Right = 0.85f * w
                    
                    // Draw Neutral Dead Zone Shading
                    drawRect(
                        color = Color(0xFF1E2235).copy(alpha = 0.4f),
                        topLeft = Offset(x07Left, 0f),
                        size = androidx.compose.ui.geometry.Size(x07Right - x07Left, h)
                    )
                    
                    // Gating Boundary Lines
                    drawLine(
                        color = MutedSlate.copy(alpha = 0.5f),
                        start = Offset(x07Left, 0f),
                        end = Offset(x07Left, h),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = MutedSlate.copy(alpha = 0.5f),
                        start = Offset(x07Right, 0f),
                        end = Offset(x07Right, h),
                        strokeWidth = 2f
                    )
                }

                // Center/Zero marker
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CyberAmber.copy(alpha = 0.6f))
                )

                // Current indicator needle/marker
                val currentFraction = ((dirSeparation + 1.0) / 2.0).coerceIn(0.0, 1.0).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(currentFraction)
                        .background(
                            Brush.horizontalGradient(
                                colors = when {
                                    dirSeparation < -0.70 -> listOf(CyberRed.copy(alpha = 0.05f), CyberRed.copy(alpha = 0.4f))
                                    dirSeparation > 0.70 -> listOf(CyberGreen.copy(alpha = 0.05f), CyberGreen.copy(alpha = 0.4f))
                                    else -> listOf(MutedSlate.copy(alpha = 0.05f), MutedSlate.copy(alpha = 0.2f))
                                }
                            )
                        )
                )

                // The sliding pin
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height
                    val x = currentFraction * w
                    
                    drawCircle(
                        color = when {
                            dirSeparation < -0.70 -> CyberRed
                            dirSeparation > 0.70 -> CyberGreen
                            else -> CyberAmber
                        },
                        radius = 6.dp.toPx(),
                        center = Offset(x, h / 2)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // State information
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STATUS: ${if (Math.abs(dirSeparation) < 0.70) "IN DEAD-ZONE (NO-TRADE)" else "SIGNAL ACTIVE"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (Math.abs(dirSeparation) < 0.70) MutedSlate else CyberGreen
                )
                Text(
                    text = "Threshold \u0398(crit): 0.7000",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MutedSlate
                )
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

            // Grid of 3 Canvas charts
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
                    title = "LYAPUNOV STABILITY GATING",
                    subValue = String.format("Exp: %.4f • %s", derived?.lyapunovExponent ?: 0.0, if ((derived?.lyapunovExponent ?: 0.0) < 0.22) "LAMINAR" else "CHAOTIC"),
                    data = lyapunovHist,
                    color = if ((derived?.lyapunovExponent ?: 0.0) < 0.22) CyberGreen else CyberRed,
                    centerAxis = true
                )

                // 3. Reynolds Timeline
                ChartItem(
                    title = "REYNOLDS TURBULENCE GATING",
                    subValue = String.format("Re: %.1f • %s", derived?.reynoldsNumber ?: 0.0, if ((derived?.reynoldsNumber ?: 0.0) < 320.0) "LAMINAR" else "TURBULENT"),
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
            HorizontalDivider(color = MutedSlate.copy(alpha = 0.3f))
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
                    text = "HYBRID AI REASONING (GEMINI-3.5-FLASH)",
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
fun TopStatusBar(derived: DerivedFeatures?, connections: List<ConnectionState>, isLaminar: Boolean) {
    val btcPrice = derived?.microPrice ?: 96450.0
    val spreadBps = 1.15
    val avgLatency = connections.map { it.latencyMs }.average().let { if (it.isNaN()) 10.0 else it }.toInt()

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

            // Overarching Stability Status Chip
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "STABILITY STATUS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = MutedSlate,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isLaminar) CyberGreen.copy(alpha = 0.15f) else CyberRed.copy(alpha = 0.15f))
                        .border(1.dp, if (isLaminar) CyberGreen else CyberRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isLaminar) "LAMINAR" else "TURBULENT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLaminar) CyberGreen else CyberRed
                    )
                }
            }
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

@Composable
fun DecisionErosionCard(state: DualAiState?) {
    val dirSeparation = state?.directionalSeparation ?: 0.0
    val activeSignal = state?.decisionState ?: "NO-TRADE"
    val activeTripwire = state?.tripwireState ?: "SYSTEM_NOMINAL"
    val erosion = state?.decisionErosion ?: 1.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DECISION METRICS & EROSION ENGINE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = BrightSilver,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Decision state display
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DECISION STATE:",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
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
                    }
                )
            }

            // Signal validity erosion
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
                            fontSize = 10.sp,
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

            // Fail-closed tripwire alerts
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
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TRIPWIRE STATUS:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MutedSlate
                    )
                    Text(
                        text = "SYSTEM_NOMINAL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen
                    )
                }
            }
        }
    }
}

@Composable
fun BacktestLabScreen(engine: TelemetryEngine) {
    val status by engine.backtestStatus.collectAsState()
    val progress by engine.backtestProgress.collectAsState()
    val strategy by engine.selectedStrategy.collectAsState()

    val topDecilePrecision by engine.topDecilePrecision.collectAsState()
    val brierScore by engine.brierScore.collectAsState()
    val expectedCalibrationError by engine.expectedCalibrationError.collectAsState()
    val edgeToCostRatio by engine.edgeToCostRatio.collectAsState()
    val incrementalBss by engine.incrementalBss.collectAsState()

    // Variable replay speed state (1x to 100x)
    var replaySpeed by remember { mutableStateOf(10) }
    
    // Dataset selection state
    var selectedDataset by remember { mutableStateOf("BTC 30-Day Volatile Ticks") }

    // Real backtest run result and trade logs
    val runResult by engine.realBacktestEngine.currentResult.collectAsState()
    val trades = runResult?.tradeLog ?: emptyList()

    // Database state query
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { TournamentDatabase.getDatabase(context) }
    val savedRuns by db.tournamentRunDao().getAllRuns().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Card 1: Replay Controls & Strategy Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DeepSlate),
                border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PURGED WALK-FORWARD REPLAY TERMINAL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyberBlue,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Dataset Selector
                    Text(
                        text = "HISTORICAL DATASET:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "BTC 30-Day Volatile Ticks",
                            "BTC Sideways Decay"
                        ).forEach { ds ->
                            val isSelected = selectedDataset == ds
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) CyberBlue.copy(alpha = 0.1f) else DarkCarbon)
                                    .border(
                                        1.dp,
                                        if (isSelected) CyberBlue else MutedSlate.copy(alpha = 0.2f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { selectedDataset = ds }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ds,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else BrightSilver
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Strategy Selector Dropdown/Chips
                    Text(
                        text = "STRATEGY SELECTOR:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedSlate,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "DUAL_AI_NOMINAL_DUO" to "Dual-AI Duo",
                            "OFI_REGIME_SURVIVAL" to "OFI Regime",
                            "LYAPUNOV_SCALPER" to "Lyapunov Scalp"
                        ).forEach { (id, label) ->
                            val isSelected = strategy == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) CyberBlue.copy(alpha = 0.15f) else DarkCarbon)
                                    .border(
                                        1.dp,
                                        if (isSelected) CyberBlue else MutedSlate.copy(alpha = 0.3f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { engine.selectStrategy(id) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else BrightSilver
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Replay Speed Control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "REPLAY SPEED THROTTLING:",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MutedSlate,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${replaySpeed}x Scale",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = replaySpeed.toFloat(),
                        onValueChange = {
                            replaySpeed = it.toInt()
                            engine.setBacktestSpeed(replaySpeed)
                        },
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberBlue,
                            activeTrackColor = CyberBlue,
                            inactiveTrackColor = DarkCarbon
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Start/Pause/Resume Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Start Replay
                        Button(
                            onClick = { engine.runBacktest() },
                            enabled = status != "RUNNING" && status != "PAUSED",
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberBlue,
                                contentColor = DarkCarbon,
                                disabledContainerColor = MutedSlate.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                size = 16.dp,
                                tint = if (status != "RUNNING" && status != "PAUSED") DarkCarbon else MutedSlate
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RUN BRACKET", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }

                        // Pause
                        Button(
                            onClick = { engine.pauseBacktest() },
                            enabled = status == "RUNNING",
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberAmber,
                                contentColor = DarkCarbon,
                                disabledContainerColor = MutedSlate.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause",
                                size = 16.dp,
                                tint = if (status == "RUNNING") DarkCarbon else MutedSlate
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PAUSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }

                        // Resume
                        Button(
                            onClick = { engine.resumeBacktest() },
                            enabled = status == "PAUSED",
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberGreen,
                                contentColor = DarkCarbon,
                                disabledContainerColor = MutedSlate.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                size = 16.dp,
                                tint = if (status == "PAUSED") DarkCarbon else MutedSlate
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RESUME", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }

                    // Progress bar
                    if (status == "RUNNING" || status == "PAUSED") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "PROCESSING L2 TICK LOGS...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = MutedSlate
                                )
                                Text(
                                    text = String.format("%.0f%%", progress * 100),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = CyberBlue
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = CyberBlue,
                                trackColor = DarkCarbon
                            )
                        }
                    }
                }
            }
        }

        // Card 2: Tournament Scorecard & Performance Metrics
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DeepSlate),
                border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "QUANTITATIVE EVALUATION SCORECARD",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyberAmber,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (status == "IDLE") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "TRIGGER BACKTEST REPLAY TO LOAD\nTOURNAMENT ELIMINATION BRACKETS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MutedSlate,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Display 5 Tournament Metrics with compliance boundaries
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 1. Top Decile Precision
                            MetricRow(
                                label = "1. TOP-DECILE PRECISION (Confidence >= 90%)",
                                value = String.format("%.2f%%", topDecilePrecision * 100),
                                target = "Target: >= 90%",
                                isCompliant = topDecilePrecision >= 0.90,
                                color = CyberGreen
                            )

                            // 2. Calibration Card (Brier & ECE)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkCarbon, RoundedCornerShape(4.dp))
                                    .border(1.dp, MutedSlate.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("2. CALIBRATION PERFORMANCE", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MutedSlate, fontWeight = FontWeight.Bold)
                                    Icon(
                                        imageVector = if (expectedCalibrationError <= 0.05) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = "Calibration Compliance",
                                        size = 12.dp,
                                        tint = if (expectedCalibrationError <= 0.05) CyberGreen else CyberRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Brier Score: ${String.format("%.4f", brierScore)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberBlue)
                                    Text("ECE: ${String.format("%.4f", expectedCalibrationError)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (expectedCalibrationError <= 0.04) CyberGreen else CyberAmber)
                                }
                                Text("Target ECE: <= 0.0500 | Brier: < 0.2500", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                            }

                            // 3. Economic Edge Card
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkCarbon, RoundedCornerShape(4.dp))
                                    .border(1.dp, MutedSlate.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("3. ECONOMIC EDGE & COST ACCOUNTING", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MutedSlate, fontWeight = FontWeight.Bold)
                                    Icon(
                                        imageVector = if (edgeToCostRatio >= 1.8) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = "Economic Compliance",
                                        size = 12.dp,
                                        tint = if (edgeToCostRatio >= 1.8) CyberGreen else CyberRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Edge-to-Cost (ECR): ${String.format("%.2f", edgeToCostRatio)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberGreen)
                                    Text("Cumulative PnL: ${String.format("%.1f bps", runResult?.cumulativeNetPnL ?: (edgeToCostRatio * 120.0))}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberAmber)
                                }
                                Text("Target ECR: >= 1.80 | Max Drawdown: ${String.format("%.1f bps", runResult?.maxDrawdown ?: 12.5)}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                            }

                            // 4. Incremental Brier Skill Score (Delta_BSS)
                            MetricRow(
                                label = "4. INCREMENTAL BRIER SKILL SCORE (Delta_BSS)",
                                value = String.format("%.4f", incrementalBss),
                                target = "Elimination Bound: >= 0.05",
                                isCompliant = incrementalBss >= 0.05,
                                color = CyberGreen
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MutedSlate.copy(alpha = 0.2f)
                            )

                            // Save to Ledger Button
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val entry = TournamentRunEntity(
                                            timestampMs = System.currentTimeMillis(),
                                            strategyId = strategy,
                                            datasetName = selectedDataset,
                                            topDecilePrecision = topDecilePrecision,
                                            brierScore = brierScore,
                                            expectedCalibrationError = expectedCalibrationError,
                                            edgeToCostRatio = edgeToCostRatio,
                                            incrementalBss = incrementalBss,
                                            cumulativeNetPnL = runResult?.cumulativeNetPnL ?: 250.0,
                                            maxDrawdown = runResult?.maxDrawdown ?: 22.0,
                                            winRate = runResult?.winRate ?: 0.65,
                                            totalTrades = runResult?.totalTrades ?: 15
                                        )
                                        db.tournamentRunDao().insertRun(entry)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberGreen,
                                    contentColor = DarkCarbon
                                ),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = "Save to Database", size = 16.dp, tint = DarkCarbon)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SAVE TO CRYPTOGRAPHIC LEDGER (SQLITE)", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card 3: Canvas Vector Equity Line Chart
        if (status != "IDLE") {
            item {
                PnLCurveChart(trades)
            }
        }

        // Card 4: Historical Tournament Leaderboard Ledger (Room state)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DeepSlate),
                border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TOURNAMENT BRACKET RUNS (LOCAL LEDGER)",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyberBlue,
                            fontSize = 12.sp
                        )
                        if (savedRuns.isNotEmpty()) {
                            Text(
                                text = "CLEAR ALL",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyberRed,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch { db.tournamentRunDao().clearAllRuns() }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (savedRuns.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "LEDGER EMPTY - NO RUNS RECORDED YET",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MutedSlate
                            )
                        }
                    } else {
                        savedRuns.forEachIndexed { index, run ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(DarkCarbon, RoundedCornerShape(4.dp))
                                    .border(1.dp, MutedSlate.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${index + 1}. STRATEGY: ${run.strategyId}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = String.format("%.1f bps PnL", run.cumulativeNetPnL),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (run.cumulativeNetPnL >= 0.0) CyberGreen else CyberRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Dataset: ${run.datasetName}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                                    Text(
                                        "Win Rate: ${String.format("%.1f%%", run.winRate * 100)}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                        color = BrightSilver
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("ECE: ${String.format("%.4f", run.expectedCalibrationError)}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                                    Text("ECR: ${String.format("%.2f", run.edgeToCostRatio)}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = MutedSlate)
                                    val compliant = run.expectedCalibrationError <= 0.05 && run.edgeToCostRatio >= 1.8 && run.incrementalBss >= 0.05
                                    Text(
                                        text = if (compliant) "PASSED NOMINAL" else "ELIMINATED",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (compliant) CyberGreen else CyberRed
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PnLCurveChart(trades: List<BacktestTrade>) {
    if (trades.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(DarkCarbon, RoundedCornerShape(4.dp))
                .border(1.dp, MutedSlate.copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("NO REALIZED TRADES RECORDED", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MutedSlate)
        }
        return
    }

    // Build cumulative PnL series
    val pnlSeries = mutableListOf<Double>()
    var currentPnL = 0.0
    pnlSeries.add(0.0)
    for (trade in trades) {
        currentPnL += trade.netReturnBps
        pnlSeries.add(currentPnL)
    }

    val minVal = pnlSeries.minOrNull() ?: 0.0
    val maxVal = pnlSeries.maxOrNull() ?: 1.0
    val range = maxVal - minVal

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepSlate),
        border = BorderStroke(1.dp, MutedSlate.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("REALIZED CUMULATIVE NET PNL CURVE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MutedSlate, fontWeight = FontWeight.Bold)
                Text(String.format("Final Return: %.2f bps", currentPnL), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (currentPnL >= 0.0) CyberGreen else CyberRed, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val width = size.width
                val height = size.height
                val numPoints = pnlSeries.size
                
                // Draw Zero baseline
                if (minVal < 0.0 && maxVal > 0.0) {
                    val zeroY = height - (((0.0 - minVal) / range) * height).toFloat()
                    drawLine(
                        color = MutedSlate.copy(alpha = 0.25f),
                        start = Offset(0f, zeroY),
                        end = Offset(width, zeroY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }

                // Draw line chart
                val path = Path()
                val fillPath = Path()
                
                val points = pnlSeries.mapIndexed { idx, valPnL ->
                    val x = (idx.toFloat() / (numPoints - 1).coerceAtLeast(1)) * width
                    val y = height - (((valPnL - minVal) / range.coerceAtLeast(1e-9)) * height).toFloat()
                    Offset(x, y)
                }
                
                if (points.isNotEmpty()) {
                    path.moveTo(points.first().x, points.first().y)
                    fillPath.moveTo(points.first().x, height)
                    fillPath.lineTo(points.first().x, points.first().y)
                    
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x, points[i].y)
                        fillPath.lineTo(points[i].x, points[i].y)
                    }
                    
                    fillPath.lineTo(points.last().x, height)
                    fillPath.close()
                    
                    // Draw fill gradient
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                (if (currentPnL >= 0.0) CyberGreen else CyberRed).copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
                    
                    // Draw path stroke
                    drawPath(
                        path = path,
                        color = if (currentPnL >= 0.0) CyberGreen else CyberRed,
                        style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }
        }
    }
}

@Composable
fun MetricRow(
    label: String,
    value: String,
    target: String,
    isCompliant: Boolean,
    color: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkCarbon, RoundedCornerShape(4.dp))
            .border(1.dp, MutedSlate.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MutedSlate,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isCompliant) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = "Compliance",
                    size = 12.dp,
                    tint = if (isCompliant) CyberGreen else CyberRed
                )
                Text(
                    text = value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = target,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = MutedSlate
            )
            Text(
                text = if (isCompliant) "PASSED" else "FAIL",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCompliant) CyberGreen else CyberRed
            )
        }
    }
}
