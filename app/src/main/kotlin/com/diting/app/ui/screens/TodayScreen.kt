@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.diting.app.ui.components.StatusDot
import com.diting.app.ui.components.SheetCard
import com.diting.app.ui.components.Eyebrow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.data.db.ProactiveCardEntity
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.MemoryRepository
import com.diting.app.data.repo.SessionRepository
import com.diting.app.data.repo.StoredSummary
import com.diting.app.data.repo.TaskRepository
import com.diting.app.ui.components.CitationChip
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.components.formatClock
import com.diting.app.ui.components.formatDayHeader
import com.diting.app.ui.components.formatDuration
import com.diting.app.ui.theme.TimestampStyle
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.model.Citation
import com.diting.domain.model.Session
import com.diting.domain.task.Task
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One line of 今日捕捉: a time, a point, and a way back to the audio. */
data class CaptureLine(val citation: Citation?, val text: String, val clock: String, val source: String = "")

data class TodayUiState(
    val dayHeader: String = "",
    /** True when today is empty and the page is showing the last day with recordings. */
    val showingEarlierDay: Boolean = false,
    val sessions: List<Session> = emptyList(),
    /** The messiest thing today, pulled across sessions. */
    val headline: String? = null,
    val headlineCitations: List<Citation> = emptyList(),
    val captures: List<CaptureLine> = emptyList(),
    val proactiveCards: List<ProactiveCardEntity> = emptyList(),
    val tomorrowTodos: List<Task> = emptyList(),
    /** Non-null on Sundays, when the weekly review is ready. */
    val weeklyReview: WeeklyTeaser? = null,
    val isConfigured: Boolean = true,
    val summaries: Map<String, StoredSummary> = emptyMap(),
    /** Total recorded today, ms. */
    val recordedMs: Long = 0,
    val decisions: Int = 0,
    val deviceConnected: Boolean = false,
    /** Sub-line under the hero: how much 小谛 pulled together today. */
    val heroNote: String = "",
    val followUps: Int = 0,
    val repeated: Int = 0,
    val publishable: Int = 0,
)

