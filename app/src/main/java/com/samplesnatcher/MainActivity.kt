package com.samplesnatcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.samplesnatcher.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.samplesnatcher.capture.CaptureAudioService
import com.samplesnatcher.ui.EditorScreen
import com.samplesnatcher.ui.HomeScreen
import com.samplesnatcher.ui.SettingsScreen
import com.samplesnatcher.ui.theme.SampleSnatcherTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val start = Intent(this, CaptureAudioService::class.java).apply {
                    action = CaptureAudioService.ACTION_START
                    putExtra(CaptureAudioService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureAudioService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, start)
            }
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            if (granted.values.all { it }) {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        setContent {
            SampleSnatcherTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    var captureService by remember { mutableStateOf<CaptureAudioService?>(null) }
                    var showCaptureExplainer by remember { mutableStateOf(false) }

                    if (showCaptureExplainer) {
                        AlertDialog(
                            onDismissRequest = { showCaptureExplainer = false },
                            title = { Text(stringResource(R.string.projection_system_dialog_title)) },
                            text = { Text(stringResource(R.string.projection_system_dialog_body)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showCaptureExplainer = false
                                        val needs = buildList {
                                            add(Manifest.permission.RECORD_AUDIO)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }.toTypedArray()
                                        permissionLauncher.launch(needs)
                                    },
                                ) {
                                    Text(stringResource(R.string.continue_button))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCaptureExplainer = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }

                    DisposableEffect(Unit) {
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                captureService = (service as? CaptureAudioService.LocalBinder)?.getService()
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                captureService = null
                            }
                        }
                        bindService(
                            Intent(this@MainActivity, CaptureAudioService::class.java),
                            connection,
                            BIND_AUTO_CREATE,
                        )
                        onDispose {
                            try {
                                unbindService(connection)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    val svc = captureService
                    val stateFlow = remember(svc) {
                        svc?.captureState ?: MutableStateFlow(CaptureAudioService.CaptureUiState())
                    }
                    val state by stateFlow.collectAsStateWithLifecycle()

                    fun beginCaptureFlow() {
                        showCaptureExplainer = true
                    }

                    fun stopCapture() {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, CaptureAudioService::class.java).apply {
                                action = CaptureAudioService.ACTION_STOP
                            },
                        )
                    }

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                isCapturing = state.isRunning,
                                lastError = state.lastError,
                                onStartCapture = { beginCaptureFlow() },
                                onStopCapture = { stopCapture() },
                                onOpenEditor = { navController.navigate("editor") },
                                onOpenSettings = { navController.navigate("settings") },
                            )
                        }
                        composable("editor") {
                            val svc = captureService
                            val ring = svc?.getRingBuffer()
                            DisposableEffect(svc) {
                                svc?.setCapturePaused(true)
                                onDispose {
                                    svc?.setCapturePaused(false)
                                }
                            }
                            if (ring != null) {
                                EditorScreen(
                                    ring = ring,
                                    isCapturing = state.isRunning,
                                    bufferFrozen = true,
                                    onBack = { navController.popBackStack() },
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
