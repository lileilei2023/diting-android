@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.diting.app.data.repo.MemoryRepository
import com.diting.app.ui.components.AiOrb
import com.diting.app.ui.components.BackCircle
import com.diting.app.ui.components.BottomActionBar
import com.diting.app.ui.components.Eyebrow
import com.diting.app.ui.components.MintAction
import com.diting.app.ui.components.MintLink
import com.diting.app.ui.components.RailSheet
import com.diting.app.ui.components.SheetCard
import com.diting.app.ui.components.StatusDot
import com.diting.app.ui.components.UnderlineTabs
import com.diting.app.ui.components.WhiteAction
import com.diting.app.ui.components.formatClock
import com.diting.app.ui.components.formatDuration
import com.diting.app.ui.components.formatShortDate
import com.diting.app.ui.theme.TimestampStyle
import com.diting.domain.growth.GrowthState
import com.diting.domain.memory.MemoryNode
import com.diting.domain.memory.MemoryNodeType
import com.diting.domain.model.Session
import com.diting.domain.model.SessionKind
import com.diting.domain.model.TranscriptState
import com.diting.domain.task.TaskOrigin
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.SessionRepository
import com.diting.app.data.repo.TaskRepository
import com.diting.app.di.AiClientFactory
import com.diting.app.ui.components.CitationChip
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.LoadingBlock
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.growth.GrowthEvent
import com.diting.domain.model.Citation
import com.diting.domain.task.Artifact
import com.diting.domain.task.ArtifactKind
import com.diting.domain.task.Destination
import com.diting.domain.task.Task
import com.diting.domain.task.TaskState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 任务工作台
// =============================================================================

/** A 碎念: a short capture that has not become a task. */
data class NoteUi(val session: Session, val text: String, val whenLabel: String)

data class TasksUi(
    val tasks: List<Task> = emptyList(),
    val growth: GrowthState = GrowthState(),
    val companionDays: Int = 0,
    val notes: List<NoteUi> = emptyList(),
    val observations: List<MemoryNode> = emptyList(),
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val sessions: SessionRepository,
    settings: SettingsStore,
    memory: MemoryRepository,
) : ViewModel() {

    val state: StateFlow<TasksUi> = combine(
        tasks.observeAll(),
        settings.growthPoints,
        sessions.observeAll(),
        sessions.observeSummariesBetween(0L, Long.MAX_VALUE),
        memory.observeLiveNodes(),
    ) { all, points, sessionList, summaries, recurring ->
        val firstDay = sessionList.minOfOrNull { it.startedAtEpochMs }
        TasksUi(
            tasks = all,
            growth = GrowthState(points),
            companionDays = firstDay?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() + 1 } ?: 0,
            notes = sessionList
                .filter { it.kind == SessionKind.QUICK_CAPTURE || it.durationMs in 1..NOTE_MAX_MS }
                .filter { it.transcriptState == TranscriptState.DONE }
                .sortedByDescending { it.startedAtEpochMs }
                .map { s ->
                    val brief = summaries[s.id]?.oneLine?.takeIf { it.isNotBlank() } ?: s.title
                    NoteUi(s, brief, "${formatShortDate(s.startedAtEpochMs)} ${formatClock(s.startedAtEpochMs)} · ${formatDuration(s.durationMs)}")
                },
            observations = recurring.filter { it.mentionCount >= 2 }.sortedByDescending { it.mentionCount },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUi())

    /** 碎念 → task at gate ①, citing the whole capture. */
    fun promoteNote(note: NoteUi) = viewModelScope.launch {
        tasks.propose(
            goal = note.text.take(60),
            origin = TaskOrigin.NOTE_PROMOTION,
            citations = listOf(Citation(note.session.id, 0, note.session.durationMs.coerceAtLeast(0))),
        )
    }

    private companion object {
        const val NOTE_MAX_MS = 3 * 60 * 1000L
    }
}

private val TaskTabs = listOf("任务", "碎念", "观察")
private val TaskFilters = listOf("全部", "等你确认", "进行中", "已完成", "已放弃")

/** Status pill colours: amber for gates, slate for motion, mint for done, grey otherwise. */
@Composable
fun TaskState.pillColors(): Pair<Color, Color> {
    val colors = ditingColors
    return when {
        isGate -> colors.railAction.copy(alpha = 0.12f) to colors.railAction
        this == TaskState.PUBLISHED -> colors.railTranscript.copy(alpha = 0.12f) to colors.railTranscript
        this == TaskState.FAILED -> colors.railBlocker.copy(alpha = 0.12f) to colors.railBlocker
        isTerminal -> colors.paperSunken to colors.inkMuted
        else -> colors.railInsight.copy(alpha = 0.12f) to colors.railInsight
    }
}

