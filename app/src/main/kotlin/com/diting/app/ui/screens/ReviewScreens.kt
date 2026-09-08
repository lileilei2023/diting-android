@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.diting.app.ui.components.Eyebrow
import com.diting.app.ui.components.MonoMeta
import com.diting.app.ui.components.SegmentedPills
import com.diting.app.ui.components.formatClock
import com.diting.app.ui.components.formatDuration
import com.diting.app.ui.theme.TimestampStyle
import androidx.compose.material3.HorizontalDivider
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.diting.app.ui.components.BackCircle
import com.diting.app.ui.components.BottomActionBar
import com.diting.app.ui.components.MintAction
import com.diting.app.ui.components.MintLink
import com.diting.app.ui.components.RailSheet
import com.diting.app.ui.components.StatusDot
import com.diting.app.ui.components.UnderlineTabs
import com.diting.app.ui.components.WhiteAction
import com.diting.app.ui.components.formatShortDate
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    sessions: SessionRepository,
    private val brain: com.diting.app.brain.BrainApi,
    private val brainStore: com.diting.app.brain.BrainStore,
) : ViewModel() {

    val insights: StateFlow<List<InsightEntity>> = memory.observeInsights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Anything mentioned in two or more sessions, whatever its type. */
    val recurring: StateFlow<List<MemoryNode>> =
        memory.observeLiveNodes()
            .map { nodes -> nodes.filter { it.mentionCount >= 2 }.sortedByDescending { it.mentionCount } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val commitments: StateFlow<List<MemoryNode>> =
        memory.observeByType(MemoryNodeType.COMMITMENT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 定位张力: who said what. Built from `主体 · 观点/立场/要求 · 客体` facts across
     * all summaries, grouped by person — the honest version of the deck's sliders.
     */
    val stances: StateFlow<List<Stance>> = sessions.observeSummariesBetween(0L, Long.MAX_VALUE)
        .map { summaries ->
            summaries.flatMap { (sid, sm) ->
                sm.points.mapNotNull { line ->
                    val parts = line.text.split(" · ").map { it.trim() }
                    if (parts.size < 3) return@mapNotNull null
                    if (STANCE_RELATIONS.none { parts[1].contains(it) }) return@mapNotNull null
                    Stance(person = parts[0], relation = parts[1], claim = parts.drop(2).joinToString(" · "), sessionId = sid)
                }
            }.groupBy { it.person }.values.sortedByDescending { it.size }.flatten()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The brain's own proposals awaiting a decision (`GET /pending`). */
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<List<com.diting.app.brain.PendingItem>>(emptyList())
    val pending: StateFlow<List<com.diting.app.brain.PendingItem>> = _pending
    private val _pendingError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingError: StateFlow<String?> = _pendingError

    init { refreshPending() }

    fun refreshPending() = viewModelScope.launch {
        if (!brainStore.current.isLoggedIn) return@launch
        runCatching { brain.pending() }
            .onSuccess { _pending.value = it; _pendingError.value = null }
            .onFailure { _pendingError.value = friendly(it) }
    }

    private fun friendly(e: Throwable): String = when {
        e.message?.contains("Failed to connect") == true || e is java.io.IOException -> "连不上大脑，检查网络后再试"
        else -> e.message ?: "未知错误"
    }

    /** 确认 writes the node into the graph as fact. */
    fun confirm(id: String) = viewModelScope.launch {
        memory.confirmInsight(id)
        id.removePrefix("recurring:").takeIf { it != id }?.let { memory.confirmNode(it) }
    }

    /** 存疑 keeps it but marks it falsifiable, so it is never asserted back. */
    fun dispute(id: String) = viewModelScope.launch {
        memory.disputeInsight(id)
        id.removePrefix("recurring:").takeIf { it != id }?.let { memory.disputeNode(it) }
    }

    /** Approve / reject one of the brain's proposals; the list refreshes after. */
    fun decidePending(id: Long, approve: Boolean) = viewModelScope.launch {
        runCatching { brain.confirm(id, approve) }
            .onSuccess { _pending.value = _pending.value.filterNot { it.id == id } }
            .onFailure { _pendingError.value = friendly(it) }
    }

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

data class Stance(val person: String, val relation: String, val claim: String, val sessionId: String)
private val STANCE_RELATIONS = listOf("观点", "立场", "要求", "态度", "建议", "反对", "支持", "顾虑")

private val InsightTabs = listOf("纪要", "待确认", "定位张力")

/** Citations stored on an insight row, or none when the JSON is unreadable. */
private fun InsightEntity.citations(): List<Citation> = runCatching {
    ReviewJson.decodeFromString(ListSerializer(Citation.serializer()), citationsJson)
}.getOrDefault(emptyList())

@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    onSeek: (Citation) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenReport: () -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val recurring by viewModel.recurring.collectAsStateWithLifecycle()
    val commitments by viewModel.commitments.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    val live = insights.filter { !it.dismissed }
    val sessionIds = live.flatMap { it.citations() }.map { it.sessionId }.distinct()
    val latest = live.maxOfOrNull { it.createdAtEpochMs }
    val title = live.firstOrNull()?.title ?: "洞察"
    val subtitle = buildString {
        append("来自 ${sessionIds.size} 场会话")
        latest?.let { append(" · ${formatShortDate(it)}") }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(14.dp, 12.dp, 14.dp, 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackCircle(onClick = onBack)
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MintLink("事件详情 ›", onClick = {
                            val first = sessionIds.firstOrNull()
                            if (first != null) onOpenSession(first)
                            else Toast.makeText(context, "还没有可回溯的会话", Toast.LENGTH_SHORT).show()
                        })
                        MintLink("生成报告 ›", onClick = onOpenReport)
                    }
                }
                Spacer(Modifier.height(12.dp))
                UnderlineTabs(labels = InsightTabs, selected = tab, onSelect = { tab = it })
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> MinutesTab(live, recurring, onSeek, onOpenTask, viewModel)
                    1 -> ConfirmTab(live, onSeek, viewModel)
                    else -> TensionTab(commitments, viewModel, onOpenSession)
                }
            }
        }

        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            WhiteAction("让小谛改改", onClick = onOpenChat, modifier = Modifier.weight(1f))
            MintAction("发布到团队", onClick = onOpenReport, modifier = Modifier.weight(1f))
        }
    }
}

