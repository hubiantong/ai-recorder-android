package com.example.ai_recorder_android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.theme.Spacing
import com.example.ai_recorder_android.ui.theme.TimestampStyle
import com.example.ai_recorder_android.ui.theme.tabularNums
import java.io.File
import kotlin.math.abs

/**
 * AI 笔记的解析与渲染（详情页笔记卡专用，轻量自实现，无 Markwon 依赖）。
 *
 * 解析目标**对齐实际生成格式**而非完整 Markdown 规范：
 * - LLM 笔记（SYSTEM_PROMPT 约束）：`## 主题摘要 / ## 关键要点 / ## 时间轴要点` 三节 +
 *   照片引用 `![描述](attachment:秒数)`；
 * - 模板回退笔记（DeepSeekConnector.buildTemplateNote）：`# 标题` + `> 录音时长` +
 *   `- [10s] xxx` 时间戳列表（照片行带 `[照片]` 文本标记）。
 *
 * 容错原则：LLM 输出无格式校验，未识别的行一律降级 Paragraph，绝不抛异常；
 * 加粗/行内代码/待办 checkbox 等语法为容错超集（prompt 未要求但模型可能输出）。
 */

/** 笔记渲染块。 */
internal sealed interface NoteBlock {
    /** `#`/`##`/`###` 标题，[level] 从 1 起。 */
    data class Heading(val level: Int, val text: List<NoteInline>) : NoteBlock

    /** 无序列表项；[timestampSec] 为行首 `[Ns]` 时间戳（用于可点击 seek）。 */
    data class Bullet(val text: List<NoteInline>, val timestampSec: Double? = null) : NoteBlock

    /** `- [ ]`/`- [x]` 待办项（容错超集，prompt 未要求）。 */
    data class CheckboxItem(val text: List<NoteInline>, val checked: Boolean) : NoteBlock

    /** 有序列表项 `1. `/`1)`。 */
    data class OrderedItem(val index: Int, val text: List<NoteInline>) : NoteBlock

    /** `> ` 引用行（模板笔记的录音时长行）。 */
    data class Quote(val text: List<NoteInline>) : NoteBlock

    /** `---`/`***`/`___` 分隔线。 */
    data object Divider : NoteBlock

    /** 照片引用 `![描述](attachment:秒数)`；行内引用剥离后同样落此块。 */
    data class PhotoRef(val description: String, val timestampSec: Double) : NoteBlock

    /** 普通段落。 */
    data class Paragraph(val text: List<NoteInline>, val timestampSec: Double? = null) : NoteBlock
}

/** 行内片段：`**加粗**`、`` `行内代码` ``，其余原样。 */
internal sealed interface NoteInline {
    data class Plain(val text: String) : NoteInline
    data class Bold(val text: List<NoteInline>) : NoteInline
    data class Code(val text: String) : NoteInline
}

private val HEADING = Regex("""^(#{1,6})\s*(.+)$""")
private val DIVIDER = Regex("""^([-*_])\1{2,}$""")
private val CHECKBOX = Regex("""^[-*]\s+\[([xX ])\]\s*(.*)$""")
private val BULLET = Regex("""^[-*]\s+(.*)$""")
private val ORDERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")

/** 行首时间戳前缀 `[10s]`/`[10.5s]`（对齐模板 `%.0f` 与 LLM 常见输出）。 */
private val TIMESTAMP_PREFIX = Regex("""^\[(\d+(?:\.\d+)?)s\]\s*""")

/** 照片引用 `![描述](attachment:15)` / `![描述](attachment:15.0s)`。 */
private val PHOTO_REF = Regex("""!\[(.*?)\]\(attachment:(\d+(?:\.\d+)?)s?\)""")

/**
 * 行级容错解析：空行跳过，未识别语法降级 Paragraph；
 * 行内照片引用剥离为独立 [NoteBlock.PhotoRef]（剥离后正文为空则整块丢弃）。
 */