data class WeeklyTeaser(val promised: Int, val kept: Int)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val memory: MemoryRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsStore,
    private val brainStore: com.diting.app.brain.BrainStore,
    private val deviceManager: com.diting.app.device.Mr20DeviceManager,
) : ViewModel() {

    /**
     * The day being shown. Today when there is anything from today; otherwise
     * the newest day that has recordings, so the page never opens onto an empty
     * hero the morning after a full day — the header says which day it is.
     */
    private val day: kotlinx.coroutines.flow.Flow<Pair<Long, Long>> = sessions.observeAll().map { all ->
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val newest = all.maxOfOrNull { it.startedAtEpochMs }
        val start = if (newest == null || newest >= todayStart) todayStart
        else java.time.Instant.ofEpochMilli(newest).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        start to start + 24L * 60 * 60 * 1000
    }.distinctUntilChanged()

    val state: StateFlow<TodayUiState> = day.flatMapLatest { (dayStart, dayEnd) -> combine(
        sessions.observeBetween(dayStart, dayEnd),
        sessions.observeSummariesBetween(dayStart, dayEnd),
        kotlinx.coroutines.flow.combine(memory.observeProactiveCards(), memory.observeLiveNodes()) { cards, nodes -> cards to nodes.count { it.mentionCount >= 2 } },
        tasks.observeByState(TaskState.AWAITING_GOAL_CONFIRMATION),
        kotlinx.coroutines.flow.combine(settings.aiSettings, brainStore.account, deviceManager.client) { ai, brain, client ->
            // Either path produces transcripts: a vendor ASR on the phone, or the
            // brain doing it server-side after upload.
            (ai.isUsable || brain.isLoggedIn) to (client != null)
        },
    ) { todaysSessions, summaries, (cards, recurringCount), openTasks, (configured, connected) ->
        TodayUiState(
            dayHeader = formatDayHeader(dayStart),
            showingEarlierDay = dayStart < LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            sessions = todaysSessions,
            headline = headlineFor(summaries)?.let { firstSentence(it) } ?: latestBrief(todaysSessions, summaries),
            headlineCitations = headlineCitations(summaries),
            heroNote = "小谛从 ${summaries.size} 场对话里整理出 ${summaries.values.sumOf { it.points.size }} 条要点、" +
                "${summaries.values.sumOf { it.todos.size }} 件要办的事。",
            followUps = summaries.values.sumOf { it.todos.size },
            repeated = recurringCount,
            publishable = summaries.values.count { it.points.size >= 3 },
            captures = capturesFor(todaysSessions, summaries),
            proactiveCards = cards,
            tomorrowTodos = openTasks.take(4),
            // The Sunday review card is part of the weekly rhythm, so it appears
            // on its own rather than waiting to be found under 记录.
            weeklyReview = if (LocalDate.now().dayOfWeek == DayOfWeek.SUNDAY) {
                weeklyTeaser(summaries)
            } else {
                null
            },
            isConfigured = configured,
            summaries = summaries,
            recordedMs = todaysSessions.sumOf { it.durationMs },
            decisions = summaries.values.sumOf { it.decisions.size },
            deviceConnected = connected,
        )
    } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /**
     * "今天最乱的事" is the risk from whichever session flagged the most of them.
     * An unresolved disagreement is what actually costs the user tomorrow — the
     * longest recording usually does not.
     */
    private fun headlineFor(summaries: Map<String, StoredSummary>): String? =
        summaries.values
            .filter { it.risks.isNotEmpty() }
            .maxByOrNull { it.risks.size }
            ?.risks?.firstOrNull()?.text

    private fun headlineCitations(summaries: Map<String, StoredSummary>): List<Citation> =
        summaries.values
            .filter { it.risks.isNotEmpty() }
            .maxByOrNull { it.risks.size }
            ?.risks?.mapNotNull { it.citation }
            ?.take(3)
            .orEmpty()

    private fun capturesFor(
        todaysSessions: List<Session>,
        summaries: Map<String, StoredSummary>,
    ): List<CaptureLine> = todaysSessions.flatMap { session ->
        summaries[session.id]?.points.orEmpty().map { point ->
            CaptureLine(
                citation = point.citation,
                text = point.text,
                // The citation offset is relative to the recording, so it has to
                // be added to the session's start to read as a wall clock.
                clock = formatClock(session.startedAtEpochMs + (point.citation?.startMs ?: 0)),
                source = session.title.take(8),
            )
        }
    }.take(6)

    /**
     * With no risk flagged, the hero shows the newest session's one-liner —
     * its first sentence only. The deck's headline is one balanced line, not a
     * paragraph in serif.
     */
    private fun latestBrief(todaysSessions: List<Session>, summaries: Map<String, StoredSummary>): String? =
        todaysSessions.sortedByDescending { it.startedAtEpochMs }
            .firstNotNullOfOrNull { s -> summaries[s.id]?.oneLine?.takeIf { it.isNotBlank() } }
            ?.let { firstSentence(it) }

    private fun firstSentence(text: String): String {
        val cut = text.split('；', '。', ';', '\n').firstOrNull { it.isNotBlank() }?.trim() ?: text
        return if (cut.length > 42) cut.take(40) + "…" else cut
    }

    /**
     * 周 · 对表 counts commitments the week produced against the ones that were
     * closed. Todos with a citation are the promises; the rest are notes.
     */
    private fun weeklyTeaser(summaries: Map<String, StoredSummary>): WeeklyTeaser {
        val promised = summaries.values.sumOf { it.todos.size }
        val kept = summaries.values.sumOf { summary ->
            summary.todos.count { it.due != null }
        }
        return WeeklyTeaser(promised = promised, kept = kept)
    }

    fun dismissCard(id: String) = viewModelScope.launch {
        memory.dismissCardAsUnimportant(id)
    }

    fun actOnCard(id: String) = viewModelScope.launch { memory.markCardActedOn(id) }
}

@Composable
fun TodayScreen(
    onOpenSession: (String) -> Unit,
    onOpenInsights: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenModels: () -> Unit,
    onSeek: (Citation) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Eyebrow("今日谛听")
                    Text(state.dayHeader, style = MaterialTheme.typography.headlineMedium)
                    if (state.showingEarlierDay) {
                        Text("今天还没录音，先看最近一天的整理", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(if (state.deviceConnected) colors.railTranscript else colors.inkMuted, size = 8.dp)
                        Text(
                            if (state.deviceConnected) "录音卡在线" else "录音卡离线",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                        )
                    }
                }
            }
        }

        // The app is useless without a transcription model, so say so at the top
        // rather than letting every session sit silently at PENDING.
        if (!state.isConfigured) {
            item {
                Surface(
                    onClick = onOpenModels,
                    shape = RoundedCornerShape(12.dp),
                    color = colors.railAction.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.railAction))
                        Column(Modifier.padding(14.dp, 12.dp)) {
                            Text("还没有登录大脑，也没有配置模型", style = MaterialTheme.typography.titleSmall, color = colors.railAction, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("录音会同步，但不会转写。去「我的 › 大脑账户」登录即可。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                        }
                    }
                }
            }
        }

        state.headline?.let { headline ->
            item {
                Surface(
                    onClick = onOpenInsights,
                    shape = RoundedCornerShape(20.dp),
                    color = colors.railTranscript.copy(alpha = 0.09f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.railTranscript.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp, 18.dp, 18.dp, 16.dp)) {
                        Eyebrow("今天最乱的事", color = colors.railTranscript)
                        Spacer(Modifier.height(6.dp))
                        Text(headline, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(state.heroNote, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.headlineCitations.forEach { citation ->
                                CitationChip(citation = citation, onClick = onSeek)
                            }
                            Spacer(Modifier.weight(1f))
                            Surface(shape = RoundedCornerShape(percent = 50), color = colors.railTranscript) {
                                Text(
                                    "看三种声音 ›",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.sessions.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(formatDuration(state.recordedMs), "记录时长", Modifier.weight(1f))
                    StatTile("${state.sessions.size}", "会话场次", Modifier.weight(1f))
                    StatTile("${state.decisions}", "关键决策", Modifier.weight(1f))
                }
            }
        }

        if (state.captures.isNotEmpty()) {
            item {
                RailCard(rail = colors.railTranscript, contentPadding = PaddingValues(16.dp, 14.dp)) {
                    Text("今日捕捉", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    state.captures.forEachIndexed { i, line ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (line.citation != null) Modifier.clickable { onSeek(line.citation) } else Modifier)
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                line.clock,
                                style = TimestampStyle,
                                color = colors.railTranscript,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text(
                                buildAnnotatedString {
                                    append(line.text)
                                    if (line.source.isNotBlank()) {
                                        append("  ")
                                        withStyle(SpanStyle(color = colors.inkMuted, fontSize = MaterialTheme.typography.labelSmall.fontSize)) {
                                            append(line.source)
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // 小谛自动整理: three sunken tiles.
        if (state.sessions.isNotEmpty()) {
            item {
                SheetCard {
                    Text("小谛自动整理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SunkenStat("${state.followUps}", "件要继续跟进", colors.railAction, Modifier.weight(1f), onOpenTasks)
                        SunkenStat("${state.repeated}", "个反复被提", null, Modifier.weight(1f), onOpenInsights)
                        SunkenStat("${state.publishable}", "个可沉淀成内容", null, Modifier.weight(1f), onOpenInsights)
                    }
                }
            }
        }

        items(state.proactiveCards, key = { it.id }) { card ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.railAction.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.railAction.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.railAction))
                    Column(Modifier.padding(16.dp, 14.dp)) {
                        Text("被你忽略的提醒", style = MaterialTheme.typography.titleSmall, color = colors.railAction, fontWeight = FontWeight.SemiBold)
                        card.quote?.let { quote ->
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.railAction.copy(alpha = 0.35f)))
                                Text(
                                    "\u201C$quote\u201D",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(card.rationale, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = { viewModel.actOnCard(card.id) },
                                shape = RoundedCornerShape(10.dp),
                                color = colors.railAction,
                            ) {
                                Text("去处理", Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                            Surface(
                                onClick = { viewModel.dismissCard(card.id) },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                // Feeds noise gate 4 rather than merely hiding the card.
                                Text("这不重要", Modifier.padding(14.dp, 8.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        if (state.tomorrowTodos.isNotEmpty()) {
            item {
                RailCard(rail = colors.railAction) {
                    Text("明日待办", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    state.tomorrowTodos.forEach { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                task.goal,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onOpenTask(task.id) }) { Text("确认") }
                        }
                    }
                }
            }
        }

        state.weeklyReview?.let { teaser ->
            item {
                InkPanel(onClick = onOpenReview) {
                    Text(
                        "周日 · 本周复盘已就绪",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accentOnPanel,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "答应了 ${teaser.promised} 件事，兑现了 ${teaser.kept} 件。",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onInkPanel,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "对表 ›",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accentOnPanel,
                    )
                }
            }
        }

        if (state.sessions.isNotEmpty()) {
            item { Eyebrow("今天的会话", Modifier.padding(top = 8.dp)) }
            items(state.sessions, key = { it.id }) { session ->
                SessionCard(session = session, summary = state.summaries[session.id], onClick = { onOpenSession(session.id) })
            }
        }

        if (state.sessions.isEmpty() && state.proactiveCards.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("今天还没有录音", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "长按下方录音键开始，短按是快速捕捉。\n录音卡连上后会自动同步。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Sunken tile inside 小谛自动整理: amber number when it demands action. */
@Composable
private fun SunkenStat(value: String, label: String, accent: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = ditingColors.paperRoot) {
        Column(Modifier.padding(10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = accent ?: Color.Unspecified,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = ditingColors.inkMuted)
        }
    }
}

/** Mono number over a muted caption — the deck's 记录时长 / 会话场次 / 关键决策 tiles. */
@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    SheetCard(modifier = modifier, contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 10.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = ditingColors.inkMuted)
    }
}

/**
 * The deck's session card: title with a mono clock on the right, a one-line
 * brief, then pills — kind · duration, speakers, and an amber "N 个待办" when
 * the summary produced any.
 */
@Composable
internal fun SessionCard(session: Session, summary: StoredSummary?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val colors = ditingColors
    SheetCard(onClick = onClick, onLongClick = onLongClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                formatClock(session.startedAtEpochMs),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.inkMuted,
            )
        }
        val brief = summary?.oneLine?.takeIf { it.isNotBlank() }
            ?: summary?.points?.firstOrNull()?.text
            ?: when (session.transcriptState) {
                com.diting.domain.model.TranscriptState.DONE -> "已转写，还没有摘要。"
                com.diting.domain.model.TranscriptState.RUNNING -> "正在转写…"
                com.diting.domain.model.TranscriptState.FAILED -> session.transcriptError ?: "转写没有成功。"
                else -> "等待上传转写。"
            }
        Spacer(Modifier.height(4.dp))
        Text(
            brief,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                "${if (session.device == com.diting.domain.model.DeviceKind.MR20) "录音卡" else "手机"} · ${formatDuration(session.durationMs)}",
                background = colors.paperRoot,
            )
            val speakers = session.speakers.size
            if (speakers > 0) Pill("$speakers 人", background = colors.paperRoot)
            val todos = summary?.todos?.size ?: 0
            if (todos > 0) {
                Pill("$todos 个待办", color = colors.railAction, background = colors.railAction.copy(alpha = 0.1f))
            } else if (session.transcriptState != com.diting.domain.model.TranscriptState.DONE) {
                Pill(transcriptLabel(session), background = colors.paperRoot)
            }
        }
    }
}

private fun transcriptLabel(session: Session) = when (session.transcriptState) {
    com.diting.domain.model.TranscriptState.PENDING -> "待转写"
    com.diting.domain.model.TranscriptState.RUNNING -> "转写中"
    com.diting.domain.model.TranscriptState.DONE -> "已转写"
    com.diting.domain.model.TranscriptState.FAILED -> "转写失败"
}
