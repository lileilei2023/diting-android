@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.style.TextAlign
import com.diting.app.ui.components.formatClock
import com.diting.app.ui.components.formatShortDate
import com.diting.app.ui.theme.TimestampStyle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.diting.app.ui.components.AiOrb
import com.diting.app.ui.components.BackCircle
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
    val context = LocalContext.current
    var quickMode by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf(false) }

    // Elapsed clock: starts when recording flips on, ticks every second.
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            if (startedAt == null) startedAt = System.currentTimeMillis()
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000)
            }
        } else {
            startedAt = null
        }
    }
    val elapsed = startedAt?.let { now - it } ?: 0L
    val clock = "%02d:%02d:%02d".format(elapsed / 3_600_000, elapsed / 60_000 % 60, elapsed / 1_000 % 60)

    // Live bars: a slow drift while idle, lively while recording.
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (state.isRecording) 900 else 3_000, easing = LinearEasing)),
        label = "phase",
    )
    val blink by transition.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink",
    )

    val ink = colors.inkPanel
    val fg = colors.onInkPanel
    val mint = colors.accentOnPanel
    val sceneName = state.scenes.firstOrNull { it.id == state.activeSceneId }?.name ?: "自动"

    Column(
        Modifier
            .fillMaxSize()
            .background(ink)
            .padding(20.dp, 16.dp, 20.dp, 28.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (quickMode) "快速捕捉" else "会议模式",
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(percent = 50))
                    .clickable { quickMode = !quickMode }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rt = if (state.realtimeActive) mint else fg.copy(alpha = 0.5f)
                Box(Modifier.size(7.dp).clip(CircleShape).background(rt))
                Text(if (state.realtimeActive) "实时通道开" else "已省电暂停", style = MaterialTheme.typography.labelMedium, color = rt)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatShortDate(now)} ${formatClock(now)} · " + when {
                    state.deviceConnected -> "录音卡"
                    else -> "手机麦克风"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = fg.copy(alpha = 0.6f),
            )
            Text(
                "场景：$sceneName ▾",
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(mint.copy(alpha = 0.15f))
                    .clickable {
                        val ids = listOf<String?>(null) + state.scenes.map { it.id }
                        val next = ids[(ids.indexOf(state.activeSceneId) + 1) % ids.size]
                        viewModel.overrideScene(next)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = mint,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE0533A).copy(alpha = if (state.isRecording) blink else 0.3f)))
            Text(clock, style = TimestampStyle.copy(fontSize = 52.sp, letterSpacing = (-1).sp), fontWeight = FontWeight.SemiBold, color = fg)
        }

        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp)) {
            val n = 48
            val gap = size.width / n
            val w = gap * 0.5f
            for (i in 0 until n) {
                val base = 0.15f + 0.85f * (0.5f + 0.5f * kotlin.math.sin((i * 0.7f + phase * 6.28f).toDouble()).toFloat()) *
                    (0.4f + 0.6f * kotlin.math.abs(kotlin.math.sin((i * 1.9f + 1f).toDouble()).toFloat()))
                val h = size.height * (if (state.isRecording) base else base * 0.35f)
                drawRoundRect(
                    color = if (state.isRecording) mint.copy(alpha = 0.9f) else fg.copy(alpha = 0.25f),
                    topLeft = androidx.compose.ui.geometry.Offset(i * gap + (gap - w) / 2, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
            }
        }

        Column(Modifier.weight(1f).padding(top = 14.dp), verticalArrangement = Arrangement.Bottom) {
            if (quickMode) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "快速捕捉不联网、不开字幕。\n松手即存，小谛稍后离线转写。",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp),
                        color = fg.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                if (state.deviceConnected) {
                    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.deviceInfo.batteryPercent?.let { Pill("电量 $it%", color = fg, background = Color.White.copy(alpha = 0.1f)) }
                        state.deviceInfo.freeMb?.let { Pill("剩余 ${it}MB", color = fg, background = Color.White.copy(alpha = 0.1f)) }
                        state.deviceInfo.recordMode?.let { Pill(it.name, color = fg, background = Color.White.copy(alpha = 0.1f)) }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.amberOnPanel.copy(alpha = 0.10f))
                        .border(1.dp, colors.amberOnPanel.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(12.dp, 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("小谛 · 会后整理", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), fontWeight = FontWeight.SemiBold, color = colors.amberOnPanel)
                        Text(clock, style = TimestampStyle.copy(fontSize = 11.sp), color = fg.copy(alpha = 0.5f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.realtimeActive) "实时字幕通道还没接上，这场仍按结束后批处理转写。承诺与待办会进「待确认」，现在不打断。"
                        else "这场只在本机录音，结束后同步到大脑批处理转写。听到承诺会进「待确认」，现在不打断。",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = fg,
                    )
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A",
                        modifier = Modifier.border(1.dp, mint, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = mint,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.isRecording) "正在录…" else "按下方按钮开始",
                            style = MaterialTheme.typography.bodyLarge,
                            color = fg.copy(alpha = 0.8f),
                        )
                        Box(Modifier.padding(start = 2.dp).width(2.dp).height(16.dp).background(mint.copy(alpha = blink)))
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("中文 → English", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.6f))
            Row(
                Modifier.clickable {
                    translate = !translate
                    viewModel.setRealtime(translate)
                    if (translate) Toast.makeText(context, "实时通道按时长计费；翻译模型还没接入", Toast.LENGTH_SHORT).show()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("实时翻译", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.6f))
                Box(Modifier.width(34.dp).height(20.dp).clip(RoundedCornerShape(percent = 50)).background(if (translate) colors.railTranscript else Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.padding(2.dp).align(if (translate) Alignment.CenterEnd else Alignment.CenterStart).size(16.dp).clip(CircleShape).background(Color.White))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Text("取消", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = fg) }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        if (state.isRecording) {
                            Toast.makeText(context, "录音不支持暂停，点右侧 ✓ 结束", Toast.LENGTH_SHORT).show()
                        } else if (state.deviceConnected) {
                            viewModel.startOnDevice()
                        } else {
                            onStartPhoneCapture()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.isRecording) "录音中" else "开始", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), fontWeight = FontWeight.SemiBold, color = ink)
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (state.isRecording) colors.railTranscript else colors.railTranscript.copy(alpha = 0.35f))
                    .clickable(enabled = state.isRecording) {
                        if (state.deviceConnected && state.deviceInfo.isRecording) viewModel.stopOnDevice() else onStopPhoneCapture()
                        Toast.makeText(context, "已结束，稍后自动转写", Toast.LENGTH_SHORT).show()
                        onDone()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = "完成", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// =============================================================================
// AI 对话
// =============================================================================

data class ChatMessageUi(
    val fromUser: Boolean,
    val text: String,
    val taskId: String? = null,
    /** Memory rows the brain answered from, shown as ↩ lines. */
    val sources: List<String> = emptyList(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val brain: com.diting.app.brain.BrainApi,
    private val brainStore: com.diting.app.brain.BrainStore,
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

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /**
     * An imperative becomes a task at gate ①, not an immediate action.
     *
     * Typing "帮我给李总发个邮件" must not send an email. It creates something the
     * user then confirms — the same amber gate every other origin funnels into.
     * Anything else is a question, answered by the brain from the memory graph.
     */
    fun send(text: String) = viewModelScope.launch {
        _messages.value = _messages.value + ChatMessageUi(fromUser = true, text = text)

        if (looksImperative(text)) {
            val task = tasks.propose(
                goal = text.removePrefix("帮我").trim().ifBlank { text },
                origin = TaskOrigin.CHAT_IMPERATIVE,
            )
            reply("已经建成一条待确认任务，你确认目标之后我再开始做。", taskId = task.id)
            return@launch
        }

        if (!brainStore.current.isLoggedIn) {
            reply("回答问题要翻记忆图谱，这需要登录大脑账户（我的 › 大脑账户）。")
            return@launch
        }

        _thinking.value = true
        try {
            val result = brain.ask(text)
            val sources = result.sources.map { it.text.ifBlank { it.title } }.filter { it.isNotBlank() }.distinct().take(3)
            when {
                result.answer.isNotBlank() -> reply(result.answer, sources = sources)
                sources.isNotEmpty() -> reply("大脑这会儿没有理解模型，只找到这些相关记忆：", sources = sources)
                else -> reply("记忆里还没有和这个相关的内容。多同步几场录音再问我。")
            }
        } catch (e: Exception) {
            reply("没连上大脑：${e.message ?: "网络错误"}")
        } finally {
            _thinking.value = false
        }
    }

    private fun reply(text: String, taskId: String? = null, sources: List<String> = emptyList()) {
        _messages.value = _messages.value + ChatMessageUi(fromUser = false, text = text, taskId = taskId, sources = sources)
    }

    private fun looksImperative(text: String): Boolean =
        IMPERATIVE_MARKERS.any { text.startsWith(it) || text.contains("帮我") }

    private companion object {
        val IMPERATIVE_MARKERS = listOf("帮我", "给我", "去做", "安排", "起草", "整理一下")
    }
}

private val ChatChips = listOf("今天说了什么要紧事？", "帮我整理今天的待办", "上次答应别人的事")

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTasks: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val thinking by viewModel.thinking.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, thinking) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + if (thinking) 1 else 0)
    }

    fun submit() {
        if (draft.isBlank()) return
        viewModel.send(draft.trim())
        draft = ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackCircle(onClick = onBack)
            AiOrb(onClick = {}, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("小谛", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("全局 · 记忆图谱", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
            Text(
                "任务 ›",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.inkPanel)
                    .clickable(onClick = onOpenTasks)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        HorizontalDivider(color = colors.paperSunken)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth(0.82f)
                            .wrapContentWidth(if (message.fromUser) Alignment.End else Alignment.Start)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (message.fromUser) colors.inkPanel else Color.White)
                            .border(1.dp, if (message.fromUser) colors.inkPanel else colors.paperSunken, RoundedCornerShape(16.dp))
                            .padding(14.dp, 10.dp),
                    ) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = if (message.fromUser) colors.onInkPanel else colors.inkPanel,
                        )
                        message.sources.forEach { src ->
                            Text(
                                "↩ $src",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.railTranscript,
                                maxLines = 2,
                            )
                        }
                        message.taskId?.let { id ->
                            Text(
                                "去确认这条任务 ›",
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.railAction.copy(alpha = 0.10f))
                                    .clickable { onOpenTask(id) }
                                    .padding(10.dp, 8.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = colors.railAction,
                            )
                        }
                    }
                }
            }
        }

        if (thinking) {
            Row(Modifier.padding(14.dp, 0.dp, 14.dp, 8.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, colors.paperSunken, RoundedCornerShape(16.dp)).padding(16.dp, 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(colors.railTranscript.copy(alpha = 0.4f + 0.3f * it))) } }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(14.dp, 8.dp, 14.dp, 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChatChips.forEach { chip ->
                Text(
                    chip,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White)
                        .border(1.dp, colors.paperSunken, RoundedCornerShape(percent = 50))
                        .clickable { viewModel.send(chip) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.railTranscript,
                    maxLines = 1,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, colors.paperSunken, CircleShape)
                    .clickable { Toast.makeText(context, "语音提问还没接上，先用录音卡短按记一句", Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "语音", tint = colors.inkPanel, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White)
                    .border(1.dp, colors.paperSunken, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text("问小谛，或说「帮我…」直接派活", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPanel),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    cursorBrush = SolidColor(colors.railTranscript),
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.railTranscript)
                    .clickable { submit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(20.dp))
            }
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