/** 纪要 — numbered cards, one per insight, plus the recurring asks as their own cards. */
@Composable
private fun MinutesTab(
    insights: List<InsightEntity>,
    recurring: List<MemoryNode>,
    onSeek: (Citation) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: InsightsViewModel,
) {
    val colors = ditingColors
    val context = LocalContext.current

    if (insights.isEmpty() && recurring.isEmpty()) {
        EmptyState(
            headline = "还没有洞察",
            hint = "洞察来自跨会话的重复：同一件事被提起两次以上，小谛才会拿出来说。先同步几场录音。",
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(insights, key = { _, it -> it.id }) { index, insight ->
            val first = insight.citations().firstOrNull()
            NumberedInsightCard(
                n = index + 1,
                title = insight.title,
                body = insight.body,
                citation = first,
                onSeek = onSeek,
                trailing = {
                    Text(
                        "建成任务 ›",
                        modifier = Modifier.clickable {
                            viewModel.promoteToTask(insight)
                            Toast.makeText(context, "已建成任务，去「任务」看进度", Toast.LENGTH_SHORT).show()
                        }.padding(4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.railAction,
                    )
                },
            )
        }
        itemsIndexed(recurring, key = { _, it -> "r-" + it.id }) { index, node ->
            NumberedInsightCard(
                n = insights.size + index + 1,
                title = node.label,
                body = "反复被提 ${node.mentionCount} 次 · ${node.confidence.chineseLabel()}",
                citation = null,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun NumberedInsightCard(
    n: Int,
    title: String,
    body: String,
    citation: Citation?,
    onSeek: (Citation) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ditingColors
    RailSheet(contentPadding = PaddingValues(16.dp, 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "$n",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 22.sp),
                fontWeight = FontWeight.SemiBold,
                color = colors.railTranscript,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp), color = colors.inkMuted)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (citation != null) {
                        CitationChip(citation = citation, onClick = onSeek, label = if (citation.startMs == 0L) "回到原声" else "回到原声 ${citation.timeLabel()}")
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    trailing?.invoke()
                }
            }
        }
    }
}

/** 待确认 — the AI's guesses, each with 确认 / 存疑 and its origin time. */
@Composable
private fun ConfirmTab(
    insights: List<InsightEntity>,
    onSeek: (Citation) -> Unit,
    viewModel: InsightsViewModel,
) {
    val colors = ditingColors
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val pendingError by viewModel.pendingError.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "小谛不确定的事不会写进记忆。你点「确认」它才成为事实；点「存疑」则标注为可证伪。",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = colors.inkMuted,
            )
            pendingError?.let { Text("大脑待办没拉到：$it", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.railBlocker) }
        }
        if (insights.isEmpty() && pending.isEmpty()) {
            item { EmptyState("没有待确认的事", "小谛还没有提出需要你拍板的推断。") }
        }
        items(pending, key = { "p-" + it.id }) { p ->
            RailSheet(rail = colors.railAction) {
                Text(p.title.ifBlank { p.action }, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                val why = listOf(p.reason, p.evidence.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                if (why.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(why, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallChoice("确认", fill = colors.railTranscript.copy(alpha = 0.10f), text = colors.railTranscript, border = colors.railTranscript.copy(alpha = 0.3f)) { viewModel.decidePending(p.id, true) }
                    SmallChoice("驳回", fill = Color.White, text = colors.inkPanel, border = colors.paperSunken) { viewModel.decidePending(p.id, false) }
                    Spacer(Modifier.weight(1f))
                    Text("大脑提议", style = MaterialTheme.typography.labelSmall, color = colors.railAction, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(insights, key = { it.id }) { insight ->
            val bar = when (insight.confidence) {
                NodeConfidence.CONFIRMED -> colors.railTranscript
                NodeConfidence.DISPUTED -> colors.inkMuted
                NodeConfidence.PROPOSED -> colors.railAction
            }
            val first = insight.citations().firstOrNull()
            RailSheet(rail = bar) {
                Text(insight.title, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                if (insight.body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(insight.body, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val confirmed = insight.confidence == NodeConfidence.CONFIRMED
                    val disputed = insight.confidence == NodeConfidence.DISPUTED
                    SmallChoice(
                        if (confirmed) "已确认" else "确认",
                        fill = if (confirmed) colors.railTranscript else colors.railTranscript.copy(alpha = 0.10f),
                        text = if (confirmed) Color.White else colors.railTranscript,
                        border = colors.railTranscript.copy(alpha = if (confirmed) 1f else 0.3f),
                    ) { viewModel.confirm(insight.id) }
                    SmallChoice(
                        if (disputed) "已存疑" else "存疑",
                        fill = if (disputed) colors.paperSunken else Color.White,
                        text = colors.inkPanel,
                        border = colors.paperSunken,
                    ) { viewModel.dispute(insight.id) }
                    Spacer(Modifier.weight(1f))
                    first?.let {
                        Text(
                            "↩ ${it.timeLabel()}",
                            modifier = Modifier.clickable { onSeek(it) }.padding(2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.railTranscript,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallChoice(label: String, fill: Color, text: Color, border: Color, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = text,
    )
}

/**
 * 定位张力 — who stands where. The deck plots each party on a slider; the app
 * has no stance model yet, so this shows the commitments ledger (who promised
 * what) and says plainly what is missing rather than drawing invented dots.
 */
@Composable
private fun TensionTab(commitments: List<MemoryNode>, viewModel: InsightsViewModel, onOpenSession: (String) -> Unit) {
    val colors = ditingColors
    val stances by viewModel.stances.collectAsStateWithLifecycle()
    val palette = listOf(colors.railTranscript, colors.railAction, colors.railInsight, colors.railBlocker, colors.inkMuted)
    val people = stances.map { it.person }.distinct()
    LazyColumn(contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            RailSheet(contentPadding = PaddingValues(16.dp)) {
                Text("定位张力 · 谁站在哪", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (stances.isEmpty()) "小谛还没有从会话里听出明确的观点。有人表达立场、要求或顾虑之后，会按人列在这里。"
                    else "从 ${stances.map { it.sessionId }.distinct().size} 场会话里听出 ${people.size} 个人的 ${stances.size} 条观点。",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = colors.inkMuted,
                )
                if (people.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        people.forEachIndexed { i, name ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                StatusDot(palette[i % palette.size], 8.dp)
                                Text(name, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                            }
                        }
                    }
                }
            }
        }
        items(stances, key = { "${it.sessionId}:${it.person}:${it.claim}" }) { st ->
            val c = palette[people.indexOf(st.person).coerceAtLeast(0) % palette.size]
            RailSheet(rail = c, onClick = { onOpenSession(st.sessionId) }, contentPadding = PaddingValues(16.dp, 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(st.person, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c)
                    Text("· ${st.relation}", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
                Spacer(Modifier.height(3.dp))
                Text(st.claim, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
            }
        }
        if (commitments.isNotEmpty()) {
            item {
                Text("承诺 · 谁说了什么", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.railAction, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(commitments, key = { it.id }) { node ->
                RailSheet(rail = colors.railAction) {
                    Text(node.label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Pill(node.confidence.chineseLabel())
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
    val people: List<MemoryNode> = emptyList(),
    val decisions: List<String> = emptyList(),
    val forgettable: Int = 0,
    val fromEpochMs: Long = 0,
    val toEpochMs: Long = 0,
)

data class PromiseRow(val text: String, val owner: String?, val due: String?, val kept: Boolean, val citation: Citation?)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val memory: MemoryRepository,
    private val tasks: TaskRepository,
) : ViewModel() {

    private val period = ReviewPeriod.parse(savedStateHandle["period"])

    /**
     * Which calendar week (Monday–Sunday) or month is shown. Offset 0 is the
     * period containing the newest recording — not necessarily today, so a
     * quiet fortnight does not open onto an empty ledger. ‹ › move by whole
     * periods, so the header's dates are always a real week or month.
     */
    private val _offset = kotlinx.coroutines.flow.MutableStateFlow(0)
    val offset: StateFlow<Int> = _offset
    fun shift(delta: Int) { _offset.value = (_offset.value + delta).coerceAtMost(0) }

    private val window: kotlinx.coroutines.flow.Flow<Pair<Long, Long>> = combine(sessions.observeAll(), _offset) { all, off ->
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val newest = all.maxOfOrNull { it.startedAtEpochMs } ?: now
        val anchor = java.time.Instant.ofEpochMilli(minOf(now, newest)).atZone(zone).toLocalDate()
        val (start, endExclusive) = when (period) {
            ReviewPeriod.WEEK -> {
                val monday = anchor.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(off.toLong())
                monday to monday.plusWeeks(1)
            }
            ReviewPeriod.MONTH -> {
                val first = anchor.withDayOfMonth(1).plusMonths(off.toLong())
                first to first.plusMonths(1)
            }
        }
        start.atStartOfDay(zone).toInstant().toEpochMilli() to endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
    }.distinctUntilChanged()

    val state: StateFlow<ReviewUiState> = window.flatMapLatest { (from, to) ->
        combine(
        sessions.observeBetween(from, to),
        sessions.observeSummariesBetween(from, to),
        combine(memory.observeByType(MemoryNodeType.TOPIC), memory.observeByType(MemoryNodeType.PERSON)) { t, p -> t to p },
        memory.observeArchivedCount(),
        tasks.observeAll(),
    ) { rangeSessions, summaries, (topics, people), archived, allTasks ->
        ReviewUiState(
            period = period,
            sessionCount = rangeSessions.size,
            promises = promisesFrom(summaries, allTasks),
            topics = topics.sortedByDescending { it.mentionCount },
            people = people.sortedByDescending { it.mentionCount },
            decisions = summaries.values.flatMap { it.decisions.map { line -> line.text } },
            forgettable = archived,
            fromEpochMs = from,
            toEpochMs = to,
        )
    }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState(period))

    /**
     * The commitment ledger — the core of 周 · 对表.
     *
     * A promise counts as kept when a task covering it reached PUBLISHED. Matching
     * uses [normalizeIntent], the same rule the task de-duplicator used when the
     * task was created; any other rule would let the two disagree about whether
     * a promise and a task are the same thing.
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
                    due = todo.due,
                    kept = closed.contains(normalizeIntent(todo.text)),
                    citation = todo.citation,
                )
            }
        }
    }

    fun createTaskFromSuggestion(text: String, citation: Citation? = null) = viewModelScope.launch {
        tasks.propose(text, TaskOrigin.WEEKLY_REVIEW, listOfNotNull(citation))
    }
}

@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onSeek: (Citation) -> Unit,
    onOpenMemory: () -> Unit,
    onSwitchPeriod: (ReviewPeriod) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    val isWeek = state.period == ReviewPeriod.WEEK
    val kept = state.promises.count { it.kept }
    val unkept = state.promises.filter { !it.kept }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 100.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("复盘", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SegmentedPills(
                    options = listOf("周 · 对表", "月 · 沉淀"),
                    selected = if (isWeek) 0 else 1,
                    onSelect = { onSwitchPeriod(if (it == 0) ReviewPeriod.WEEK else ReviewPeriod.MONTH) },
                )
            }
            Spacer(Modifier.height(18.dp))
            val start = java.time.Instant.ofEpochMilli(state.fromEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
            val last = java.time.Instant.ofEpochMilli(state.toEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)
            val offset by viewModel.offset.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(
                    if (isWeek) "第 ${start.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())} 周 · ${formatShortDate(state.fromEpochMs)} – ${formatShortDate(last.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())} · 对表"
                    else "${start.year} 年 ${start.monthValue} 月 · 沉淀",
                    Modifier.weight(1f),
                )
                Text("‹", modifier = Modifier.clickable { viewModel.shift(-1) }.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium, color = colors.railTranscript)
                Text("›", modifier = Modifier.clickable(enabled = offset < 0) { viewModel.shift(1) }.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium, color = if (offset < 0) colors.railTranscript else colors.paperSunken)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    state.sessionCount == 0 -> if (isWeek) "这周没有录音。" else "这个月没有录音。"
                    isWeek && state.promises.isEmpty() -> "这周共 ${state.sessionCount} 场会话，还没有记下承诺。"
                    isWeek -> "这周你答应了 ${state.promises.size} 件事，兑现了 $kept 件。"
                    state.topics.isEmpty() -> "这个月 ${state.sessionCount} 场会话，主题还在聚。"
                    else -> "这个月只有 ${state.topics.size.coerceAtMost(3)} 件事真正重要。"
                },
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isWeek) "周复盘不重述内容，只对表：说过的 vs 做了的。"
                else "月复盘做三件事：把主题聚成文档、回看当时的决策、决定遗忘什么。",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = colors.inkMuted,
            )
        }

        if (isWeek) {
            item {
                Spacer(Modifier.height(16.dp))
                RailSheet(rail = colors.railAction) {
                    Text("承诺兑现表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (state.promises.isEmpty()) {
                        Text("转写完成并生成总结之后，承诺会自动进这张表。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                    state.promises.forEach { p ->
                        HorizontalDivider(color = colors.paperSunken)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(p.citation?.let { c -> Modifier.clickable { onSeek(c) } } ?: Modifier)
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Pill(
                                if (p.kept) "已兑现" else "未兑现",
                                modifier = Modifier.padding(top = 2.dp),
                                color = if (p.kept) colors.railTranscript else colors.railAction,
                                background = (if (p.kept) colors.railTranscript else colors.railAction).copy(alpha = 0.12f),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(p.text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp))
                                Text(
                                    listOfNotNull(p.owner?.takeIf { !it.startsWith("说话人") }?.let { "对 $it" }, p.due).joinToString(" · ").ifEmpty { "没说给谁、什么时候" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkMuted,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                RailSheet(rail = colors.railInsight) {
                    Text("反复被提 · 本周", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val top = state.topics.take(4)
                    if (top.isEmpty()) Text("还没有反复出现的主题。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    val max = top.maxOfOrNull { it.mentionCount }?.coerceAtLeast(1) ?: 1
                    top.forEachIndexed { i, t ->
                        val c = listOf(colors.railTranscript, colors.railAction, colors.railInsight, colors.inkMuted)[i.coerceAtMost(3)]
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t.label, modifier = Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.paperRoot)) {
                                Box(Modifier.fillMaxWidth(t.mentionCount.toFloat() / max).height(8.dp).background(c))
                            }
                            Text("×${t.mentionCount}", modifier = Modifier.width(52.dp), style = TimestampStyle, fontWeight = FontWeight.SemiBold, color = c, textAlign = TextAlign.End)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RailSheet(modifier = Modifier.weight(1f)) {
                        Text("谁出现得最多", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        val people = state.people.take(3)
                        if (people.isEmpty()) Text("还没认出人。", style = MaterialTheme.typography.bodySmall.copy(lineHeight = 24.sp), color = colors.inkMuted)
                        people.forEach { Text("${it.label} · ${it.mentionCount} 次", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 24.sp), color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    RailSheet(modifier = Modifier.weight(1f)) {
                        Text("决策落地", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.railTranscript, fontWeight = FontWeight.SemiBold)) { append("${state.decisions.size}") }
                                append(" 项已记录\n")
                                withStyle(SpanStyle(color = colors.railAction, fontWeight = FontWeight.SemiBold)) { append("$kept") }
                                append(" 项已兑现\n${unkept.size} 项待验证")
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 24.sp),
                            color = colors.inkMuted,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                RailSheet(rail = colors.railAction, fill = colors.railAction.copy(alpha = 0.08f), border = colors.railAction.copy(alpha = 0.2f)) {
                    Text("下周建议 · 点即建任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.railAction)
                    Spacer(Modifier.height(6.dp))
                    if (unkept.isEmpty()) Text("没有欠着的承诺。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    unkept.take(5).forEach { p ->
                        HorizontalDivider(color = colors.railAction.copy(alpha = 0.15f))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.createTaskFromSuggestion(p.text, p.citation)
                                    Toast.makeText(context, "已建成待确认任务", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).border(1.5.dp, colors.railAction, RoundedCornerShape(5.dp)))
                            Text(p.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("建任务 ›", style = MaterialTheme.typography.labelSmall, color = colors.railAction)
                        }
                    }
                }
            }
        } else {
            item { Eyebrow("主题 · 图谱自动聚类", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
            if (state.topics.isEmpty()) {
                item { RailSheet { Text("同一个主题在两场以上会话里出现，小谛才会把它聚起来。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) } }
            }
            items(state.topics, key = { it.id }) { topic ->
                RailSheet(rail = colors.railInsight, modifier = Modifier.padding(bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(topic.label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        MonoMeta("${topic.mentionCount} 次")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "从 ${formatShortDate(topic.firstSeenEpochMs)} 到 ${formatShortDate(topic.lastSeenEpochMs)} · ${topic.confidence.chineseLabel()}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = colors.inkMuted,
                    )
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "沉淀成文档",
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(colors.railTranscript)
                                .clickable {
                                    viewModel.createTaskFromSuggestion("把「${topic.label}」整理成文档")
                                    Toast.makeText(context, "已建成待确认任务", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }

            item {
                Eyebrow("决策回看 · 当时 vs 现在", Modifier.padding(top = 18.dp, bottom = 8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, colors.paperSunken, RoundedCornerShape(16.dp)),
                ) {
                    if (state.decisions.isEmpty()) {
                        Text("这个月还没有记录到决策。", modifier = Modifier.padding(16.dp, 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                    state.decisions.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(color = colors.paperSunken)
                        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Pill("已记录", color = colors.railTranscript, background = colors.railTranscript.copy(alpha = 0.12f))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                RailSheet(rail = colors.railBlocker, onClick = onOpenMemory) {
                    Text("遗忘建议", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.forgettable} 个冷节点已归档：还能搜到，但不再主动提醒你。去「记忆与遗忘」看看 ›",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                        color = colors.inkMuted,
                    )
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

/** The deck's three templates: same content, different skin and framing. */
private data class ReportTemplate(val label: String, val kicker: String, val bg: Color, val fg: Color, val tile: Color)

@Composable
private fun reportTemplates(): List<ReportTemplate> {
    val colors = ditingColors
    return listOf(
        ReportTemplate("会议纪要", "MEETING MINUTES", colors.inkPanel, colors.onInkPanel, Color.White.copy(alpha = 0.08f)),
        ReportTemplate("客户简报", "CLIENT BRIEF", Color(0xFF0E7D70), Color.White, Color.White.copy(alpha = 0.14f)),
        ReportTemplate("周报", "WEEKLY", Color.White, colors.inkPanel, colors.paperRoot),
    )
}

private data class ReportSection(val title: String, val body: String)

@Composable
fun ReportScreen(
    onBack: () -> Unit,
    onSeek: (Citation) -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    val templates = reportTemplates()
    var tpl by remember { mutableIntStateOf(0) }
    val summary = detail?.summary
    val session = detail?.session

    if (summary == null || session == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            EmptyState("还没有可用的报告", "报告基于会话总结生成，先在会话详情里让小谛整理一次。")
        }
        return
    }

    val t = templates[tpl]
    val sections = buildList {
        if (summary.points.isNotEmpty()) add(ReportSection("会议内容", summary.points.joinToString("\n") { "· ${it.text}" }))
        if (summary.decisions.isNotEmpty()) add(ReportSection("决策", summary.decisions.joinToString("\n") { "· ${it.text}" }))
        if (summary.risks.isNotEmpty()) add(ReportSection("风险与分歧", summary.risks.joinToString("\n") { "· ${it.text}" }))
        if (summary.todos.isNotEmpty()) add(ReportSection("待办", summary.todos.joinToString("\n") { "· ${it.text}" + (it.owner?.let { o -> "（$o）" } ?: "") }))
    }
    val conclusion = summary.decisions.firstOrNull()?.text ?: summary.oneLine.trim().substringBefore("\n").take(90)
    val shareText = buildString {
        appendLine("${t.label} · ${summary.title}")
        appendLine("${formatShortDate(session.startedAtEpochMs)} ${formatClock(session.startedAtEpochMs)} · ${formatDuration(session.durationMs)}")
        appendLine()
        appendLine("核心结论：$conclusion")
        sections.forEach { appendLine(); appendLine(it.title); appendLine(it.body) }
        appendLine(); append("—— 由谛听基于会话原声生成")
    }
    val share = {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, summary.title)
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享报告"))
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackCircle(onClick = onBack)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        templates.forEachIndexed { i, item -> FilterChipPill(item.label, selected = i == tpl) { tpl = i } }
                    }
                    MintLink("分享", onClick = share)
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(t.bg)
                        .border(1.dp, colors.paperSunken, RoundedCornerShape(20.dp))
                        .padding(20.dp, 22.dp),
                ) {
                    Text(t.kicker, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), fontWeight = FontWeight.SemiBold, color = t.fg.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                    Text(summary.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp), fontWeight = FontWeight.SemiBold, color = t.fg)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatShortDate(session.startedAtEpochMs)} ${formatClock(session.startedAtEpochMs)} · ${formatDuration(session.durationMs)} · ${detail?.speakers?.size ?: 0} 人",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                        color = t.fg.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("${summary.points.size}" to "要点", "${summary.decisions.size}" to "决策", "${summary.todos.size}" to "待办").forEach { (v, k) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(t.tile).padding(10.dp)) {
                                Text(v, style = TimestampStyle.copy(fontSize = 20.sp), fontWeight = FontWeight.SemiBold, color = t.fg)
                                Text(k, style = MaterialTheme.typography.labelSmall, color = t.fg.copy(alpha = 0.75f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                RailSheet(fill = colors.railTranscript.copy(alpha = 0.09f), border = colors.railTranscript.copy(alpha = 0.25f)) {
                    Text("核心结论", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
                    Spacer(Modifier.height(6.dp))
                    Text(conclusion, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 25.sp))
                }
            }
            itemsIndexed(sections) { i, sec ->
                Spacer(Modifier.height(12.dp))
                RailSheet {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 22.sp), fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
                        Column {
                            Text(sec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(sec.body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp), color = colors.inkMuted)
                        }
                    }
                }
            }
            item {
                Text(
                    "由谛听基于本场会话原声与总结生成\n报告引用过的片段永久保留，不受 30 天期限影响",
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 19.sp),
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            WhiteAction("回到会话", onClick = onBack, modifier = Modifier.weight(1f))
            MintAction("导出 / 分享", onClick = share, modifier = Modifier.weight(1f))
        }
    }
}
