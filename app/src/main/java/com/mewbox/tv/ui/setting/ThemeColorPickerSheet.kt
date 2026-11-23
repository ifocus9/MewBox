package com.mewbox.tv.ui.setting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.ThemeColor
import com.mewbox.tv.ui.components.LocalMewOverlayDismiss
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.ui.theme.contrastOnAccent

private val HueSpectrumColors = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)

/**
 * 自定义主题色底部选择器：2D 饱和度/明度色板 + 全光谱色相条 + 预览圆点与十六进制输入框。
 */
@Composable
fun ThemeColorPickerSheet(
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initialArgb) { ThemeColor.argbToHsv(initialArgb) }
    var hue by rememberSaveable(initialArgb) { mutableFloatStateOf(initialHsv.hue) }
    var saturation by rememberSaveable(initialArgb) { mutableFloatStateOf(initialHsv.saturation) }
    var value by rememberSaveable(initialArgb) { mutableFloatStateOf(initialHsv.value) }
    var hexText by rememberSaveable(initialArgb) { mutableStateOf(ThemeColor.toHexRgb(initialArgb)) }
    var isHexFocused by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val currentColorArgb = ThemeColor.hsvToArgb(hue, saturation, value)
    val currentColor = Color(currentColorArgb)
    val parsedHex = ThemeColor.parseHex(hexText)
    val previewArgb = parsedHex ?: currentColorArgb
    val previewColor = Color(previewArgb)

    val inputBorderColor by animateColorAsState(
        targetValue = if (isHexFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "hexInputBorder",
    )

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val dismiss = LocalMewOverlayDismiss.current ?: onDismiss
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "主题色",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = OverlayTokens.DialogTitle,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(OverlayTokens.SheetItemBg)
                        .clickable(onClickLabel = "关闭", onClick = dismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = OverlayTokens.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeColor.presets.forEach { preset ->
                    PresetColorSwatch(
                        color = Color(preset.argb),
                        name = preset.name,
                        selected = previewArgb == preset.argb,
                        onClick = {
                            if (isHexFocused) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }
                            val hsv = ThemeColor.argbToHsv(preset.argb, fallbackHue = hue)
                            hue = hsv.hue
                            saturation = hsv.saturation
                            value = hsv.value
                            hexText = ThemeColor.toHexRgb(preset.argb)
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SaturationValuePanel(
                hue = hue,
                saturation = saturation,
                value = value,
                selectedColor = currentColor,
                onSaturationValueChange = { newS, newV ->
                    if (isHexFocused) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                    saturation = newS
                    value = newV
                    hexText = ThemeColor.toHexRgb(ThemeColor.hsvToArgb(hue, newS, newV))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            )

            Spacer(Modifier.height(18.dp))

            HueSliderBar(
                hue = hue,
                onHueChange = { newHue ->
                    if (isHexFocused) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                    hue = newHue
                    hexText = ThemeColor.toHexRgb(ThemeColor.hsvToArgb(newHue, saturation, value))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(0.75.dp, MewBoxTheme.colors.subtleBorder, CircleShape),
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(OverlayTokens.SheetItemBg)
                        .border(1.dp, inputBorderColor, RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = OverlayTokens.TextSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (hexText.isEmpty()) {
                            Text(
                                text = "RRGGBB",
                                color = OverlayTokens.TextSecondary.copy(alpha = 0.6f),
                                fontSize = 15.sp,
                                letterSpacing = 0.8.sp,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = hexText,
                            onValueChange = { raw ->
                                val filtered = raw
                                    .removePrefix("#")
                                    .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                                    .uppercase()
                                    .take(6)
                                hexText = filtered
                                if (filtered.length == 6) {
                                    ThemeColor.parseHex(filtered)?.let { argb ->
                                        val hsv = ThemeColor.argbToHsv(argb, fallbackHue = hue)
                                        hue = hsv.hue
                                        saturation = hsv.saturation
                                        value = hsv.value
                                    }
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = OverlayTokens.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.8.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isHexFocused = it.isFocused },
                        )
                    }

                    if (hexText.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = "清除") { hexText = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(OverlayTokens.TextSecondary.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    parsedHex?.let { argb ->
                        onConfirm(argb)
                        dismiss()
                    }
                },
                enabled = parsedHex != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(bottom = 0.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = previewColor,
                    contentColor = contrastOnAccent(previewColor),
                    disabledContainerColor = OverlayTokens.SheetItemBg,
                    disabledContentColor = OverlayTokens.TextSecondary,
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
            ) {
                Text(
                    text = "使用这个颜色",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    selectedColor: Color,
    onSaturationValueChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChange by rememberUpdatedState(onSaturationValueChange)
    val pureHueColor = Color(ThemeColor.hsvToArgb(hue, 1f, 1f))
    val borderColor = MewBoxTheme.colors.subtleBorder

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val update: (Offset) -> Unit = { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val s = (pos.x / w).coerceIn(0f, 1f)
                    val v = (1f - pos.y / h).coerceIn(0f, 1f)
                    latestOnChange(s, v)
                }
                update(down.position)
                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        break
                    }
                    change.consume()
                    update(change.position)
                }
            }
        },
    ) {
        val cornerPx = 16.dp.toPx()
        val panelPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset.Zero, size),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                ),
            )
        }

        clipPath(panelPath) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, pureHueColor),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                ),
            )
            drawRoundRect(
                color = borderColor,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = 0.75.dp.toPx()),
            )
        }

        val cx = (saturation.coerceIn(0f, 1f) * size.width).coerceIn(0f, size.width)
        val cy = ((1f - value.coerceIn(0f, 1f)) * size.height).coerceIn(0f, size.height)
        val outerRadius = 11.dp.toPx()
        val innerRadius = 8.dp.toPx()

        drawCircle(
            color = Color.Black.copy(alpha = 0.24f),
            radius = outerRadius + 1.5.dp.toPx(),
            center = Offset(cx, cy + 1.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = outerRadius,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = selectedColor,
            radius = innerRadius,
            center = Offset(cx, cy),
        )
    }
}

