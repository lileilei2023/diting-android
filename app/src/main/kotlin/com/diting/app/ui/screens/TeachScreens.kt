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

@Composable
fun TeachScreen(
    onOpenOnboarding: () -> Unit,
    onOpenHotwords: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val colors = ditingColors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("教小谛", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                // The framing matters: this is not a settings maze, and most users
                // will never come here — they teach by correcting a word instead.
                "配置分三个时机：首次教一分钟，随手教（改一个错字就是配置），深度教在下面四层。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            LayerCard(
                layer = "听感",
                accent = colors.railTranscript,
                entries = listOf(
                    "声纹与口音 · 朗读 3 句" to onOpenOnboarding,
                    "热词与专名" to onOpenHotwords,
                ),
            )
        }

        item {
            LayerCard(
                layer = "理解",
                accent = colors.railInsight,
                entries = listOf("场景与响应规则" to onOpenScenes),
            )
        }

        item {
            LayerCard(
                layer = "行动",
                accent = colors.railAction,
                entries = listOf(
                    "模型与 Skill" to onOpenModels,
                    "目的地与通道" to onOpenDestinations,
                ),
            )
        }

        item {
            LayerCard(
                layer = "记忆",
                accent = colors.railBlocker,
                entries = listOf("记忆与遗忘" to onOpenMemory),
            )
        }
    }
}