internal fun parseNoteMarkdown(markdown: String): List<NoteBlock> =
    markdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { parseLine(it) }
        .toList()

private fun parseLine(line: String): List<NoteBlock> {
    PHOTO_REF.find(line)?.let { m ->
        if (m.value == line.trim()) {
            return listOf(m.toPhotoRef())
        }
    }

    HEADING.find(line)?.let { m ->
        return listOf(NoteBlock.Heading(m.groupValues[1].length, parseInlines(m.groupValues[2])))
    }

    if (DIVIDER.matches(line)) return listOf(NoteBlock.Divider)

    CHECKBOX.find(line)?.let { m ->
        return textBlocks(m.groupValues[2].trim(), null) { text ->
            NoteBlock.CheckboxItem(text, checked = m.groupValues[1] != " ")
        }
    }

    BULLET.find(line)?.let { m ->
        val body = m.groupValues[1].trim()
        val ts = TIMESTAMP_PREFIX.find(body)?.let { it.groupValues[1].toDouble() to it.value } ?: (null to "")
        val rest = body.removePrefix(ts.second)
        return textBlocks(rest.trim(), ts.first) { text -> NoteBlock.Bullet(text, ts.first) }
    }

    ORDERED.find(line)?.let { m ->
        return textBlocks(m.groupValues[2].trim(), null) { text ->
            NoteBlock.OrderedItem(m.groupValues[1].toInt(), text)
        }
    }

    QUOTE.find(line)?.let { m ->
        return textBlocks(m.groupValues[1].trim(), null) { text -> NoteBlock.Quote(text) }
    }

    val ts = TIMESTAMP_PREFIX.find(line)
    val rest = if (ts != null) line.removePrefix(ts.value) else line
    return textBlocks(rest.trim(), ts?.groupValues?.get(1)?.toDouble()) { text ->
        NoteBlock.Paragraph(text, ts?.groupValues?.get(1)?.toDouble())
    }
}

/** 提取行内照片引用并构建正文块 + PhotoRef 块；正文为空（纯照片行/空列表项）只留 PhotoRef。 */
private inline fun textBlocks(
    text: String,
    timestampSec: Double?,
    build: (List<NoteInline>) -> NoteBlock,
): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var rest = text
    PHOTO_REF.findAll(text).forEach { m ->
        blocks.add(m.toPhotoRef())
        rest = rest.replace(m.value, "")
    }
    if (rest.isNotBlank()) {
        blocks.add(0, build(parseInlines(rest.trim())))
    }
    return blocks
}

private fun MatchResult.toPhotoRef(): NoteBlock.PhotoRef = NoteBlock.PhotoRef(
    description = groupValues[1].ifBlank { "照片" },
    timestampSec = groupValues[2].toDouble(),
)

/**
 * 行内解析：`**加粗**` → Bold（内部再拆行内代码），反引号 → Code。
 * 未闭合的 `**`/反引号按原样文本返回（容错）。
 */
internal fun parseInlines(text: String): List<NoteInline> {
    val out = mutableListOf<NoteInline>()
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        if (start < 0) break
        val end = rest.indexOf("**", start + 2)
        if (end < 0) break
        out += splitCode(rest.substring(0, start))
        val inner = rest.substring(start + 2, end)
        if (inner.isNotBlank()) out += NoteInline.Bold(splitCode(inner))
        rest = rest.substring(end + 2)
    }
    out += splitCode(rest)
    return out.ifEmpty { listOf(NoteInline.Plain("")) }
}

/** 反引号分段：奇数个（未闭合）整体按纯文本返回，避免半截高亮。 */
private fun splitCode(text: String): List<NoteInline> {
    val segments = text.split('`')
    if (segments.size % 2 == 0) return listOf(NoteInline.Plain(text))
    val out = mutableListOf<NoteInline>()
    segments.forEachIndexed { i, seg ->
        if (seg.isNotEmpty()) out += if (i % 2 == 1) NoteInline.Code(seg) else NoteInline.Plain(seg)
    }
    return out
}