@Composable
private fun HueSliderBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChange by rememberUpdatedState(onHueChange)
    val pureHueColor = Color(ThemeColor.hsvToArgb(hue, 1f, 1f))

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val thumbRadiusPx = 13.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val update: (Float) -> Unit = { x ->
                    val usableWidth = (size.width - 2f * thumbRadiusPx).coerceAtLeast(1f)
                    val fraction = ((x - thumbRadiusPx) / usableWidth).coerceIn(0f, 1f)
                    latestOnChange(fraction * 360f)
                }
                update(down.position.x)
                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        break
                    }
                    change.consume()
                    update(change.position.x)
                }
            }
        },
    ) {
        val trackHeight = 18.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val trackRadius = trackHeight / 2f
        val thumbOuterRadius = 13.dp.toPx()
        val thumbInnerRadius = 9.5.dp.toPx()

        val usableWidth = (size.width - 2f * thumbOuterRadius).coerceAtLeast(1f)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = HueSpectrumColors,
                startX = thumbOuterRadius,
                endX = thumbOuterRadius + usableWidth,
                tileMode = TileMode.Clamp,
            ),
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackRadius, trackRadius),
        )

        val cx = thumbOuterRadius + (hue.coerceIn(0f, 360f) / 360f) * usableWidth
        val cy = size.height / 2f

        drawCircle(
            color = Color.Black.copy(alpha = 0.24f),
            radius = thumbOuterRadius + 1.5.dp.toPx(),
            center = Offset(cx, cy + 1.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = thumbOuterRadius,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = pureHueColor,
            radius = thumbInnerRadius,
            center = Offset(cx, cy),
        )
    }
}
