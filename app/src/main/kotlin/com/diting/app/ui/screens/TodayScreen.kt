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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One line of 今日捕捉: a time, a point, and a way back to the audio. */
data class CaptureLine(val citation: Citation?, val text: String, val clock: String)

data class TodayUiState(
    val dayHeader: String = "",
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
)

data class WeeklyTeaser(val promised: Int, val kept: Int)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val memory: MemoryRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        .toInstant().toEpochMilli()
    private val dayEnd = dayStart + 24L * 60 * 60 * 1000

    val state: StateFlow<TodayUiState> = combine(
        sessions.observeBetween(dayStart, dayEnd),
        sessions.observeSummariesBetween(dayStart, dayEnd),
        memory.observeProactiveCards(),
        tasks.observeByState(TaskState.AWAITING_GOAL_CONFIRMATION),
        settings.aiSettings,
    ) { todaysSessions, summaries, cards, openTasks, ai ->
        TodayUiState(
            dayHeader = formatDayHeader(System.currentTimeMillis()),
            sessions = todaysSessions,
            headline = headlineFor(summaries),
            headlineCitations = headlineCitations(summaries),
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
            isConfigured = ai.isUsable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

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
            )
        }
    }.take(6)

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
            Text(state.dayHeader, style = MaterialTheme.typography.headlineMedium)
        }

        // The app is useless without a transcription model, so say so at the top
        // rather than letting every session sit silently at PENDING.
        if (!state.isConfigured) {
            item {
                RailCard(rail = colors.railAction, onClick = onOpenModels) {
                    Text("还没有配置模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "录音可以同步，但不会转写。去「教小谛 › 模型与 Skill」填入千问或豆包的 API Key。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                }
            }
        }

        state.headline?.let { headline ->
            item {
                RailCard(rail = colors.railTranscript, onClick = onOpenInsights) {
                    Text(
                        "今天最乱的事",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.railTranscript,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(headline, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.headlineCitations.forEach { citation ->
                            CitationChip(citation = citation, onClick = onSeek)
                        }
                    }
                }
            }
        }

        if (state.captures.isNotEmpty()) {
            item {
                RailCard(rail = colors.railTranscript) {
                    Text("今日捕捉", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    state.captures.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                line.clock,
                                style = TimestampStyle,
                                color = colors.railTranscript,
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            line.citation?.let {
                                CitationChip(citation = it, onClick = onSeek, label = "↩")
                            }
                        }
                    }
                }
            }
        }

        items(state.proactiveCards, key = { it.id }) { card ->
            RailCard(rail = colors.railAction) {
                Text(
                    "被你忽略的提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railAction,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                card.quote?.let {
                    Text("「$it」", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    card.rationale,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.actOnCard(card.id) }) { Text("去处理") }
                    // Feeds noise gate 4 rather than merely hiding the card.
                    TextButton(onClick = { viewModel.dismissCard(card.id) }) { Text("这不重要") }
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
            item {
                Text(
                    "今天的会话",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onOpenSession(session.id) })
            }
        }

        if (state.sessions.isEmpty() && state.proactiveCards.isEmpty()) {
            item {
                EmptyState(
                    headline = "今天还没有录音",
                    hint = "短按下方录音键快速捕捉，或长按进入会议模式。已配对的录音卡会在连接时自动同步。",
                )
            }
        }
    }
}

@Composable
internal fun SessionRow(session: Session, onClick: () -> Unit) {
    val colors = ditingColors
    RailCard(rail = colors.railTranscript, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(formatClock(session.startedAtEpochMs))
                    Pill(formatDuration(session.durationMs))
                    Pill(session.device.name)
                    Pill(transcriptLabel(session))
                }
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
