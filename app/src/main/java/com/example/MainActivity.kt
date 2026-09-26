package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.telemetry.TelemetryEngine
import com.example.ui.hud.TelemetryHUD
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val telemetryEngine = TelemetryEngine.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.example.ui.theme.DarkCarbon
                ) {
                    TelemetryHUD(engine = telemetryEngine)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start the high-precision background calculation loop (decoupled thread pool)
        telemetryEngine.startDecoupledCalculationLoop()
    }

    override fun onStop() {
        super.onStop()
        // Cancel the calculation loop to release coroutines thread resources
        telemetryEngine.stopDecoupledCalculationLoop()
    }
}
