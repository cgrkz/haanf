package app.haanf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import app.haanf.training.Zone
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import app.haanf.ui.theme.color
import app.haanf.ui.theme.condensed

/*
 * The shared pieces of the visual system: 8 dp corners, flat cards with no shadows or
 * borders, outline icons, and touch targets of at least 48 dp.
 */

val Corner = RoundedCornerShape(8.dp)

@Composable
fun HScreen(
    modifier: Modifier = Modifier,
    background: Color = HaanfColors.Background,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // At least a screen tall, so BottomActions can sit at the bottom of a short screen.
    BoxWithConstraints(Modifier.fillMaxSize().background(background).then(modifier)) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun Eyebrow(text: String, color: Color = HaanfColors.TextSecondary) {
    Text(text, style = body(14.sp, FontWeight.SemiBold), color = color)
}

@Composable
fun ScreenTitle(text: String, size: Int = 44, color: Color = HaanfColors.Text, modifier: Modifier = Modifier) {
    Text(text, style = condensed(size.sp, lineHeight = 1.03), color = color, modifier = modifier)
}

@Composable
fun Lead(text: String, color: Color = HaanfColors.TextSecondary) {
    Text(text, style = body(16.sp), color = color)
}

@Composable
fun BodyText(text: String, size: Int = 16, weight: FontWeight = FontWeight.Normal, color: Color = HaanfColors.Text) {
    Text(text, style = body(size.sp, weight), color = color)
}

@Composable
fun SectionHeading(text: String, color: Color = HaanfColors.Text) {
    Text(text, style = body(17.sp, FontWeight.Bold), color = color)
}

@Composable
fun BigNumber(value: String, unit: String? = null, size: Int = 48, color: Color = HaanfColors.Text, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Text(value, style = condensed(size.sp, lineHeight = 0.95), color = color)
        if (unit != null) {
            Text(
                unit,
                style = condensed((size / 6.5).coerceAtLeast(16.0).sp, FontWeight.SemiBold),
                color = color,
                modifier = Modifier.padding(start = 6.dp, bottom = (size / 10).dp),
            )
        }
    }
}

@Composable
fun HCard(
    modifier: Modifier = Modifier,
    color: Color = HaanfColors.Card,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Corner)
            .background(color)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    container: Color = HaanfColors.Primary,
    content: Color = HaanfColors.White,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Corner,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = HaanfColors.WeekFuture,
            disabledContentColor = HaanfColors.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        Text(text, style = body(if (height >= 60.dp) 19.sp else 17.sp, FontWeight.Bold))
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    color: Color = HaanfColors.Text,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Corner,
        border = BorderStroke(if (height >= 60.dp) 2.dp else 1.5.dp, if (enabled) color else HaanfColors.InputBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        Text(text, style = body(if (height >= 60.dp) 18.sp else 16.sp, FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

/** An underlined text action, sized for a thumb and aligned with the text around it. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HaanfColors.Text) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(Corner)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = body(16.sp, FontWeight.SemiBold).copy(textDecoration = TextDecoration.Underline),
            color = color,
        )
    }
}

@Composable
fun HIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = HaanfColors.Text) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    Box(Modifier.padding(start = 0.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).padding(end = 4.dp)) {
            Icon(HaanfIcons.Back, contentDescription = "Back", tint = HaanfColors.Text)
        }
    }
}

/** Choices as a row of buttons. The chosen one is filled black. */
@Composable
fun <T> Choices(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    columns: Int = options.size,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val chosen = option == selected
                    OutlinedButton(
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        shape = Corner,
                        border = BorderStroke(1.dp, HaanfColors.Text),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (chosen) HaanfColors.Text else HaanfColors.Card,
                            contentColor = if (chosen) HaanfColors.White else HaanfColors.Text,
                            disabledContainerColor = if (chosen) HaanfColors.TextSecondary else HaanfColors.Card,
                            disabledContentColor = if (chosen) HaanfColors.White else HaanfColors.TextSecondary,
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(height)
                            .semantics { this.selected = chosen },
                    ) {
                        Text(label(option), style = body(16.sp, FontWeight.SemiBold, 1.15), textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun LabeledChoices(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(title)
        content()
    }
}

@Composable
fun ToggleRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
            if (hint != null) Text(hint, style = body(15.sp, lineHeight = 1.3), color = HaanfColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HaanfColors.White,
                checkedTrackColor = HaanfColors.Primary,
                checkedBorderColor = HaanfColors.Primary,
                uncheckedThumbColor = HaanfColors.White,
                uncheckedTrackColor = HaanfColors.SwitchOff,
                uncheckedBorderColor = HaanfColors.SwitchOff,
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

enum class NoticeKind { WARNING, INFO }

@Composable
fun Notice(text: String, kind: NoticeKind = NoticeKind.WARNING, icon: ImageVector? = null, title: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Corner)
            .background(if (kind == NoticeKind.WARNING) HaanfColors.WarningBackground else HaanfColors.InfoBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shown = icon ?: if (kind == NoticeKind.WARNING) HaanfIcons.Warning else null
        if (shown != null) {
            Icon(shown, contentDescription = null, tint = HaanfColors.WarningIcon, modifier = Modifier.size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = body(16.sp, FontWeight.Bold), color = HaanfColors.Text)
            Text(text, style = body(15.sp), color = HaanfColors.Text)
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, size: Int = 30) {
    Column(
        modifier
            .clip(Corner)
            .background(HaanfColors.Card)
            .padding(12.dp),
    ) {
        Text(value, style = condensed(size.sp), color = HaanfColors.Text)
        Text(label, style = body(14.sp), color = HaanfColors.TextSecondary)
    }
}

/** Stat tiles side by side, all as tall as the tallest. */
@Composable
fun StatRow(vararg tiles: Pair<String, String>, size: Int = 30) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f).fillMaxHeight(), size) }
    }
}

/** A row of a list, with a divider below unless it is the last. */
@Composable
fun ListRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit = {},
    divider: Boolean = true,
    minHeight: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.weight(1f)) { start() }
            end()
        }
        if (divider) HorizontalDivider(color = HaanfColors.Divider, thickness = 1.dp)
    }
}

