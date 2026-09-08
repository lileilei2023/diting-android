@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diting.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.diting.app.ui.components.BackCircle
import com.diting.app.ui.components.BottomActionBar
import com.diting.app.ui.components.Eyebrow
import com.diting.app.ui.components.IconSquare
import com.diting.app.ui.components.MintAction
import com.diting.app.ui.components.MintLink
import com.diting.app.ui.components.RailSheet
import com.diting.app.ui.components.SheetCard
import com.diting.app.ui.components.WhiteAction
import com.diting.app.ui.theme.TimestampStyle
import com.diting.domain.task.ArtifactKind
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.diting.ai.AiEndpoint
import com.diting.ai.AiSettings
import com.diting.ai.AiVendor
import com.diting.ai.AsrEndpoint
import com.diting.app.data.db.HotwordDao
import com.diting.app.data.db.HotwordEntity
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.MemoryRepository
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.memory.RetentionPolicy
import com.diting.domain.model.Intent
import com.diting.domain.scene.GlobalIgnoreRules
import com.diting.domain.scene.ResponseAction
import com.diting.domain.scene.Scene
import com.diting.domain.task.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 教小谛 Hub — the four layers
// =============================================================================

@HiltViewModel
class TeachViewModel @Inject constructor(
    settings: SettingsStore,
    hotwordDao: HotwordDao,
) : ViewModel() {
    data class Counts(val hotwords: Int = 0, val scenes: Int = 0, val skills: Int = 0, val corrections: Int = 0, val asr: Boolean = false, val llm: Boolean = false, val agent: Boolean = false, val onboarded: Boolean = false)

    val counts: StateFlow<Counts> = combine(hotwordDao.observeAll(), settings.scenes, settings.aiSettings, settings.onboardingComplete) { hot, scenes, ai, onboarded ->
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        Counts(
            hotwords = hot.size,
            scenes = scenes.count { sc -> sc.rules.any { it.enabled } },
            skills = listOfNotNull(ai.transcription, ai.understanding, ai.agent).size,
            corrections = hot.count { it.source == "CORRECTION" && it.createdAtEpochMs >= weekAgo },
            asr = ai.transcription != null,
            llm = ai.understanding != null,
            agent = ai.agent != null,
            onboarded = onboarded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts())
}

@Composable
fun TeachScreen(
    onBack: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenHotwords: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenMemory: () -> Unit,
    viewModel: TeachViewModel = hiltViewModel(),
) {
    val c by viewModel.counts.collectAsStateWithLifecycle()
    val colors = ditingColors

    data class RowSpec(val name: String, val desc: String, val value: String, val valueColor: Color, val go: () -> Unit)
    data class Group(val title: String, val bar: Color, val rows: List<RowSpec>)

    val groups = listOf(
        Group("听感 · 听得准", colors.railTranscript, listOf(
            RowSpec("声纹与口音", "朗读 3 句：说话人 A 永远是你", if (c.onboarded) "已完成" else "未做", if (c.onboarded) colors.railTranscript else colors.railAction, onOpenOnboarding),
            RowSpec("热词与专名", "产品名、同事名、缩写；纠正会自动加入", "${c.hotwords}", colors.inkMuted, onOpenHotwords),
        )),
        Group("理解 · 听得懂", colors.railInsight, listOf(
            RowSpec("场景与响应规则", "听到什么句子 → 做什么，挂在场景下", "${c.scenes} 启用", colors.inkMuted, onOpenScenes),
        )),
        Group("行动 · 办得了", colors.railAction, listOf(
            RowSpec("模型与 Skill", "转写 / 理解 / Agent 三层各选其一", if (c.llm) "已配置" else "未配置", if (c.llm) colors.railTranscript else colors.railAction, onOpenModels),
            RowSpec("目的地与通道", "采用之后东西去哪", "留在谛听", colors.inkMuted, onOpenDestinations),
        )),
        Group("记忆 · 记得少", colors.railBlocker, listOf(
            RowSpec("记忆与遗忘", "三层寿命；被引用即永久", "默认", colors.inkMuted, onOpenMemory),
        )),
    )

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("教小谛", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "你在任何页面里的纠正，都会自动落到这里。这一页只是让你看得见、改得了。",
                modifier = Modifier.padding(4.dp, 14.dp, 4.dp, 0.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(14.dp))
            RailSheet(fill = colors.railTranscript.copy(alpha = 0.09f), border = colors.railTranscript.copy(alpha = 0.25f), contentPadding = PaddingValues(16.dp, 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf("${c.hotwords}" to "热词", "${c.scenes}" to "启用场景", "${c.skills}" to "已连接模型", "${c.corrections}" to "本周纠正").forEach { (v, k) ->
                        Column(Modifier.weight(1f)) {
                            Text(v, style = TimestampStyle.copy(fontSize = 20.sp), fontWeight = FontWeight.SemiBold)
                            Text(k, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
        items(groups) { g ->
            Eyebrow(g.title, Modifier.padding(top = 18.dp, bottom = 8.dp))
            RailSheet(rail = g.bar, contentPadding = PaddingValues(0.dp)) {
                g.rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = colors.paperSunken)
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = r.go).padding(16.dp, 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(r.desc, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = colors.inkMuted)
                        }
                        Text(r.value, style = TimestampStyle, color = r.valueColor, maxLines = 1)
                        Text("›", color = colors.inkMuted)
                    }
                }
            }
        }
    }
}

// =============================================================================
// 首次引导 — read three sentences
// =============================================================================

private val ONBOARDING_SENTENCES = listOf(
    "谛听，从今天起帮我记住重要的事。",
    "小张周五之前出三种方案的对比表。",
    "李总提到数据导出不要单独收费。",
)

/** The server's industry list, mirrored so the picker renders before the network answers. */
internal val CALIBRATION_INDUSTRIES = listOf(
    "software" to "软件 / 互联网", "ai" to "AI / 算法", "finance" to "金融 / 投资", "medical" to "医疗 / 生物",
    "legal" to "法律 / 咨询", "edu" to "教育 / 科研", "manufacturing" to "制造 / 供应链",
    "realestate" to "地产 / 建筑", "media" to "媒体 / 内容", "other" to "其他",
)

/** Where the 口音校准 sub-flow is. */
enum class CalibPhase { PICK, GENERATING, READ, RECORDING, ANALYSING, RESULT }

data class CalibrationUi(
    val phase: CalibPhase = CalibPhase.PICK,
    val industries: Set<String> = emptySet(),
    val note: String = "",
    val terms: List<String> = emptyList(),
    val script: String = "",
    val recordSeconds: Int = 0,
    val heard: String = "",
    val pairs: List<com.diting.app.brain.BrainApi.CalibrationPair> = emptyList(),
    val saved: Int = 0,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val brainAvailable: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val settings: SettingsStore,
    private val hotwordDao: HotwordDao,
    private val brain: com.diting.app.brain.BrainApi,
    private val brainStore: com.diting.app.brain.BrainStore,
) : ViewModel() {

    val scenes: StateFlow<List<Scene>> = settings.scenes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scene.BuiltIns)

    private val _calib = MutableStateFlow(CalibrationUi(brainAvailable = brainStore.current.isLoggedIn))
    val calib: StateFlow<CalibrationUi> = _calib

    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: java.io.File? = null
    private var recordStartedAt = 0L
    private var ticker: kotlinx.coroutines.Job? = null

    fun toggleIndustry(id: String) = _calib.update { c ->
        val next = if (id in c.industries) c.industries - id else if (c.industries.size < 3) c.industries + id else c.industries
        c.copy(industries = next)
    }

    fun setNote(note: String) = _calib.update { it.copy(note = note) }

    /**
     * ① industries → terms the ASR mishears in that field, ② terms → a passage
     * that hides them in natural speech. Both come from the brain; nothing local
     * can know what "your" jargon is.
     */
    fun generateScript() = viewModelScope.launch {
        val c = _calib.value
        if (c.industries.isEmpty()) { _calib.update { it.copy(error = "先选一个领域") }; return@launch }
        _calib.update { it.copy(phase = CalibPhase.GENERATING, error = null) }
        runCatching {
            val terms = brain.calibrateTerms(c.industries.toList(), c.note)
            val script = brain.calibrateScript(terms, c.industries.toList())
            terms to script
        }.onSuccess { (terms, script) ->
            _calib.update { it.copy(phase = CalibPhase.READ, terms = terms, script = script) }
        }.onFailure { e ->
            _calib.update { it.copy(phase = CalibPhase.PICK, error = "大脑没生成朗读稿：${e.message}") }
        }
    }

    fun startRecording() {
        val file = java.io.File(appContext.cacheDir, "calibrate.m4a")
        runCatching {
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(appContext) else android.media.MediaRecorder()
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            recorder = r; recordFile = file; recordStartedAt = System.currentTimeMillis()
        }.onFailure { e ->
            _calib.update { it.copy(error = "麦克风打不开：${e.message}") }
            return
        }
        _calib.update { it.copy(phase = CalibPhase.RECORDING, recordSeconds = 0, error = null) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                _calib.update { it.copy(recordSeconds = ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt()) }
            }
        }
    }

    /** Stops, sends the raw audio, and asks the brain what it heard versus what was on screen. */
    fun stopAndAnalyse() = viewModelScope.launch {
        ticker?.cancel()
        val seconds = (System.currentTimeMillis() - recordStartedAt) / 1000.0
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        val file = recordFile ?: return@launch
        if (seconds < 3) { _calib.update { it.copy(phase = CalibPhase.READ, error = "读得太短了，整段念完再停") }; return@launch }
        _calib.update { it.copy(phase = CalibPhase.ANALYSING, error = null) }
        val c = _calib.value
        runCatching {
            val heard = brain.transcribeRaw(file, "m4a")
            if (heard.isBlank()) throw IllegalStateException("没有听到说话，离手机近一点再读一遍")
            val result = brain.calibrateDiff(c.script, heard, seconds, c.terms, c.industries.toList(), c.note)
            heard to result
        }.onSuccess { (heard, result) ->
            _calib.update { it.copy(phase = CalibPhase.RESULT, heard = heard, pairs = result.pairs, saved = result.saved, warnings = result.warnings) }
            // The passage's jargon is now known to the phone's ASR path too.
            c.terms.forEach { w -> hotwordDao.insertIgnoring(HotwordEntity(word = w, source = "ONBOARDING", createdAtEpochMs = System.currentTimeMillis())) }
        }.onFailure { e ->
            _calib.update { it.copy(phase = CalibPhase.READ, error = "校准没完成：${e.message}") }
        }
    }

    fun retryReading() = _calib.update { it.copy(phase = CalibPhase.READ, error = null) }

    fun finish(selectedSceneIds: Set<String>, hotwords: List<String>) = viewModelScope.launch {
        val all = settings.scenes.first()
        // Selected scenes come first; the recording screen only shows four chips,
        // and they should be the ones the user just chose.
        settings.setScenes(
            all.sortedByDescending { selectedSceneIds.contains(it.id) }
        )
        hotwords.filter { it.isNotBlank() }.forEach { word ->
            hotwordDao.insertIgnoring(
                HotwordEntity(
                    word = word.trim(),
                    source = "ONBOARDING",
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
        runCatching { brain.pushHotwords(hotwordDao.observeAll().first().filter { it.enabled }.map { it.word }) }
        settings.setOnboardingComplete(true)
        settings.addGrowthPoints(
            com.diting.domain.growth.GrowthEvent.COMPLETE_ONBOARDING.points
        )
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { recorder?.release() }
    }
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val calib by viewModel.calib.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current

    var step by remember { mutableIntStateOf(0) }
    var readIndex by remember { mutableIntStateOf(0) }
    var selectedScenes by remember { mutableStateOf(setOf<String>()) }
    var hotwordDraft by remember { mutableStateOf("") }
    var hotwords by remember { mutableStateOf(listOf<String>()) }

    val kicker = listOf("第一步 · 听感", "第二步 · 理解", "第三步 · 记忆")[step]
    val title = listOf("读一段话，小谛就听得懂你的说法。", "你常在哪些场合用它？", "先教几个专名。")[step]
    val sub = listOf(
        "大约一分钟。小谛按你的领域写一段稿子，你读一遍，它对照原文找出听错的地方，立刻记住。",
        "选 2–4 个。每个场景自带一套响应规则和提示词。",
        "产品名、同事名、英文缩写。转写模型会先认得它们。",
    )[step]

    fun finish() {
        viewModel.finish(selectedScenes, hotwords + hotwordDraft.split('、', '\n', ',', ' ').map { it.trim() })
        Toast.makeText(context, "已完成首次引导 +30 BP", Toast.LENGTH_SHORT).show()
        onDone()
    }

    Column(Modifier.fillMaxSize().padding(20.dp, 16.dp, 20.dp, 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) colors.railTranscript else colors.paperSunken)) }
            Text("${step + 1} / 3", modifier = Modifier.padding(start = 6.dp), style = TimestampStyle.copy(fontSize = 11.sp), color = colors.inkMuted)
        }
        Spacer(Modifier.height(22.dp))
        Text(kicker, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 31.sp), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), color = colors.inkMuted)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    Spacer(Modifier.height(18.dp))
                    if (!calib.brainAvailable) {
                        SheetCard(contentPadding = PaddingValues(18.dp)) {
                            Text("请朗读 · ${readIndex + 1} / ${ONBOARDING_SENTENCES.size}", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold, color = colors.inkMuted)
                            Spacer(Modifier.height(8.dp))
                            Text(ONBOARDING_SENTENCES[readIndex], style = MaterialTheme.typography.headlineSmall.copy(fontSize = 19.sp, lineHeight = 30.sp))
                            Spacer(Modifier.height(10.dp))
                            Text("没登录大脑账户时只能练习朗读，不会校准。登录后再来一次，小谛会真的学你的说法。", style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.railAction)
                            Spacer(Modifier.height(12.dp))
                            MintAction(if (readIndex < ONBOARDING_SENTENCES.lastIndex) "下一句" else "读完了", onClick = { if (readIndex < ONBOARDING_SENTENCES.lastIndex) readIndex++ else step = 1 })
                        }
                    } else when (calib.phase) {
                        CalibPhase.PICK, CalibPhase.GENERATING -> {
                            SheetCard(contentPadding = PaddingValues(18.dp)) {
                                Text("你在哪个领域 · 最多选 3 个", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold, color = colors.inkMuted)
                                Spacer(Modifier.height(10.dp))
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CALIBRATION_INDUSTRIES.forEach { (id, label) -> FilterChipPill(label, selected = id in calib.industries) { viewModel.toggleIndustry(id) } }
                                }
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = calib.note, onValueChange = viewModel::setNote, modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("常打交道的人、项目、工具，例如：李总、Fable5、飞书") }, minLines = 2, shape = RoundedCornerShape(12.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                MintAction(
                                    if (calib.phase == CalibPhase.GENERATING) "小谛在写朗读稿…" else "生成我的朗读稿",
                                    onClick = viewModel::generateScript, enabled = calib.phase == CalibPhase.PICK && calib.industries.isNotEmpty(),
                                )
                            }
                        }
                        CalibPhase.READ, CalibPhase.RECORDING, CalibPhase.ANALYSING -> {
                            SheetCard(contentPadding = PaddingValues(18.dp)) {
                                Text(
                                    when (calib.phase) { CalibPhase.RECORDING -> "正在录 · ${calib.recordSeconds} 秒"; CalibPhase.ANALYSING -> "小谛在对照原文…"; else -> "按住手机，自然地读完这段" },
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold,
                                    color = if (calib.phase == CalibPhase.RECORDING) colors.railBlocker else colors.inkMuted,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(calib.script, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp, lineHeight = 28.sp))
                                if (calib.terms.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text("埋在里面的词：" + calib.terms.take(8).joinToString("、"), style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted)
                                }
                                Spacer(Modifier.height(12.dp))
                                when (calib.phase) {
                                    CalibPhase.READ -> MintAction("开始读，同时录音", onClick = viewModel::startRecording)
                                    CalibPhase.RECORDING -> MintAction("读完了", onClick = viewModel::stopAndAnalyse, fill = colors.railBlocker)
                                    else -> MintAction("小谛在对照原文…", onClick = {}, enabled = false)
                                }
                            }
                        }
                        CalibPhase.RESULT -> {
                            SheetCard(contentPadding = PaddingValues(18.dp)) {
                                Text("校准结果", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp), fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (calib.pairs.isEmpty()) "这段小谛全听对了，没有需要记的混淆。"
                                    else "小谛记住了 ${calib.pairs.size} 处你的说法，以后听到就按右边写：",
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                )
                                calib.pairs.forEach { pr ->
                                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(pr.wrong, style = MaterialTheme.typography.bodyMedium, color = colors.railBlocker)
                                        Text("→", color = colors.inkMuted)
                                        Text(pr.right, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.railTranscript)
                                    }
                                }
                                calib.warnings.forEach { w -> Text("· $w", modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.railAction) }
                                Spacer(Modifier.height(8.dp))
                                Text("识别到的原文：${calib.heard.take(120)}", style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WhiteAction("再读一遍", onClick = viewModel::retryReading, modifier = Modifier.weight(1f))
                                    MintAction("下一步", onClick = { step = 1 }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    calib.error?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.railBlocker) }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("口音" to "已知原文对照实际听到，混淆对立刻生效", "热词" to "领域术语直接进热词表和大脑", "声纹" to "还没接上：说话人要在转写里手动标").forEach { (t, d) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(colors.railTranscript.copy(alpha = 0.09f)).padding(10.dp)) {
                                Text(t, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.railTranscript)
                                Text(d, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp))
                            }
                        }
                    }
                }

                1 -> {
                    Spacer(Modifier.height(16.dp))
                    scenes.chunked(2).forEach { pair ->
                        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { scene ->
                                val on = selectedScenes.contains(scene.id)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (on) colors.railTranscript.copy(alpha = 0.09f) else Color.White)
                                        .border(1.5.dp, if (on) colors.railTranscript else colors.paperSunken, RoundedCornerShape(16.dp))
                                        .clickable { selectedScenes = if (on) selectedScenes - scene.id else selectedScenes + scene.id }
                                        .padding(14.dp),
                                ) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape)
                                            .background(if (on) colors.railTranscript else Color.White)
                                            .border(1.5.dp, if (on) colors.railTranscript else colors.paperSunken, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                                    Column(Modifier.padding(end = 24.dp)) {
                                        Text(scene.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        Text(scene.cues.take(3).joinToString(" · ").ifBlank { "${scene.rules.size} 条响应规则" }, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted, maxLines = 2)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("每个场景自带一套「响应规则」和提示词，之后可在 我的 › 教小谛 里细调。录音时也能临时指定「这场按 X 处理」。", style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = colors.inkMuted, modifier = Modifier.padding(top = 4.dp))
                }

                else -> {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("通讯录" to "导入人名", "日历" to "导入会议名", "飞书 / 企微" to "稍后连接").forEachIndexed { i, (t, d) ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, colors.paperSunken, RoundedCornerShape(14.dp))
                                    .clickable { Toast.makeText(context, "$t 导入还没接入，先手动填", Toast.LENGTH_SHORT).show() }
                                    .alpha(if (i == 2) 0.6f else 1f).padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(t, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(d, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    RailSheet {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("首批热词", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${hotwords.size}", style = TimestampStyle, color = colors.inkMuted)
                        }
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            hotwords.forEach { w ->
                                Text(
                                    "$w ×",
                                    modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(colors.railTranscript.copy(alpha = 0.09f)).border(1.dp, colors.paperSunken, RoundedCornerShape(percent = 50))
                                        .clickable { hotwords = hotwords - w }.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium, color = colors.railTranscript,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = hotwordDraft,
                                onValueChange = { hotwordDraft = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("OSS、谛听、李总") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                            )
                            MintAction("加入", onClick = {
                                hotwords = (hotwords + hotwordDraft.split('、', '\n', ',', ' ').map { it.trim() }.filter { it.isNotBlank() }).distinct()
                                hotwordDraft = ""
                            }, modifier = Modifier.width(72.dp))
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (step < 2) "先跳过" else "稍后再教",
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, colors.paperSunken, RoundedCornerShape(14.dp))
                    .clickable { if (step < 2) step++ else finish() }.padding(18.dp, 14.dp),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.inkMuted,
            )
            MintAction(
                when (step) { 0 -> "跳过校准，下一步"; 1 -> "下一步"; else -> "完成，开始用" },
                onClick = { if (step < 2) step++ else finish() },
                modifier = Modifier.weight(1f),
                fill = if (step == 0 && calib.phase != CalibPhase.RESULT) colors.inkMuted else colors.railTranscript,
            )
        }
    }
}

// =============================================================================
// 热词与专名
// =============================================================================

@HiltViewModel
class HotwordsViewModel @Inject constructor(
    private val hotwordDao: HotwordDao,
    private val brain: com.diting.app.brain.BrainApi,
    private val brainStore: com.diting.app.brain.BrainStore,
) : ViewModel() {

    val hotwords: StateFlow<List<HotwordEntity>> = hotwordDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "已同步到大脑 N 个词" / an error, so the user can see the list is actually used. */
    private val _sync = MutableStateFlow<String?>(null)
    val sync: StateFlow<String?> = _sync

    /**
     * The brain biases its transcription with whatever it has been told; the
     * phone's list is the source of truth, pushed after every change.
     */
    fun syncToBrain() = viewModelScope.launch {
        if (!brainStore.current.isLoggedIn) { _sync.value = "未登录大脑，热词只在本机 ASR 生效"; return@launch }
        val words = hotwordDao.observeAll().first().filter { it.enabled }.map { it.word }
        runCatching { brain.pushHotwords(words) }
            .onSuccess { _sync.value = "已同步到大脑 ${words.size} 个词，下次转写生效" }
            .onFailure { _sync.value = "同步大脑失败：${it.message}" }
    }

    fun edit(id: Long, word: String, pronunciation: String?) = viewModelScope.launch {
        val row = hotwordDao.observeAll().first().firstOrNull { it.id == id } ?: return@launch
        if (word.isBlank()) return@launch
        hotwordDao.update(row.copy(word = word.trim(), pronunciation = pronunciation?.takeIf { it.isNotBlank() }))
        syncToBrain()
    }

    fun add(word: String, pronunciation: String?) = viewModelScope.launch {
        if (word.isBlank()) return@launch
        hotwordDao.insertIgnoring(
            HotwordEntity(
                word = word.trim(),
                pronunciation = pronunciation?.takeIf { it.isNotBlank() },
                source = "MANUAL",
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
        syncToBrain()
    }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        hotwordDao.setEnabled(id, enabled)
        syncToBrain()
    }

    fun delete(id: Long) = viewModelScope.launch { hotwordDao.delete(id) }
}

private val HotSources = listOf("全部" to null, "你纠正过" to "CORRECTION", "首次引导" to "ONBOARDING", "自动发现" to "DISCOVERED", "手动添加" to "MANUAL")

@Composable
fun HotwordsScreen(onBack: () -> Unit, viewModel: HotwordsViewModel = hiltViewModel()) {
    val hotwords by viewModel.hotwords.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<HotwordEntity?>(null) }
    LaunchedEffect(Unit) { viewModel.syncToBrain() }
    val shown = hotwords.filter { HotSources[filter].second?.let { src -> it.source == src } ?: true }
    val candidates = hotwords.filter { it.source == "DISCOVERED" && !it.enabled }

    fun add() {
        val parts = input.split('/', '／').map { it.trim() }
        viewModel.add(parts[0], parts.getOrNull(1))
        if (parts[0].isNotBlank()) Toast.makeText(context, "已加入「${parts[0]}」", Toast.LENGTH_SHORT).show()
        input = ""
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 110.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackCircle(onClick = onBack)
                    Text("热词与专名", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${hotwords.size}", style = TimestampStyle, color = colors.inkMuted)
                }
                sync?.let {
                    Text(it, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 0.dp), style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("已同步")) colors.railTranscript else colors.railAction)
                }
                Spacer(Modifier.height(14.dp))
                RailSheet(rail = colors.railAction, fill = colors.railAction.copy(alpha = 0.08f), border = colors.railAction.copy(alpha = 0.2f), contentPadding = PaddingValues(16.dp, 12.dp)) {
                    Text(if (candidates.isEmpty()) "热词会作为提示传给转写模型" else "小谛从会话里发现了新词", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), fontWeight = FontWeight.SemiBold, color = colors.railAction)
                    Text(
                        if (candidates.isEmpty()) "让它一开始就听对，而不是事后替换。在转写里改一个错字，改过的词会自动出现在这里。" else "反复出现、但词表里没有，转写可能不稳。点一下加入。",
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted, modifier = Modifier.padding(top = 2.dp),
                    )
                    if (candidates.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { c ->
                                Text(
                                    "+ ${c.word}",
                                    modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(Color.White).dashedPill(colors.railAction)
                                        .clickable { viewModel.setEnabled(c.id, true) }.padding(horizontal = 11.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.railAction,
                                )
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HotSources.forEachIndexed { i, (label, _) -> FilterChipPill(label, selected = filter == i) { filter = i } }
                }
                Spacer(Modifier.height(10.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, colors.paperSunken, RoundedCornerShape(16.dp))) {
                    if (shown.isEmpty()) {
                        Text("这一类还没有词。", modifier = Modifier.padding(16.dp, 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                    shown.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(color = colors.paperSunken)
                        Row(Modifier.fillMaxWidth().padding(16.dp, 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.weight(1f).clickable { editing = h }.alpha(if (h.enabled) 1f else 0.45f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(h.word, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                h.pronunciation?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted) }
                            }
                            val srcColor = if (h.source == "CORRECTION") colors.railAction else colors.inkMuted
                            Pill(sourceLabel(h.source), modifier = Modifier.clickable { editing = h }, color = srcColor, background = srcColor.copy(alpha = 0.12f))
                            PillSwitch(on = h.enabled, onToggle = { viewModel.setEnabled(h.id, it) })
                            Text("×", modifier = Modifier.clickable { viewModel.delete(h.id) }.padding(4.dp), style = MaterialTheme.typography.titleMedium, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).height(52.dp),
                placeholder = { Text("输入词，可用「词 / 读音」", style = MaterialTheme.typography.bodyMedium) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
            )
            MintAction("加入", onClick = { add() }, modifier = Modifier.width(76.dp))
        }
    }

    editing?.let { h ->
        var word by remember(h.id) { mutableStateOf(h.word) }
        var pron by remember(h.id) { mutableStateOf(h.pronunciation.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑热词") },
            text = {
                Column {
                    OutlinedTextField(value = word, onValueChange = { word = it }, label = { Text("词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pron, onValueChange = { pron = it }, label = { Text("读音 / 提示（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("来源：${sourceLabel(h.source)}", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.edit(h.id, word, pron)
                    editing = null
                    Toast.makeText(context, "已修改", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.delete(h.id); editing = null }) { Text("删除", color = colors.railBlocker) }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}

private fun Modifier.dashedPill(color: Color): Modifier = this.drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    drawRoundRect(color = color, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
}

private fun sourceLabel(source: String) = when (source) {
    "ONBOARDING" -> "首次引导"
    "CORRECTION" -> "你纠正过"
    "DISCOVERED" -> "自动发现"
    "IMPORTED" -> "通讯录导入"
    else -> "手动添加"
}

// =============================================================================
// 场景与响应规则
// =============================================================================

data class ScenesUiState(
    val scenes: List<Scene> = emptyList(),
    val globalRules: GlobalIgnoreRules = GlobalIgnoreRules(),
    val summaryPrompt: String? = null,
)

@HiltViewModel
class ScenesViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {

    val state: StateFlow<ScenesUiState> = combine(
        settings.scenes,
        settings.globalIgnoreRules,
        settings.summaryPrompt,
    ) { scenes, rules, prompt -> ScenesUiState(scenes, rules, prompt) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenesUiState())

    fun toggleRule(sceneId: String, intent: Intent, enabled: Boolean) = viewModelScope.launch {
        val scenes = settings.scenes.first().map { scene ->
            if (scene.id != sceneId) scene
            else scene.copy(
                rules = scene.rules.map { rule ->
                    if (rule.intent == intent) rule.copy(enabled = enabled) else rule
                }
            )
        }
        settings.setScenes(scenes)
    }

    fun setScenePrompt(sceneId: String, prompt: String) = viewModelScope.launch {
        val scenes = settings.scenes.first().map { scene ->
            if (scene.id == sceneId) scene.copy(customPrompt = prompt.ifBlank { null }) else scene
        }
        settings.setScenes(scenes)
    }

    fun setStopPhrase(phrase: String) = viewModelScope.launch {
        settings.setGlobalIgnoreRules(
            settings.globalIgnoreRules.first().copy(stopPhrase = phrase)
        )
    }

    fun setRedact(enabled: Boolean) = viewModelScope.launch {
        settings.setGlobalIgnoreRules(
            settings.globalIgnoreRules.first().copy(redactSensitiveNumbers = enabled)
        )
    }

    fun setSummaryPrompt(prompt: String) = viewModelScope.launch {
        settings.setSummaryPrompt(prompt)
    }
}

@Composable
private fun PillSwitch(on: Boolean, onToggle: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = ditingColors
    Box(
        Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(percent = 50))
            .background(if (on) colors.railTranscript else colors.paperSunken)
            .clickable(enabled = enabled) { onToggle(!on) }
            .alpha(if (enabled) 1f else 0.6f),
    ) {
        Box(Modifier.padding(3.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun ScenesScreen(onBack: () -> Unit, viewModel: ScenesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var current by remember { mutableIntStateOf(0) }
    var promptTab by remember { mutableIntStateOf(0) }
    val scene = state.scenes.getOrNull(current)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("场景与响应规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "响应规则 = 「听到什么句子 → 做什么」。它们挂在场景下，而不是全局开关；录音时小谛自动识别场景，也可手动指定。",
                modifier = Modifier.padding(4.dp, 12.dp, 4.dp, 0.dp),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = colors.inkMuted,
            )
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.scenes.forEachIndexed { i, sc ->
                    Box(Modifier.alpha(if (sc.rules.any { it.enabled }) 1f else 0.55f)) { FilterChipPill(sc.name, selected = i == current) { current = i } }
                }
            }
        }
        if (scene != null) {
            item {
                Spacer(Modifier.height(12.dp))
                RailSheet {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(scene.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold)
                            Text(
                                (if (scene.speakerSeparation) "说话人分离 · " else "") + "${scene.rules.count { it.enabled }} / ${scene.rules.size} 条规则启用",
                                style = MaterialTheme.typography.labelSmall, color = colors.inkMuted,
                            )
                        }
                        PillSwitch(on = scene.rules.any { it.enabled }, onToggle = { on -> scene.rules.forEach { viewModel.toggleRule(scene.id, it.intent, on) } })
                    }
                    Eyebrow("识别线索", Modifier.padding(top = 14.dp, bottom = 6.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (scene.cues.isEmpty()) Text("没有线索词，只能手动指定。", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                        scene.cues.forEach { Pill(it, color = colors.inkPanel, background = colors.paperRoot) }
                    }
                }
                Eyebrow("响应规则 · 听到 → 做", Modifier.padding(top = 16.dp, bottom = 8.dp))
                RailSheet(rail = colors.railAction, contentPadding = PaddingValues(0.dp)) {
                    scene.rules.forEachIndexed { i, rule ->
                        if (i > 0) HorizontalDivider(color = colors.paperSunken)
                        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rule.intent.chineseLabel()) }
                                        withStyle(SpanStyle(color = colors.inkMuted)) { append("  →  ") }
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (rule.action == ResponseAction.IGNORE) colors.inkMuted else colors.railAction)) { append(rule.action.chineseLabel()) }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(rule.intent.example(), style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted)
                            }
                            PillSwitch(on = rule.enabled, onToggle = { viewModel.toggleRule(scene.id, rule.intent, it) })
                        }
                    }
                }
            }
        }
        item {
            Eyebrow("忽略规则 · 全局生效", Modifier.padding(top = 16.dp, bottom = 8.dp))
            RailSheet(contentPadding = PaddingValues(0.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("口令「${state.globalRules.stopPhrase}」", style = MaterialTheme.typography.bodyMedium)
                        Text("说出后到下一句「好了」之间不落库、不转写", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                    PillSwitch(on = true, onToggle = {}, enabled = false)
                }
                HorizontalDivider(color = colors.paperSunken)
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("私人时段", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            state.globalRules.privateHours.takeIf { it.isNotEmpty() }?.joinToString("，") { "%02d:%02d–%02d:%02d".format(it.startMinute / 60, it.startMinute % 60, it.endMinute / 60, it.endMinute % 60) }?.plus(" 只本地缓存，不上云不理解")
                                ?: "未设置 · 在「我的」里打开安静时段",
                            style = MaterialTheme.typography.labelSmall, color = colors.inkMuted,
                        )
                    }
                    MintLink("编辑", onClick = { Toast.makeText(context, "在「我的 › 安静时段」调整", Toast.LENGTH_SHORT).show() })
                }
                HorizontalDivider(color = colors.paperSunken)
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("敏感信息打码", style = MaterialTheme.typography.bodyMedium)
                        Text("手机号 / 身份证 / 银行卡在转写与工件中脱敏", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                    PillSwitch(on = state.globalRules.redactSensitiveNumbers, onToggle = viewModel::setRedact)
                }
            }
        }
        item {
            Eyebrow("提示词 · ${scene?.name ?: "纪要"}", Modifier.padding(top = 16.dp, bottom = 8.dp))
            val tabs = listOf("场景提示词", "纪要提示词")
            var draft by remember(scene?.id, promptTab, state.summaryPrompt) {
                mutableStateOf(if (promptTab == 0) scene?.customPrompt.orEmpty() else state.summaryPrompt.orEmpty())
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, colors.paperSunken, RoundedCornerShape(16.dp))) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    tabs.forEachIndexed { i, label ->
                        Column(Modifier.width(IntrinsicSize.Max).clickable { promptTab = i }) {
                            Text(label, modifier = Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (i == promptTab) colors.inkPanel else colors.inkMuted)
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (i == promptTab) colors.railTranscript else Color.Transparent))
                        }
                    }
                }
                HorizontalDivider(color = colors.paperSunken)
                androidx.compose.foundation.text.BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(16.dp, 12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 22.sp, color = colors.inkPanel),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(if (promptTab == 0) "追加给这个场景的要求，例如：客户说的价格一律标为待确认。" else "追加在小谛的基础提示词后面，不会替换它。", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 22.sp), color = colors.inkMuted)
                        inner()
                    },
                )
                Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("可用变量 {scene} {speakers} {hotwords}", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    MintLink("保存", onClick = {
                        if (promptTab == 0 && scene != null) viewModel.setScenePrompt(scene.id, draft) else viewModel.setSummaryPrompt(draft)
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

private fun Intent.example(): String = when (this) {
    Intent.COMMITMENT -> "「我周五之前给你」"
    Intent.IMPERATIVE -> "「帮我整理一份对比表」"
    Intent.DECISION -> "「那就这么定」"
    Intent.RECURRING_ASK -> "同一件事第二次被提起"
    Intent.RISK -> "「这个可能来不及」"
    Intent.SMALL_TALK -> "寒暄、闲聊"
    Intent.FOREIGN_LANGUAGE -> "对方切换到英文"
    Intent.TECH_DECISION -> "「接口改成异步」"
}

fun Intent.chineseLabel(): String = when (this) {
    Intent.COMMITMENT -> "承诺句"
    Intent.IMPERATIVE -> "祈使句"
    Intent.DECISION -> "决策"
    Intent.RECURRING_ASK -> "反复诉求"
    Intent.RISK -> "风险"
    Intent.SMALL_TALK -> "闲聊"
    Intent.FOREIGN_LANGUAGE -> "外语"
    Intent.TECH_DECISION -> "技术决策"
}

fun ResponseAction.chineseLabel(): String = when (this) {
    ResponseAction.CREATE_TODO -> "建待办"
    ResponseAction.CREATE_TASK -> "建任务"
    ResponseAction.RECORD_IN_MINUTES -> "写进纪要"
    ResponseAction.TRACK_AS_OBSERVATION -> "记为观察"
    ResponseAction.TRANSLATE -> "翻译"
    // "（待确认）" is part of the label, not a footnote: this rule never
    // dispatches on its own, it only proposes a task at gate ①.
    ResponseAction.DISPATCH_TO_DEV_AGENT -> "派给开发 Agent（待确认）"
    ResponseAction.IGNORE -> "忽略"
}

// =============================================================================
// 模型与 Skill
// =============================================================================

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {

    val settingsState: StateFlow<AiSettings> = settings.aiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings())

    /** @return false when the input was rejected, so the screen can say so. */
    fun saveAsr(baseUrl: String, model: String, key: String): Boolean {
        if (baseUrl.isBlank() || model.isBlank()) return false
        viewModelScope.launch {
            settings.setTranscriptionEndpoint(
                AsrEndpoint.selfHosted(baseUrl.trim(), model.trim(), key.trim())
            )
        }
        return true
    }

    fun saveUnderstanding(vendor: AiVendor, baseUrl: String, model: String, key: String): Boolean {
        if (baseUrl.isBlank() || model.isBlank()) return false
        viewModelScope.launch {
            settings.setUnderstandingEndpoint(
                AiEndpoint(vendor, baseUrl.trim(), model.trim(), key.trim())
            )
        }
        return true
    }

    fun saveAgent(vendor: AiVendor, baseUrl: String, model: String, key: String): Boolean {
        if (baseUrl.isBlank() || model.isBlank()) return false
        viewModelScope.launch {
            settings.setAgentEndpoint(
                AiEndpoint(vendor, baseUrl.trim(), model.trim(), key.trim())
            )
        }
        return true
    }
}

@Composable
fun ModelsScreen(onBack: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val ai by viewModel.settingsState.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var editing by remember { mutableIntStateOf(-1) }
    // A save that silently succeeds reads as a save that did nothing.
    fun saved(ok: Boolean) = Toast.makeText(context, if (ok) "已保存" else "地址和模型名不能为空", Toast.LENGTH_SHORT).show()

    data class Layer(val name: String, val hint: String, val configured: String?, val accent: Color)
    val layers = listOf(
        Layer("转写 · ASR", "每分钟音频都要过", ai.transcription?.model, colors.railTranscript),
        Layer("理解 · 总结与待办", "只在转写完成后跑一次", ai.understanding?.let { "${it.vendor.displayName} · ${it.model}" }, colors.railInsight),
        Layer("Agent · 任务执行", "不填沿用理解层", ai.agent?.let { "${it.vendor.displayName} · ${it.model}" }, colors.railAction),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("模型与 Skill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Eyebrow("模型 · 三层各选其一", Modifier.padding(top = 16.dp, bottom = 8.dp))
        }
        itemsIndexed(layers) { i, layer ->
            RailSheet(modifier = Modifier.padding(bottom = 8.dp), contentPadding = PaddingValues(16.dp, 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(layer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(layer.hint, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options = if (i == 0) listOf("谛听大脑" to "登录即用", "自建 ASR" to (layer.configured ?: "未配置"))
                    else listOf("谛听大脑" to "登录即用", "自带 Key" to (layer.configured ?: "未配置"))
                    options.forEachIndexed { j, (name, tag) ->
                        val selected = if (j == 0) layer.configured == null else layer.configured != null
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) layer.accent.copy(alpha = 0.09f) else Color.White)
                                .border(1.5.dp, if (selected) layer.accent else colors.paperSunken, RoundedCornerShape(10.dp))
                                .clickable { if (j == 1) editing = if (editing == i) -1 else i else Toast.makeText(context, "登录大脑账户后，这一层自动走大脑", Toast.LENGTH_SHORT).show() }
                                .padding(10.dp, 8.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), fontWeight = FontWeight.SemiBold)
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (editing == i) {
                    Spacer(Modifier.height(10.dp))
                    when (i) {
                        0 -> AsrEndpointCard(current = ai.transcription, onSave = { url, model, key -> saved(viewModel.saveAsr(url, model, key)) })
                        1 -> LlmEndpointCard(title = "理解", accent = colors.railInsight, current = ai.understanding, onSave = { v, url, model, key -> saved(viewModel.saveUnderstanding(v, url, model, key)) })
                        else -> LlmEndpointCard(title = "Agent", accent = colors.railAction, current = ai.agent, hint = "不填就沿用「理解」那一层的模型。", onSave = { v, url, model, key -> saved(viewModel.saveAgent(v, url, model, key)) })
                    }
                }
            }
        }
        item {
            Eyebrow("Skill 注册 · 小谛能动手做的事", Modifier.padding(top = 16.dp, bottom = 8.dp))
            data class Skill(val name: String, val desc: String, val color: Color, val on: Boolean, val perm: String)
            val skills = listOf(
                Skill("整理纪要", "把转写整理成要点、决策、待办", colors.railInsight, ai.understanding != null, "只读原声"),
                Skill("起草文档 / 邮件", "从引用片段生成草稿，不发送", colors.railTranscript, ai.agent != null || ai.understanding != null, "只读原声"),
                Skill("日历与提醒", "采用后写入系统日历", colors.railAction, false, "写日历"),
                Skill("飞书 / 邮件发送", "采用并二次确认后对外发送", colors.railBlocker, false, "对外写入"),
            )
            RailSheet(rail = colors.railAction, contentPadding = PaddingValues(0.dp)) {
                skills.forEachIndexed { i, sk ->
                    if (i > 0) HorizontalDivider(color = colors.paperSunken)
                    Column(Modifier.padding(16.dp, 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IconSquare(sk.color, Modifier.size(30.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sk.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(sk.desc, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                            }
                            Pill(if (sk.on) "已启用" else "未接入", color = if (sk.on) colors.railTranscript else colors.inkMuted, background = (if (sk.on) colors.railTranscript else colors.inkMuted).copy(alpha = 0.12f))
                        }
                        if (sk.on) {
                            Row(Modifier.padding(start = 40.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill("权限：${sk.perm}", color = colors.inkPanel, background = colors.paperRoot)
                                Pill("执行前需确认", color = colors.railAction, background = colors.railAction.copy(alpha = 0.12f))
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.paperSunken)
                Row(Modifier.fillMaxWidth().clickable { Toast.makeText(context, "MCP 接入还没开放", Toast.LENGTH_SHORT).show() }.padding(16.dp, 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).dashedPill(colors.inkMuted))
                    Column {
                        Text("添加 MCP 服务器", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("粘贴地址即可，新 Skill 默认「执行前需确认」", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            RailSheet(rail = colors.railAction, fill = colors.railAction.copy(alpha = 0.08f), border = Color.Transparent, contentPadding = PaddingValues(14.dp, 12.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.inkPanel)) { append("底线不可关：") }
                        append("发送 / 写入 / 下单类 Skill 永远走「待确认结果」闸门，即使关掉「执行前需确认」，也只是跳过目标确认那一步。")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 19.sp), color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun AsrEndpointCard(current: AsrEndpoint?, onSave: (String, String, String) -> Unit) {
    val colors = ditingColors
    var baseUrl by remember(current) { mutableStateOf(current?.baseUrl.orEmpty()) }
    var model by remember(current) { mutableStateOf(current?.model ?: "whisper-1") }
    var key by remember(current) { mutableStateOf(current?.apiKey.orEmpty()) }

    Column {
        Text(
            "需要支持 POST /audio/transcriptions 的服务（自建 Whisper / FunASR 都可以）。请求会带上 verbose_json 以获取逐句时间戳 —— 没有时间戳就没有「回到原声」。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(10.dp))
        EndpointFields(
            baseUrl = baseUrl,
            onBaseUrl = { baseUrl = it },
            model = model,
            onModel = { model = it },
            apiKey = key,
            onApiKey = { key = it },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MintAction("保存", onClick = { onSave(baseUrl, model, key) }, modifier = Modifier.width(96.dp))
            if (current != null) Pill("已配置", color = colors.railTranscript)
        }
    }
}

@Composable
private fun LlmEndpointCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    current: AiEndpoint?,
    hint: String? = null,
    onSave: (AiVendor, String, String, String) -> Unit,
) {
    val colors = ditingColors
    var vendor by remember(current) { mutableStateOf(current?.vendor ?: AiVendor.QWEN) }
    var baseUrl by remember(current, vendor) {
        mutableStateOf(current?.baseUrl ?: defaultBaseUrl(vendor))
    }
    var model by remember(current) { mutableStateOf(current?.model.orEmpty()) }
    var key by remember(current) { mutableStateOf(current?.apiKey.orEmpty()) }

    Column {
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiVendor.entries.forEach { option ->
                FilterChipPill(option.displayName, selected = vendor == option) {
                    vendor = option
                    baseUrl = defaultBaseUrl(option)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (vendor == AiVendor.DOUBAO) {
            Text(
                // Ark addresses models by endpoint id, which is not something we
                // can default to a sensible value.
                "豆包通常要填控制台里的推理接入点 ID（ep- 开头），不是模型名。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(8.dp))
        }

        EndpointFields(
            baseUrl = baseUrl,
            onBaseUrl = { baseUrl = it },
            model = model,
            onModel = { model = it },
            apiKey = key,
            onApiKey = { key = it },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MintAction("保存", onClick = { onSave(vendor, baseUrl, model, key) }, modifier = Modifier.width(96.dp))
            if (current != null) Pill("已配置", color = accent)
        }
    }
}

@Composable
private fun EndpointFields(
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
) {
    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrl,
        label = { Text("Base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = model,
        onValueChange = onModel,
        label = { Text("模型") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKey,
        label = { Text("API Key") },
        singleLine = true,
        // Encrypted at rest; masking here only guards against shoulder-surfing.
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun defaultBaseUrl(vendor: AiVendor) = when (vendor) {
    AiVendor.QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
    AiVendor.DOUBAO -> "https://ark.cn-beijing.volces.com/api/v3"
    AiVendor.OPENAI_COMPATIBLE -> ""
}

// =============================================================================
// 目的地与通道
// =============================================================================

@Composable
fun DestinationsScreen(onBack: () -> Unit) {
    val colors = ditingColors
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<Destination?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("目的地与通道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "你点「采用」之后，东西去哪。按工件类型走默认目的地，任务详情里可临时改。",
                modifier = Modifier.padding(4.dp, 14.dp, 4.dp, 0.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), color = colors.inkMuted,
            )
            Eyebrow("默认路由", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RailSheet(contentPadding = PaddingValues(0.dp)) {
                ArtifactKind.entries.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = colors.paperSunken)
                    Row(Modifier.fillMaxWidth().padding(16.dp, 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(kind.chineseLabel(), modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                        Text("→", color = colors.inkMuted)
                        Text(kind.defaultDestination.chineseLabel(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        MintLink("改", onClick = { Toast.makeText(context, "在任务详情里可临时改；默认路由稍后开放", Toast.LENGTH_SHORT).show() })
                    }
                }
            }
            Eyebrow("目的地", Modifier.padding(top = 18.dp, bottom = 8.dp))
        }
        items(Destination.entries.filter { it != Destination.NONE }) { d ->
            val bar = if (d.isExternalWrite) colors.railAction else colors.railInsight
            val open = expanded == d
            RailSheet(rail = bar, modifier = Modifier.padding(bottom = 10.dp), contentPadding = PaddingValues(0.dp)) {
                Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else d }.padding(16.dp, 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(bar), contentAlignment = Alignment.Center) {
                        Text(d.chineseLabel().take(1), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.chineseLabel(), style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold)
                        Text(if (d.isExternalWrite) "对外写入 · 采用后二次确认" else "不对外 · 采用即生效", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                    Pill("未连接", color = colors.inkMuted, background = colors.paperRoot)
                }
                if (open) {
                    HorizontalDivider(color = colors.paperSunken)
                    Column(Modifier.fillMaxWidth().background(colors.paperCard).padding(16.dp, 6.dp, 16.dp, 12.dp)) {
                        listOf("状态" to "未连接", "副作用" to d.sideEffectWarning()).forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(k, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colors.inkMuted)
                                Text(v, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp), textAlign = TextAlign.End)
                            }
                            HorizontalDivider(color = colors.paperSunken)
                        }
                        Text(
                            "连接需要授权或令牌，本版本还没接入。采用的工件会留在谛听，不会真的发送。",
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.railAction,
                        )
                    }
                }
            }
        }
        item {
            RailSheet(rail = colors.railAction, fill = colors.railAction.copy(alpha = 0.08f), border = Color.Transparent, contentPadding = PaddingValues(14.dp, 12.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.inkPanel)) { append("不变的底线：") }
                        append("任何目的地的写入都发生在你点「采用」之后；外部 Agent 的执行结果必须回到谛听的「待确认结果」，不能直接对外。")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 19.sp), color = colors.inkMuted,
                )
            }
        }
    }
}

// =============================================================================
// 记忆与遗忘
// =============================================================================

data class MemoryUiState(
    val liveNodes: Int = 0,
    val archivedNodes: Int = 0,
    val policy: RetentionPolicy = RetentionPolicy.Default,
    /** 四道闸 counters: enabled rules, single-mention nodes, proposed insights, archived. */
    val rulesEnabled: Int = 0,
    val singles: Int = 0,
    val proposed: Int = 0,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memory: MemoryRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    val state: StateFlow<MemoryUiState> = combine(
        memory.observeLiveNodes(),
        memory.observeArchivedCount(),
        settings.retentionPolicy,
        settings.scenes,
        memory.observeInsights(),
    ) { nodes, archived, policy, scenes, insights ->
        MemoryUiState(
            liveNodes = nodes.size,
            archivedNodes = archived,
            policy = policy,
            rulesEnabled = scenes.sumOf { sc -> sc.rules.count { it.enabled } },
            singles = nodes.count { it.mentionCount < 2 },
            proposed = insights.count { it.confidence == com.diting.domain.memory.NodeConfidence.PROPOSED && !it.dismissed },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun useMinimalPolicy(minimal: Boolean) = viewModelScope.launch {
        settings.setRetentionPolicy(
            if (minimal) RetentionPolicy.Minimal else RetentionPolicy.Default
        )
    }

    fun forgetEverything() = viewModelScope.launch { memory.forgetEverything() }
}

@Composable
fun MemoryScreen(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    var confirmingForget by remember { mutableStateOf(false) }
    val minimal = state.policy == RetentionPolicy.Minimal
    val total = state.liveNodes + state.archivedNodes

    data class Tier(val name: String, val size: String, val desc: String, val color: Color, val opts: List<Pair<String, Boolean>>)
    val tiers = listOf(
        Tier("原声音频", "${state.policy.audioLifetime.inWholeDays} 天", "过期只删音频，转写与引用保留", colors.railTranscript, listOf("7 天" to minimal, "30 天" to !minimal)),
        Tier("转写文本", "${state.policy.transcriptLifetime.inWholeDays} 天", "未被引用的段落 ${state.policy.transcriptCompressionAge.inWholeDays} 天后压缩为摘要", colors.railInsight, listOf("90 天" to minimal, "1 年" to !minimal)),
        Tier("记忆图谱", "永久", "${state.policy.graphColdAge.inWholeDays} 天不出现的冷节点归档：可搜索，不再触发提醒", colors.railAction, listOf("60 天归档" to minimal, "180 天归档" to !minimal)),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("记忆与遗忘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                buildAnnotatedString {
                    append("记住得越少，提醒才越准。三层各有寿命；唯一例外：")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.inkPanel)) { append("被引用过的，永不过期") }
                    append("。")
                },
                modifier = Modifier.padding(4.dp, 14.dp, 4.dp, 0.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), color = colors.inkMuted,
            )
            Eyebrow("三层存储", Modifier.padding(top = 18.dp, bottom = 8.dp))
        }
        items(tiers) { t ->
            RailSheet(rail = t.color, modifier = Modifier.padding(bottom = 8.dp), contentPadding = PaddingValues(16.dp, 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(t.size, style = TimestampStyle, color = colors.inkMuted)
                }
                Text(t.desc, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.opts.forEachIndexed { i, (label, on) -> FilterChipPill(label, selected = on) { viewModel.useMinimalPolicy(i == 0) } }
                }
            }
        }
        item {
            RailSheet(contentPadding = PaddingValues(16.dp, 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("被引用即永久", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("任务 / 洞察 / 报告引用过的原声片段不参与过期", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                    PillSwitch(on = true, onToggle = {}, enabled = false)
                }
            }
            Eyebrow("图谱温度 · $total 个节点", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RailSheet {
                val live = state.liveNodes.coerceAtLeast(0); val cold = state.archivedNodes.coerceAtLeast(0)
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.weight((live.coerceAtLeast(1)).toFloat()).fillMaxHeight().background(colors.railTranscript))
                    Box(Modifier.weight((cold.coerceAtLeast(1)).toFloat()).fillMaxHeight().background(Color(0xFFD9D9D2)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.railTranscript)) { append("$live") }; append(" 活跃") }, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$cold") }; append(" 冷 · 已归档") }, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
                Text("冷节点不再触发主动提醒，但仍可搜索。", style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Eyebrow("噪音过滤 · 四道闸", Modifier.padding(top = 18.dp, bottom = 8.dp))
            RailSheet(contentPadding = PaddingValues(0.dp)) {
                listOf(
                    Triple("场景规则", "不在当前场景响应规则里的句子，不落图谱", "${state.rulesEnabled} 条启用"),
                    Triple("重复阈值", "同一件事被提两次以上，才成为观察", "${state.singles} 个只提过一次"),
                    Triple("待确认", "小谛的推断先进「待确认」，你点头才是事实", "${state.proposed} 条待你拍板"),
                    Triple("冷却归档", "长期不出现的节点归档，不再主动提醒", "${state.archivedNodes} 个已归档"),
                ).forEachIndexed { i, (name, desc, stat) ->
                    if (i > 0) HorizontalDivider(color = colors.paperSunken)
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${i + 1}", modifier = Modifier.width(18.dp), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold, color = colors.railInsight)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(desc, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp), color = colors.inkMuted)
                        }
                        Text(stat, style = TimestampStyle, color = colors.inkPanel, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WhiteAction("导出全部记忆", onClick = { Toast.makeText(context, "导出还没接入", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f))
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, colors.railBlocker, RoundedCornerShape(14.dp)).clickable { confirmingForget = true }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("全部遗忘", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.railBlocker) }
            }
        }
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("确认清空记忆图谱？") },
            text = { Text("人物、承诺、决策、反复诉求都会被删除，无法撤销。录音和转写会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetEverything()
                    confirmingForget = false
                    Toast.makeText(context, "已清空记忆图谱", Toast.LENGTH_SHORT).show()
                }) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { confirmingForget = false }) { Text("取消") } },
        )
    }
}
