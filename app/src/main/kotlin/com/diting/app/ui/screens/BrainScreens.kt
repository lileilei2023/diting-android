@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.diting.app.brain.AuthExpiredException
import com.diting.app.brain.BrainAccount
import com.diting.app.brain.BrainApi
import com.diting.app.brain.BrainBridge
import com.diting.app.brain.BrainStore
import com.diting.app.brain.ChannelState
import com.diting.app.data.db.SessionDao
import com.diting.app.ui.components.InkPanel
import com.diting.app.ui.components.GroupToggleRow
import com.diting.app.ui.components.GroupRow
import com.diting.app.ui.components.GroupCard
import com.diting.app.ui.components.LoadingBlock
import com.diting.app.ui.components.Pill
import com.diting.app.ui.components.RailCard
import com.diting.app.ui.theme.ditingColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================================
// 我的 › 大脑账户
// =============================================================================

data class BrainUiState(
    val account: BrainAccount = BrainAccount(),
    val channel: ChannelState = ChannelState.NotConfigured,
    val uploaded: Int = 0,
    val pendingUpload: Int = 0,
)

@HiltViewModel
class BrainViewModel @Inject constructor(
    private val store: BrainStore,
    private val api: BrainApi,
    private val bridge: BrainBridge,
    sessionDao: SessionDao,
) : ViewModel() {

    val state: StateFlow<BrainUiState> = combine(
        store.account,
        bridge.channelState,
        sessionDao.observeBrainUploadedCount(),
        sessionDao.observeBrainPendingCount(),
    ) { account, channel, uploaded, pending ->
        BrainUiState(account, channel, uploaded, pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrainUiState())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setBaseUrl(url: String) = store.setBaseUrl(url)

    fun login(phone: String, password: String) = run("登录") {
        val r = api.login(phone.trim(), password)
        "已登录：${r.nick.ifBlank { r.subject }}"
    }

    fun register(phone: String, password: String, nick: String, adoptCode: String) = run("注册") {
        val r = api.register(phone.trim(), password, nick.trim(), adoptCode.trim())
        "已注册并登录：${r.nick.ifBlank { r.subject }}"
    }

    fun testConnection() = run("连接测试") {
        if (!api.health()) return@run "连不上 ${store.current.baseUrl}/health，检查地址和网络。"
        if (!store.current.isLoggedIn) return@run "服务器在线，但还没登录。"
        if (api.probe()) "服务器在线，token 有效。" else "服务器在线，但鉴权探测失败。"
    }

    fun readopt() = run("登记设备") {
        val sn = store.current.sn ?: return@run "还没有配对录音卡，先去「设备管理」配对。"
        bridge.adoptDevice(sn)
        "已把录音卡 $sn 登记到大脑，通道即将打开。"
    }

    fun uploadNow() {
        bridge.enqueueUpload()
        _message.value = "已排队上传未同步到大脑的录音。"
    }

    fun logout() = run("退出") {
        bridge.logout()
        "已退出登录。"
    }

    fun setUploadHistorical(value: Boolean) = store.update { it.copy(uploadHistorical = value) }
    fun setQuietHours(value: Boolean) = store.update { it.copy(quietHours = value) }

    fun clearMessage() {
        _message.value = null
    }

    private fun run(what: String, block: suspend () -> String) = viewModelScope.launch {
        _busy.value = true
        try {
            _message.value = block()
        } catch (e: AuthExpiredException) {
            store.clearAuth()
            _message.value = "$what 失败：登录已过期，请重新登录。"
        } catch (e: Exception) {
            _message.value = "$what 失败：${e.message ?: e::class.simpleName}"
        } finally {
            _busy.value = false
        }
    }
}

@Composable
fun BrainScreen(viewModel: BrainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = ditingColors
    val account = state.account
    val context = LocalContext.current

    var baseUrl by remember(account.baseUrl) { mutableStateOf(account.baseUrl) }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    var adoptCode by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("大脑账户", style = MaterialTheme.typography.headlineMedium) }

        if (busy) item { LoadingBlock("正在和大脑通信…") }

        if (!account.isLoggedIn) {
            item {
                RailCard(rail = colors.railAction) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (registering) {
                            OutlinedButton(onClick = { registering = false }) { Text("登录") }
                            Button(onClick = { registering = true }) { Text("注册") }
                        } else {
                            Button(onClick = { registering = false }) { Text("登录") }
                            OutlinedButton(onClick = { registering = true }) { Text("注册") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("手机号") }, singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (registering) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nick, onValueChange = { nick = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = adoptCode, onValueChange = { adoptCode = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("绑定码") },
                            supportingText = { Text("接到已有大脑数据时填写") },
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !busy && phone.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            viewModel.setBaseUrl(baseUrl)
                            if (registering) viewModel.register(phone, password, nick, adoptCode)
                            else viewModel.login(phone, password)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (registering) "注册并登录" else "登录") }
                }
            }

            item {
                RailCard(rail = colors.inkMuted, contentPadding = PaddingValues(14.dp, 12.dp)) {
                    Text("服务器", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = {
                            viewModel.setBaseUrl(baseUrl)
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        }) { Text("保存") }
                        TextButton(onClick = viewModel::testConnection, enabled = !busy) { Text("测试连接") }
                    }
                }
            }
        } else {
            item {
                InkPanel {
                    Text(
                        account.nick.ifBlank { account.subject ?: "已登录" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onInkPanel,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(account.baseUrl, style = MaterialTheme.typography.bodySmall, color = colors.accentOnPanel)
                    Spacer(Modifier.height(14.dp))
                    val (dotColor, channelText) = when (val ch = state.channel) {
                        is ChannelState.Live -> colors.accentOnPanel to "设备通道在线"
                        ChannelState.Opening -> colors.accentOnPanel to "设备通道连接中"
                        ChannelState.Disconnected -> colors.railAction to "设备通道离线，重连中"
                        is ChannelState.AuthFailed -> colors.railBlocker to "设备通道被拒绝"
                        ChannelState.NotConfigured -> colors.railAction to "录音卡还没登记"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                        Text(channelText, style = MaterialTheme.typography.bodyMedium, color = colors.onInkPanel)
                    }
                    account.sn?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("录音卡 $it", style = MaterialTheme.typography.bodySmall, color = colors.accentOnPanel)
                    }
                }
            }

            item {
                GroupCard {
                    GroupRow(
                        title = "现在上传",
                        subtitle = "同步下来的录音送去大脑转写、提待办",
                        value = "已传 ${state.uploaded} · 待传 ${state.pendingUpload}",
                        chevron = true,
                        onClick = viewModel::uploadNow,
                    )
                    GroupToggleRow(
                        title = "包含配对前的历史录音",
                        subtitle = "默认不上传卡里更早的录音",
                        checked = account.uploadHistorical,
                        onCheckedChange = viewModel::setUploadHistorical,
                    )
                    GroupToggleRow(
                        title = "夜间勿扰 22:00–08:00",
                        subtitle = "只静默通知，不震动",
                        checked = account.quietHours,
                        onCheckedChange = viewModel::setQuietHours,
                        last = true,
                    )
                }
            }

            item {
                GroupCard {
                    GroupRow(
                        title = "服务器",
                        value = account.baseUrl.removePrefix("http://").removePrefix("https://"),
                        last = false,
                    )
                    GroupRow(
                        title = "测试连接",
                        chevron = true,
                        enabled = !busy,
                        onClick = viewModel::testConnection,
                    )
                    GroupRow(
                        title = "重新登记录音卡",
                        subtitle = "换一把设备 key；通道被拒时用",
                        chevron = true,
                        enabled = !busy && account.sn != null,
                        onClick = viewModel::readopt,
                    )
                    GroupRow(
                        title = "退出登录",
                        titleColor = colors.railBlocker,
                        enabled = !busy,
                        last = true,
                        onClick = viewModel::logout,
                    )
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("知道了") } },
            text = { Text(text) },
        )
    }
}