@Composable
private fun LayerCard(
    layer: String,
    accent: androidx.compose.ui.graphics.Color,
    entries: List<Pair<String, () -> Unit>>,
) {
    RailCard(rail = accent) {
        Text(
            layer,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        entries.forEachIndexed { index, (label, action) ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = action) { Text("›") }
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

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val hotwordDao: HotwordDao,
) : ViewModel() {

    val scenes: StateFlow<List<Scene>> = settings.scenes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scene.BuiltIns)

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
        settings.setOnboardingComplete(true)
        settings.addGrowthPoints(
            com.diting.domain.growth.GrowthEvent.COMPLETE_ONBOARDING.points
        )
    }
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val colors = ditingColors

    var step by remember { mutableStateOf(0) }
    var selectedScenes by remember { mutableStateOf(setOf<String>()) }
    var hotwordDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("首次引导", style = MaterialTheme.typography.headlineMedium)
        Text(
            "大约一分钟。朗读会同时完成三件事：把「说话人 A」绑成你、校准口音、收下第一批热词。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        when (step) {
            0 -> {
                RailCard(rail = colors.railTranscript) {
                    Text("朗读这三句", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ONBOARDING_SENTENCES.forEachIndexed { index, sentence ->
                        Text(
                            "${index + 1}. $sentence",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // Skipping is allowed, but the consequence has to be stated
                        // rather than discovered later when diarisation is wrong.
                        "可以跳过，但跳过之后「说话人 A = 你」不成立，转写里分不出谁在说话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { step = 1 }) { Text("读完了") }
                    OutlinedButton(onClick = { step = 1 }) { Text("先跳过") }
                }
            }

            1 -> {
                RailCard(rail = colors.railInsight) {
                    Text("常用场景选 2–4 个", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        scenes.forEach { scene ->
                            FilterChip(
                                selected = selectedScenes.contains(scene.id),
                                onClick = {
                                    selectedScenes = if (selectedScenes.contains(scene.id)) {
                                        selectedScenes - scene.id
                                    } else {
                                        selectedScenes + scene.id
                                    }
                                },
                                label = { Text(scene.name, maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
                Button(onClick = { step = 2 }) { Text("下一步") }
            }

            else -> {
                RailCard(rail = colors.railTranscript) {
                    Text("先教几个专名", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "产品名、同事名、英文缩写。用顿号或换行分隔。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hotwordDraft,
                        onValueChange = { hotwordDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("OSS、谛听、李总") },
                    )
                }
                Button(
                    onClick = {
                        viewModel.finish(
                            selectedScenes,
                            hotwordDraft.split('、', '\n', ',').map { it.trim() },
                        )
                        onDone()
                    }
                ) { Text("完成") }
            }
        }
    }
}

// =============================================================================
// 热词与专名
// =============================================================================

@HiltViewModel
class HotwordsViewModel @Inject constructor(
    private val hotwordDao: HotwordDao,
) : ViewModel() {

    val hotwords: StateFlow<List<HotwordEntity>> = hotwordDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        hotwordDao.setEnabled(id, enabled)
    }

    fun delete(id: Long) = viewModelScope.launch { hotwordDao.delete(id) }
}

@Composable
fun HotwordsScreen(viewModel: HotwordsViewModel = hiltViewModel()) {
    val hotwords by viewModel.hotwords.collectAsStateWithLifecycle()
    val colors = ditingColors
    var word by remember { mutableStateOf("") }
    var pronunciation by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("热词与专名", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                // These are passed to the recogniser as a bias, not applied as a
                // find-and-replace afterwards — that is what makes them work.
                "热词会作为提示传给转写模型，让它一开始就听对，而不是事后替换。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            RailCard(rail = colors.railTranscript) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("词") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pronunciation,
                    onValueChange = { pronunciation = it },
                    label = { Text("读音（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.add(word, pronunciation)
                        word = ""
                        pronunciation = ""
                    }
                ) { Text("添加") }
            }
        }

        if (hotwords.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有热词",
                    hint = "在转写里改一个错字，改过的词会自动出现在这里。",
                )
            }
        }

        items(hotwords, key = { it.id }) { hotword ->
            RailCard(rail = colors.railTranscript) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(hotword.word, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(sourceLabel(hotword.source))
                            hotword.pronunciation?.let { Pill(it) }
                        }
                    }
                    Switch(
                        checked = hotword.enabled,
                        onCheckedChange = { viewModel.setEnabled(hotword.id, it) },
                    )
                    TextButton(onClick = { viewModel.delete(hotword.id) }) { Text("删除") }
                }
            }
        }
    }
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
fun ScenesScreen(viewModel: ScenesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    var editingPrompt by remember { mutableStateOf<Scene?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("场景与响应规则", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                // The key restructuring from the design chat.
                "响应规则挂在场景下，不是全局开关。同一句话在「客户通话」里要建待办，在「家人闲聊」里应该直接忽略。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        items(state.scenes, key = { it.id }) { scene ->
            RailCard(rail = colors.railInsight) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium)
                    if (scene.speakerSeparation) Pill("说话人分离")
                }
                Spacer(Modifier.height(8.dp))
                scene.rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${rule.intent.chineseLabel()} → ${rule.action.chineseLabel()}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = {
                                viewModel.toggleRule(scene.id, rule.intent, it)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { editingPrompt = scene }) {
                    Text(if (scene.customPrompt != null) "编辑场景提示词" else "添加场景提示词")
                }
            }
        }

        item {
            RailCard(rail = colors.railBlocker) {
                Text("全局忽略规则", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "全局只留忽略规则，其他都归场景。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.globalRules.stopPhrase,
                    onValueChange = viewModel::setStopPhrase,
                    label = { Text("停录口令") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    title = "敏感数字打码",
                    subtitle = "卡号、身份证、手机号在转写里替换成掩码。",
                    checked = state.globalRules.redactSensitiveNumbers,
                    onCheckedChange = viewModel::setRedact,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "私人时段：${
                        state.globalRules.privateHours.takeIf { it.isNotEmpty() }
                            ?.joinToString("，") { "${it.startMinute / 60}:00–${it.endMinute / 60}:00" }
                            ?: "未设置"
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }
        }

        item {
            RailCard(rail = colors.railInsight) {
                Text("纪要提示词", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "追加在小谛的基础提示词后面，不会替换它 —— 引用原声的规则始终有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                var prompt by remember(state.summaryPrompt) {
                    mutableStateOf(state.summaryPrompt.orEmpty())
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.setSummaryPrompt(prompt) }) { Text("保存") }
            }
        }
    }

    editingPrompt?.let { scene ->
        var draft by remember(scene.id) { mutableStateOf(scene.customPrompt.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingPrompt = null },
            title = { Text("${scene.name} 的提示词") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setScenePrompt(scene.id, draft)
                    editingPrompt = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingPrompt = null }) { Text("取消") }
            },
        )
    }
}