/** Zones as proportional coloured bars. Names always appear next to the colours elsewhere. */
@Composable
fun ZoneBar(parts: List<Pair<Zone, Float>>, height: Dp = 12.dp, description: String) {
    val shown = parts.filter { it.second > 0f }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description; role = Role.Image },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { (zone, weight) ->
            Box(
                Modifier
                    .weight(weight)
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(zone.color()),
            )
        }
    }
}

@Composable
fun ZoneKey(zone: Zone, text: String, value: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(zone.color()))
        Text(text, style = body(15.sp), color = HaanfColors.Text, modifier = Modifier.weight(1f, fill = value != null))
        if (value != null) Text(value, style = body(15.sp, FontWeight.SemiBold), color = HaanfColors.Text)
    }
}

/** A small line chart with its latest point marked. Values are drawn as given, oldest first. */
@Composable
fun Sparkline(values: List<Double>, description: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .width(108.dp)
            .height(36.dp)
            .semantics { contentDescription = description; role = Role.Image },
    ) {
        if (values.size < 2) return@Canvas
        val low = values.min()
        val high = values.max()
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val inset = 5.dp.toPx()
        val usableW = size.width - inset * 2
        val usableH = size.height - inset * 2
        val points = values.mapIndexed { index, value ->
            Offset(
                inset + usableW * index / (values.size - 1),
                inset + usableH * (1 - ((value - low) / span).toFloat()),
            )
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, HaanfColors.Primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(HaanfColors.White, 5.dp.toPx(), points.last())
        drawCircle(HaanfColors.Primary, 3.5.dp.toPx(), points.last())
    }
}

@Composable
fun HTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    placeholder: String? = null,
    singleLine: Boolean = true,
    password: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = body(14.sp, FontWeight.SemiBold), color = HaanfColors.Text)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            enabled = enabled,
            trailingIcon = trailing,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            placeholder = placeholder?.let { { Text(it, style = body(16.sp), color = HaanfColors.TextSecondary) } },
            textStyle = body(17.sp, FontWeight.Medium),
            shape = Corner,
            keyboardOptions = when {
                numeric -> KeyboardOptions(keyboardType = KeyboardType.Number)
                password -> KeyboardOptions(keyboardType = KeyboardType.Password)
                else -> KeyboardOptions.Default
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HaanfColors.Card,
                unfocusedContainerColor = HaanfColors.Card,
                unfocusedBorderColor = HaanfColors.InputBorder,
                focusedBorderColor = HaanfColors.Primary,
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

/** The bottom actions of a screen: the main button, then optional secondary ones. */
@Composable
fun ColumnScope.BottomActions(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.weight(1f, fill = true))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A confirmation in the app's style. [onConfirm] runs before the dialog closes. */
@Composable
fun HDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
    onDismissRequest: () -> Unit = onDismiss,
    extra: String? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = HaanfColors.Card,
        shape = Corner,
        title = { Text(title, style = condensed(30.sp), color = HaanfColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = body(16.sp), color = HaanfColors.Text)
                if (extra != null) Text(extra, style = body(16.sp), color = HaanfColors.Text)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(confirm, style = body(16.sp, FontWeight.Bold), color = HaanfColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(dismiss, style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.Text)
            }
        },
    )
}

/** A screen opened over the tabs: a back button, then the content. */
@Composable
fun SubScreen(
    onBack: () -> Unit,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    HScreen(spacing = spacing) {
        Box(Modifier.offset(x = (-12).dp)) { BackButton(onBack) }
        content()
    }
}

/** Light status and navigation bar icons over a dark or coloured screen, while it is shown. */
@Composable
fun SystemBarIcons(darkBackground: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, darkBackground) {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !darkBackground
        controller?.isAppearanceLightNavigationBars = !darkBackground
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
        }
    }
}
