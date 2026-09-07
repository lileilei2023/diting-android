package com.diting.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

        requestPermissions.launch(DitingPermissions.all())

        setContent {
            DitingTheme {
                val gateCount by shellViewModel.gateCount.collectAsStateWithLifecycle()
                val isRecording by CaptureService.isRecording.collectAsStateWithLifecycle()
                val captureError by CaptureService.error.collectAsStateWithLifecycle()

                DitingNavHost(
                    isRecording = isRecording,
                    gateCount = gateCount,
                    onStartCapture = { CaptureService.start(this) },
                    onStopCapture = { CaptureService.stop(this) },
                )

                // A capture that refused to start has no UI of its own — the
                // service is gone by the time anyone could look at it — so the
                // shell is the only place left to say why.
                captureError?.let { message ->
                    AlertDialog(
                        onDismissRequest = CaptureService::clearError,
                        title = { Text("无法录音") },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = {
                                CaptureService.clearError()
                                // Straight to this app's permission page: telling
                                // someone to "go to Settings" and leaving them to
                                // find it is where most people give up.
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null),
                                    )
                                )
                            }) { Text("去设置") }
                        },
                        dismissButton = {
                            TextButton(onClick = CaptureService::clearError) { Text("知道了") }
                        },
                    )
                }
            }
        }
    }

}
