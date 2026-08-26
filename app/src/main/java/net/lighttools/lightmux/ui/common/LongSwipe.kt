package net.lighttools.lightmux.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 横划从哪一边起手。[Left] 即「从左往右划」，[Right] 即「从右往左划」。 */
enum class SwipeSide { Left, Right }

/**
 * 「这块地方别抢横划」。[excludedTop] 是 root 坐标下的一条横线，起手点落在它下方就让开。
 *
 * 为什么要反过来由子节点报位置：横划的判定挂在祖先节点的 **Initial pass** 上（见 [longSwipe]），
 * 而 Initial pass 是 root→leaf，子节点无论怎么 consume 都轮不到它先说话。终端底部的快捷栏
 * 自己要横滚，划它的时候上面那几个手势必须装作没看见。
 *
 * 只在手势协程里读写，不参与重组，所以是普通 `var` 不是 `mutableStateOf`。
 */
@Stable
class SwipeOpenGuard {
    var excludedTop: Float = Float.POSITIVE_INFINITY
}

val LocalSwipeOpenGuard = staticCompositionLocalOf { SwipeOpenGuard() }

/**
 * 起手区：靠 [SwipeSide] 那一侧的半屏。
 *
 * **不能只认最边上那一条**。Android 10+ 的手势导航把两侧边缘的横划当成「返回」，
 * 而 `systemGestureExclusion` 每条边最多只排得掉 200dp 高，那条带子之外系统照抢——
 * 表现就是「从边上划完全没反应」。放宽到半屏，用户自然会从离边缘远一点的地方起手，
 * 绕开系统的手势区。「是不是要触发」全靠下面那两个阈值判，不靠起手点判。
 */
private const val START_ZONE_FRACTION = 0.5f

/**
 * 要划够这么长才算数。
 *
 * **远大于 touchSlop 是有意的**：这几个手势（开抽屉、拉出浏览器、收起浏览器）的误触成本
 * 都是「正在看的东西被整个盖住」，而漏触不过是再划一次。用 touchSlop（~8dp）当阈值时，
 * 滑列表、滚终端只要手指带一点斜，横向分量就够了。48dp 得是个存心的横划才够得着。
 */
private val TRIGGER_DISTANCE = 48.dp

/** 纵向漂移超过这个数就判定「用户在上下滑」，直接让开。比 touchSlop 宽松，容得下手划弧线。 */
private val VERTICAL_LIMIT = 40.dp

/**
 * 半屏起手的横向长划。
 *
 * `TerminalView.onTouchEvent`、`WebView` 都是无条件 `return true`，Compose interop 在
 * **Main pass** 就把事件消费掉，任何靠「事件未被消费」起判的手势在它们上面永远收不到。
 * 所以在祖先节点的 **Initial pass**（root→leaf，早于 interop）先看一眼：不到条件绝不 consume，
 * 底下的点击 / 选词 / 滚屏一律不受影响；确凿的长划才消费，此时 interop 会给 View 补发
 * ACTION_CANCEL，那一半手势干净取消。
 *
 * 只做「甩开」不做「跟手」：跟手要拿到目标容器的可拖拽状态，而 `DrawerState` 里的
 * anchoredDraggableState 在 M3 1.3 是 internal；三处手势统一成甩开，手感也一致。
 *
 * `pointerInput(from)` 而不是把 [onTrigger] 当 key：它每次重组都是个新 lambda，
 * 拿它当 key 会让手势协程在重组时重启，**半路的滑动被就地取消**。
 */
internal fun Modifier.longSwipe(
    from: SwipeSide,
    guard: SwipeOpenGuard? = null,
    onTrigger: () -> Unit,
): Modifier = pointerInput(from) {
    val trigger = TRIGGER_DISTANCE.toPx()
    val verticalLimit = VERTICAL_LIMIT.toPx()
    val slop = viewConfiguration.touchSlop
    // 从左边起手就是往右划，从右边起手就是往左划：把两个方向折进一个符号，阈值只有一套
    val sign = if (from == SwipeSide.Left) 1f else -1f
    awaitEachGesture {
        // requireUnconsumed = false：Initial pass 上还没人消费，但下游可能已在处理上一段手势
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val zone = size.width * START_ZONE_FRACTION
        val inZone = if (from == SwipeSide.Left) {
            down.position.x <= zone
        } else {
            down.position.x >= size.width - zone
        }
        if (!inZone) return@awaitEachGesture
        // 调用方保证这个节点铺满窗口且贴着 root 原点，所以局部坐标和 [SwipeOpenGuard] 报的是同一套
        if (guard != null && down.position.y >= guard.excludedTop) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture       // 抬手 = 这是一次点击，让给下面
            dx += change.positionChange().x
            dy += change.positionChange().y
            if (abs(dy) > verticalLimit) return@awaitEachGesture // 在上下滑，别抢
            val along = dx * sign
            if (along < -slop) return@awaitEachGesture          // 划反了，不是这个手势
            // 光看距离不够：竖直划也能攒出 48dp 的横向漂移。还要求横向明显占优，
            // 这样「斜着滑列表」和「横着长划」才分得开。
            if (along >= trigger && along > abs(dy) * 1.5f) {
                change.consume()
                onTrigger()
                return@awaitEachGesture
            }
        }
    }
}
