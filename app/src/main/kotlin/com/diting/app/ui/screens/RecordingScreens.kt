@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.capture.CaptureService
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.TaskRepository
import com.diting.app.device.Mr20DeviceManager
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.scene.Scene
import com.diting.domain.task.TaskOrigin
import com.diting.protocol.mr20.Mr20DeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 实时录音 · 会议模式
// =============================================================================

data class RecordingUiState(
    val isRecording: Boolean = false,
    val deviceInfo: Mr20DeviceInfo = Mr20DeviceInfo(),
    val deviceConnected: Boolean = false,
    val scenes: List<Scene> = emptyList(),
    val activeSceneId: String? = null,
    /**
     * Whether the realtime channel is running.
     *
     * Kept visible at all times on purpose: principle 4 is that the cost of the
     * realtime channel is the user's to see, not a background black box.
     */
    val realtimeActive: Boolean = false,
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val deviceManager: Mr20DeviceManager,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _activeScene = MutableStateFlow<String?>(null)
    private val _realtime = MutableStateFlow(false)

    /**
     * The two screen-local toggles, folded into one flow.
     *
     * combine() takes at most five typed sources, and reading `_realtime.value`
     * from inside the lambda instead would not re-emit when it changed — the
     * realtime pill would silently stop matching the actual channel state.
     */
    private val localToggles: kotlinx.coroutines.flow.Flow<Pair<String?, Boolean>> =
        combine(_activeScene, _realtime) { sceneId, realtime -> sceneId to realtime }

    val state: StateFlow<RecordingUiState> = combine(
        CaptureService.isRecording,
        deviceManager.info,
        deviceManager.client,
        settings.scenes,
        localToggles,
    ) { recording, info, client, scenes, (sceneId, realtime) ->
        RecordingUiState(
            isRecording = recording || info.isRecording,
            deviceInfo = info,
            deviceConnected = client != null,
            scenes = scenes,
            activeSceneId = sceneId,
            realtimeActive = realtime,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    /** Overrides the auto-detected scene for this recording only. */
    fun overrideScene(sceneId: String?) {
        _activeScene.value = sceneId
    }

    /** The realtime channel is opt-in per session, not a global setting. */
    fun setRealtime(active: Boolean) {
        _realtime.value = active
    }

    /** Asks the recorder itself to start, when one is connected. */
    fun startOnDevice() = viewModelScope.launch {
        deviceManager.client.value?.let { runCatching { it.startRecording() } }
    }

    fun stopOnDevice() = viewModelScope.launch {
        deviceManager.client.value?.let { runCatching { it.stopRecording() } }
    }
}

@Composable
fun RecordingScreen(
    onStartPhoneCapture: () -> Unit,
    onStopPhoneCapture: () -> Unit,
    onOpenScenes: () -> Unit,
    onDone: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("会议模式", style = MaterialTheme.typography.headlineMedium)

        // Principle 4 made visible: the channel's state is always on screen.
        InkPanel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        if (state.realtimeActive) "实时中" else "已省电暂停",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onInkPanel,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (state.realtimeActive) {
                            "正在联网实时转写，耗电与流量都在走。"
                        } else {
                            "只在本机录音，转写会在结束后批处理。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentOnPanel,
                    )
                }
                TextButton(onClick = { viewModel.setRealtime(!state.realtimeActive) }) {
                    Text(if (state.realtimeActive) "暂停实时" else "开启实时")
                }
            }
        }

        RailCard(rail = if (state.deviceConnected) colors.railTranscript else colors.inkMuted) {
            Text(
                if (state.deviceConnected) "录音卡已连接" else "没有连接录音卡",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.deviceInfo.batteryPercent?.let { Pill("电量 $it%") }
                state.deviceInfo.freeMb?.let { Pill("剩余 ${it}MB") }
                state.deviceInfo.recordMode?.let { Pill(it.name) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.deviceConnected) {
                    if (state.isRecording) {
                        OutlinedButton(onClick = viewModel::stopOnDevice) { Text("停止设备录音") }
                    } else {
                        Button(onClick = viewModel::startOnDevice) { Text("让设备开始录音") }
                    }
                }
            }
        }

        // Scene override — "这场按 X 处理". One chip, not a settings trip.
        RailCard(rail = colors.railInsight) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("这场按哪个场景处理", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenScenes) { Text("管理场景") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.scenes.take(4).forEach { scene ->
                    AssistChip(
                        onClick = { viewModel.overrideScene(scene.id) },
                        label = { Text(scene.name, maxLines = 1, softWrap = false) },
                    )
                }
            }
            state.activeSceneId?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已覆写为「${state.scenes.firstOrNull { s -> s.id == it }?.name ?: it}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (state.isRecording) {
            Button(
                onClick = {
                    onStopPhoneCapture()
                    onDone()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.railAction),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成 ✓") }
        } else {
            Button(
                onClick = onStartPhoneCapture,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用手机麦克风开始录音") }
        }
    }
}