fun Intent.chineseLabel(): String = when (this) {
    Intent.COMMITMENT -> "承诺句"
    Intent.IMPERATIVE -> "祈使句"
    Intent.DECISION -> "决策"
    Intent.RECURRING_ASK -> "反复诉求"
    Intent.RISK -> "风险"
    Intent.SMALL_TALK -> "闲聊"
    Intent.FOREIGN_LANGUAGE -> "外语"
}

fun ResponseAction.chineseLabel(): String = when (this) {
    ResponseAction.CREATE_TODO -> "建待办"
    ResponseAction.CREATE_TASK -> "建任务"
    ResponseAction.RECORD_IN_MINUTES -> "写进纪要"
    ResponseAction.TRACK_AS_OBSERVATION -> "记为观察"
    ResponseAction.TRANSLATE -> "翻译"
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

    fun saveAsr(baseUrl: String, model: String, key: String) = viewModelScope.launch {
        if (baseUrl.isBlank() || model.isBlank()) return@launch
        settings.setTranscriptionEndpoint(
            AsrEndpoint.selfHosted(baseUrl.trim(), model.trim(), key.trim())
        )
    }

    fun saveUnderstanding(vendor: AiVendor, baseUrl: String, model: String, key: String) =
        viewModelScope.launch {
            if (baseUrl.isBlank() || model.isBlank()) return@launch
            settings.setUnderstandingEndpoint(
                AiEndpoint(vendor, baseUrl.trim(), model.trim(), key.trim())
            )
        }

    fun saveAgent(vendor: AiVendor, baseUrl: String, model: String, key: String) =
        viewModelScope.launch {
            if (baseUrl.isBlank() || model.isBlank()) return@launch
            settings.setAgentEndpoint(
                AiEndpoint(vendor, baseUrl.trim(), model.trim(), key.trim())
            )
        }
}

@Composable
fun ModelsScreen(viewModel: ModelsViewModel = hiltViewModel()) {
    val ai by viewModel.settingsState.collectAsStateWithLifecycle()
    val colors = ditingColors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("模型与 Skill", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                // The three slots are separate so a cheap recogniser can run on
                // every minute of audio while an expensive model only reasons.
                "转写 / 理解 / Agent 三层各自选模型。地址和模型名都可以改 —— 厂商改路径的时候你不用等新版本。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            AsrEndpointCard(
                current = ai.transcription,
                onSave = viewModel::saveAsr,
            )
        }

        item {
            LlmEndpointCard(
                title = "理解 · 总结与待办",
                accent = colors.railInsight,
                current = ai.understanding,
                onSave = viewModel::saveUnderstanding,
            )
        }

        item {
            LlmEndpointCard(
                title = "Agent · 任务执行",
                accent = colors.railAction,
                current = ai.agent,
                hint = "不填就沿用「理解」那一层的模型。",
                onSave = viewModel::saveAgent,
            )
        }
    }
}

