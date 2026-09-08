@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.diting.app.ui.components.AiOrb
import com.diting.app.ui.components.BackCircle
import com.diting.app.ui.components.RailSheet
import com.diting.app.ui.theme.TimestampStyle
import com.diting.domain.growth.GrowthState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.brain.BrainBridge
import com.diting.app.brain.BrainStore
import com.diting.app.brain.ChannelState
import com.diting.app.data.db.DeviceDao
import com.diting.app.data.db.DeviceEntity
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.device.DiscoveredDevice
import com.diting.app.device.Mr20DeviceManager
import com.diting.app.device.SyncProgress
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.StatusDot
import com.diting.app.ui.components.ProgressNotice
import com.diting.app.ui.components.GroupRow
import com.diting.app.ui.components.GroupCard
import com.diting.app.ui.components.IconSquare
import com.diting.app.ui.components.GroupToggleRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.LoadingBlock
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import com.diting.protocol.mr20.Mr20Exception
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// =============================================================================
// 我的
// =============================================================================

data class MeUiState(
    val growth: GrowthState = GrowthState(),
    val deviceCount: Int = 0,
    val sessionCount: Int = 0,
    val preferWifiSync: Boolean = false,
    val deleteAfterSync: Boolean = false,
    val deviceLine: String = "还没有配对",
    val teachLine: String = "声纹 · 热词 · 场景",
    val brainLine: String = "未登录",
    val companionDays: Int = 0,
    val quietHours: Boolean = true,
    val uploadHistorical: Boolean = false,
)

