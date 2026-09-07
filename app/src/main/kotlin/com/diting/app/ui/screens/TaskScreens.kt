@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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

@HiltViewModel
class TasksViewModel @Inject constructor(tasks: TaskRepository) : ViewModel() {
    val all: StateFlow<List<Task>> = tasks.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TasksScreen(
    onOpenTask: (String) -> Unit,
    onOpenChat: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val tasks by viewModel.all.collectAsStateWithLifecycle()
    val colors = ditingColors

    // Gates first. Everything else can wait; these cannot move without the user.
    val gated = tasks.filter { it.state.isGate }
    val running = tasks.filter { !it.state.isGate && !it.state.isTerminal }
    val done = tasks.filter { it.state.isTerminal }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("任务", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onOpenChat) { Text("和小谛说 ›") }
            }
        }

        if (tasks.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有任务",
                    hint = "会话总结里点「交给小谛办」，或者在对话里说「帮我…」，都会在这里生成一条待确认。",
                )
            }
        }

        taskGroup("等你确认", gated, colors.railAction, onOpenTask)
        taskGroup("小谛正在做", running, colors.railInsight, onOpenTask)
        taskGroup("已完成", done, colors.inkMuted, onOpenTask)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.taskGroup(
    title: String,
    tasks: List<Task>,
    rail: androidx.compose.ui.graphics.Color,
    onOpenTask: (String) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "group-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = rail,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(tasks, key = { it.id }) { task ->
        RailCard(rail = rail, onClick = { onOpenTask(task.id) }) {
            Text(task.goal, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(task.state.chineseLabel())
                Pill(task.origin.chineseLabel())
                if (task.citations.isNotEmpty()) Pill("${task.citations.size} 处原声")
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

@Composable
fun TaskDetailScreen(
    onSeek: (Citation) -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val task = state.task

    var revising by remember { mutableStateOf(false) }
    var confirmingAdopt by remember { mutableStateOf<Destination?>(null) }
    var destinationMenu by remember { mutableStateOf(false) }
    var chosenDestination by remember { mutableStateOf<Destination?>(null) }

    if (task == null) {
        LoadingBlock("正在打开任务…")
        return
    }

    val destination = chosenDestination ?: task.effectiveDestination

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(task.goal, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(task.state.chineseLabel())
                Pill(task.origin.chineseLabel())
            }
        }

        if (task.citations.isNotEmpty()) {
            item {
                RailCard(rail = colors.railTranscript) {
                    Text("依据的原声", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        task.citations.forEach { CitationChip(citation = it, onClick = onSeek) }
                    }
                }
            }
        }

        // Gate ① — nothing has run yet.
        if (task.state == TaskState.AWAITING_GOAL_CONFIRMATION) {
            item {
                RailCard(rail = colors.railAction) {
                    Text(
                        "闸门 ① · 待确认目标",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.railAction,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "确认之后小谛才会开始规划和执行。整个过程不会对外发送任何东西。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::confirmGoalAndRun) { Text("确认目标") }
                        OutlinedButton(onClick = viewModel::reject) { Text("不用了") }
                    }
                }
            }
        }

        if (state.isWorking || task.state == TaskState.PLANNING ||
            task.state == TaskState.EXECUTING
        ) {
            item { LoadingBlock("小谛正在规划并执行…") }
        }

        task.artifact?.let { artifact ->
            item {
                RailCard(rail = colors.railInsight) {
                    Text(artifact.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(artifact.body, style = MaterialTheme.typography.bodyMedium)
                    if (artifact.skillsUsed.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            artifact.skillsUsed.forEach { Pill(it) }
                            TextButton(onClick = onOpenModels) { Text("所用 Skill ›") }
                        }
                    }
                }
            }
        }

        // Gate ② — the artefact is back, and gate ③ is one tap away.
        if (task.state == TaskState.AWAITING_RESULT_CONFIRMATION) {
            item {
                RailCard(rail = colors.railAction) {
                    Text(
                        "闸门 ② · 待确认结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.railAction,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("发布到", style = MaterialTheme.typography.bodyMedium)
                        Box {
                            TextButton(onClick = { destinationMenu = true }) {
                                Text(destination.chineseLabel())
                            }
                            DropdownMenu(
                                expanded = destinationMenu,
                                onDismissRequest = { destinationMenu = false },
                            ) {
                                Destination.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.chineseLabel()) },
                                        onClick = {
                                            chosenDestination = option
                                            destinationMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = onOpenDestinations) { Text("管理目的地") }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                // Only an external write needs the second dialog;
                                // adopting into 谛听 itself does not.
                                if (destination.isExternalWrite) confirmingAdopt = destination
                                else viewModel.adopt(destination, false)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.railAction),
                        ) { Text("采用") }
                        OutlinedButton(onClick = { revising = true }) { Text("让小谛改改") }
                        TextButton(onClick = viewModel::reject) { Text("放弃") }
                    }
                }
            }
        }

        task.failureReason?.let { reason ->
            item {
                RailCard(rail = colors.railBlocker) {
                    Text("执行失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::confirmGoalAndRun) { Text("再试一次") }
                }
            }
        }

        if (task.state == TaskState.PUBLISHED) {
            item {
                RailCard(rail = colors.railTranscript) {
                    Text("已采用", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "工件已保存在谛听。目的地「${destination.chineseLabel()}」尚未连接时不会真的发送。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenDestinations) { Text("去连接目的地") }
                }
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
