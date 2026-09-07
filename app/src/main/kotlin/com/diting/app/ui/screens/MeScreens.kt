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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.data.db.DeviceDao
import com.diting.app.data.db.DeviceEntity
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.device.DiscoveredDevice
import com.diting.app.device.Mr20DeviceManager
import com.diting.app.device.SyncProgress
import com.diting.app.ui.components.EmptyState
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.LoadingBlock
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.growth.GrowthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 我的
// =============================================================================

data class MeUiState(
    val growth: GrowthState = GrowthState(),
    val deviceCount: Int = 0,
    val sessionCount: Int = 0,
    val preferWifiSync: Boolean = false,
    val deleteAfterSync: Boolean = false,
)

@HiltViewModel
class MeViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val deviceDao: DeviceDao,
    sessionDao: com.diting.app.data.db.SessionDao,
) : ViewModel() {

    val state: StateFlow<MeUiState> = combine(
        settings.growthPoints.map(::GrowthState),
        deviceDao.observeAll().map { it.size },
        sessionDao.observeCount(),
        settings.preferWifiSync,
        settings.deleteAfterSync,
    ) { growth, devices, sessions, wifi, deleteAfter ->
        MeUiState(growth, devices, sessions, wifi, deleteAfter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

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
    onOpenTeach: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenGrowth: () -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("我的", style = MaterialTheme.typography.headlineMedium) }

        item {
            InkPanel(onClick = onOpenGrowth) {
                Text(
                    "Lv.${state.growth.level}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onInkPanel,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.growth.levelProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentOnPanel,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // BP rewards closing loops, not recording more — the growth
                    // copy has to say so or it reads as a usage counter.
                    state.growth.pointsToNextLevel
                        ?.let { "再 $it BP 升级。BP 只奖励确认、采用和纠错。" }
                        ?: "已经是最高等级。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accentOnPanel,
                )
            }
        }

        item {
            RailCard(rail = colors.railTranscript, onClick = onOpenDevices) {
                Text("设备管理", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "已配对 ${state.deviceCount} 台 · 共 ${state.sessionCount} 条录音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }

        item {
            RailCard(rail = colors.railInsight, onClick = onOpenTeach) {
                Text("教小谛", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "听感 · 理解 · 行动 · 记忆",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }

        item {
            RailCard(rail = colors.railAction, onClick = onOpenSubscription) {
                Text("能力与订阅", style = MaterialTheme.typography.titleMedium)
            }
        }

        item {
            RailCard(rail = colors.inkMuted) {
                Text("同步偏好", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    title = "优先用 Wi-Fi 同步",
                    subtitle = "快很多，但要临时加入录音卡自己的热点，期间手机没有外网。",
                    checked = state.preferWifiSync,
                    onCheckedChange = viewModel::setPreferWifi,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SettingRow(
                    title = "同步后删除设备上的录音",
                    subtitle = "只在文件已经落盘并入库之后才删。",
                    checked = state.deleteAfterSync,
                    onCheckedChange = viewModel::setDeleteAfterSync,
                )
            }
        }
    }
}

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
    val sync: SyncProgress = SyncProgress.Idle,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceManager: Mr20DeviceManager,
    private val deviceDao: DeviceDao,
) : ViewModel() {

    val state: StateFlow<DevicesUiState> = combine(
        deviceDao.observeAll(),
        deviceManager.client,
        deviceManager.sync,
    ) { devices, client, sync ->
        DevicesUiState(devices, if (client != null) devices.firstOrNull()?.id else null, sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

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
                            .sortedByDescending { it.rssi }
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
    fun connect(address: String) = viewModelScope.launch {
        // Scanning while connecting measurably hurts the connection: the radio
        // is time-slicing between the two, and the MR20's pairing exchange has a
        // timeout. Stop looking as soon as the user has chosen.
        stopScan()

        _connecting.value = true
        _error.value = null
        try {
            val existing = deviceDao.find(address)?.bindKey
            deviceManager.connect(address, existing)
            _paired.value = address
        } catch (e: Exception) {
            _error.value = e.message ?: "连接失败"
        } finally {
            _connecting.value = false
        }
    }

    /** Consumed by the screen once it has acted on [paired]. */
    fun clearPaired() {
        _paired.value = null
    }

    fun disconnect() = deviceManager.disconnect()

    fun forget(address: String) = viewModelScope.launch {
        deviceManager.disconnect()
        deviceDao.delete(address)
    }

    fun clearError() {
        _error.value = null
    }
}

@Composable
fun DevicesScreen(
    onAddDevice: () -> Unit,
    onSync: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设备", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onAddDevice) { Text("添加设备") }
            }
        }

        when (val sync = state.sync) {
            is SyncProgress.Transferring -> item {
                RailCard(rail = colors.railTranscript) {
                    Text(
                        "正在同步 ${sync.index}/${sync.total}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(sync.fileName, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { sync.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is SyncProgress.Failed -> item {
                RailCard(rail = colors.railBlocker) {
                    Text("同步失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(sync.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }

            is SyncProgress.Done -> item {
                RailCard(rail = colors.railTranscript) {
                    Text(
                        if (sync.filesSynced == 0) "没有新录音" else "已同步 ${sync.filesSynced} 条",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            is SyncProgress.Listing -> item { LoadingBlock("正在读取设备文件列表…") }
            SyncProgress.Idle -> Unit
        }

        if (state.devices.isEmpty()) {
            item {
                EmptyState(
                    headline = "还没有配对设备",
                    hint = "打开录音卡的蓝牙，然后点右上角「添加设备」。",
                    action = { Button(onClick = onAddDevice) { Text("添加设备") } },
                )
            }
        }

        items(state.devices, key = { it.id }) { device ->
            RailCard(rail = colors.railTranscript) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    device.batteryPercent?.let { Pill("电量 $it%") }
                    device.freeMb?.let { free ->
                        device.totalMb?.let { total -> Pill("$free / $total MB") }
                    }
                    device.firmwareVersion?.let { Pill("固件 $it") }
                    if (device.isDefault) Pill("默认")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.connect(device.id) }) { Text("连接") }
                    OutlinedButton(onClick = onSync) { Text("同步录音") }
                    TextButton(onClick = { viewModel.forget(device.id) }) { Text("解绑") }
                }
            }
        }
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
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    val colors = ditingColors

    LaunchedEffect(Unit) { viewModel.startScan() }

    // Leave only once the device has actually paired. Pairing writes a key to
    // the recorder and can take several seconds — navigating on the tap would
    // mean the failure dialog below appears on a screen that is already gone,
    // so a rejected pairing would look exactly like a successful one.
    LaunchedEffect(paired) {
        if (paired != null) {
            viewModel.clearPaired()
            onPaired()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("添加设备", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "只会显示广播了 MR20 服务的录音卡。第一次连接时谛听会生成一个 16 位密钥并写进设备，之后每次连接都用它握手。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(16.dp))

        // First pairing also re-derives the recorder's Wi-Fi credentials, which
        // takes about ten seconds and a device reset. Ten silent seconds reads
        // as a hang, so the wait is named rather than hidden.
        if (connecting) LoadingBlock("正在配对：写入密钥、同步 Wi-Fi 凭据并对时，约需 10 秒…")

        if (discovered.isEmpty() && !connecting) {
            EmptyState(
                headline = "正在搜索…",
                hint = "确认录音卡已开机、蓝牙已打开，并且没有被另一台手机占用。",
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(discovered, key = { it.address }) { found ->
                RailCard(
                    rail = colors.railTranscript,
                    onClick = { viewModel.connect(found.address) },
                ) {
                    Text(
                        found.name ?: "未命名录音卡",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(found.address)
                        Pill("${found.rssi} dBm")
                    }
                }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
            title = { Text("连接失败") },
            text = { Text(message) },
        )
    }
}

// =============================================================================
// 能力与订阅 · 成长
// =============================================================================

@Composable
fun SubscriptionScreen(onOpenModels: () -> Unit) {
    val colors = ditingColors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("能力与订阅", style = MaterialTheme.typography.headlineMedium) }

        item {
            RailCard(rail = colors.railTranscript) {
                Text("自带 Key", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "谛听目前不代收费用。转写和理解都直接用你自己的千问 / 豆包 Key，或者你自建的服务，账单在你自己的控制台。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenModels) { Text("去配置模型") }
            }
        }

        item {
            RailCard(rail = colors.railInsight) {
                Text("实时通道预算", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "实时转写按时长计费，所以谛听默认不开。会议模式里可以单场打开，状态一直显示在顶部。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
fun GrowthScreen(viewModel: MeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ditingColors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("成长", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "BP 只在你确认事实、采用产出、纠正转写时增加 —— 录得多不加分。这样成长值和「克制、不打扰」才是一致的。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }

        items(com.diting.domain.growth.GrowthEvent.entries) { event ->
            RailCard(rail = colors.railTranscript) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(event.label, style = MaterialTheme.typography.bodyLarge)
                    Pill("+${event.points} BP", color = colors.railTranscript)
                }
            }
        }

        item {
            Text(
                "当前 ${state.growth.totalPoints} BP · Lv.${state.growth.level}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
