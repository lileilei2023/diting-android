@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.diting.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Brush
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


// -- Grouped list (the deck's 我的 / 录音卡 pages) -----------------------------

/**
 * A white sheet holding rows separated by hairlines — the deck's settings idiom.
 * Rows are [GroupRow] / [GroupToggleRow]; the last row passes `last = true` so
 * the sheet does not end on a divider.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = ditingColors.paperCard,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/**
 * One settings row: title (+ optional subtitle) on the left, a value and/or a
 * chevron on the right. Disabled rows fade rather than disappear, so a screen
 * never loses buttons when the device is merely disconnected.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = ditingColors.inkMuted,
    chevron: Boolean = false,
    enabled: Boolean = true,
    last: Boolean = false,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ditingColors
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 13.dp)
                .alpha(alpha),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = if (leading != null) FontWeight.SemiBold else null)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                }
            }
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End)
            }
            if (chevron) Text("›", style = MaterialTheme.typography.titleMedium, color = colors.inkMuted)
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
fun GroupToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    last: Boolean = false,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ditingColors.inkMuted) }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/** 6 px status dot: mint = connected, amber = working, red = recording, grey = offline. */
@Composable
fun StatusDot(color: Color, size: Dp = 6.dp) {
    Box(
        Modifier
            .size(size)
            .background(color, CircleShape)
    )
}

/** A soft amber notice with a 3 px rail and a thin progress bar — the deck's 回填中 card. */
@Composable
fun ProgressNotice(
    title: String,
    body: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    accent: Color = ditingColors.railAction,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp, 12.dp, 14.dp, 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = ditingColors.inkMuted)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = accent,
                    trackColor = ditingColors.paperCard,
                )
            }
        }
    }
}


/** The deck's 32 dp rounded icon square that leads a settings row. */
@Composable
fun IconSquare(color: Color, modifier: Modifier = Modifier, brush: Brush? = null) {
    Box(
        modifier
            .size(32.dp)
            .then(if (brush != null) Modifier.background(brush, RoundedCornerShape(8.dp)) else Modifier.background(color, RoundedCornerShape(8.dp)))
    )
}

/** Spaced-out small caps label the deck uses above every section ("今天", "扫描结果 · 3 个"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = ditingColors.inkMuted) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = TextUnit(0.14f, TextUnitType.Em),
    )
}

/** Sunken pill group with one white selected segment — the deck's 会话/周/月 control. */
@Composable
fun SegmentedPills(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(ditingColors.paperSunken, PillShape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Text(
                label,
                modifier = Modifier
                    .clip(PillShape)
                    .then(if (active) Modifier.background(Color.White, PillShape) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) ditingColors.inkPanel else ditingColors.inkMuted,
            )
        }
    }
}

/** Plain white sheet with the deck's soft shadow — for list cards without a rail. */
@Composable
fun SheetCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp, 14.dp),
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    val body: @Composable () -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onLongClick != null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = Color.White, border = border, shadowElevation = 1.dp) {
            Box(Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)) { body() }
        }
    } else if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CardShape, color = Color.White, border = border, shadowElevation = 1.dp, content = body)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = Color.White, border = border, shadowElevation = 1.dp, content = body)
    }
}


// -- Detail-page chrome (the deck's 会话详情 / 洞察 / 任务详情 headers) ---------

/** 36dp white circle with a chevron — every deep page's back affordance. */
@Composable
fun BackCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White)
            .then(Modifier.border(1.dp, ditingColors.paperSunken, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = ditingColors.inkPanel,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Small mint text link — 「生成报告 ›」「事件详情 ›」. */
@Composable
fun MintLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = ditingColors.railTranscript,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Underlined tab strip with a hairline under the row — the deck's 转写/总结/导图
 * and 纪要/待确认/定位张力 controls. [trailing] sits at the right end of the row.
 */
@Composable
fun UnderlineTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ditingColors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Column(Modifier.width(IntrinsicSize.Max).clickable { onSelect(i) }) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (active) colors.inkPanel else colors.inkMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) colors.railTranscript else Color.Transparent)
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(bottom = 6.dp)) { trailing() }
            }
        }
        HorizontalDivider(color = colors.paperSunken)
    }
}

/** Tiny outlined pill toggle (「中英对照」). */
@Composable
fun TogglePill(text: String, on: Boolean, onClick: () -> Unit) {
    val colors = ditingColors
    Text(
        text,
        modifier = Modifier
            .clip(PillShape)
            .background(if (on) colors.railTranscript.copy(alpha = 0.10f) else Color.White)
            .border(1.dp, if (on) colors.railTranscript else colors.paperSunken, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (on) colors.railTranscript else colors.inkMuted,
        maxLines = 1,
        softWrap = false,
    )
}

/** White bordered 14dp-radius button — the secondary half of a bottom bar. */
@Composable
fun WhiteAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, ditingColors.paperSunken, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ditingColors.inkPanel, maxLines = 1, softWrap = false)
    }
}

/** Solid mint 14dp-radius button — the primary half of a bottom bar. */
@Composable
fun MintAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, fill: Color = ditingColors.railTranscript) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false)
    }
}

/** The 48dp radial mint orb that opens a scoped AI chat. */
@Composable
fun AiOrb(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val mint = ditingColors.railTranscript
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.White, mint),
                    center = androidx.compose.ui.geometry.Offset(size.value * 0.9f, size.value * 0.9f),
                    radius = size.value * 2.6f,
                )
            )
            .clickable(onClick = onClick),
    )
}

/**
 * Bottom action bar that fades up from the paper — the deck's fixed footer on
 * every detail page. Content is a Row; give each action `weight(1f)`.
 */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val paper = ditingColors.paperRoot
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper, paper)))
            .padding(14.dp, 18.dp, 14.dp, 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Mono 12sp secondary text — the deck's `time · dur · speakers` meta line. */
@Composable
fun MonoMeta(text: String, modifier: Modifier = Modifier, color: Color = ditingColors.inkMuted) {
    Text(text, modifier = modifier, style = TimestampStyle, color = color, maxLines = 1)
}

/**
 * The deck's 16dp-radius white sheet with an optional 3dp left rail — the
 * summary / insight cards. Unlike [RailCard] the rail is part of the border,
 * and the fill can be tinted (the amber 待办 card).
 */
@Composable
fun RailSheet(
    rail: Color? = null,
    modifier: Modifier = Modifier,
    fill: Color = Color.White,
    border: Color = ditingColors.paperSunken,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp, 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        if (rail != null) Box(Modifier.width(3.dp).fillMaxHeight().background(rail))
        Column(Modifier.weight(1f).padding(contentPadding), content = content)
    }
}
