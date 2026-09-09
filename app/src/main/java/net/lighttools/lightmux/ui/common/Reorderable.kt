package net.lighttools.lightmux.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.lighttools.lightmux.R

/**
 * 列表内拖动排序。
 *
 * 只做「拖过一格就换一格」这一件事：拖动中实时交换，松手即定稿。不做落位动画，
 * 也不做拖到边缘自动滚屏——用它的两张表都短（快捷栏十来个键、命令表几条），一屏够得着；
 * 自动滚屏得接 [androidx.compose.foundation.lazy.LazyListState] 的定时滚动，代码量翻倍。
 *
 * **列表项不要设 `key`**：拖动中列表一直在重排，按 key 复用会把正在收手势的那个节点
 * 搬到别处，手势断在半路。让它按下标复用——节点留在原位只换内容，手势才连得上。
 */
class ReorderState {

    /** 被拖那行的当前下标；[NONE] = 没在拖。跟着交换一路更新，所以它始终指着手指底下那行。 */
    var index by mutableIntStateOf(NONE)
        private set

    /** 相对本行当前落点的位移。每换一格减掉一个行高，手指与内容才不会越拖越脱节。 */
    var offset by mutableFloatStateOf(0f)
        private set

    internal fun start(from: Int) {
        index = from
        offset = 0f
    }

    internal fun stop() {
        index = NONE
        offset = 0f
    }

    /** [rowHeight] 是行高（px）；`while` 不是 `if`，甩得快时一帧能跨过好几格。 */
    internal fun drag(dy: Float, rowHeight: Int, lastIndex: Int, onMove: (Int, Int) -> Unit) {
        offset += dy
        if (rowHeight <= 0) return
        val half = rowHeight / 2f
        while (offset > half && index < lastIndex) {
            onMove(index, index + 1)
            index++
            offset -= rowHeight
        }
        while (offset < -half && index > 0) {
            onMove(index, index - 1)
            index--
            offset += rowHeight
        }
        // 到头了就别再跟手：不夹住的话手指能把这行拖出列表外，松手还得弹回来
        if (index == 0) offset = offset.coerceAtLeast(-half)
        if (index == lastIndex) offset = offset.coerceAtMost(half)
    }

    companion object {
        const val NONE = -1
    }
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/**
 * 被拖那行的样子：跟着手指走、浮到相邻行之上（不然位移后会被下一行盖住）、换个底色。
 *
 * 底色不只是反馈——浮起来的行必须不透明，否则会透出它压着的那一行。
 */
@Composable
fun Modifier.reorderableRow(state: ReorderState, index: Int): Modifier {
    if (state.index != index) return this
    return this
        .zIndex(1f)
        // offset 在 lambda 里读：每帧都在变，放外面会把整行重组一遍
        .graphicsLayer { translationY = state.offset }
        .background(MaterialTheme.colorScheme.surfaceVariant)
}

/**
 * 拖动把手，放在行首。
 *
 * @param onMove 逐格交换，拖动中会被调用很多次——**改本地那份就好，别每格都落盘**
 * @param onDrop 松手时调一次，落盘在这里
 */
@Composable
fun DragHandle(
    state: ReorderState,
    index: Int,
    lastIndex: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
) {
    // 这几个值拖动中一直在变，但不能拿去当 pointerInput 的 key——key 一变手势就重启，断在半路
    val currentIndex by rememberUpdatedState(index)
    val currentLast by rememberUpdatedState(lastIndex)
    val currentMove by rememberUpdatedState(onMove)
    val currentDrop by rememberUpdatedState(onDrop)
    // 行高向父级要：判断「拖过一格没有」得用整行的高度，把手自己那 24dp 说明不了问题
    var rowHeight by remember { mutableIntStateOf(0) }

    Icon(
        imageVector = Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.reorder),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .onGloballyPositioned { rowHeight = it.parentLayoutCoordinates?.size?.height ?: 0 }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { state.start(currentIndex) },
                    onDragEnd = {
                        state.stop()
                        currentDrop()
                    },
                    onDragCancel = {
                        state.stop()
                        currentDrop()
                    },
                ) { change, drag ->
                    // 消费掉，免得这一下被外层的 LazyColumn 当成滚动、被 ModalBottomSheet 当成下拉关闭
                    change.consume()
                    state.drag(drag.y, rowHeight, currentLast, currentMove)
                }
            }
            // padding 在 pointerInput 之后：手势区域按 36dp 算（跟行尾图标同一档下限），画出来的图标只有 24dp
            .padding(6.dp)
            .size(24.dp),
    )
}

/** 拖动排序用的顺序调整：把下标 [from] 的元素挪到 [to]。 */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().also { it.add(to, it.removeAt(from)) }