@Composable
private fun AsrEndpointCard(current: AsrEndpoint?, onSave: (String, String, String) -> Unit) {
    val colors = ditingColors
    var baseUrl by remember(current) { mutableStateOf(current?.baseUrl.orEmpty()) }
    var model by remember(current) { mutableStateOf(current?.model ?: "whisper-1") }
    var key by remember(current) { mutableStateOf(current?.apiKey.orEmpty()) }

    RailCard(rail = colors.railTranscript) {
        Text("转写 · ASR", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
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
            Button(onClick = { onSave(baseUrl, model, key) }) { Text("保存") }
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

    RailCard(rail = accent) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiVendor.entries.forEach { option ->
                FilterChip(
                    selected = vendor == option,
                    onClick = {
                        vendor = option
                        baseUrl = defaultBaseUrl(option)
                    },
                    label = { Text(option.displayName, maxLines = 1, softWrap = false) },
                )
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
            Button(onClick = { onSave(vendor, baseUrl, model, key) }) { Text("保存") }
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
fun DestinationsScreen() {
    val colors = ditingColors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("目的地与通道", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "采用一份产出之后它去哪里，由工件类型决定。任何目的地的写入都发生在你点「采用」之后。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        items(Destination.entries.filter { it != Destination.NONE }) { destination ->
            RailCard(
                rail = if (destination.isExternalWrite) colors.railAction else colors.railInsight
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(destination.chineseLabel(), style = MaterialTheme.typography.titleMedium)
                    Pill(if (destination.isExternalWrite) "对外写入" else "不对外")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    destination.sideEffectWarning(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                // Connecting a destination means an OAuth flow or a token, which
                // this build does not ship. Saying so beats a button that lies.
                OutlinedButton(onClick = { }, enabled = false) { Text("连接（尚未接入）") }
            }
        }

        item {
            RailCard(rail = colors.railInsight) {
                Text("Hermes 实时通道", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "通道不是目的地，只配三样：什么时候开、月度预算、降级阈值（电量 < 20% 或 RTT > 400ms 回本地转写）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
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
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memory: MemoryRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    val state: StateFlow<MemoryUiState> = combine(
        memory.observeLiveCount(),
        memory.observeArchivedCount(),
        settings.retentionPolicy,
    ) { live, archived, policy -> MemoryUiState(live, archived, policy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun useMinimalPolicy(minimal: Boolean) = viewModelScope.launch {
        settings.setRetentionPolicy(
            if (minimal) RetentionPolicy.Minimal else RetentionPolicy.Default
        )
    }

    fun forgetEverything() = viewModelScope.launch { memory.forgetEverything() }
}

@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    var confirmingForget by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("记忆与遗忘", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "记住得越少，提醒才越准。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        item {
            RailCard(rail = colors.railTranscript) {
                Text("三层寿命", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TierRow("原声音频", "${state.policy.audioLifetime.inWholeDays} 天", "过期只删音频，转写与引用保留")
                TierRow("转写文本", "${state.policy.transcriptLifetime.inWholeDays} 天", "未被引用的段落 ${state.policy.transcriptCompressionAge.inWholeDays} 天后压缩为摘要")
                TierRow("记忆图谱", "永久", "${state.policy.graphColdAge.inWholeDays} 天不出现的冷节点归档：可搜索，不再触发提醒")
            }
        }

        item {
            RailCard(rail = colors.railAction) {
                Text(
                    "唯一例外：被任务 / 洞察 / 报告引用过的片段，永不过期。",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        item {
            RailCard(rail = colors.railInsight) {
                Text("图谱温度", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("活跃 ${state.liveNodes}")
                    Pill("已归档 ${state.archivedNodes}")
                }
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    title = "更短的保留期",
                    subtitle = "音频 7 天、转写 90 天、60 天归档。",
                    checked = state.policy == RetentionPolicy.Minimal,
                    onCheckedChange = viewModel::useMinimalPolicy,
                )
            }
        }

        item {
            RailCard(rail = colors.railBlocker) {
                Text("全部遗忘", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "清空整个记忆图谱。录音和转写不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmingForget = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.railBlocker),
                ) { Text("全部遗忘") }
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
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TierRow(title: String, lifetime: String, detail: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Pill(lifetime)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = ditingColors.inkMuted,
        )
    }
}
