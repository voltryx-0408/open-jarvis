package com.openjarvis.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.ui.dashboard.DashboardScreen
import com.openjarvis.ui.settings.SettingsScreen
import com.openjarvis.ui.theme.OpenJarvisTheme

class MainActivity : ComponentActivity() {

    private lateinit var graphifyRepo: GraphifyRepository
    private var permissionRefresh by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        permissionRefresh++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        graphifyRepo = GraphifyRepository(this)

        setContent {
            OpenJarvisTheme {
                // Re-read permission state after returning from Android Settings.
                val permissionRefreshSnapshot = permissionRefresh
                val accessibilityEnabled = isAccessibilityServiceEnabled()
                val overlayEnabled = Settings.canDrawOverlays(this)

                if (!accessibilityEnabled || !overlayEnabled) {
                    PermissionScreen(
                        accessibilityEnabled = accessibilityEnabled,
                        overlayEnabled = overlayEnabled,
                        onEnableAccessibility = { startAccessibilitySettings() },
                        onEnableOverlay = { startOverlaySettings() }
                    )
                } else {
                    DashboardScreen(
                        onStartOverlay = { startOverlayService() },
                        onOpenSettings = { openSettings() },
                        graphifyRepo = graphifyRepo,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val componentName = ComponentName(this, JarvisAccessibilityService::class.java)
        return enabledServices.contains(componentName.flattenToString())
    }

    private fun startAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }

    private fun startOverlayService() {
        androidx.core.content.ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java)
        )
        Toast.makeText(this, "Jarvis is active", Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@Composable
fun PermissionScreen(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit
) {
    val primaryColor = com.openjarvis.ui.theme.VoidColor.Violet
    val onSurfaceColor = com.openjarvis.ui.theme.VoidColor.TextPrimary
    val onSurfaceVariantColor = com.openjarvis.ui.theme.VoidColor.TextSecondary
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        androidx.compose.material3.Text(
            text = "Permission Required",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = onSurfaceColor
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.material3.Text(
            text = "Open Jarvis uses Accessibility to read visible app content and perform requested taps or text entry. Overlay access displays its floating controls.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = onSurfaceVariantColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(32.dp))

        if (!accessibilityEnabled) {
            androidx.compose.material3.Button(
                onClick = { showAccessibilityDisclosure = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Text("Review Accessibility Use")
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        }

        if (!overlayEnabled) {
            androidx.compose.material3.Button(
                onClick = onEnableOverlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Text("Enable Overlay Permission")
            }
        }

        if (accessibilityEnabled && overlayEnabled) {
            androidx.compose.material3.Text(
                text = "Jarvis is active",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = primaryColor
            )
        }
    }

    if (showAccessibilityDisclosure) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = {
                androidx.compose.material3.Text("Before enabling Accessibility")
            },
            text = {
                androidx.compose.material3.Text(
                    "When you ask Jarvis to automate an app, Android Accessibility can inspect visible screen text and interact with app controls, including tapping and entering text. If you use an online AI provider, command context and screen text may be sent to the provider you configured to process your request. Avoid using Jarvis on passwords, payment screens, private messages, or other sensitive content. Accessibility is optional and can be turned off any time in Android Settings. Continue only if you understand and agree."
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        showAccessibilityDisclosure = false
                        onEnableAccessibility()
                    }
                ) {
                    androidx.compose.material3.Text("I Understand — Continue")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showAccessibilityDisclosure = false }
                ) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }
}
