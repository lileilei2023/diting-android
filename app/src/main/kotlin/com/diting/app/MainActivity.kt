package com.diting.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diting.app.capture.CaptureService
import com.diting.app.data.repo.TaskRepository
import com.diting.app.ui.nav.DitingNavHost
import com.diting.app.ui.theme.DitingTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import javax.inject.Inject

/** Only the counter the bottom bar needs; screens own the rest of their state. */
@HiltViewModel
class ShellViewModel @Inject constructor(tasks: TaskRepository) : ViewModel() {
    val gateCount: StateFlow<Int> = tasks.observeGateCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val shellViewModel: ShellViewModel by viewModels()

    /**
     * Permissions are requested as a group at launch rather than one at a time.
     *
     * A recorder app that cannot scan, connect and record is not partially
     * usable — it is broken — so asking once up front beats interrupting the user
     * three separate times mid-flow.
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Each screen re-checks what it needs and explains what is missing. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissions.launch(requiredPermissions())

        setContent {
            DitingTheme {
                val gateCount by shellViewModel.gateCount.collectAsStateWithLifecycle()
                val isRecording by CaptureService.isRecording.collectAsStateWithLifecycle()

                DitingNavHost(
                    isRecording = isRecording,
                    gateCount = gateCount,
                    onStartCapture = { CaptureService.start(this) },
                    onStopCapture = { CaptureService.stop(this) },
                )
            }
        }
    }

    /**
     * The permission set differs sharply across versions: API 31 replaced the
     * location-implying Bluetooth permissions, and 33 added notification consent
     * that the foreground sync service depends on.
     */
    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Below 31 the platform will not scan at all without a location grant,
            // however unrelated that is to what the app actually does.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