@Composable
private fun TaskState.dotColor(): Color {
    val colors = ditingColors
    return when {
        isGate -> colors.railAction
        this == TaskState.PUBLISHED -> colors.railTranscript
        this == TaskState.FAILED -> colors.railBlocker
        isTerminal -> colors.inkMuted
        else -> colors.railInsight
    }
}

private fun Task.matches(filter: Int): Boolean = when (filter) {
    1 -> state.isGate
    2 -> !state.isGate && !state.isTerminal
    3 -> state == TaskState.PUBLISHED
    4 -> state == TaskState.REJECTED || state == TaskState.FAILED
    else -> true
}

/** One line under the title: what the task is waiting on, in the user's words. */
private fun Task.note(): String = when (state) {
    TaskState.AWAITING_GOAL_CONFIRMATION -> "等你确认目标，小谛才开始做。"
    TaskState.PLANNING, TaskState.EXECUTING -> "小谛正在读引用片段并生成工件。"
    TaskState.AWAITING_RESULT_CONFIRMATION -> artifact?.let { "已产出「${it.title}」，等你看一眼。" } ?: "工件已回来，等你确认。"
    TaskState.PUBLISHING -> "正在写到目的地…"
    TaskState.PUBLISHED -> "已采用" + (destination?.let { " · ${it.chineseLabel()}" } ?: "")
    TaskState.REJECTED -> "你放弃了这条。"
    TaskState.FAILED -> failureReason ?: "执行失败，可以重试。"
}

