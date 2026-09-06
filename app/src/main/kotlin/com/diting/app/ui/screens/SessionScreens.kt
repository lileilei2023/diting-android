@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.ai.understanding.CitedLine
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.SessionRepository
import com.diting.app.data.repo.SessionWithTranscript
import com.diting.app.data.repo.TaskRepository
import com.diting.app.di.AiClientFactory
import com.diting.app.ui.components.CitationChip
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.LoadingBlock
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.components.formatShortDate
import com.diting.app.ui.theme.TimestampStyle
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.model.Citation
import com.diting.domain.model.Segment
import com.diting.domain.model.Session
import com.diting.domain.task.TaskOrigin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 记录 — session list
// =============================================================================

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
) : ViewModel() {

    val all: StateFlow<List<Session>> = sessions.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow<List<Segment>>(emptyList())
    val results: StateFlow<List<Segment>> = _results.asStateFlow()

    fun search(query: String) = viewModelScope.launch {
        _results.value = if (query.isBlank()) emptyList() else sessions.search(query)
    }
}

@Composable
fun SessionsScreen(
    onOpenSession: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenReview: (com.diting.app.ui.nav.ReviewPeriod) -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.all.collectAsStateWithLifecycle()

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
                Text("记录", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("搜索原声")
                }
            }
        }

        // 日 / 周 / 月 are three different actions, not one report at three
        // zoom levels — so they are peers here rather than a granularity toggle.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenReview(com.diting.app.ui.nav.ReviewPeriod.WEEK) }) {
                    Text("周 · 对表")
                }
                TextButton(onClick = { onOpenReview(com.diting.app.ui.nav.ReviewPeriod.MONTH) }) {
                    Text("月 · 沉淀")
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有录音",
                    hint = "连接录音卡后会自动同步，也可以短按录音键用手机麦克风捕捉。",
                )
            }
        }

        val grouped = sessions.groupBy { formatShortDate(it.startedAtEpochMs) }
        grouped.forEach { (day, daySessions) ->
            item(key = "header-$day") {
                Text(
                    day,
                    style = MaterialTheme.typography.labelSmall,
                    color = ditingColors.inkMuted,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(daySessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onOpenSession(session.id) })
            }
        }
    }
}

// =============================================================================
// 转写检索
// =============================================================================

@Composable
fun SearchScreen(
    onSeek: (Citation) -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.search(it)
            },
            label = { Text("搜原声里说过的话") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (query.isNotBlank() && results.isEmpty()) {
            EmptyState("没有匹配的原声", "换个说法试试，或者确认这段录音已经转写完成。")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { segment ->
                RailCard(rail = ditingColors.railTranscript) {
                    Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    CitationChip(citation = segment.citation, onClick = onSeek)
                }
            }
        }
    }
}

// =============================================================================
// 会话详情 — 转写 / 总结 / 导图
// =============================================================================