/** 照片引用秒数 → 就近匹配附件；超过 [toleranceSeconds] 视为无匹配（LLM 可能把秒数取整）。 */
internal fun matchAttachment(
    attachments: List<RecordingAttachmentEntity>,
    timestampSec: Double,
    toleranceSeconds: Double = 5.0,
): RecordingAttachmentEntity? = attachments
    .minByOrNull { abs(it.timestamp - timestampSec) }
    ?.takeIf { abs(it.timestamp - timestampSec) <= toleranceSeconds }

/** 笔记时间戳秒 → "m:ss"（详情页播放器/转写/笔记时间展示的单一实现）。 */
internal fun formatNoteTimestamp(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** 安全解析 locator；非法路径返回 null（不抛异常）。 */
internal fun resolveOrNull(storage: StorageService, locator: String): File? =
    try {
        storage.resolvePath(locator)
    } catch (_: IllegalArgumentException) {
        null
    }

// ---------- 渲染 ----------

/**
 * 融合笔记：层级化 Markdown 渲染。
 * 解析见 [parseNoteMarkdown]；渲染按块类型分层：标题分级、时间戳行可点击 seek、
 * 照片引用内嵌、待办/引用/分隔线，间距走 Spacing 令牌。
 */
@Composable
internal fun FusionNoteSection(
    summary: String,
    images: List<RecordingAttachmentEntity>,
    storage: StorageService,
    onSeekSeconds: (Double) -> Unit,
    onOpenAttachment: (RecordingAttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(summary) { parseNoteMarkdown(summary) }
    SectionCard(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            NoteBlockView(
                block = block,
                isFirst = index == 0,
                images = images,
                storage = storage,
                onSeekSeconds = onSeekSeconds,
                onOpenAttachment = onOpenAttachment,
            )
        }
    }
}

/** 单个笔记块渲染。块顶距：节标题前 md、图片/分隔线前 sm、其余 xs，首块无顶距。 */
@Composable
private fun NoteBlockView(
    block: NoteBlock,
    isFirst: Boolean,
    images: List<RecordingAttachmentEntity>,
    storage: StorageService,
    onSeekSeconds: (Double) -> Unit,
    onOpenAttachment: (RecordingAttachmentEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val codeBackground = scheme.surfaceContainerHigh
    val topPad = when {
        isFirst -> 0.dp
        block is NoteBlock.Heading && block.level <= 2 -> Spacing.md
        block is NoteBlock.Heading -> Spacing.sm
        block is NoteBlock.PhotoRef || block is NoteBlock.Divider -> Spacing.sm
        else -> Spacing.xs
    }
    when (block) {
        is NoteBlock.Heading -> Text(
            text = noteInlineText(block.text, codeBackground),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = if (block.level == 2) scheme.primary else scheme.onSurface,
            modifier = Modifier.padding(top = topPad),
        )

        is NoteBlock.Bullet -> NoteBulletRow(
            timestampSec = block.timestampSec,
            onSeekSeconds = onSeekSeconds,
            modifier = Modifier.padding(top = topPad),
        ) {
            Text(noteInlineText(block.text, codeBackground), style = MaterialTheme.typography.bodyMedium)
        }

        is NoteBlock.CheckboxItem -> NoteCheckboxRow(
            block = block,
            codeBackground = codeBackground,
            modifier = Modifier.padding(top = topPad),
        )

        is NoteBlock.OrderedItem -> Row(modifier = Modifier.padding(top = topPad)) {
            Text(
                text = "${block.index}.",
                style = MaterialTheme.typography.labelMedium.tabularNums(),
                color = scheme.primary,
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(noteInlineText(block.text, codeBackground), style = MaterialTheme.typography.bodyMedium)
        }

        is NoteBlock.Quote -> Row(
            modifier = Modifier
                .padding(top = topPad)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(scheme.primary.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = noteInlineText(block.text, codeBackground),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        is NoteBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(top = topPad),
            thickness = Spacing.hairline,
            color = scheme.outlineVariant,
        )

        is NoteBlock.PhotoRef -> NotePhotoRef(
            block = block,
            images = images,
            storage = storage,
            onOpenAttachment = onOpenAttachment,
            topPad = topPad,
        )

        is NoteBlock.Paragraph -> if (block.timestampSec != null) {
            NoteBulletRow(
                timestampSec = block.timestampSec,
                onSeekSeconds = onSeekSeconds,
                showDot = false,
                modifier = Modifier.padding(top = topPad),
            ) {
                Text(noteInlineText(block.text, codeBackground), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(
                text = noteInlineText(block.text, codeBackground),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = topPad),
            )
        }
    }
}

/**
 * 列表行骨架：可选圆点 + 时间戳标签 + 内容。
 * 带时间戳的行整行可点击 seek（对齐转写气泡的整行可点先例，触控目标为全宽行而非小标签）。
 */
@Composable
private fun NoteBulletRow(
    timestampSec: Double?,
    onSeekSeconds: (Double) -> Unit,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val seekModifier = timestampSec?.let { sec ->
        Modifier.fillMaxWidth().clickable { onSeekSeconds(sec) }
    } ?: Modifier
    Row(modifier = modifier.then(seekModifier)) {
        if (showDot) {
            Text("•", color = scheme.primary)
            Spacer(Modifier.width(Spacing.xs))
        }
        timestampSec?.let { sec ->
            Text(
                text = formatNoteTimestamp(sec),
                style = TimestampStyle,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        content()
    }
}

/** 待办行：自绘勾选框（无 material-icons-extended 依赖）+ 文本，完成态变灰。 */
@Composable
private fun NoteCheckboxRow(
    block: NoteBlock.CheckboxItem,
    codeBackground: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val boxShape = RoundedCornerShape(4.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(boxShape)
                .then(
                    if (block.checked) {
                        Modifier.background(scheme.primary)
                    } else {
                        Modifier.border(1.dp, scheme.outlineVariant, boxShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (block.checked) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
            }
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = noteInlineText(block.text, codeBackground),
            style = MaterialTheme.typography.bodyMedium,
            color = if (block.checked) scheme.onSurfaceVariant else Color.Unspecified,
        )
    }
}

/** 笔记内嵌照片卡：就近匹配附件渲染缩略图 + 描述（点击全屏）；匹配不到降级为「照片」标签行。 */
@Composable
private fun NotePhotoRef(
    block: NoteBlock.PhotoRef,
    images: List<RecordingAttachmentEntity>,
    storage: StorageService,
    onOpenAttachment: (RecordingAttachmentEntity) -> Unit,
    topPad: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    val attachment = remember(block.timestampSec, images) { matchAttachment(images, block.timestampSec) }
    if (attachment == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = topPad),
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = scheme.secondaryContainer) {
                Text(
                    text = "照片",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = block.description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        return
    }
    val file = resolveOrNull(storage, attachment.storageLocator)
    Column(
        modifier = Modifier
            .padding(top = topPad)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpenAttachment(attachment) },
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = block.description,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(scheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NoteImageHeight)
                    .clip(MaterialTheme.shapes.small),
            )
        }
        Text(
            text = block.description,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs),
        )
    }
}

/** 笔记内嵌照片卡高度。 */
private val NoteImageHeight = 160.dp

/** 行内片段 → AnnotatedString：Bold 加重、Code 等宽 + 底色。 */
private fun noteInlineText(inlines: List<NoteInline>, codeBackground: Color): AnnotatedString =
    buildAnnotatedString { appendInlines(inlines, codeBackground) }

private fun AnnotatedString.Builder.appendInlines(inlines: List<NoteInline>, codeBackground: Color) {
    inlines.forEach { inline ->
        when (inline) {
            is NoteInline.Plain -> append(inline.text)
            is NoteInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlines(inline.text, codeBackground)
            }
            is NoteInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) { append(inline.text) }
        }
    }
}