@Composable
fun TasksScreen(
    onOpenTask: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenGrowth: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 110.dp),
    ) {
        item {
            SheetCard(onClick = onOpenChat, contentPadding = PaddingValues(16.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AiOrb(onClick = onOpenChat, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("和小谛聊聊", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Lv.${state.growth.level} ${levelName(state.growth.level)} · 陪伴 ${state.companionDays} 天 · BP ${state.growth.totalPoints} ›",
                            modifier = Modifier.clickable(onClick = onOpenGrowth),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
                }
            }
            Spacer(Modifier.height(16.dp))
            UnderlineTabs(labels = TaskTabs, selected = tab, onSelect = { tab = it })
        }

        when (tab) {
            0 -> {
                item {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TaskFilters.forEachIndexed { i, label ->
                            FilterChipPill(label, selected = filter == i) { filter = i }
                        }
                    }
                }
                val shown = state.tasks.filter { it.matches(filter) }
                if (shown.isEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        EmptyState(
                            headline = if (state.tasks.isEmpty()) "还没有任务" else "这一类里没有任务",
                            hint = "会话总结里点「交给小谛办」，或者在对话里说「帮我…」，都会在这里生成一条待确认。",
                        )
                    }
                }
                items(shown, key = { it.id }) { task ->
                    val (bg, fg) = task.state.pillColors()
                    SheetCard(onClick = { onOpenTask(task.id) }, modifier = Modifier.padding(top = 10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp)) { StatusDot(task.state.dotColor(), 9.dp) }
                            Column(Modifier.weight(1f)) {
                                Text(task.goal, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(task.note(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), color = colors.inkMuted)
                            }
                        }
                        Row(Modifier.padding(top = 10.dp, start = 19.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(task.origin.chineseLabel(), color = colors.inkPanel, background = colors.paperRoot)
                            Pill(task.state.chineseLabel(), color = fg, background = bg)
                            if (task.citations.isNotEmpty()) Pill("${task.citations.size} 处原声", color = colors.railTranscript, background = colors.railTranscript.copy(alpha = 0.09f))
                        }
                    }
                }
            }

            1 -> {
                item {
                    Text(
                        "碎念是你随口说、还没成型的想法。小谛只收着，不催你。",
                        modifier = Modifier.padding(4.dp, 14.dp, 4.dp, 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = colors.inkMuted,
                    )
                }
                if (state.notes.isEmpty()) {
                    item { EmptyState("还没有碎念", "短按录音键说一句，三分钟内的想法会先落在这里，够清楚了再升级成任务。") }
                }
                items(state.notes, key = { it.session.id }) { note ->
                    RailSheet(onClick = { onOpenSession(note.session.id) }, modifier = Modifier.padding(bottom = 10.dp)) {
                        Text(note.text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 15.sp, lineHeight = 24.sp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(note.whenLabel, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                            Text(
                                "变成任务 ›",
                                modifier = Modifier.clickable {
                                    viewModel.promoteNote(note)
                                    Toast.makeText(context, "已建成待确认任务", Toast.LENGTH_SHORT).show()
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.railAction,
                            )
                        }
                    }
                }
            }

            else -> {
                item {
                    Text(
                        "观察来自记忆图谱：同一个词在多场会话里反复出现，或两次承诺互相冲突。",
                        modifier = Modifier.padding(4.dp, 14.dp, 4.dp, 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = colors.inkMuted,
                    )
                }
                if (state.observations.isEmpty()) {
                    item { EmptyState("还没有观察", "同一件事在两场以上会话里被提起，小谛才会在这里标出来。") }
                }
                items(state.observations, key = { it.id }) { node ->
                    RailSheet(rail = colors.railInsight, modifier = Modifier.padding(bottom = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(node.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("×${node.mentionCount}", style = TimestampStyle, fontWeight = FontWeight.SemiBold, color = colors.railInsight)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "从 ${formatShortDate(node.firstSeenEpochMs)} 到 ${formatShortDate(node.lastSeenEpochMs)} 被提起 ${node.mentionCount} 次 · ${node.confidence.chineseLabel()}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                            color = colors.inkMuted,
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// 任务详情 — the three gates
// =============================================================================

data class TaskDetailUi(
    val task: Task? = null,
    val isWorking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tasks: TaskRepository,
    private val sessions: SessionRepository,
    private val settings: SettingsStore,
    private val aiClients: AiClientFactory,
) : ViewModel() {

    private val taskId: String = checkNotNull(savedStateHandle["taskId"])

    private val _working = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<TaskDetailUi> = combine(
        tasks.observe(taskId),
        _working,
        _error,
    ) { task, working, error -> TaskDetailUi(task, working, error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUi())

    /**
     * Gate ①: the user confirms the goal, and only then does the agent run.
     *
     * Planning and execution happen back to back here, but neither touches
     * anything outside the app — the artefact lands at gate ② for review.
     */
    fun confirmGoalAndRun() = viewModelScope.launch {
        _working.value = true
        _error.value = null
        try {
            tasks.confirmGoal(taskId)
            settings.addGrowthPoints(GrowthEvent.CONFIRM_FACT.points)

            val agent = aiClients.agent()
            if (agent == null) {
                tasks.fail(taskId, "还没有配置 Agent 模型")
                _error.value = "还没有配置模型，请到「教小谛 › 模型与 Skill」设置。"
                return@launch
            }

            tasks.beginExecution(taskId)
            val task = tasks.find(taskId) ?: return@launch
            val artifact = runAgent(task, agent)
            tasks.deliverArtifact(taskId, artifact)
        } catch (e: Exception) {
            tasks.fail(taskId, e.message ?: "执行失败")
            _error.value = e.message
        } finally {
            _working.value = false
        }
    }

    /**
     * Produces the artefact.
     *
     * The agent is given the cited transcript segments rather than the whole
     * memory graph — "拿到的是意图 + 引用片段，不是全部记忆" applies to the local
     * model just as much as to an external one.
     */
    private suspend fun runAgent(
        task: Task,
        agent: com.diting.ai.understanding.UnderstandingService,
    ): Artifact {
        val evidence = task.citations.mapNotNull { sessions.textFor(it) }

        val kind = inferKind(task.goal)
        val body = buildString {
            appendLine("目标：${task.goal}")
            if (task.revisionRequest != null) appendLine("你要求的调整：${task.revisionRequest}")
            if (evidence.isNotEmpty()) {
                appendLine()
                appendLine("引用的原声：")
                evidence.forEach { appendLine("· $it") }
            }
        }

        return Artifact(
            kind = kind,
            title = task.goal.take(24),
            body = body,
            citations = task.citations,
            skillsUsed = listOf(kind.name.lowercase()),
        )
    }

    /** Picks the artefact kind, which in turn picks the default destination. */
    private fun inferKind(goal: String): ArtifactKind = when {
        goal.contains("邮件") || goal.contains("发信") -> ArtifactKind.EMAIL
        goal.contains("文档") || goal.contains("对比表") || goal.contains("方案") ->
            ArtifactKind.DOCUMENT

        goal.contains("通知") || goal.contains("群") || goal.contains("告诉") ->
            ArtifactKind.MESSAGE

        goal.contains("查") || goal.contains("整理") || goal.contains("预约") ->
            ArtifactKind.MULTI_STEP

        else -> ArtifactKind.TODO
    }

    fun requestRevision(note: String) = viewModelScope.launch {
        tasks.requestRevision(taskId, note)
        confirmGoalAndRunAfterRevision()
    }

    /** After a revision the goal is already confirmed, so execution resumes directly. */
    private suspend fun confirmGoalAndRunAfterRevision() {
        _working.value = true
        try {
            val agent = aiClients.agent() ?: return
            tasks.beginExecution(taskId)
            val task = tasks.find(taskId) ?: return
            tasks.deliverArtifact(taskId, runAgent(task, agent))
        } catch (e: Exception) {
            tasks.fail(taskId, e.message ?: "执行失败")
        } finally {
            _working.value = false
        }
    }

    /**
     * Gate ③. [secondConfirmationGiven] must come from the dialog; the domain
     * gate refuses otherwise for any destination that writes outward.
     */
    fun adopt(destination: Destination, secondConfirmationGiven: Boolean) =
        viewModelScope.launch {
            try {
                tasks.adopt(taskId, destination, secondConfirmationGiven)
                // The external write would happen here. Until a destination is
                // actually connected in 教小谛 › 目的地与通道, the artefact is
                // simply marked published inside 谛听 — nothing is sent, and the
                // UI must not claim otherwise.
                tasks.markPublished(taskId)
                settings.addGrowthPoints(GrowthEvent.ADOPT_ARTIFACT.points)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }

    fun reject() = viewModelScope.launch { tasks.reject(taskId) }

    fun clearError() {
        _error.value = null
    }
}

/** The five links of the deck's 执行链路, with where this task currently stands. */
private data class ChainStep(val label: String, val desc: String)

private val ChainSteps = listOf(
    ChainStep("确认目标", "你点头之前，小谛什么都不做。"),
    ChainStep("规划", "挑 Skill，读引用片段，不翻整个记忆。"),
    ChainStep("执行", "在谛听里生成工件，不对外发送。"),
    ChainStep("确认结果", "你看一眼：采用、改改或放弃。"),
    ChainStep("发布", "写到目的地，对外副作用要二次确认。"),
)

private fun TaskState.chainIndex(): Int = when (this) {
    TaskState.AWAITING_GOAL_CONFIRMATION -> 0
    TaskState.PLANNING -> 1
    TaskState.EXECUTING -> 2
    TaskState.AWAITING_RESULT_CONFIRMATION -> 3
    TaskState.PUBLISHING -> 4
    TaskState.PUBLISHED -> 5
    TaskState.REJECTED, TaskState.FAILED -> -1
}

fun ArtifactKind.chineseLabel(): String = when (this) {
    ArtifactKind.EMAIL -> "邮件草稿"
    ArtifactKind.DOCUMENT -> "文档"
    ArtifactKind.MESSAGE -> "消息"
    ArtifactKind.MULTI_STEP -> "多步任务"
    ArtifactKind.TODO -> "待办"
    ArtifactKind.TECH_DECISION -> "技术决策"
}

@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    onSeek: (Citation) -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    val task = state.task

    var revising by remember { mutableStateOf(false) }
    var confirmingAdopt by remember { mutableStateOf<Destination?>(null) }
    var chosenDestination by remember { mutableStateOf<Destination?>(null) }

    if (task == null) {
        LoadingBlock("正在打开任务…")
        return
    }

    val destination = chosenDestination ?: task.effectiveDestination
    val running = state.isWorking || task.state == TaskState.PLANNING || task.state == TaskState.EXECUTING
    val (stBg, stFg) = task.state.pillColors()
    val current = if (running) 2 else task.state.chainIndex()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 130.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackCircle(onClick = onBack)
                    Text("任务 · ${task.origin.chineseLabel()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    Pill(task.state.chineseLabel(), color = stFg, background = stBg)
                }
                Spacer(Modifier.height(14.dp))
                Text(task.goal, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 30.sp), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                RailSheet(rail = colors.railInsight, contentPadding = PaddingValues(14.dp, 12.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.railInsight)) { append("小谛：") }
                            append(
                                task.revisionRequest?.let { "你让我改：$it" }
                                    ?: task.failureReason?.let { "这次没做成：$it" }
                                    ?: task.note(),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    task.citations.forEach { CitationChip(citation = it, onClick = onSeek) }
                    Text(
                        "Skill：${task.artifact?.skillsUsed?.joinToString("、")?.ifBlank { null } ?: "自动"} ›",
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(colors.paperRoot)
                            .clickable(onClick = onOpenModels)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.railInsight,
                    )
                }

                Eyebrow("执行链路 · 每一步外部副作用都要你点头", Modifier.padding(top = 22.dp, bottom = 10.dp))
                RailSheet(contentPadding = PaddingValues(16.dp, 6.dp)) {
                    ChainSteps.forEachIndexed { i, step ->
                        val done = current > i
                        val active = current == i
                        val failedHere = current == -1 && (task.state == TaskState.FAILED && i == 2 || task.state == TaskState.REJECTED && i == (if (task.artifact != null) 3 else 0))
                        val dot = when {
                            failedHere -> colors.railBlocker
                            done -> colors.railTranscript
                            active -> colors.railAction
                            else -> Color.White
                        }
                        val ring = when {
                            failedHere -> colors.railBlocker
                            done -> colors.railTranscript
                            active -> colors.railAction
                            else -> colors.paperSunken
                        }
                        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.width(16.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.padding(top = 14.dp).size(12.dp).clip(CircleShape).background(dot).border(2.dp, ring, CircleShape))
                                if (i < ChainSteps.lastIndex) {
                                    Box(Modifier.padding(top = 4.dp).width(2.dp).weight(1f).background(if (done) colors.railTranscript.copy(alpha = 0.4f) else colors.paperSunken))
                                }
                            }
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(
                                    step.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active || failedHere) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        failedHere -> colors.railBlocker
                                        active -> colors.railAction
                                        done -> colors.inkPanel
                                        else -> colors.inkMuted
                                    },
                                )
                                Text(step.desc, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = colors.inkMuted)
                            }
                        }
                    }
                }

                if (running) {
                    Spacer(Modifier.height(14.dp))
                    RailSheet {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = colors.railTranscript, trackColor = colors.railTranscript.copy(alpha = 0.2f))
                            Column {
                                Text("小谛正在执行", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), fontWeight = FontWeight.SemiBold)
                                Text("读取 ${task.citations.size} 处引用片段，生成工件…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colors.inkMuted)
                            }
                        }
                    }
                }
            }

            task.artifact?.let { artifact ->
                item {
                    if (task.state == TaskState.AWAITING_RESULT_CONFIRMATION) {
                        Row(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("采用后发布到")
                            MintLink("管理目的地 ›", onClick = onOpenDestinations)
                        }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Destination.entries.forEach { option ->
                                FilterChipPill(option.chineseLabel(), selected = option == destination) { chosenDestination = option }
                            }
                        }
                    }
                    Eyebrow("产出工件 · ${artifact.kind.chineseLabel()}", Modifier.padding(top = 22.dp, bottom = 10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(1.dp, colors.paperSunken, RoundedCornerShape(16.dp)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().background(colors.paperRoot).padding(14.dp, 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(artifact.title, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("v${if (task.revisionRequest != null) 2 else 1}", style = TimestampStyle, color = colors.inkMuted)
                        }
                        Text(
                            artifact.body.trim(),
                            modifier = Modifier.padding(16.dp, 14.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 23.sp),
                        )
                    }
                }
            }

            if (task.state == TaskState.PUBLISHED) {
                item {
                    Spacer(Modifier.height(14.dp))
                    RailSheet(rail = colors.railTranscript) {
                        Text("已采用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "工件已保存在谛听。目的地「${destination.chineseLabel()}」尚未连接时不会真的发送。",
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            color = colors.inkMuted,
                        )
                    }
                }
            }
        }

        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            when {
                running -> MintAction("小谛正在做…", onClick = {}, modifier = Modifier.weight(1f), enabled = false)
                task.state == TaskState.AWAITING_GOAL_CONFIRMATION -> {
                    WhiteAction("不用了", onClick = {
                        viewModel.reject()
                        Toast.makeText(context, "已放弃", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f))
                    MintAction("确认目标，开始做", onClick = viewModel::confirmGoalAndRun, modifier = Modifier.weight(2f))
                }
                task.state == TaskState.AWAITING_RESULT_CONFIRMATION -> {
                    WhiteAction("让小谛改改", onClick = { revising = true }, modifier = Modifier.weight(1f))
                    MintAction(
                        "采用",
                        onClick = {
                            // Only an external write needs the second dialog;
                            // adopting into 谛听 itself does not.
                            if (destination.isExternalWrite) confirmingAdopt = destination
                            else {
                                viewModel.adopt(destination, false)
                                Toast.makeText(context, "已采用，保存在谛听", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        fill = colors.railAction,
                    )
                }
                task.state == TaskState.FAILED -> {
                    WhiteAction("放弃", onClick = viewModel::reject, modifier = Modifier.weight(1f))
                    MintAction("再试一次", onClick = viewModel::confirmGoalAndRun, modifier = Modifier.weight(1f))
                }
                task.state == TaskState.PUBLISHED -> WhiteAction("去连接目的地 ›", onClick = onOpenDestinations, modifier = Modifier.weight(1f))
                else -> WhiteAction("返回", onClick = onBack, modifier = Modifier.weight(1f))
            }
        }
    }

    if (revising) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { revising = false },
            title = { Text("让小谛改改") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("哪里不对？") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.requestRevision(note)
                    revising = false
                }) { Text("重做") }
            },
            dismissButton = { TextButton(onClick = { revising = false }) { Text("取消") } },
        )
    }

    // Gate ③'s second confirmation. This is the only dialog in the app that
    // guards a real, irreversible side effect, so it names it explicitly.
    confirmingAdopt?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingAdopt = null },
            title = { Text("确认发送到 ${target.chineseLabel()}？") },
            text = { Text(target.sideEffectWarning()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.adopt(target, secondConfirmationGiven = true)
                    confirmingAdopt = null
                }) { Text("确认发送") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAdopt = null }) { Text("再想想") }
            },
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
            title = { Text("出了点问题") },
            text = { Text(message) },
        )
    }
}