@HiltViewModel
class MeViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val deviceDao: DeviceDao,
    sessionDao: com.diting.app.data.db.SessionDao,
    hotwordDao: com.diting.app.data.db.HotwordDao,
    private val deviceManager: Mr20DeviceManager,
    private val brainStore: BrainStore,
) : ViewModel() {

    val state: StateFlow<MeUiState> = combine(
        combine(settings.growthPoints.map(::GrowthState), deviceDao.observeAll(), deviceManager.client) { g, d, c -> Triple(g, d, c) },
        combine(sessionDao.observeAll(), hotwordDao.observeAll(), settings.scenes) { s, h, sc -> Triple(s, h, sc) },
        combine(settings.preferWifiSync, settings.deleteAfterSync) { w, d -> w to d },
        brainStore.account,
    ) { (growth, devices, client), (sessions, hotwords, scenes), (wifi, deleteAfter), account ->
        val device = devices.firstOrNull()
        val firstDay = sessions.minOfOrNull { it.startedAtEpochMs }
        MeUiState(
            growth = growth,
            deviceCount = devices.size,
            sessionCount = sessions.size,
            preferWifiSync = wifi,
            deleteAfterSync = deleteAfter,
            deviceLine = when {
                device == null -> "还没有配对"
                client != null -> "MR20 · 已连接" + (device.batteryPercent?.let { " · 电量 $it%" } ?: "")
                else -> "MR20 · 未连接" + (device.batteryPercent?.let { " · 上次电量 $it%" } ?: "")
            },
            teachLine = "声纹 · 热词 ${hotwords.size} · 场景 ${scenes.size}",
            brainLine = if (account.isLoggedIn) (if (account.isAdopted) "已登录 · 录音卡已登记" else "已登录 · 录音卡未登记") else "未登录",
            companionDays = firstDay?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() + 1 } ?: 0,
            quietHours = account.quietHours,
            uploadHistorical = account.uploadHistorical,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

    fun setQuietHours(value: Boolean) = brainStore.update { it.copy(quietHours = value) }
    fun setUploadHistorical(value: Boolean) = brainStore.update { it.copy(uploadHistorical = value) }

    fun setPreferWifi(value: Boolean) = viewModelScope.launch {
        settings.setPreferWifiSync(value)
    }

    fun setDeleteAfterSync(value: Boolean) = viewModelScope.launch {
        settings.setDeleteAfterSync(value)
    }
}

@Composable
fun MeScreen(
    onOpenDevices: () -> Unit,
    onOpenBrain: () -> Unit,
    onOpenTeach: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenGrowth: () -> Unit,
    onOpenPairing: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val mintDark = androidx.compose.ui.graphics.Color(0xFF0E8578)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("我的", style = MaterialTheme.typography.headlineMedium) }

        // The deck's growth banner: mint gradient, an orb, level + BP + progress.
        item {
            Surface(
                onClick = onOpenGrowth,
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.railTranscript, mintDark)),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    listOf(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)),
                                    center = androidx.compose.ui.geometry.Offset(20f, 20f),
                                ),
                                CircleShape,
                            )
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.companionDays > 0) "与小谛相伴 ${state.companionDays} 天" else "刚认识小谛",
                            style = MaterialTheme.typography.titleMedium,
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                        Text(
                            "Lv.${state.growth.level} ${levelName(state.growth.level)} · BP ${state.growth.totalPoints}" +
                                (state.growth.pointsToNextLevel?.let { " · 距 Lv.${state.growth.level + 1} 还差 $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.growth.levelProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }

        item {
            GroupCard {
                GroupRow(
                    title = "录音卡",
                    subtitle = state.deviceLine,
                    chevron = true,
                    leading = { IconSquare(colors.inkPanel) },
                    onClick = onOpenDevices,
                )
                GroupRow(
                    title = "大脑账户",
                    subtitle = state.brainLine,
                    chevron = true,
                    leading = {
                        IconSquare(
                            colors.railTranscript,
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(androidx.compose.ui.graphics.Color.White, colors.railTranscript),
                                center = androidx.compose.ui.geometry.Offset(10f, 10f),
                            ),
                        )
                    },
                    onClick = onOpenBrain,
                )
                GroupRow(
                    title = "教小谛",
                    subtitle = state.teachLine,
                    chevron = true,
                    leading = { IconSquare(colors.railInsight) },
                    onClick = onOpenTeach,
                )
                GroupRow(
                    title = "能力与订阅",
                    subtitle = "自带 Key · 转写 / 理解 / Agent",
                    chevron = true,
                    leading = { IconSquare(colors.railAction) },
                    onClick = onOpenSubscription,
                )
                GroupRow(
                    title = "重新配对 / 添加设备",
                    subtitle = "MR20 录音卡；已配过的卡填同一把密钥",
                    chevron = true,
                    last = true,
                    leading = { IconSquare(colors.railTranscript) },
                    onClick = onOpenPairing,
                )
            }
        }

        item {
            GroupCard {
                GroupToggleRow(
                    title = "同步后删除设备上的录音",
                    subtitle = "只在文件已经落盘并入库之后才删",
                    checked = state.deleteAfterSync,
                    onCheckedChange = viewModel::setDeleteAfterSync,
                )
                GroupToggleRow(
                    title = "上传配对前的历史录音",
                    subtitle = "默认不动卡里更早的谈话",
                    checked = state.uploadHistorical,
                    onCheckedChange = viewModel::setUploadHistorical,
                )
                GroupToggleRow(
                    title = "夜间勿扰 22:00–08:00",
                    subtitle = "小谛的提醒只静默通知，不震动",
                    checked = state.quietHours,
                    onCheckedChange = viewModel::setQuietHours,
                    last = true,
                )
            }
        }

        item {
            Text(
                "谛听 v0.1 预研 · 听得见，更听得懂",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 记录者 → 参谋: the deck's stage names for each level. */
internal fun levelName(level: Int): String =
    listOf("记录者", "倾听者", "整理者", "参谋", "搭档", "知己", "军师").getOrElse(level - 1) { "军师" }

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ditingColors.inkMuted,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// =============================================================================
// 设备管理 & 配对
// =============================================================================

data class DevicesUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val connectedId: String? = null,
    val recording: Boolean = false,
    val recordMode: com.diting.protocol.mr20.Mr20RecordMode? = null,
    val outstanding: Mr20DeviceManager.Outstanding? = null,
    val pairedAtEpochSec: Long = 0,
    val sync: SyncProgress = SyncProgress.Idle,
    val channel: ChannelState = ChannelState.NotConfigured,
    val brainLoggedIn: Boolean = false,
    val brainSn: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val deviceManager: Mr20DeviceManager,
    private val deviceDao: DeviceDao,
    private val brainStore: BrainStore,
    private val brainBridge: BrainBridge,
    private val sessions: com.diting.app.data.repo.SessionRepository,
    @com.diting.app.di.RecordingsDir private val recordingsDir: java.io.File,
) : ViewModel() {

    private val _importNotice = MutableStateFlow<String?>(null)
    val importNotice: StateFlow<String?> = _importNotice

    /**
     * 「导入录音文件」: copies the picked files into the recordings folder and
     * registers each as a session, then hands them to the brain like a sync.
     */
    fun importFiles(uris: List<android.net.Uri>) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        var ok = 0
        val resolver = appContext.contentResolver
        for (uri in uris) {
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "import-${System.currentTimeMillis()}.mp3"
            val target = java.io.File(recordingsDir, name)
            runCatching {
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                if (sessions.importLocalRecording(target) != null) ok++ else target.delete()
            }
        }
        _importNotice.value = if (ok == 0) "没有导入任何文件（太短或不是音频）" else "已导入 $ok 条录音，正在交给大脑转写"
        if (ok > 0) com.diting.app.brain.BrainUploadWorker.enqueue(androidx.work.WorkManager.getInstance(appContext))
    }

    fun clearImportNotice() { _importNotice.value = null }

    val state: StateFlow<DevicesUiState> = combine(
        combine(deviceDao.observeAll(), deviceManager.client, deviceManager.info) { d, c, i -> Triple(d, c, i) },
        combine(deviceManager.sync, deviceManager.outstanding) { s, o -> s to o },
        brainBridge.channelState,
        brainStore.account,
    ) { (devices, client, info), (sync, outstanding), channel, account ->
        DevicesUiState(
            devices = devices,
            connectedId = if (client != null) devices.firstOrNull()?.id else null,
            recording = client != null && info.isRecording,
            recordMode = info.recordMode,
            outstanding = outstanding,
            pairedAtEpochSec = account.pairedAtEpochSec,
            sync = sync,
            channel = channel,
            brainLoggedIn = account.isLoggedIn,
            brainSn = account.sn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    /** Non-fatal outcome of the last pairing (adoption skipped or failed). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    /**
     * Set once a device has actually paired, so the screen leaves on success
     * rather than on the tap.
     */
    private val _paired = MutableStateFlow<String?>(null)
    val paired: StateFlow<String?> = _paired.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _discovered.value = emptyList()
            _error.value = null
            runCatching {
                deviceManager.scan().collect { found ->
                    // Keep the strongest reading per address; a scan reports the same
                    // device many times as the user moves.
                    _discovered.value =
                        (_discovered.value.filter { it.address != found.address } + found)
                            .sortedWith(compareByDescending<DiscoveredDevice> { it.isLikelyMr20 }
                                .thenByDescending { it.rssi })
                }
            }.onFailure { _error.value = it.message }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Connects, and on a first pairing generates and stores the bind key.
     *
     * The stored key is whatever the *device* accepted — it truncates anything
     * over 16 characters — so it comes back from the manager rather than being
     * assumed here.
     */
    /**
     * @param manualKey a bind key the recorder already holds — typed in by the
     *   user when the card was paired by another app or phone. Takes precedence
     *   over anything stored here; null falls back to the stored key, and no key
     *   at all means a first pairing that generates one.
     */
    fun connect(address: String, manualKey: String? = null) = viewModelScope.launch {
        // Scanning while connecting measurably hurts the connection: the radio
        // is time-slicing between the two, and the MR20's pairing exchange has a
        // timeout. Stop looking as soon as the user has chosen.
        stopScan()

        _connecting.value = true
        _error.value = null
        _notice.value = null
        try {
            val existing = manualKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: deviceDao.find(address)?.bindKey
            deviceManager.connect(address, existing)
            adoptInBrain(address)
            _paired.value = address
        } catch (e: Exception) {
            _rejected.value = e is Mr20Exception.PairingRejected
            _error.value = friendlyMessage(e)
        } finally {
            _connecting.value = false
        }
    }

    /** True when the last failure was the card refusing our key (SK&ERR). */
    private val _rejected = MutableStateFlow(false)
    val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

    /**
     * Registers the paired recorder with the brain. Its SN is the BT MAC the
     * device reports (falling back to the BLE address), lowercase, no colons —
     * the same identity the deployed brain already knows this card by.
     */
    private suspend fun adoptInBrain(address: String) {
        val mac = deviceManager.info.value.macAddress ?: address
        val sn = mac.filter { it.isLetterOrDigit() }.lowercase()
        brainStore.setSn(sn)
        if (!brainStore.current.isLoggedIn) {
            _notice.value = "已配对。登录大脑账户后会自动把这台录音卡登记到大脑。"
            return
        }
        runCatching { brainBridge.adoptDevice(sn) }
            .onFailure { _notice.value = "已配对，但向大脑登记设备失败：${it.message}" }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is Mr20Exception.PairingRejected ->
            "录音卡只认一把密钥。它之前被另一部手机或旧版 App 绑定过，把那把 16 位密钥填在下面重试；找不回来就在原手机解绑或重置录音卡。"

        is Mr20Exception.Timeout ->
            "录音卡没有应答。确认它没被另一部手机或旧版 App 占用（先在那边断开），再试一次。"

        else -> e.message ?: "连接失败"
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Consumed by the screen once it has acted on [paired]. */
    fun clearPaired() {
        _paired.value = null
    }

    fun disconnect() = deviceManager.disconnect()

    fun stopRecording() = viewModelScope.launch {
        runCatching { deviceManager.stopRecording() }
            .onFailure { _error.value = "停止录音失败：${it.message}" }
    }

    val wifiAssist: StateFlow<Mr20DeviceManager.WifiAssist?> = deviceManager.wifiAssist

    fun startWifiAssist() = deviceManager.startWifiAssist()

    fun dismissWifiAssist() = deviceManager.dismissWifiAssist()

    fun forget(address: String) = viewModelScope.launch {
        deviceManager.disconnect()
        deviceDao.delete(address)
    }

    fun clearError() {
        _error.value = null
        _rejected.value = false
    }
}

@Composable
fun DevicesScreen(
    onAddDevice: () -> Unit,
    onSync: () -> Unit,
    onSyncAll: () -> Unit,
    onOpenBrain: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val wifiAssist by viewModel.wifiAssist.collectAsStateWithLifecycle()
    val importNotice by viewModel.importNotice.collectAsStateWithLifecycle()
    val colors = ditingColors
    val context = LocalContext.current
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) { toast("正在导入 ${uris.size} 个文件…"); viewModel.importFiles(uris) } }
    LaunchedEffect(importNotice) {
        importNotice?.let { toast(it); viewModel.clearImportNotice() }
    }
    var confirmForget by remember { mutableStateOf<String?>(null) }

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
                Text("录音卡", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onAddDevice) { Text("添加设备") }
            }
        }

        if (state.devices.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有配对录音卡",
                    hint = "打开录音卡，点右上角「添加设备」。",
                    action = { Button(onClick = onAddDevice) { Text("添加设备") } },
                )
            }
        }

        items(state.devices, key = { it.id }) { device ->
            val connected = state.connectedId == device.id
            val dot = when {
                connected && state.recording -> colors.railBlocker
                connected -> colors.railTranscript
                else -> colors.inkMuted
            }

            // The deck's device header: an ink panel with the card's silhouette,
            // a status dot, the SN in mono and a few pills. Nothing else.
            InkPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(width = 44.dp, height = 70.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.onInkPanel.copy(alpha = 0.10f),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { StatusDot(dot) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "MR20 · ${device.name}",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onInkPanel,
                        )
                        Text(
                            listOfNotNull(
                                state.brainSn?.let { "SN $it" },
                                device.firmwareVersion?.let { "FW $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onInkPanel.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val tint = colors.onInkPanel.copy(alpha = 0.12f)
                            Pill(
                                when {
                                    connected && state.recording -> "录音中"
                                    connected -> "已连接"
                                    else -> "未连接"
                                },
                                color = colors.onInkPanel, background = tint,
                            )
                            device.batteryPercent?.let { Pill("电量 $it%", color = colors.onInkPanel, background = tint) }
                            device.freeMb?.let { Pill("剩余 ${it / 1024} GB", color = colors.onInkPanel, background = tint) }
                        }
                    }
                }
            }

            when (val sync = state.sync) {
                is SyncProgress.Transferring -> {
                    Spacer(Modifier.height(12.dp))
                    val pct = ((sync.fraction ?: 0f) * 100).toInt()
                    ProgressNotice(
                        title = "同步中 ${sync.index}/${sync.total} · $pct%",
                        body = "%s · %.1f MB，队列还剩 %.0f MB".format(
                            sync.fileName, sync.fileBytes / 1024f / 1024f, sync.remainingBytes / 1024f / 1024f,
                        ),
                        fraction = sync.fraction,
                    )
                }
                is SyncProgress.Listing -> {
                    Spacer(Modifier.height(12.dp))
                    ProgressNotice("正在读取卡上的文件…", "列目录只要几秒。", fraction = null)
                }
                is SyncProgress.FallingBackToBle -> {
                    Spacer(Modifier.height(12.dp))
                    ProgressNotice("Wi-Fi 没连上，改走蓝牙", sync.reason, fraction = null)
                }
                is SyncProgress.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    ProgressNotice("同步没有完成", sync.reason, fraction = null, accent = colors.railBlocker)
                }
                is SyncProgress.Done -> {
                    Spacer(Modifier.height(12.dp))
                    ProgressNotice(
                        title = if (sync.filesSynced == 0) "没有新的短录音" else "已同步 ${sync.filesSynced} 条",
                        body = if (sync.skippedLarge > 0) "${sync.skippedLarge} 条 5 MB 以上的长录音还在卡里，用「同步全部录音」拉取。"
                        else "卡上的录音都已经在手机里了。",
                        fraction = 1f,
                        accent = colors.railTranscript,
                    )
                }
                SyncProgress.Idle -> Unit
            }

            Spacer(Modifier.height(14.dp))
            GroupCard {
                GroupRow(
                    title = if (connected) "已连接" else "连接录音卡",
                    subtitle = if (connected) "蓝牙 · 点击断开" else "蓝牙",
                    value = if (connected) null else "连接",
                    valueColor = colors.railTranscript,
                    onClick = {
                        if (connected) { toast("已断开"); viewModel.disconnect() }
                        else { toast("正在连接…"); viewModel.connect(device.id) }
                    },
                )
                GroupRow(
                    title = "停止录音",
                    subtitle = "同步前必须停录：实时音频和文件走同一条蓝牙通道",
                    value = if (connected && state.recording) "录音中" else "未录音",
                    valueColor = if (connected && state.recording) colors.railBlocker else colors.inkMuted,
                    enabled = connected && state.recording,
                    onClick = { toast("已停止录音"); viewModel.stopRecording() },
                )
                GroupRow(
                    title = "同步新录音",
                    subtitle = "5 MB 以内的录音，落地后自动上传大脑",
                    chevron = true,
                    enabled = connected,
                    onClick = { toast("开始同步"); onSync() },
                )
                GroupRow(
                    title = "同步全部录音",
                    subtitle = "含长录音；蓝牙约 90 KB/s，10 MB 两分钟，请给录音卡充着电",
                    chevron = true,
                    enabled = connected,
                    onClick = { toast("开始同步全部"); onSyncAll() },
                )
                GroupRow(
                    title = "导入录音文件",
                    subtitle = "从手机里选 MP3 / M4A，按录音卡文件一样转写整理",
                    chevron = true,
                    onClick = { picker.launch(arrayOf("audio/*")) },
                )
                val (brainValue, brainColor) = when (val ch = state.channel) {
                    is ChannelState.Live -> "在线" to colors.railTranscript
                    ChannelState.Opening, ChannelState.Disconnected -> "重连中" to colors.railAction
                    is ChannelState.AuthFailed -> "被拒绝" to colors.railBlocker
                    ChannelState.NotConfigured -> (if (state.brainLoggedIn) "未登记" else "未登录") to colors.railAction
                }
                GroupRow(
                    title = "大脑",
                    subtitle = "登录、登记与上传状态",
                    value = brainValue,
                    valueColor = brainColor,
                    chevron = true,
                    last = true,
                    onClick = onOpenBrain,
                )
            }

            // The deck's facts list under the actions.
            Spacer(Modifier.height(12.dp))
            GroupCard {
                GroupRow(
                    title = "录音模式",
                    value = when (state.recordMode) {
                        com.diting.protocol.mr20.Mr20RecordMode.CONVERSATION -> "对话 · 设备端 VAD 开"
                        com.diting.protocol.mr20.Mr20RecordMode.CALL -> "通话 · 持续录音"
                        null -> "连接后读取"
                    },
                )
                GroupRow(
                    title = "卡上未同步文件",
                    value = state.outstanding?.let { "${it.files} 个 · ${it.bytes / 1024 / 1024} MB" } ?: "同步后显示",
                )
                GroupRow(
                    title = "配对于",
                    value = if (state.pairedAtEpochSec > 0) {
                        java.time.Instant.ofEpochSecond(state.pairedAtEpochSec).atZone(java.time.ZoneId.systemDefault())
                            .let { "${it.monthValue}月${it.dayOfMonth}日" } + "（更早的录音默认不上传）"
                    } else "未登记到大脑",
                    last = true,
                )
            }

            Spacer(Modifier.height(12.dp))
            GroupCard {
                GroupRow(
                    title = "重新配对 / 添加设备",
                    subtitle = "换手机或换卡时用；已配对的卡填同一把密钥",
                    chevron = true,
                    last = true,
                    leading = {
                        Box(
                            Modifier
                                .size(32.dp)
                                .border(1.5.dp, colors.inkMuted, RoundedCornerShape(8.dp))
                        )
                    },
                    onClick = onAddDevice,
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { confirmForget = device.id },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = colors.railBlocker),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.railBlocker),
            ) { Text("解绑这张录音卡") }
        }
    }

    confirmForget?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("解绑录音卡？") },
            text = { Text("手机上的录音和转写会保留；卡里的绑定密钥不变，重新配对时填同一把密钥即可。") },
            confirmButton = {
                TextButton(onClick = { confirmForget = null; toast("已解绑"); viewModel.forget(id) }) { Text("解绑") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("取消") } },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
            title = { Text("录音卡") },
            text = { Text(message) },
        )
    }

    wifiAssist?.let { assist ->
        val phase = assist.phase
        AlertDialog(
            onDismissRequest = viewModel::dismissWifiAssist,
            confirmButton = {
                TextButton(onClick = viewModel::dismissWifiAssist) {
                    Text(if (phase == Mr20DeviceManager.WifiAssistPhase.JOINED) "好" else "取消")
                }
            },
            title = {
                Text(
                    when (phase) {
                        Mr20DeviceManager.WifiAssistPhase.OPENING -> "打开热点…"
                        Mr20DeviceManager.WifiAssistPhase.TRYING -> "加入热点…"
                        Mr20DeviceManager.WifiAssistPhase.MANUAL_WAIT -> "请手动连接（${assist.secondsLeft} 秒）"
                        Mr20DeviceManager.WifiAssistPhase.JOINED -> "Wi-Fi 已就绪"
                        Mr20DeviceManager.WifiAssistPhase.FAILED -> "没连上热点"
                    }
                )
            },
            text = {
                Column {
                    when (phase) {
                        Mr20DeviceManager.WifiAssistPhase.OPENING,
                        Mr20DeviceManager.WifiAssistPhase.TRYING -> LoadingBlock(assist.detail.ifBlank { "约需 8 秒" })
                        Mr20DeviceManager.WifiAssistPhase.MANUAL_WAIT ->
                            Text("系统不肯自动加入。到 设置 › WLAN 连接下面这个热点，提示无网络时保持连接；连上后这里自动继续。")
                        else -> Text(assist.detail)
                    }
                    if (assist.ssid.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(assist.ssid, style = MaterialTheme.typography.titleMedium)
                        Text("密码 ${assist.password}", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                    }
                }
            },
        )
    }
}

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val rejected by viewModel.rejected.collectAsStateWithLifecycle()
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val colors = ditingColors
    var selected by remember { mutableStateOf<String?>(null) }
    var manualKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startScan() }

    // Leave only once the device has actually paired, and only after any
    // adoption notice has been read.
    LaunchedEffect(paired, notice) {
        if (paired != null && notice == null) {
            viewModel.clearPaired()
            onPaired()
        }
    }

    val likely = discovered.firstOrNull { it.isLikelyMr20 }?.address
    val chosen = selected ?: likely
    val dotColor = when {
        connecting -> colors.railAction
        discovered.isNotEmpty() -> colors.railTranscript
        else -> colors.inkMuted
    }

    Column(Modifier.fillMaxSize().padding(20.dp, 24.dp, 20.dp, 24.dp)) {
        Text("把录音卡靠近手机", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "谛听通过蓝牙识别录音卡，并写入一把只属于这台手机的密钥。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        PairingIllustration(dotColor = dotColor, active = !connecting)

        Text(
            "扫描结果 · " + when {
                connecting -> "正在配对"
                discovered.isEmpty() -> "搜索中"
                else -> "${discovered.size} 个设备"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(0.14f, androidx.compose.ui.unit.TextUnitType.Em),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (discovered.isEmpty() && !connecting) {
                item { LoadingBlock("确认录音卡已开机、没被另一部手机占用…") }
            }
            if (discovered.isNotEmpty()) {
                item {
                    GroupCard {
                        discovered.forEachIndexed { index, found ->
                            val isChosen = found.address == chosen
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isChosen) Modifier.background(colors.railTranscript.copy(alpha = 0.08f))
                                        else Modifier
                                    )
                                    .clickable(enabled = !connecting) { selected = found.address }
                            ) {
                                Row(
                                    Modifier.padding(16.dp, 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatusDot(if (found.isLikelyMr20) colors.railTranscript else colors.inkMuted, size = 10.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            found.name ?: "未命名设备",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "${found.address} · ${found.rssi} dBm" + if (found.isLikelyMr20) " · MR20" else "",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = colors.inkMuted,
                                        )
                                    }
                                    SignalBars(rssi = found.rssi, color = if (isChosen) colors.railTranscript else colors.inkMuted)
                                }
                                if (index < discovered.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    val accent = if (rejected) colors.railAction else colors.railBlocker
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accent.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
                            Column(Modifier.padding(14.dp, 12.dp)) {
                                Text(
                                    if (rejected) "这张卡已绑定过别的密钥" else "没连上",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                                if (rejected) {
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = manualKey,
                                        onValueChange = { manualKey = it.take(16) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("原来的 16 位密钥") },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showKey && !rejected) {
                item {
                    OutlinedTextField(
                        value = manualKey,
                        onValueChange = { manualKey = it.take(16) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("已有的 16 位密钥") },
                        supportingText = { Text("这张卡在别的手机或旧版 App 上配过对时填写") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { chosen?.let { viewModel.connect(it, manualKey) } },
            enabled = chosen != null && !connecting,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text(
                when {
                    connecting -> "正在配对…"
                    rejected && manualKey.length == 16 -> "用这把密钥重试"
                    chosen == null -> "选择一张录音卡"
                    else -> "配对"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "收起密钥" else "已有密钥？") }
            TextButton(onClick = viewModel::startScan) { Text("找不到设备？重新搜索") }
        }
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearNotice,
            confirmButton = { TextButton(onClick = viewModel::clearNotice) { Text("知道了") } },
            title = { Text("已配对") },
            text = { Text(message) },
        )
    }
}

/** The deck's pairing hero: a dark card silhouette with two breathing rings. */
@Composable
private fun PairingIllustration(dotColor: androidx.compose.ui.graphics.Color, active: Boolean) {
    val colors = ditingColors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "rings")
    val ring1 by transition.animateFloat(
        initialValue = 0.7f, targetValue = 1.5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        ),
        label = "ring1",
    )
    val ring2 by transition.animateFloat(
        initialValue = 0.7f, targetValue = 1.5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2200, delayMillis = 800, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        ),
        label = "ring2",
    )
    Box(
        Modifier.fillMaxWidth().height(190.dp).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            listOf(ring1, ring2).forEach { scale ->
                Box(
                    Modifier
                        .size(110.dp)
                        .scale(scale)
                        .alpha((1.5f - scale).coerceIn(0f, 0.8f))
                        .border(1.5.dp, colors.railTranscript, CircleShape)
                )
            }
        }
        Surface(
            modifier = Modifier.size(width = 64.dp, height = 100.dp),
            shape = RoundedCornerShape(14.dp),
            color = colors.inkPanel,
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier.fillMaxSize().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { StatusDot(dotColor, size = 8.dp) }
        }
    }
}

/** Four bars, filled by RSSI: > -55 all four, > -65 three, > -75 two, else one. */
@Composable
private fun SignalBars(rssi: Int, color: androidx.compose.ui.graphics.Color) {
    val lit = when {
        rssi > -55 -> 4
        rssi > -65 -> 3
        rssi > -75 -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(if (i < lit) color else color.copy(alpha = 0.25f), RoundedCornerShape(1.dp))
            )
        }
    }
}

// =============================================================================
// 能力与订阅 · 成长
// =============================================================================

private data class Plan(val name: String, val price: String, val feats: String, val current: Boolean, val onClick: (() -> Unit)?)

@Composable
fun SubscriptionScreen(onBack: () -> Unit, onOpenModels: () -> Unit, onOpenBrain: () -> Unit, viewModel: MeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val brainOn = state.brainLine != "未登录"
    val plans = listOf(
        Plan("自带 Key", "¥0", "转写与理解直接用你自己的千问 / 豆包 Key\n账单在你自己的控制台\n不限量，走批处理", current = !brainOn, onClick = onOpenModels),
        Plan("谛听大脑", if (brainOn) "已登录" else "登录即用", "录音同步后由大脑批处理转写与整理\n记忆图谱、主动提醒、卡片震动\n转写与摘要不限量", current = brainOn, onClick = onOpenBrain),
        Plan("实时通道 + Agent", "按时长", "会议中实时字幕与同传\nAgent 执行链路与外部 Skill\n尚未开放，开放后按小时计", current = false, onClick = null),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("能力与订阅", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "按「能力」而不是「分钟数」分层：贵的是实时通道与 Agent 执行，转写与摘要走批处理，不限量。",
                modifier = Modifier.padding(4.dp, 16.dp, 4.dp, 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = colors.inkMuted,
            )
        }
        items(plans) { plan ->
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(shape)
                    .background(Color.White)
                    .border(1.5.dp, if (plan.current) colors.railTranscript else colors.paperSunken, shape)
                    .then(plan.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier.alpha(0.7f))
                    .padding(16.dp, 14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(plan.price, style = TimestampStyle.copy(fontSize = 14.sp), color = if (plan.current) colors.railTranscript else colors.inkMuted)
                }
                Spacer(Modifier.height(4.dp))
                Text(plan.feats, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 22.sp), color = colors.inkMuted)
                if (plan.current) {
                    Spacer(Modifier.height(8.dp))
                    Pill("当前在用", color = colors.railTranscript, background = colors.railTranscript.copy(alpha = 0.12f))
                }
            }
        }
    }
}

data class GrowthUi(
    val growth: GrowthState = GrowthState(),
    val companionDays: Int = 0,
    val people: Int = 0,
    val commitments: Int = 0,
    val decisions: Int = 0,
    val nodes: Int = 0,
)

@HiltViewModel
class GrowthViewModel @Inject constructor(
    settings: SettingsStore,
    sessionDao: com.diting.app.data.db.SessionDao,
    memory: com.diting.app.data.repo.MemoryRepository,
) : ViewModel() {
    val state: StateFlow<GrowthUi> = combine(
        settings.growthPoints,
        sessionDao.observeAll(),
        memory.observeByType(com.diting.domain.memory.MemoryNodeType.PERSON),
        memory.observeByType(com.diting.domain.memory.MemoryNodeType.COMMITMENT),
        combine(
            memory.observeByType(com.diting.domain.memory.MemoryNodeType.DECISION),
            memory.observeByType(com.diting.domain.memory.MemoryNodeType.TOPIC),
            memory.observeByType(com.diting.domain.memory.MemoryNodeType.RECURRING_ASK),
        ) { d, t, r -> Triple(d.size, t.size, r.size) },
    ) { points, sessions, people, commitments, (decisions, topics, recurring) ->
        val firstDay = sessions.minOfOrNull { it.startedAtEpochMs }
        GrowthUi(
            growth = GrowthState(points),
            companionDays = firstDay?.let { ((System.currentTimeMillis() - it) / 86_400_000L).toInt() + 1 } ?: 0,
            people = people.size,
            commitments = commitments.size,
            decisions = decisions,
            nodes = people.size + commitments.size + decisions + topics + recurring,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrowthUi())
}

private fun levelBlurb(level: Int): String = listOf(
    "小谛开始认得你的声音",
    "小谛开始记住反复出现的人和事",
    "小谛开始替你整理承诺与待办",
    "小谛开始替你预判风险",
    "小谛开始主动帮你办事",
    "小谛知道什么该忘",
    "小谛已是你的军师",
).getOrElse(level - 1) { "小谛已是你的军师" }

@Composable
fun GrowthScreen(onBack: () -> Unit, viewModel: GrowthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors
    val level = state.growth.level

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 60.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackCircle(onClick = onBack)
                Text("成长卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AiOrb(onClick = {}, size = 96.dp)
                Spacer(Modifier.height(14.dp))
                Text("Lv.$level ${levelName(level)}", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold)
                Text(
                    state.growth.pointsToNextLevel?.let { "下一阶：Lv.${level + 1} ${levelName(level + 1)} —— ${levelBlurb(level + 1)} · 还差 $it BP" }
                        ?: "已是最高阶 —— ${levelBlurb(level)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.inkMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("${state.companionDays}" to "陪伴天数", "${state.growth.totalPoints}" to "BP", "${state.nodes}" to "记忆节点").forEach { (v, k) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, colors.paperSunken, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(v, style = TimestampStyle.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold)
                        Text(k, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            RailSheet {
                Text("BP 怎么来", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("BP 不奖励「录得多」，只奖励「闭环」：")
                        com.diting.domain.growth.GrowthEvent.entries.forEach { append("\n· ${it.label} +${it.points}") }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 23.sp),
                    color = colors.inkMuted,
                )
            }
            Spacer(Modifier.height(14.dp))
            RailSheet(fill = colors.railTranscript.copy(alpha = 0.09f), border = colors.railTranscript.copy(alpha = 0.25f)) {
                Text(
                    buildAnnotatedString {
                        append("小谛现在知道你世界里的 ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${state.people} 个人") }
                        append("、")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${state.commitments} 个承诺") }
                        append("、")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${state.decisions} 项进行中的决策") }
                        append("。它们都能回溯到原声，随时可删。")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 22.sp),
                )
            }
        }
    }
}
