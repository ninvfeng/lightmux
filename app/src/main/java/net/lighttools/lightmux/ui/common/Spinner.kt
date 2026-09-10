package net.lighttools.lightmux.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 转一圈的时长。匀速转，1.1s 一圈读起来最像「在干活」，再快显得慌、再慢显得卡。 */
private const val ROTATION_MILLIS = 1100

/**
 * 弧长。留 80° 缺口是为了给「在转」一个参照物——圆闭合了就看不出在动。
 *
 * 注意圆角端帽会沿切线各外扩半个 strokeWidth，小圆上尤其明显：
 * 20dp 配 4dp 描边时视觉缺口只剩 52°，所以这个数不要再往上加。
 */
private const val SWEEP_DEGREES = 280f

/** 默认直径对齐 M3 的 CircularProgressIndicator，换掉它时不带默认尺寸的调用点不用改。 */
private val SpinnerDiameter = 40.dp

/**
 * 定长弧 · 匀速旋转的加载指示器，替代 M3 的 CircularProgressIndicator。
 *
 * 换掉的理由：M3 1.4.0 那个 indeterminate 圈弧长在 36°↔313° 之间伸缩，圈永远不完整；
 * 旋转还叠了一条「每 1.5 秒突然多转 90°」的 keyframes，肉眼看是一秒卡一下。
 *
 * @param progress 非空 = 弧长跟着这个值走（下拉刷新的跟手阶段），null = 匀速自转。
 *   这个值只在绘制阶段被调用，读它不会触发重组——下拉刷新那处依赖这一点。
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    progress: (() -> Float)? = null,
) {
    // 只有自转才建动画：跟手阶段角度由手势给，多挂一条无限动画等于白烧一份帧回调
    val rotation = if (progress == null) {
        rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(ROTATION_MILLIS, easing = LinearEasing)),
        )
    } else {
        null
    }
    val stroke = with(LocalDensity.current) { Stroke(strokeWidth.toPx(), cap = StrokeCap.Round) }
    // rotation.value 和 progress() 都只在 draw lambda 里读：每帧只重绘，不进重组。
    // 把任何一个提到这行以上，就是每帧重组一次。
    Canvas(modifier.progressSemantics().size(SpinnerDiameter)) {
        // 描边以椭圆路径为中心线，两边各让出半个描边宽，外沿才正好贴住边界
        val inset = stroke.width / 2f
        val diameter = size.width - stroke.width
        drawArc(
            color = color,
            startAngle = (rotation?.value ?: 0f) - 90f,
            sweepAngle = SWEEP_DEGREES * (progress?.invoke()?.coerceIn(0f, 1f) ?: 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = stroke,
        )
    }
}
