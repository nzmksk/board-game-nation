package com.boardgamenation.tracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.boardgamenation.tracker.R
import com.boardgamenation.tracker.ui.theme.LocalChartColors

/**
 * Charts, drawn on a Canvas rather than pulled in as a library.
 *
 * A few shared rules run through all of them, taken from what actually makes a chart
 * readable rather than from defaults:
 *
 *  - **One hue per chart.** These are all single-series, so the marks are one colour and
 *    there is no legend to read; the labels carry identity.
 *  - **Labels, not colour, carry meaning.** Every row states its value, so nothing here
 *    depends on distinguishing shades.
 *  - **Recessive chrome.** Gridlines and baselines are hairlines in a muted ink; the data
 *    is the only thing with weight.
 *  - **Rounded data-ends.** Bars round only at the end away from the baseline, so the
 *    baseline stays a straight line and the bar keeps its exact length.
 */

private val BarCorner = 4.dp
private val BarGap = 2.dp

/**
 * Ranked magnitudes with long category names, which is most of the interesting data
 * here: mechanics, most-played games, biggest spends.
 */
@Composable
fun HorizontalBarChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    valueFormatter: (Double) -> String = { it.toInt().toString() },
    maxRows: Int = 12,
    barColor: Color? = null,
    /**
     * Value text per row, positional against [data], for the charts where the number
     * alone is not the whole fact -- a win rate wants the sample it came from beside
     * it. Rows it does not cover fall back to [valueFormatter].
     */
    valueLabels: List<String> = emptyList(),
    /** Widen when [valueLabels] carry more than a bare number. */
    valueWidth: androidx.compose.ui.unit.Dp = 56.dp,
    /**
     * The top of the scale, for data that has a fixed ceiling rather than one taken
     * from the rows. A win rate is out of 100 whether or not anybody has reached it,
     * and scaling it to the best row instead says a 40% best is a full bar.
     *
     * Rows drawn against a fixed scale get a recessive track behind the bar, so the
     * length a bar does not fill reads as the rest of the scale rather than as the
     * edge of the chart.
     */
    scaleMax: Double? = null,
) {
    if (data.isEmpty()) {
        EmptyChartMessage(modifier)
        return
    }
    val colors = LocalChartColors.current
    val color = barColor ?: colors.magnitude
    val rows = data.take(maxRows)
    val ceiling = scaleMax?.takeIf { it > 0 }
    val max = ceiling ?: rows.maxOf { it.second }.takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { index, (label, value) ->
            val valueText = valueLabels.getOrElse(index) { valueFormatter(value) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(104.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .semantics { contentDescription = "$label: $valueText" },
                ) {
                    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                        val top = size.height * 0.15f
                        val barHeight = size.height * 0.7f
                        if (ceiling != null) {
                            drawEndRoundedBar(
                                color = colors.grid,
                                left = 0f,
                                top = top,
                                width = size.width,
                                height = barHeight,
                                cornerPx = BarCorner.toPx(),
                                roundedEnd = RoundedEnd.RIGHT,
                            )
                        }
                        val fraction = (value / max).toFloat().coerceIn(0f, 1f)
                        val barWidth = size.width * fraction
                        if (barWidth > 0f) {
                            drawEndRoundedBar(
                                color = color,
                                left = 0f,
                                top = top,
                                width = barWidth,
                                height = barHeight,
                                cornerPx = BarCorner.toPx(),
                                roundedEnd = RoundedEnd.RIGHT,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.width(valueWidth),
                )
            }
        }
    }
}

/** Ordered buckets: weight spread, player-count coverage, plays by weekday. */
@Composable
fun VerticalBarChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    valueFormatter: (Double) -> String = { it.toInt().toString() },
) {
    if (data.isEmpty()) {
        EmptyChartMessage(modifier)
        return
    }
    val colors = LocalChartColors.current
    val max = data.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    val summary = data.joinToString(", ") { "${it.first} ${valueFormatter(it.second)}" }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .semantics { contentDescription = summary },
        ) {
            val gap = BarGap.toPx()
            val slot = size.width / data.size
            val barWidth = (slot - gap).coerceAtLeast(2f)

            // A single hairline at the top of the scale is enough context; a full grid
            // would compete with the bars for attention.
            drawLine(
                color = colors.grid,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f,
            )

            data.forEachIndexed { index, (_, value) ->
                val fraction = (value / max).toFloat().coerceIn(0f, 1f)
                val barHeight = size.height * fraction
                if (barHeight > 0f) {
                    drawEndRoundedBar(
                        color = colors.magnitude,
                        left = index * slot + gap / 2f,
                        top = size.height - barHeight,
                        width = barWidth,
                        height = barHeight,
                        cornerPx = BarCorner.toPx(),
                        roundedEnd = RoundedEnd.TOP,
                    )
                }
            }

            drawLine(
                color = colors.axis,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            data.forEach { (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Change over time: plays per month. */
@Composable
fun LineChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp,
    valueFormatter: (Double) -> String = { it.toInt().toString() },
) {
    if (data.size < 2) {
        EmptyChartMessage(modifier)
        return
    }
    val colors = LocalChartColors.current
    val max = data.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    val summary = data.joinToString(", ") { "${it.first} ${valueFormatter(it.second)}" }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary },
        ) {
            val stepX = size.width / (data.size - 1).coerceAtLeast(1)
            val inset = 10f
            val plotHeight = size.height - inset

            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = inset + plotHeight * fraction
                drawLine(
                    color = colors.grid,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val points = data.mapIndexed { index, (_, value) ->
                Offset(
                    x = index * stepX,
                    y = inset + plotHeight * (1f - (value / max).toFloat().coerceIn(0f, 1f)),
                )
            }

            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = path,
                color = colors.magnitude,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Only the endpoints get a marker. A dot on every month would turn a trend
            // line into a scatter of noise.
            listOf(points.first(), points.last()).forEach { point ->
                drawCircle(color = colors.magnitude, radius = 4.dp.toPx(), center = point)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = data.first().first,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = data.last().first,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Signed values around a neutral zero: how far a game's real length diverges from the
 * time BoardGameGeek states.
 *
 * Two hues with a hueless midpoint, because the sign is the point. A single-hue bar
 * would make "an hour over" and "an hour under" look like the same fact.
 */
@Composable
fun DivergingBarChart(
    data: List<Triple<String, Double, String>>,
    modifier: Modifier = Modifier,
    maxRows: Int = 8,
) {
    if (data.isEmpty()) {
        EmptyChartMessage(modifier)
        return
    }
    val colors = LocalChartColors.current
    val rows = data.take(maxRows)
    val extent = rows.maxOf { kotlin.math.abs(it.second) }.takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, delta, valueLabel) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(96.dp),
                )
                Spacer(Modifier.width(8.dp))
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .semantics { contentDescription = "$label: $valueLabel" },
                ) {
                    val centre = size.width / 2f
                    drawLine(
                        color = colors.axis,
                        start = Offset(centre, 0f),
                        end = Offset(centre, size.height),
                        strokeWidth = 1f,
                    )
                    val fraction = (kotlin.math.abs(delta) / extent).toFloat().coerceIn(0f, 1f)
                    val barWidth = centre * fraction
                    if (barWidth > 0f) {
                        val runsLong = delta > 0
                        drawEndRoundedBar(
                            color = if (runsLong) colors.divergingHigh else colors.divergingLow,
                            left = if (runsLong) centre else centre - barWidth,
                            top = size.height * 0.15f,
                            width = barWidth,
                            height = size.height * 0.7f,
                            cornerPx = BarCorner.toPx(),
                            roundedEnd = if (runsLong) RoundedEnd.RIGHT else RoundedEnd.LEFT,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

private enum class RoundedEnd { TOP, RIGHT, LEFT }

/**
 * Rounds only the growing end of a bar. Rounding both would shorten the mark at the
 * baseline and make short bars read as longer than they are.
 */
private fun DrawScope.drawEndRoundedBar(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerPx: Float,
    roundedEnd: RoundedEnd,
) {
    val radius = CornerRadius(cornerPx.coerceAtMost(width / 2f).coerceAtMost(height / 2f))
    val zero = CornerRadius.Zero
    val rect = Rect(left, top, left + width, top + height)
    val rounded = when (roundedEnd) {
        RoundedEnd.TOP -> RoundRect(rect, radius, radius, zero, zero)
        RoundedEnd.RIGHT -> RoundRect(rect, zero, radius, radius, zero)
        RoundedEnd.LEFT -> RoundRect(rect, radius, zero, zero, radius)
    }
    drawPath(Path().apply { addRoundRect(rounded) }, color)
}

@Composable
private fun EmptyChartMessage(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.stats_no_data),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 12.dp),
    )
}
