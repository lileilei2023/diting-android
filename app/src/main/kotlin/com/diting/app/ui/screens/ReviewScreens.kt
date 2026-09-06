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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.data.db.InsightEntity
import com.diting.app.data.repo.MemoryRepository
import com.diting.app.data.repo.SessionRepository
import com.diting.app.data.repo.StoredSummary
import com.diting.app.data.repo.TaskRepository
import com.diting.app.data.repo.normalizeIntent
import com.diting.app.ui.components.CitationChip
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.nav.ReviewPeriod
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.memory.MemoryNode
import com.diting.domain.memory.MemoryNodeType
import com.diting.domain.memory.NodeConfidence
import com.diting.domain.model.Citation
import com.diting.domain.task.Task
import com.diting.domain.task.TaskOrigin
import com.diting.domain.task.TaskState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private val ReviewJson = Json { ignoreUnknownKeys = true }

// =============================================================================
// 洞察三视图
// =============================================================================

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val memory: MemoryRepository,
    private val tasks: TaskRepository,
) : ViewModel() {

    val insights: StateFlow<List<InsightEntity>> = memory.observeInsights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recurring asks are the graph view that actually earns a card. */
    val recurring: StateFlow<List<MemoryNode>> =
        memory.observeByType(MemoryNodeType.RECURRING_ASK)
            .map { nodes -> nodes.sortedByDescending { it.mentionCount } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val commitments: StateFlow<List<MemoryNode>> =
        memory.observeByType(MemoryNodeType.COMMITMENT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 确认 writes the node into the graph as fact. */
    fun confirm(id: String) = viewModelScope.launch { memory.confirmInsight(id) }

    /** 存疑 keeps it but marks it falsifiable, so it is never asserted back. */
    fun dispute(id: String) = viewModelScope.launch { memory.disputeInsight(id) }

    fun promoteToTask(insight: InsightEntity) = viewModelScope.launch {
        val citations = runCatching {
            ReviewJson.decodeFromString(
                ListSerializer(Citation.serializer()),
                insight.citationsJson,
            )
        }.getOrDefault(emptyList())
        tasks.propose(insight.title, TaskOrigin.INSIGHT, citations)
    }
}

@Composable
fun InsightsScreen(
    onSeek: (Citation) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenReport: () -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val recurring by viewModel.recurring.collectAsStateWithLifecycle()
    val commitments by viewModel.commitments.collectAsStateWithLifecycle()
    val colors = ditingColors

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
                Text("洞察", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onOpenReport) { Text("生成报告 ›") }
            }
        }

        if (insights.isEmpty() && recurring.isEmpty() && commitments.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有洞察",
                    hint = "洞察来自跨会话的重复：同一件事被提起两次以上，小谛才会拿出来说。",
                )
            }
        }

        if (recurring.isNotEmpty()) {
            item {
                Text(
                    "反复被提",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railInsight,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(recurring, key = { it.id }) { node ->
                RailCard(rail = colors.railInsight) {
                    Text(node.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("被提 ${node.mentionCount} 次")
                        Pill(node.confidence.chineseLabel())
                    }
                }
            }
        }

        if (commitments.isNotEmpty()) {
            item {
                Text(
                    "承诺",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railAction,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(commitments, key = { it.id }) { node ->
                RailCard(rail = colors.railAction) {
                    Text(node.label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Pill(node.confidence.chineseLabel())
                }
            }
        }

        items(insights, key = { it.id }) { insight ->
            RailCard(rail = colors.railInsight) {
                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(insight.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Confirm writes it into the graph; 存疑 keeps it falsifiable.
                    // The AI's guess does not silently become a fact either way.
                    Button(onClick = { viewModel.confirm(insight.id) }) { Text("确认") }
                    OutlinedButton(onClick = { viewModel.dispute(insight.id) }) { Text("存疑") }
                    TextButton(onClick = { viewModel.promoteToTask(insight) }) {
                        Text("建成任务")
                    }
                }
            }
        }
    }
}

fun NodeConfidence.chineseLabel(): String = when (this) {
    NodeConfidence.PROPOSED -> "待确认"
    NodeConfidence.CONFIRMED -> "已确认"
    NodeConfidence.DISPUTED -> "存疑"
}

// =============================================================================
// 周 / 月复盘
// =============================================================================

data class ReviewUiState(
    val period: ReviewPeriod = ReviewPeriod.WEEK,
    val sessionCount: Int = 0,
    val promises: List<PromiseRow> = emptyList(),
    val topics: List<MemoryNode> = emptyList(),
    val decisions: List<String> = emptyList(),
    val forgettable: Int = 0,
)

data class PromiseRow(val text: String, val owner: String?, val kept: Boolean, val citation: Citation?)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val memory: MemoryRepository,
    private val tasks: TaskRepository,
) : ViewModel() {

    private val period = ReviewPeriod.parse(savedStateHandle["period"])

    private val from: Long = when (period) {
        ReviewPeriod.WEEK -> LocalDate.now().minusDays(7)
        ReviewPeriod.MONTH -> LocalDate.now().minusMonths(1)
    }.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val to: Long = System.currentTimeMillis()

    val state: StateFlow<ReviewUiState> = combine(
        sessions.observeBetween(from, to),
        sessions.observeSummariesBetween(from, to),
        memory.observeByType(MemoryNodeType.TOPIC),
        memory.observeArchivedCount(),
        tasks.observeAll(),
    ) { rangeSessions, summaries, topics, archived, allTasks ->
        ReviewUiState(
            period = period,
            sessionCount = rangeSessions.size,
            promises = promisesFrom(summaries, allTasks),
            topics = topics.sortedByDescending { it.mentionCount },
            decisions = summaries.values.flatMap { it.decisions.map { line -> line.text } },
            forgettable = archived,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState(period))

    /**
     * The commitment ledger — the core of 周 · 对表.
     *
     * A promise counts as kept when a task covering it reached PUBLISHED. Matching
     * uses [normalizeIntent], the same rule the task de-duplicator used when the
     * task was created; any other rule would let the two disagree about whether
     * a promise and a task are the same thing.
     *
     * Without this link the table would just re-list to-dos, which is 日 · 整理's
     * job — the week is supposed to compare said against done.
     */
    private fun promisesFrom(
        summaries: Map<String, StoredSummary>,
        allTasks: List<Task>,
    ): List<PromiseRow> {
        val closed = allTasks
            .filter { it.state == TaskState.PUBLISHED }
            .map { normalizeIntent(it.goal) }
            .toSet()

        return summaries.values.flatMap { summary ->
            summary.todos.map { todo ->
                PromiseRow(
                    text = todo.text,
                    owner = todo.owner,
                    kept = closed.contains(normalizeIntent(todo.text)),
                    citation = todo.citation,
                )
            }
        }
    }

    fun createTaskFromSuggestion(text: String) = viewModelScope.launch {
        tasks.propose(text, TaskOrigin.WEEKLY_REVIEW)
    }
}

@Composable
fun ReviewScreen(
    onSeek: (Citation) -> Unit,
    onOpenMemory: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val isWeek = state.period == ReviewPeriod.WEEK

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                if (isWeek) "周 · 对表" else "月 · 沉淀",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // The granularity is a different action, not a bigger report.
                if (isWeek) "说过的 vs 做了的。粒度越大，小谛越少转述、越多对表。"
                else "该留下什么、该忘掉什么。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            InkPanel {
                Text(
                    "这${if (isWeek) "周" else "个月"}共 ${state.sessionCount} 场会话",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onInkPanel,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "答应了 ${state.promises.size} 件事，兑现了 ${state.promises.count { it.kept }} 件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accentOnPanel,
                )
            }
        }

        if (isWeek) {
            item {
                Text(
                    "承诺兑现表",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railAction,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.promises.isEmpty()) {
                item { EmptyState("这周没有承诺被记录", "转写完成并生成总结之后，承诺会自动进这张表。") }
            }
            items(state.promises) { promise ->
                RailCard(rail = if (promise.kept) colors.railTranscript else colors.railAction) {
                    Text(promise.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        promise.owner?.let { Pill(it) }
                        Pill(if (promise.kept) "已兑现" else "未兑现")
                        promise.citation?.let { CitationChip(citation = it, onClick = onSeek) }
                        TextButton(onClick = { viewModel.createTaskFromSuggestion(promise.text) }) {
                            Text("建成任务")
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    "可以沉淀成文档的主题",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railInsight,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.topics, key = { it.id }) { topic ->
                RailCard(rail = colors.railInsight) {
                    Text(topic.label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("被提 ${topic.mentionCount} 次")
                        TextButton(
                            onClick = {
                                viewModel.createTaskFromSuggestion("把「${topic.label}」整理成文档")
                            }
                        ) { Text("沉淀成文档") }
                    }
                }
            }

            item {
                RailCard(rail = colors.railBlocker, onClick = onOpenMemory) {
                    Text("遗忘建议", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.forgettable} 个冷节点已归档：还能搜到，但不再主动提醒你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                }
            }
        }

        if (state.decisions.isNotEmpty()) {
            item {
                RailCard(rail = colors.railTranscript) {
                    Text(
                        if (isWeek) "本周决策" else "决策回看",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    state.decisions.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// =============================================================================
// 可视化报告
// =============================================================================

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessions: SessionRepository,
) : ViewModel() {
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    val detail = sessions.observeDetail(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ReportScreen(
    onSeek: (Citation) -> Unit,
    onShare: (String) -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val colors = ditingColors
    val summary = detail?.summary

    if (summary == null) {
        EmptyState("还没有可用的报告", "报告基于会话总结生成，先让小谛整理一次。")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InkPanel {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onInkPanel,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    summary.oneLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.accentOnPanel,
                )
            }
        }

        item {
            RailCard(rail = colors.railTranscript) {
                Text("这份报告由什么支撑", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("${summary.points.size} 条要点")
                    Pill("${summary.decisions.size} 项决策")
                    Pill("${summary.todos.size} 条待办")
                    Pill("${summary.risks.size} 处风险")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    // Generating a report cites everything it draws on, which is
                    // what makes those segments permanent under the retention rule.
                    "报告引用过的片段会永久保留，不再受 30 天 / 1 年的期限影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }

        items(summary.points) { line ->
            RailCard(rail = colors.railInsight) {
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
                line.citation?.let {
                    Spacer(Modifier.height(6.dp))
                    CitationChip(citation = it, onClick = onSeek)
                }
            }
        }

        item {
            Button(
                onClick = { onShare(summary.title) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("分享 / 导出") }
        }
    }
}
