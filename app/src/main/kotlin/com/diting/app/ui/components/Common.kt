@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diting.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diting.app.ui.theme.TimestampStyle
import com.diting.app.ui.theme.ditingColors
import com.diting.domain.model.Citation

private val CardShape = RoundedCornerShape(18.dp)
private val PillShape = RoundedCornerShape(percent = 50)

/**
 * The design's core card: a coloured left rail plus paper.
 *
 * The rail is not decoration. Mint means "this came from the recording", slate
 * means "小谛 wrote this", amber means "this is waiting on you". Users learn to
 * read the rail before the text, so every card in the app goes through here
 * rather than styling its own border.
 */
@Composable
fun RailCard(
    rail: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ditingColors
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    // IntrinsicSize.Min lets the rail stretch to the content's height without the
    // caller having to know it.
    val body: @Composable () -> Unit = {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(rail)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = colors.paperCard,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = colors.paperCard,
            border = border,
            content = body,
        )
    }
}

/**
 * The "↩ 回到原声" chip.
 *
 * Present on every generated line that has a [Citation], absent when it does
 * not. That absence is meaningful: it means the model gave no usable segment
 * index, and a chip that jumps somewhere plausible-but-wrong is worse than no
 * chip at all.
 */
@Composable
fun CitationChip(
    citation: Citation,
    onClick: (Citation) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = ditingColors
    Surface(
        onClick = { onClick(citation) },
        modifier = modifier,
        shape = PillShape,
        color = colors.railTranscript.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "回到原声",
                tint = colors.railTranscript,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label ?: citation.timeLabel(),
                style = TimestampStyle,
                color = colors.railTranscript,
                fontWeight = FontWeight.SemiBold,
                // Chips must never wrap: a broken pill reads as a layout bug, and
                // fixing exactly this was a round of the design review.
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** A small pill label. Never wraps — see [CitationChip]. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Surface(modifier = modifier, shape = PillShape, color = background) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Section heading: a spaced-out accent label above a serif title. */
@Composable
fun SectionHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = ditingColors.railTranscript,
) {
    Column(modifier) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Dark panel — the deck's 北极星闭环 strip and the Sunday review card. */
@Composable
fun InkPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ditingColors
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(18.dp), content = content)
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = colors.inkPanel,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = colors.inkPanel,
            content = body,
        )
    }
}

/** Empty state that says what to do next, not merely that there is nothing here. */
@Composable
fun EmptyState(
    headline: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = ditingColors.inkMuted,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
}

@Composable
fun LoadingBlock(label: String = "小谛正在处理…", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = ditingColors.railTranscript,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ditingColors.inkMuted)
    }
}

/** Formats a duration for the session list: `12:05` or `1:12:05`. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

/** `9月6日 · 周日` — the header on 今日谛听. */
fun formatDayHeader(epochMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    val weekday = when (time.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "周一"
        java.time.DayOfWeek.TUESDAY -> "周二"
        java.time.DayOfWeek.WEDNESDAY -> "周三"
        java.time.DayOfWeek.THURSDAY -> "周四"
        java.time.DayOfWeek.FRIDAY -> "周五"
        java.time.DayOfWeek.SATURDAY -> "周六"
        java.time.DayOfWeek.SUNDAY -> "周日"
    }
    return "${time.monthValue}月${time.dayOfMonth}日 · $weekday"
}

fun formatClock(epochMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(time.hour, time.minute)
}

/** `9月6日` — used in list section headers. */
fun formatShortDate(epochMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    return "${time.monthValue}月${time.dayOfMonth}日"
}