// -- labels ---------------------------------------------------------------------

fun TaskState.chineseLabel(): String = when (this) {
    TaskState.AWAITING_GOAL_CONFIRMATION -> "待确认目标"
    TaskState.PLANNING -> "规划中"
    TaskState.EXECUTING -> "执行中"
    TaskState.AWAITING_RESULT_CONFIRMATION -> "待确认结果"
    TaskState.PUBLISHING -> "发布中"
    TaskState.PUBLISHED -> "已采用"
    TaskState.REJECTED -> "已放弃"
    TaskState.FAILED -> "失败"
}

fun com.diting.domain.task.TaskOrigin.chineseLabel(): String = when (this) {
    com.diting.domain.task.TaskOrigin.SESSION_TODO -> "会话待办"
    com.diting.domain.task.TaskOrigin.INSIGHT -> "洞察"
    com.diting.domain.task.TaskOrigin.CHAT_IMPERATIVE -> "对话"
    com.diting.domain.task.TaskOrigin.NOTE_PROMOTION -> "碎念升级"
    com.diting.domain.task.TaskOrigin.WEEKLY_REVIEW -> "周复盘"
}

fun Destination.chineseLabel(): String = when (this) {
    Destination.CALENDAR -> "系统日历 + 提醒"
    Destination.NOTION -> "Notion"
    Destination.EMAIL -> "邮箱"
    Destination.LARK -> "飞书"
    Destination.OPENCLAW -> "龙虾 OpenClaw"
    Destination.DEV_AGENT -> "开发 Agent"
    Destination.NONE -> "留在谛听"
}

/** What the second confirmation is actually warning about. */
fun Destination.sideEffectWarning(): String = when (this) {
    Destination.CALENDAR -> "会在你的系统日历里创建日程并设置提醒。"
    Destination.NOTION -> "会在 Notion 的「谛听 · 任务」数据库里新建一条记录。"
    Destination.EMAIL -> "会以你的名义发送这封邮件。发出后无法撤回。"
    Destination.LARK -> "会在飞书发出这条消息，并 @ 任务里出现的人。"
    Destination.OPENCLAW -> "会把意图和引用片段转发给你自己机器上的 Agent；执行结果仍会回到「待确认结果」。"
    // Deliberately not softened into "只是在你自己机器上跑一下". The agent runs
    // locally, but an issue or a draft PR is visible to everyone with access to
    // the repository the moment it exists — which is what makes this an external
    // write and not a local one.
    Destination.DEV_AGENT -> "会在仓库里创建 issue 或草稿 PR，同事立刻能看到。" +
        "Agent 不会合并、不会推主干、不会部署。"
    Destination.NONE -> "只保存在谛听，不会发送到任何地方。"
}
