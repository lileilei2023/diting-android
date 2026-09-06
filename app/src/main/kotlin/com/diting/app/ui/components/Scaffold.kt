@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.diting.app.ui.nav.Routes
import com.diting.app.ui.theme.ditingColors

/**
 * The four tabs plus the record key.
 *
 * The record key sits above the bar rather than in it, because it is not a fifth
 * destination — it is an action available from all four. Short press captures,
 * long press opens 会议模式, which is the distinction the deck draws between
 * "不联网的快速捕捉" and the realtime channel whose cost the user can see.
 */
@Composable
fun DitingBottomBar(
    currentRoute: String?,
    gateCount: Int,
    isRecording: Boolean,
    onNavigate: (String) -> Unit,
    onRecordTap: () -> Unit,
    onRecordLongPress: () -> Unit,
) {
    val colors = ditingColors

    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = colors.paperElevated,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(62.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabItem(
                    icon = Icons.Filled.Home,
                    label = "今日",
                    selected = currentRoute == Routes.TODAY,
                    modifier = Modifier.weight(1f),
                ) { onNavigate(Routes.TODAY) }

                TabItem(
                    icon = Icons.Outlined.GraphicEq,
                    label = "记录",
                    selected = currentRoute == Routes.SESSIONS,
                    modifier = Modifier.weight(1f),
                ) { onNavigate(Routes.SESSIONS) }

                // The record key's footprint, so the two right-hand tabs are not
                // pushed under it.
                Box(Modifier.weight(1f))

                TabItem(
                    icon = Icons.Filled.CheckCircle,
                    label = "任务",
                    selected = currentRoute == Routes.TASKS,
                    badge = gateCount,
                    modifier = Modifier.weight(1f),
                ) { onNavigate(Routes.TASKS) }

                TabItem(
                    icon = Icons.Filled.Person,
                    label = "我的",
                    selected = currentRoute == Routes.ME,
                    modifier = Modifier.weight(1f),
                ) { onNavigate(Routes.ME) }
            }
        }

        RecordKey(
            isRecording = isRecording,
            onTap = onRecordTap,
            onLongPress = onRecordLongPress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-6).dp),
        )
    }
}

@Composable
private fun TabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = ditingColors
    val tint = if (selected) colors.railTranscript else colors.inkMuted

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(
            badge = {
                // Amber, because a task at a gate is waiting on the user — the
                // same colour the card itself uses.
                if (badge > 0) {
                    Badge(containerColor = colors.railAction) { Text(badge.toString()) }
                }
            }
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Short press = quick capture, long press = 会议模式.
 *
 * `combinedClickable` rather than a gesture detector so TalkBack still announces
 * it as a button; the long-press action is exposed as a separate affordance in
 * the recording screen for anyone who cannot perform it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordKey(
    isRecording: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ditingColors
    Box(
        modifier = modifier
            .size(58.dp)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(if (isRecording) colors.railAction else colors.railTranscript)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "停止录音" else "开始录音（长按进入会议模式）",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