data class SessionDetailUi(
    val detail: SessionWithTranscript? = null,
    val isWorking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsStore,
    private val aiClients: AiClientFactory,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    /**
     * Offset the caller asked to jump to, or null when the screen was opened
     * normally. Read once: re-reading on recomposition would re-scroll the user
     * back every time they moved.
     */
    val seekToMs: Long? =
        savedStateHandle.get<Long>("seek")?.takeIf { it >= 0 }

    private val _working = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<SessionDetailUi> = kotlinx.coroutines.flow.combine(
        sessions.observeDetail(sessionId),
        _working,
        _error,
    ) { detail, working, error ->
        SessionDetailUi(detail, working, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUi())

    /** Re-runs the summary; also used when the user changes the scene. */
    fun summarize() = viewModelScope.launch {
        _working.value = true
        _error.value = null
        try {
            val understanding = aiClients.understanding()
            if (understanding == null) {
                _error.value = "还没有配置理解模型，请到「教小谛 › 模型与 Skill」设置。"
                return@launch
            }
            sessions.summarize(
                sessionId,
                understanding,
                settings.scenes.first(),
                settings.summaryPrompt.first(),
            )
        } catch (e: Exception) {
            _error.value = e.message ?: "生成总结失败"
        } finally {
            _working.value = false
        }
    }

    /**
     * 随手教: fixing a word both corrects the transcript and, because the original
     * is kept, gives the hotword list something to learn from.
     */
    fun correct(segmentId: String, newText: String) = viewModelScope.launch {
        sessions.correctSegment(segmentId, newText)
    }

    /** 「交给小谛办」 — a to-do becomes a task at gate ①. */
    fun handToAgent(text: String, citation: Citation?) = viewModelScope.launch {
        tasks.propose(
            goal = text,
            origin = TaskOrigin.SESSION_TODO,
            citations = listOfNotNull(citation),
        )
    }

    fun setScene(sceneId: String?) = viewModelScope.launch {
        sessions.setScene(sessionId, sceneId, overridden = true)
    }

    fun clearError() {
        _error.value = null
    }
}

private enum class DetailTab(val label: String) {
    TRANSCRIPT("转写"),
    SUMMARY("总结"),
    MINDMAP("导图"),
}

@Composable
fun SessionDetailScreen(
    onSeek: (Citation) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenHotwords: () -> Unit,
    onOpenReport: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(DetailTab.TRANSCRIPT) }
    val detail = state.detail

    if (detail == null) {
        LoadingBlock("正在打开会话…")
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) {
            Text(detail.session.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(com.diting.app.ui.components.formatDuration(detail.session.durationMs))
                Pill(detail.session.device.name)
                detail.session.sceneId?.let { Pill(it) }
                if (detail.session.audioPath == null) Pill("原声已过期")
            }
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            DetailTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.label) },
                )
            }
        }

        state.error?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::clearError,
                confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
                title = { Text("没能生成") },
                text = { Text(message) },
            )
        }

        when (tab) {
            DetailTab.TRANSCRIPT -> TranscriptTab(
                detail = detail,
                seekToMs = viewModel.seekToMs,
                onSeek = onSeek,
                onCorrect = viewModel::correct,
                onOpenHotwords = onOpenHotwords,
            )

            DetailTab.SUMMARY -> SummaryTab(
                detail = detail,
                isWorking = state.isWorking,
                onSeek = onSeek,
                onSummarize = viewModel::summarize,
                onOpenReport = onOpenReport,
                onHandToAgent = { text, citation ->
                    viewModel.handToAgent(text, citation)
                    onOpenTasks()
                },
            )

            DetailTab.MINDMAP -> MindmapTab(detail = detail, onSeek = onSeek)
        }
    }
}

