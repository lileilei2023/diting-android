@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.background
import kotlinx.coroutines.flow.combine
import com.diting.app.data.repo.isLowValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.diting.app.playback.PlaybackUi
import com.diting.app.playback.SessionPlayer
import com.diting.app.ui.components.AiOrb
import com.diting.app.ui.components.BackCircle
import com.diting.app.ui.components.BottomActionBar
import com.diting.app.ui.components.MintAction
import com.diting.app.ui.components.MintLink
import com.diting.app.ui.components.MonoMeta
import com.diting.app.ui.components.RailSheet
import com.diting.app.ui.components.StatusDot
import com.diting.app.ui.components.TogglePill
import com.diting.app.ui.components.UnderlineTabs
import com.diting.app.ui.components.WhiteAction
import com.diting.app.ui.components.formatClock
import com.diting.app.ui.theme.TimestampStyle
import androidx.compose.foundation.layout.Arrangement
import com.diting.app.data.repo.StoredSummary
import com.diting.app.ui.components.SegmentedPills
import com.diting.app.ui.components.Eyebrow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
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

    val emptyRecordings: StateFlow<List<Session>> = sessions.observeEmptyRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) = viewModelScope.launch { sessions.deleteSession(id) }

    /** Transcribed but hollow — small talk, venting, an opening few seconds. */
    val lowValue: StateFlow<List<Session>> = combine(sessions.observeAll(), sessions.observeSummariesBetween(0L, Long.MAX_VALUE)) { list, sums ->
        list.filter { s -> sums[s.id]?.isLowValue() == true && s.transcriptState == com.diting.domain.model.TranscriptState.DONE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMany(ids: List<String>, onDone: (Int) -> Unit) = viewModelScope.launch {
        ids.forEach { sessions.deleteSession(it) }
        onDone(ids.size)
    }

    fun deleteEmptyRecordings(onDone: (Int) -> Unit) = viewModelScope.launch { onDone(sessions.deleteEmptyRecordings()) }

    /** Summaries for every session, so the list can show a brief and a todo count. */
    val summaries: StateFlow<Map<String, StoredSummary>> =
        sessions.observeSummariesBetween(0L, Long.MAX_VALUE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val empties by viewModel.emptyRecordings.collectAsStateWithLifecycle()
    val lowValue by viewModel.lowValue.collectAsStateWithLifecycle()
    val junk = (empties + lowValue).distinctBy { it.id }
    val colors = ditingColors
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val shown = when (filter) {
        1 -> sessions.filter { it.device == com.diting.domain.model.DeviceKind.MR20 }
        2 -> sessions.filter { it.device != com.diting.domain.model.DeviceKind.MR20 }
        3 -> sessions.filter { (summaries[it.id]?.todos?.size ?: 0) > 0 }
        else -> sessions
    }

    // Keys in list order, so the date picker can scroll straight to a day header.
    val today = formatShortDate(System.currentTimeMillis())
    val yesterday = formatShortDate(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
    val grouped = shown.groupBy { formatShortDate(it.startedAtEpochMs) }
    val itemKeys = buildList<String> {
        add("header"); add("filters"); if ((empties + lowValue).isNotEmpty()) add("junk")
        grouped.forEach { (day, daySessions) -> add("header-$day"); daySessions.forEach { add(it.id) } }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("记录", style = MaterialTheme.typography.headlineMedium)
                // 日 / 周 / 月 are three different actions, not one report at three
                // zoom levels; the deck still puts them in one control.
                SegmentedPills(
                    options = listOf("会话", "周", "月"),
                    selected = 0,
                    onSelect = { i ->
                        when (i) {
                            1 -> onOpenReview(com.diting.app.ui.nav.ReviewPeriod.WEEK)
                            2 -> onOpenReview(com.diting.app.ui.nav.ReviewPeriod.MONTH)
                        }
                    },
                )
            }
        }

        item(key = "filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                listOf("全部", "录音卡", "手机", "有待办").forEachIndexed { i, label ->
                    FilterChipPill(label, selected = filter == i) { filter = i }
                }
                Spacer(Modifier.weight(1f))
                FilterChipPill("日期", selected = false, onClick = { picking = true })
                FilterChipPill("搜索", selected = false, onClick = onOpenSearch)
            }
        }

        if (junk.isNotEmpty()) {
            item(key = "junk") {
                RailSheet(
                    rail = colors.inkMuted,
                    fill = colors.paperCard,
                    onClick = { cleaning = true },
                    contentPadding = PaddingValues(16.dp, 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${junk.size} 条录音没什么内容", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    empties.size.takeIf { it > 0 }?.let { "$it 条没听到说话" },
                                    lowValue.size.takeIf { it > 0 }?.let { "$it 条是闲聊或没有可记的事" },
                                ).joinToString("，") + "。点这里看一遍再清；长按任何一条也可以删。",
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp), color = colors.inkMuted,
                            )
                        }
                        Text("清理 ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.railBlocker)
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有录音",
                    hint = "连接录音卡后会自动同步，也可以短按录音键用手机麦克风捕捉。",
                )
            }
        }

        grouped.forEach { (day, daySessions) ->
            item(key = "header-$day") {
                Eyebrow(
                    when (day) { today -> "今天"; yesterday -> "昨天"; else -> day },
                    Modifier.padding(top = 10.dp),
                )
            }
            items(daySessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    summary = summaries[session.id],
                    onClick = { onOpenSession(session.id) },
                    onLongClick = { deleting = session },
                )
            }
        }
    }

    if (picking) {
        // Days that actually have recordings, newest first, grouped by month.
        val days = shown.map { java.time.Instant.ofEpochMilli(it.startedAtEpochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .groupingBy { it }.eachCount().toSortedMap(compareByDescending { it })
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("跳到哪一天") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (days.isEmpty()) Text("还没有录音。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    days.entries.groupBy { it.key.year to it.key.monthValue }.forEach { (ym, entries) ->
                        Text("${ym.first} 年 ${ym.second} 月", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.inkMuted)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            entries.forEach { (date, count) ->
                                FilterChipPill("${date.dayOfMonth} 日 · $count", selected = false) {
                                    picking = false
                                    val key = "header-${date.monthValue}月${date.dayOfMonth}日"
                                    val index = itemKeys.indexOf(key)
                                    if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条录音？") },
            text = { Text("「${session.title}」的音频、转写和整理都会删除，无法恢复。引用过它的任务不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(session.id)
                    deleting = null
                    android.widget.Toast.makeText(context, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = colors.railBlocker) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    if (cleaning) {
        var keep by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { cleaning = false },
            title = { Text("清理 ${junk.size - keep.size} 条录音？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("点一下可以保留。删除后无法恢复；被引用过的不在这里。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    Spacer(Modifier.height(8.dp))
                    junk.forEach { s ->
                        val kept = s.id in keep
                        Row(
                            Modifier.fillMaxWidth().clickable { keep = if (kept) keep - s.id else keep + s.id }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (kept) "保留" else "删除", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = if (kept) colors.railTranscript else colors.railBlocker, modifier = Modifier.width(30.dp))
                            Text("${s.title} · ${com.diting.app.ui.components.formatDuration(s.durationMs)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cleaning = false
                    viewModel.deleteMany(junk.map { it.id }.filter { it !in keep }) { n -> android.widget.Toast.makeText(context, "已清理 $n 条", android.widget.Toast.LENGTH_SHORT).show() }
                }) { Text("清理", color = colors.railBlocker) }
            },
            dismissButton = { TextButton(onClick = { cleaning = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ditingColors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) colors.inkPanel else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) colors.inkPanel else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else colors.inkMuted,
        )
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
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
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

    /** The recessed player at the top of the page; released with the screen. */
    val player = SessionPlayer(appContext, viewModelScope)
    val playback: StateFlow<PlaybackUi> = player.state

    private val _working = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<SessionDetailUi> = kotlinx.coroutines.flow.combine(
        sessions.observeDetail(sessionId),
        _working,
        _error,
    ) { detail, working, error ->
        SessionDetailUi(detail, working, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUi())

    init {
        viewModelScope.launch {
            state.collect { ui ->
                val session = ui.detail?.session ?: return@collect
                player.load(session.audioPath, session.durationMs)
            }
        }
    }

    /** 「回到原声」 inside this session: move the player, no navigation. */
    fun seek(ms: Long) = player.seekTo(ms)

    /** Tells the app who a diarisation label is; `owner` renders as 「你」. */
    fun nameSpeaker(label: String, name: String?, owner: Boolean) = viewModelScope.launch {
        sessions.nameSpeaker(sessionId, label, name, owner)
    }

    suspend fun knownSpeakerNames(): List<String> = sessions.knownSpeakerNames()

    /** Re-runs the summary; also used when the user changes the scene. */
    fun summarize() = viewModelScope.launch {
        _working.value = true
        try {
            val understanding = aiClients.understanding()
                ?: throw IllegalStateException("还没有配置理解模型：去「教小谛 › 模型与 Skill」填一个，或登录大脑账户由服务端整理。")
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

    override fun onCleared() {
        player.release()
    }
}

private enum class DetailTab(val label: String) {
    TRANSCRIPT("转写"),
    SUMMARY("总结"),
    MINDMAP("导图"),
}

/** Speaker colour: the owner is mint, everyone else amber — same rule as the summary. */
@Composable
private fun speakerColor(detail: SessionWithTranscript, label: String): Color {
    val colors = ditingColors
    val speaker = detail.speakers.firstOrNull { it.label == label }
    return if (speaker?.isOwner == true) colors.railTranscript else colors.railAction
}

private fun speakerName(detail: SessionWithTranscript, label: String): String {
    val speaker = detail.speakers.firstOrNull { it.label == label }
    return if (speaker?.isOwner == true) "你" else speaker?.displayName ?: "说话人$label"
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onSeek: (Citation) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenHotwords: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(DetailTab.TRANSCRIPT) }
    var bilingual by remember { mutableStateOf(false) }
    val detail = state.detail
    val colors = ditingColors
    val context = androidx.compose.ui.platform.LocalContext.current

    if (detail == null) {
        LoadingBlock("正在打开会话…")
        return
    }

    // A citation from elsewhere lands the player on the line, once.
    LaunchedEffect(viewModel.seekToMs, playback.ready) {
        val target = viewModel.seekToMs ?: return@LaunchedEffect
        if (playback.ready) viewModel.seek(target)
    }

    val session = detail.session
    val speakerCount = detail.speakers.size.takeIf { it > 0 } ?: detail.segments.map { it.speakerLabel }.distinct().size

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(14.dp, 12.dp, 14.dp, 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackCircle(onClick = onBack)
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        MonoMeta(
                            "${formatClock(session.startedAtEpochMs)} · " +
                                com.diting.app.ui.components.formatDuration(session.durationMs) +
                                " · ${speakerCount} 人",
                        )
                    }
                    MintLink("生成报告", onClick = onOpenReport)
                }

                Spacer(Modifier.height(12.dp))
                WaveformPlayer(
                    playback = playback,
                    seed = session.id.hashCode(),
                    onToggle = viewModel.player::toggle,
                    onScrub = { fraction ->
                        val dur = playback.durationMs.takeIf { it > 0 } ?: session.durationMs
                        viewModel.seek((dur * fraction).toLong())
                    },
                )

                Spacer(Modifier.height(10.dp))
                UnderlineTabs(
                    labels = DetailTab.entries.map { it.label },
                    selected = tab.ordinal,
                    onSelect = { tab = DetailTab.entries[it] },
                    trailing = if (tab == DetailTab.TRANSCRIPT) {
                        {
                            TogglePill("中英对照", on = bilingual) {
                                bilingual = !bilingual
                                if (bilingual) android.widget.Toast.makeText(context, "翻译模型还没配置，先只显示原文", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else null,
                )
            }

            state.error?.let { message ->
                AlertDialog(
                    onDismissRequest = viewModel::clearError,
                    confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
                    title = { Text("没能生成") },
                    text = { Text(message) },
                )
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    DetailTab.TRANSCRIPT -> TranscriptTab(
                        detail = detail,
                        seekToMs = viewModel.seekToMs,
                        positionMs = playback.positionMs,
                        onSeek = viewModel::seek,
                        onCorrect = viewModel::correct,
                        onOpenHotwords = onOpenHotwords,
                        onNameSpeaker = viewModel::nameSpeaker,
                    )

                    DetailTab.SUMMARY -> SummaryTab(
                        detail = detail,
                        isWorking = state.isWorking,
                        onSeek = viewModel::seek,
                        onSummarize = viewModel::summarize,
                        onHandToAgent = { text, citation ->
                            viewModel.handToAgent(text, citation)
                            android.widget.Toast.makeText(context, "已交给小谛，去「任务」看进度", android.widget.Toast.LENGTH_SHORT).show()
                            onOpenTasks()
                        },
                    )

                    DetailTab.MINDMAP -> MindmapTab(detail = detail, onSeek = viewModel::seek)
                }
            }
        }

        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            WhiteAction("升维成洞察", onClick = onOpenInsights, modifier = Modifier.weight(1f))
            MintAction(
                if (state.isWorking) "小谛整理中…" else "再谛听一次",
                onClick = viewModel::summarize,
                modifier = Modifier.weight(1f),
                enabled = !state.isWorking && detail.segments.isNotEmpty(),
            )
            AiOrb(onClick = onOpenChat)
        }
    }
}

/**
 * The recessed player strip: mint play circle, static bars, red playhead, mono
 * clock. The bars are decorative and seeded per session so the same recording
 * always looks the same; the real signal is the playhead.
 */
@Composable
private fun WaveformPlayer(
    playback: PlaybackUi,
    seed: Int,
    onToggle: () -> Unit,
    onScrub: (Float) -> Unit,
) {
    val colors = ditingColors
    val bars = remember(seed) {
        val rnd = java.util.Random(seed.toLong())
        List(44) { 0.25f + rnd.nextFloat() * 0.75f }
    }
    val red = colors.railBlocker
    val barColor = colors.inkMuted.copy(alpha = 0.55f)
    var trackWidth by remember { mutableStateOf(1f) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.paperSunken)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (playback.ready) colors.railTranscript else colors.inkMuted)
                .clickable(enabled = playback.ready, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.playing) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        androidx.compose.foundation.Canvas(
            Modifier
                .weight(1f)
                .height(34.dp)
                .pointerInput(playback.ready) {
                    if (!playback.ready) return@pointerInput
                    detectTapGestures { offset -> onScrub((offset.x / trackWidth).coerceIn(0f, 1f)) }
                },
        ) {
            trackWidth = size.width
            val gap = size.width / bars.size
            val w = gap * 0.55f
            bars.forEachIndexed { i, h ->
                val barH = size.height * h
                drawRoundRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(i * gap + (gap - w) / 2, (size.height - barH) / 2),
                    size = androidx.compose.ui.geometry.Size(w, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
            }
            val x = size.width * playback.fraction
            drawRect(
                color = red,
                topLeft = androidx.compose.ui.geometry.Offset(x - 1.dp.toPx(), 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height - 8.dp.toPx()),
            )
        }
        MonoMeta(clock(playback.positionMs))
    }
}

@Composable
private fun TranscriptTab(
    detail: SessionWithTranscript,
    seekToMs: Long?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onCorrect: (String, String) -> Unit,
    onOpenHotwords: () -> Unit,
    onNameSpeaker: (String, String?, Boolean) -> Unit,
) {
    val colors = ditingColors
    var editing by remember { mutableStateOf<Segment?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
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
            hint = when (detail.session.transcriptState) {
                com.diting.domain.model.TranscriptState.FAILED ->
                    detail.session.transcriptError ?: "转写没有成功，稍后会自动重试。"
                com.diting.domain.model.TranscriptState.RUNNING -> "正在上传大脑转写，长录音要几分钟。"
                else -> "同步后会自动上传大脑转写；没有登录大脑的话，需要在「教小谛」里配置转写模型。"
            },
        )
        return
    }

    val activeId = detail.segments.firstOrNull { positionMs in it.startMs until it.endMs }?.id

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(detail.segments, key = { it.id }) { segment ->
            val spColor = speakerColor(detail, segment.speakerLabel)
            val active = segment.id == activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .combinedClickable(
                        onClick = { onSeek(segment.startMs) },
                        onLongClick = { editing = segment },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.width(50.dp).clickable { naming = segment.speakerLabel }) {
                    Text(
                        speakerName(detail, segment.speakerLabel),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = spColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        clock(segment.startMs),
                        style = TimestampStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = colors.inkMuted,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        segment.text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit(23f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    )
                    if (segment.wasCorrected) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已纠正 · 加入热词",
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(colors.railAction.copy(alpha = 0.08f))
                                .dashedBorder(colors.railAction)
                                .clickable(onClick = onOpenHotwords)
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.railAction,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "长按任意句可纠正，纠正会自动加入热词；点说话人标签可以标记「这是我」或写上名字。",
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    naming?.let { label ->
        val current = detail.speakers.firstOrNull { it.label == label }
        var name by remember(label) { mutableStateOf(current?.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("说话人$label 是谁？") },
            text = {
                Column {
                    Text("这场会话里标为 $label 的所有句子都会跟着改。", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名字，例如 李总") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "声纹识别还没接上：大脑目前不能自动分出你和别人，这里的标记是人工的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { onNameSpeaker(label, null, true); naming = null }) { Text("这是我") }
                    TextButton(onClick = { onNameSpeaker(label, name, false); naming = null }) { Text("保存") }
                }
            },
            dismissButton = {
                Row {
                    if (current?.isOwner == true || current?.displayName != null) {
                        TextButton(onClick = { onNameSpeaker(label, null, false); naming = null }) { Text("清除") }
                    }
                    TextButton(onClick = { naming = null }) { Text("取消") }
                }
            },
        )
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

/** 1dp dashed pill outline, for the 「已纠正」 chip. */
private fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    )
    drawRoundRect(color = color, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
}

/** Inline 「↩ 12:04」 mint time, as the deck writes it after a summary point. */
@Composable
private fun InlineTime(citation: Citation?) {
    if (citation == null) return
    Text(
        "↩ ${citation.timeLabel()}",
        style = MaterialTheme.typography.labelSmall,
        color = ditingColors.railTranscript,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun SummaryTab(
    detail: SessionWithTranscript,
    isWorking: Boolean,
    onSeek: (Long) -> Unit,
    onSummarize: () -> Unit,
    onHandToAgent: (String, Citation?) -> Unit,
) {
    val colors = ditingColors
    val summary = detail.summary
    val session = detail.session

    if (isWorking) {
        LoadingBlock("小谛正在整理这场会话…")
        return
    }

    if (summary == null) {
        Column(Modifier.fillMaxWidth().padding(14.dp, 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                headline = "还没有总结",
                hint = if (detail.segments.isEmpty()) "先等转写完成，小谛才有东西可整理。" else "转写完成后小谛会自动整理；也可以现在就让它试一次。",
            )
            if (detail.segments.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MintAction("让小谛整理", onClick = onSummarize, modifier = Modifier.fillMaxWidth(0.6f))
            }
        }
        return
    }

    // Talk share per speaker, from segment durations — what 会议信息 draws.
    val shares = remember(detail.segments, detail.speakers) {
        val total = detail.segments.sumOf { it.endMs - it.startMs }.coerceAtLeast(1)
        detail.segments.groupBy { it.speakerLabel }
            .map { (label, segs) -> label to (segs.sumOf { it.endMs - it.startMs } * 100 / total).toInt() }
            .sortedByDescending { it.second }
    }
    val sharePalette = listOf(colors.railTranscript, colors.railAction, colors.railInsight, colors.railBlocker, colors.inkMuted)

    LazyColumn(
        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RailSheet(rail = colors.railInsight) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("会议信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("小谛整理", style = MaterialTheme.typography.labelSmall, color = colors.railInsight)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatClock(session.startedAtEpochMs)} · ${com.diting.app.ui.components.formatDuration(session.durationMs)} · 场景：${session.sceneId ?: "未标记"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                if (shares.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        shares.forEachIndexed { i, (label, pct) ->
                            val owner = detail.speakers.firstOrNull { it.label == label }?.isOwner == true
                            val c = if (owner) colors.railTranscript else sharePalette[(i + 1).coerceAtMost(sharePalette.lastIndex)]
                            Box(Modifier.weight(pct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(c))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        shares.forEachIndexed { i, (label, pct) ->
                            val owner = detail.speakers.firstOrNull { it.label == label }?.isOwner == true
                            val c = if (owner) colors.railTranscript else sharePalette[(i + 1).coerceAtMost(sharePalette.lastIndex)]
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                StatusDot(c, 8.dp)
                                Text("${speakerName(detail, label)} $pct%", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                            }
                        }
                    }
                }
            }
        }

        item {
            RailSheet(rail = colors.railInsight) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("一句话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("小谛整理", style = MaterialTheme.typography.labelSmall, color = colors.railInsight)
                }
                Spacer(Modifier.height(6.dp))
                Text(summary.oneLine.trim(), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp))
            }
        }

        numberedSheet("会议内容", summary.points, colors.railInsight, onSeek)
        numberedSheet("决策", summary.decisions, colors.railTranscript, onSeek)
        numberedSheet("风险与分歧", summary.risks, colors.railBlocker, onSeek)

        if (summary.todos.isNotEmpty()) {
            item {
                RailSheet(
                    rail = colors.railAction,
                    fill = colors.railAction.copy(alpha = 0.06f),
                    border = colors.railAction.copy(alpha = 0.2f),
                ) {
                    Text("待办事项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.railAction)
                    Spacer(Modifier.height(6.dp))
                    summary.todos.forEach { todo ->
                        HorizontalDivider(color = colors.railAction.copy(alpha = 0.15f))
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(todo.text, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    listOfNotNull(todo.owner, todo.due).joinToString(" · ").ifEmpty { "未指定负责人" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkMuted,
                                )
                                Text(
                                    "交给小谛办 ›",
                                    modifier = Modifier.clickable { onHandToAgent(todo.text, todo.citation) }.padding(4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.railAction,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A white sheet of numbered lines, each with an inline 「↩ time」 back to the audio. */
private fun androidx.compose.foundation.lazy.LazyListScope.numberedSheet(
    title: String,
    lines: List<CitedLine>,
    rail: Color,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) return
    item(key = "sheet-$title") {
        val colors = ditingColors
        RailSheet(rail = rail) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            lines.forEachIndexed { index, line ->
                HorizontalDivider(color = colors.paperSunken)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(line.citation?.let { c -> Modifier.clickable { onSeek(c.startMs) } } ?: Modifier)
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "%02d".format(index + 1),
                        style = TimestampStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.railTranscript,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        buildAnnotatedString {
                            append(line.text)
                            line.citation?.let {
                                append("  ")
                                withStyle(SpanStyle(color = colors.railTranscript, fontSize = MaterialTheme.typography.labelSmall.fontSize)) {
                                    append("↩ ${it.timeLabel()}")
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

/**
 * 导图 as an indented tree rather than a radial map.
 *
 * The design chose this deliberately: a radial mind map is unreadable on a phone,
 * and the indent already carries the hierarchy. The branch colour carries the
 * other axis — what was said versus what 小谛 inferred versus what to do.
 */
@Composable
private fun MindmapTab(detail: SessionWithTranscript, onSeek: (Long) -> Unit) {
    val colors = ditingColors
    val summary = detail.summary

    if (summary == null) {
        EmptyState("还没有导图", "导图由总结生成，先在「总结」页让小谛整理一次。")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 110.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0E7D70))
                    .padding(16.dp, 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(summary.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(summary.oneLine, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
                }
                Pill("主旨", color = Color.White, background = Color.White.copy(alpha = 0.18f))
            }
        }

        treeBranch("原声要点", summary.points, colors.railTranscript, onSeek)
        treeBranch("小谛归纳", summary.decisions, colors.railInsight, onSeek)
        treeBranch("风险", summary.risks, colors.railBlocker, onSeek)
        treeBranch("行动", summary.todos.map { CitedLine(it.text, it.citation) }, colors.railAction, onSeek)

        item {
            Text(
                "点叶子回到原声 · 薄荷=原声要点 · 石板青=小谛归纳 · 琥珀=行动",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.treeBranch(
    title: String,
    lines: List<CitedLine>,
    rail: Color,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) return
    item(key = "branch-$title") {
        val colors = ditingColors
        Row(Modifier.padding(start = 8.dp, top = 12.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(rail))
            Column(Modifier.padding(start = 12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, colors.paperSunken, RoundedCornerShape(12.dp))
                        .padding(12.dp, 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Pill("${lines.size} 点", color = colors.inkMuted, background = colors.paperRoot)
                }
                lines.forEach { line ->
                    Row(
                        Modifier
                            .padding(start = 14.dp, top = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(1.dp, colors.paperSunken, RoundedCornerShape(10.dp))
                            .then(line.citation?.let { c -> Modifier.clickable { onSeek(c.startMs) } } ?: Modifier)
                            .padding(12.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(rail, 6.dp)
                        Text(line.text, style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), modifier = Modifier.weight(1f))
                        line.citation?.let {
                            Text(
                                "▶ ${it.timeLabel()}",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White)
                                    .border(1.dp, colors.paperSunken, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                style = TimestampStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                color = rail,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
