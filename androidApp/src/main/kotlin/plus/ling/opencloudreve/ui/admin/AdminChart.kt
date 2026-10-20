package plus.ling.opencloudreve.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import plus.ling.opencloudreve.R
import plus.ling.opencloudreve.core.presentation.admin.AdminDailyMetric
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** 图表样式 */
private enum class ChartMode { Line, Bar }

/** 一条数据序列 */
private class ChartSeries(val label: String, val color: Color, val values: List<Int>)

/** 近 12 天每日新增卡片：折线/柱状切换 + 图例 + 坐标轴 */
@Composable
internal fun DailyChartCard(daily: List<AdminDailyMetric>, modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(ChartMode.Line) }
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.admin_daily_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    ChartMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, ChartMode.entries.size),
                            label = {
                                Text(stringResource(if (m == ChartMode.Line) R.string.admin_chart_line else R.string.admin_chart_bar))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (daily.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.admin_chart_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val colors = MaterialTheme.colorScheme
                val series = listOf(
                    ChartSeries(stringResource(R.string.admin_files), colors.primary, daily.map { it.files }),
                    ChartSeries(stringResource(R.string.admin_users), colors.tertiary, daily.map { it.users }),
                    ChartSeries(stringResource(R.string.admin_shares), colors.secondary, daily.map { it.shares }),
                )
                val labels = remember(daily) {
                    val fmt = DateTimeFormatter.ofPattern("MM-dd")
                    daily.map {
                        java.time.Instant.ofEpochMilli(it.date.toEpochMilliseconds()).atZone(ZoneId.systemDefault()).format(fmt)
                    }
                }
                val description = stringResource(R.string.admin_daily_title)
                DailyChart(
                    series = series,
                    xLabels = labels,
                    mode = mode,
                    modifier = Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description },
                )
                Spacer(Modifier.height(12.dp))
                Legend(series)
            }
        }
    }
}

@Composable
private fun Legend(series: List<ChartSeries>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = s.color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DailyChart(series: List<ChartSeries>, xLabels: List<String>, mode: ChartMode, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxValue = series.maxOf { s -> s.values.maxOrNull() ?: 0 }.coerceAtLeast(1)
    Canvas(modifier) {
        drawChart(series, xLabels, mode, maxValue, measurer, labelStyle, gridColor)
    }
}

private fun DrawScope.drawChart(
    series: List<ChartSeries>,
    xLabels: List<String>,
    mode: ChartMode,
    maxValue: Int,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
) {
    val n = xLabels.size
    if (n == 0) return
    val gap = 4.dp.toPx()
    // 纵轴刻度：0 与最大值
    val maxLabel = measurer.measure(maxValue.toString(), labelStyle)
    val zeroLabel = measurer.measure("0", labelStyle)
    val axisWidth = maxOf(maxLabel.size.width, zeroLabel.size.width) + gap
    val xLabelHeight = measurer.measure("00-00", labelStyle).size.height
    val left = axisWidth.toFloat()
    val top = maxLabel.size.height / 2f
    val bottom = size.height - xLabelHeight - gap
    val plotWidth = size.width - left
    val plotHeight = bottom - top
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val stroke = 1.dp.toPx()
    drawLine(gridColor, Offset(left, top), Offset(size.width, top), stroke)
    drawLine(gridColor, Offset(left, bottom), Offset(size.width, bottom), stroke)
    drawText(maxLabel, topLeft = Offset(left - gap - maxLabel.size.width, top - maxLabel.size.height / 2f))
    drawText(zeroLabel, topLeft = Offset(left - gap - zeroLabel.size.width, bottom - zeroLabel.size.height / 2f))

    val slot = plotWidth / n
    fun xCenter(i: Int) = left + slot * (i + 0.5f)
    fun y(v: Int) = bottom - plotHeight * v / maxValue

    when (mode) {
        ChartMode.Line -> {
            val lineWidth = 2.dp.toPx()
            val dot = 3.dp.toPx()
            series.forEach { s ->
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    if (i == 0) path.moveTo(xCenter(i), y(v)) else path.lineTo(xCenter(i), y(v))
                }
                drawPath(path, s.color, style = Stroke(width = lineWidth))
                s.values.forEachIndexed { i, v -> drawCircle(s.color, dot, Offset(xCenter(i), y(v))) }
            }
        }
        ChartMode.Bar -> {
            val group = slot * 0.7f
            val barWidth = group / series.size
            series.forEachIndexed { si, s ->
                s.values.forEachIndexed { i, v ->
                    val x = xCenter(i) - group / 2f + barWidth * si
                    val h = plotHeight * v / maxValue
                    if (h > 0f) drawRect(s.color, Offset(x, bottom - h), Size(barWidth * 0.9f, h))
                }
            }
        }
    }

    // 横轴日期：按可容纳数量抽稀
    val labelWidth = measurer.measure("00-00", labelStyle).size.width + gap * 2
    val capacity = (plotWidth / labelWidth).toInt().coerceAtLeast(1)
    val step = ceil(n / capacity.toFloat()).toInt().coerceAtLeast(1)
    for (i in 0 until n step step) {
        val layout = measurer.measure(xLabels[i], labelStyle)
        val x = (xCenter(i) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
        drawText(layout, topLeft = Offset(x, bottom + gap))
    }
}