@Composable
private fun TranscriptTab(
    detail: SessionWithTranscript,
    seekToMs: Long?,
    onSeek: (Citation) -> Unit,
    onCorrect: (String, String) -> Unit,
    onOpenHotwords: () -> Unit,
) {
    val colors = ditingColors
    var editing by remember { mutableStateOf<Segment?>(null) }
    val listState = rememberLazyListState()

    // Land on the cited line. Keyed on the offset and the transcript's size so it
    // fires once the segments have actually loaded — navigating in usually beats
    // the database query, and scrolling an empty list does nothing.
    LaunchedEffect(seekToMs, detail.segments.size) {
        if (seekToMs == null || detail.segments.isEmpty()) return@LaunchedEffect
        val index = detail.segments.indexOfFirst { it.endMs > seekToMs }
        if (index >= 0) listState.scrollToItem(index)
    }

    if (detail.segments.isEmpty()) {
        EmptyState(
            headline = when (detail.session.transcriptState) {
                com.diting.domain.model.TranscriptState.PENDING -> "还没有转写"
                com.diting.domain.model.TranscriptState.RUNNING -> "正在转写"
                com.diting.domain.model.TranscriptState.FAILED -> "转写失败"
                else -> "没有转写内容"
            },
            hint = "转写在后台按 15 分钟一轮执行，也可能是还没配置转写模型。",
        )
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(detail.segments, key = { it.id }) { segment ->
            val speaker = detail.speakers.firstOrNull { it.label == segment.speakerLabel }
            // Own speech gets the mint rail, others amber — the same distinction
            // the summary uses between what you said and what you were told.
            val rail = if (speaker?.isOwner == true) colors.railTranscript else colors.railAction

            RailCard(rail = rail) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${speaker?.name ?: "说话人 ${segment.speakerLabel}"} · " +
                            segment.citation.timeLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = rail,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        CitationChip(citation = segment.citation, onClick = onSeek, label = "播放")
                        TextButton(onClick = { editing = segment }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "纠错",
                                modifier = Modifier.height(16.dp),
                            )
                            Text("纠错")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
                if (segment.wasCorrected) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("已纠正")
                        TextButton(onClick = onOpenHotwords) { Text("加入热词") }
                    }
                }
            }
        }
    }

    editing?.let { segment ->
        var draft by remember(segment.id) { mutableStateOf(segment.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("纠正这句转写") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "改过的词会成为热词候选，下次录音就认得了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCorrect(segment.id, draft)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SummaryTab(
    detail: SessionWithTranscript,
    isWorking: Boolean,
    onSeek: (Citation) -> Unit,
    onSummarize: () -> Unit,
    onOpenReport: () -> Unit,
    onHandToAgent: (String, Citation?) -> Unit,
) {
    val colors = ditingColors
    val summary = detail.summary

    if (isWorking) {
        LoadingBlock("小谛正在整理这场会话…")
        return
    }

    if (summary == null) {
        EmptyState(
            headline = "还没有总结",
            hint = "转写完成后小谛会自动整理；也可以现在就让它试一次。",
            action = { Button(onClick = onSummarize) { Text("生成总结") } },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RailCard(rail = colors.railInsight) {
                Text(
                    "小谛整理",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.railInsight,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(summary.oneLine, style = MaterialTheme.typography.titleLarge)
            }
        }

        citedSection("会议内容", summary.points, colors.railInsight, onSeek)
        citedSection("决策", summary.decisions, colors.railTranscript, onSeek)
        citedSection("风险与分歧", summary.risks, colors.railBlocker, onSeek)

        if (summary.todos.isNotEmpty()) {
            item {
                RailCard(rail = colors.railAction) {
                    Text(
                        "待办事项",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.railAction,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    summary.todos.forEach { todo ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(todo.text, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                todo.owner?.let { Pill(it) }
                                todo.due?.let { Pill(it) }
                                todo.citation?.let {
                                    CitationChip(citation = it, onClick = onSeek)
                                }
                                TextButton(
                                    onClick = { onHandToAgent(todo.text, todo.citation) }
                                ) { Text("交给小谛办 ›") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSummarize) { Text("让小谛重新整理") }
                TextButton(onClick = onOpenReport) { Text("生成报告 ›") }
            }
        }
    }
}

/** A block of summary lines, each with its own way back to the audio. */
private fun androidx.compose.foundation.lazy.LazyListScope.citedSection(
    title: String,
    lines: List<CitedLine>,
    rail: Color,
    onSeek: (Citation) -> Unit,
) {
    if (lines.isEmpty()) return
    item(key = "section-$title") {
        RailCard(rail = rail) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            lines.forEachIndexed { index, line ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "%02d".format(index + 1),
                        style = TimestampStyle,
                        color = rail,
                    )
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // No chip when the model gave no usable index: a wrong jump is
                    // worse than no jump.
                    line.citation?.let { CitationChip(citation = it, onClick = onSeek) }
                }
            }
        }
    }
}

/**
 * 导图 as an indented tree rather than a radial map.
 *
 * The design chose this deliberately: a radial mind map is unreadable on a phone,
 * and the indent already carries the hierarchy. The rail colour carries the other
 * axis — what was said versus what 小谛 inferred versus what to do.
 */
@Composable
private fun MindmapTab(detail: SessionWithTranscript, onSeek: (Citation) -> Unit) {
    val colors = ditingColors
    val summary = detail.summary

    if (summary == null) {
        EmptyState("还没有导图", "导图由总结生成，先在「总结」页让小谛整理一次。")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        treeBranch("为什么", summary.points, colors.railTranscript, onSeek)
        treeBranch("小谛归纳", summary.decisions, colors.railInsight, onSeek)
        treeBranch("风险", summary.risks, colors.railBlocker, onSeek)
        treeBranch(
            "行动",
            summary.todos.map { CitedLine(it.text, it.citation) },
            colors.railAction,
            onSeek,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.treeBranch(
    title: String,
    lines: List<CitedLine>,
    rail: Color,
    onSeek: (Citation) -> Unit,
) {
    if (lines.isEmpty()) return
    item(key = "branch-$title") {
        Row(Modifier.padding(start = 12.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(((lines.size + 1) * 28).dp)
                    .background(rail)
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                lines.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "· ${line.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        line.citation?.let { CitationChip(citation = it, onClick = onSeek) }
                    }
                }
            }
        }
    }
}