// =============================================================================
// AI 对话
// =============================================================================

data class ChatMessageUi(val fromUser: Boolean, val text: String, val taskId: String? = null)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val tasks: TaskRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessageUi(
                fromUser = false,
                text = "我是小谛。问我今天说过什么，或者直接说「帮我…」，我会先建一条待确认的任务。",
            )
        )
    )
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    /**
     * An imperative becomes a task at gate ①, not an immediate action.
     *
     * Typing "帮我给李总发个邮件" must not send an email. It creates something the
     * user then confirms — the same amber gate every other origin funnels into.
     */
    fun send(text: String) = viewModelScope.launch {
        _messages.value = _messages.value + ChatMessageUi(fromUser = true, text = text)

        if (looksImperative(text)) {
            val task = tasks.propose(
                goal = text.removePrefix("帮我").trim().ifBlank { text },
                origin = TaskOrigin.CHAT_IMPERATIVE,
            )
            _messages.value = _messages.value + ChatMessageUi(
                fromUser = false,
                text = "已经建成一条待确认任务，你确认目标之后我再开始做。",
                taskId = task.id,
            )
        } else {
            _messages.value = _messages.value + ChatMessageUi(
                fromUser = false,
                text = "这个问题需要理解模型才能回答。配置好模型后我就能翻记忆图谱回答你了。",
            )
        }
    }

    private fun looksImperative(text: String): Boolean =
        IMPERATIVE_MARKERS.any { text.contains(it) }

    private companion object {
        val IMPERATIVE_MARKERS = listOf("帮我", "给我", "去做", "安排", "起草", "整理一下")
    }
}

@Composable
fun ChatScreen(
    onOpenTask: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val colors = ditingColors
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message ->
                RailCard(
                    rail = if (message.fromUser) colors.railTranscript else colors.railInsight,
                    onClick = message.taskId?.let { id -> { onOpenTask(id) } },
                ) {
                    Text(
                        if (message.fromUser) "你" else "小谛",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.fromUser) colors.railTranscript else colors.railInsight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    message.taskId?.let {
                        Spacer(Modifier.height(6.dp))
                        Pill("去确认 ›", color = colors.railAction)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问点什么，或者说「帮我…」") },
                maxLines = 3,
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        viewModel.send(draft)
                        draft = ""
                    }
                }
            ) { Text("发送") }
        }
    }
}

// =============================================================================
// 碎念 / 快速捕捉 — empty-state helper used by the tasks tab
// =============================================================================

@Composable
fun NotesEmptyState(onStartCapture: () -> Unit) {
    EmptyState(
        headline = "还没有碎念",
        hint = "短按录音键说一句，三分钟内的想法会先落在这里，够清楚了再升级成任务。",
        action = { Button(onClick = onStartCapture) { Text("现在记一句") } },
    )
}
