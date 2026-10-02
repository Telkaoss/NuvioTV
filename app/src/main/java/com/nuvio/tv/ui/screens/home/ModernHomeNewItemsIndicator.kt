package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.NewItemsIndicatorStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

private val IndicatorHeight = 24.dp

// Not the theme accent, which focus and buttons already use.
private val IndicatorAmber = Color(0xFFF3B54A)
private val IndicatorOnAmber = Color(0xFF241703)
private val RingFill = Color(0xFF161A23)

// Animated in the draw phase only: the row does not recompose while it runs.
@Composable
internal fun ModernHomeNewItemsIndicator(
    count: Int,
    style: NewItemsIndicatorStyle,
    animated: Boolean,
    useThemeColor: Boolean = false,
    modifier: Modifier = Modifier
) {
    val progress: State<Float>? = if (animated) {
        rememberInfiniteTransition(label = "new_items_indicator").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when (style) {
                        NewItemsIndicatorStyle.BADGE -> 3200
                        NewItemsIndicatorStyle.RING -> 2400
                        else -> 1600
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "new_items_progress"
        )
    } else null
    val accent = if (useThemeColor) NuvioTheme.palette.accentBrush() else SolidColor(IndicatorAmber)
    val accentColor = if (useThemeColor) NuvioTheme.colors.Secondary else IndicatorAmber
    val onAccent = if (useThemeColor) NuvioTheme.colors.OnSecondary else IndicatorOnAmber
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)

    when (style) {
        NewItemsIndicatorStyle.OFF -> Unit

        NewItemsIndicatorStyle.BADGE -> Box(
            modifier = modifier
                .height(IndicatorHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(accent)
                .drawWithContent {
                    drawContent()
                    val p = progress?.value ?: return@drawWithContent
                    val sweep = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    if (sweep <= 0f || sweep >= 1f) return@drawWithContent
                    val band = size.width * 0.45f
                    val start = -band + (size.width + band) * sweep
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            startX = start,
                            endX = start + band
                        )
                    )
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            HeavyText(stringResource(R.string.home_row_new_items, count), labelStyle, onAccent)
        }

        NewItemsIndicatorStyle.DOT -> Box(
            modifier = modifier
                .size(IndicatorHeight)
                .drawBehind {
                    val p = progress?.value
                    val radius = size.minDimension * 0.2f
                    if (p != null) {
                        drawCircle(
                            brush = accent,
                            radius = radius * (1f + 1.4f * p),
                            alpha = (1f - p) * 0.6f,
                            style = Stroke(width = radius * 0.35f)
                        )
                    }
                    drawCircle(brush = accent, radius = radius)
                }
        )

        NewItemsIndicatorStyle.RING -> Box(
            modifier = modifier
                .height(IndicatorHeight)
                .defaultMinSize(minWidth = IndicatorHeight)
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    val p = progress?.value
                    if (p == null) {
                        drawRect(accentColor)
                    } else {
                        // A lit arc sweeps around the border.
                        rotate(degrees = 360f * p) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    0f to accentColor,
                                    0.3f to Color.Transparent,
                                    0.6f to Color.Transparent,
                                    1f to accentColor,
                                    center = center
                                ),
                                radius = size.maxDimension
                            )
                        }
                    }
                    val border = 2.dp.toPx()
                    drawRoundRect(
                        color = RingFill,
                        topLeft = Offset(border, border),
                        size = Size(size.width - border * 2, size.height - border * 2),
                        cornerRadius = CornerRadius(size.height / 2f)
                    )
                }
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            HeavyText(stringResource(R.string.home_row_new_items_short, count), labelStyle, accentColor)
        }
    }
}

// The app fonts stop at Bold: an outline in the same colour thickens the glyphs further.
@Composable
private fun HeavyText(text: String, style: TextStyle, color: Color) {
    Box(contentAlignment = Alignment.Center) {
        Text(text = text, style = style, color = color)
        Text(
            text = text,
            style = style.copy(drawStyle = Stroke(width = with(LocalDensity.current) { 0.5.dp.toPx() }, join = StrokeJoin.Round)),
            color = color
        )
    }
}
