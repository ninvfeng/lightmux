package net.lighttools.lightmux.ui.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.lighttools.lightmux.ui.common.LocalSwipeOpenGuard
import net.lighttools.lightmux.ui.common.SwipeOpenGuard
import net.lighttools.lightmux.ui.common.SwipeSide
import net.lighttools.lightmux.ui.common.longSwipe
import net.lighttools.lightmux.ui.home.HomeViewModel

/** 抽屉打开时用户「正待在哪」。[tmuxSession] 为 null = 这一页只知道主机（监控 / 文件）。 */
data class SwitcherAnchor(val hostId: String, val tmuxSession: String? = null)

/**
 * 快速切换抽屉的**壳**：边缘手势、开合、拉开时的静默刷新。装什么由调用方给。
 *
 * 装进去的就是主页那个 Composable 本身，不是另写一棵只读的树——同一份会话状态两处渲染、
 * 两处维护，迟早各自漂移；最怕的是「加载中 / 读取失败 / 没装 tmux / 没有会话」这四个分支
 * 哪天在其中一处漏掉一个，而那个 bug 长得就像「用户的会话没了」（CLAUDE.md tmux 纪律 ④）。
 * 数据本来就只有一份：[HomeViewModel] 挂在 Activity 的 ViewModelStore 上，两处拿到同一个实例。
 *
 * 必须包在 `LightmuxRoot` 的 `when` **之外**：终端分支的 `key(sessionId)` 会连根重建子树，
 * 抽屉状态放进去等于「切一次会话抽屉就被清空」；跨页面切换同理会把关闭动画拦腰卸掉。
 *
 * @param anchor null 表示这一页不提供抽屉（主页本身就是这棵树；编辑器有未保存改动，
 *   抽屉一点就整栈替换，确认框没机会弹）。此时 [drawerContent] **一行都不组合**：
 *   主页与抽屉共用 `HomeViewModel.listState`，两个 LazyColumn 同时活着会互相把对方滚飞
 */
@Composable
fun SessionDrawer(
    vm: HomeViewModel,
    anchor: SwitcherAnchor?,
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 用 targetValue 而不是 isOpen：拉开动画途中 currentValue 还是 Closed，
    // 等动画落定再发探测，用户已经盯着旧快照看了三分之一秒。
    val opening = drawerState.targetValue == DrawerValue.Open
    val guard = remember { SwipeOpenGuard() }

    LaunchedEffect(opening, anchor?.hostId, anchor?.tmuxSession) {
        if (opening && anchor != null) vm.openSwitcher(anchor.hostId, anchor.tmuxSession)
    }
    // 走到不给抽屉的页面时它可能还开着，会遮住半屏。
    LaunchedEffect(anchor) { if (anchor == null) drawerState.close() }

    CompositionLocalProvider(LocalSwipeOpenGuard provides guard) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            /*
             * 抽屉**关着的时候一律不用它自带的跟手拖拽**，只有开着才放开。
             *
             * 自带拖拽的起手判定是 `awaitHorizontalTouchSlopOrCancellation`：只看横向位移过没过
             * touchSlop（~8dp），完全不比较纵向。滑列表、滚终端时手指多少带点斜，横向分量先够
             * 就被它抢走，抽屉从左边探出来一截又缩回去。开抽屉因此统一收到 [longSwipe]
             * 那套「必须是确凿的横向长划」的阈值上。
             *
             * 开着时要放开：gesturesEnabled=false 会连「点遮罩关闭」和「往左划关闭」一起禁掉。
             * 用 targetValue 而不是 isOpen，是让这两个关闭手势在拉开动画刚起步时就能用。
             */
            gesturesEnabled = anchor != null && opening,
            drawerContent = {
                // 必须用不带 drawerState 形参的重载：那个重载内部会装 DrawerPredictiveBackHandler，
                // 凭空多出一个返回入口，违反 CLAUDE.md ④「全应用唯一返回入口」。
                // insets 归零交给里面的 Scaffold：两边都加一次，顶栏会被状态栏高度顶下去一截。
                ModalDrawerSheet(modifier = Modifier, windowInsets = WindowInsets(0)) {
                    if (anchor != null) drawerContent()
                }
            },
        ) {
            // 手势必须挂在 content 的**父节点**上，绝不能做成盖在上面的兄弟浮层：
            // Compose 的命中测试对同层兄弟是「命中最上面那个就停」，一个全屏的兄弟会把终端的触摸全吃掉。
            // 父节点则是 Initial pass 先过一遍，不消费就照常往下传。
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (anchor != null) {
                            Modifier.longSwipe(SwipeSide.Left, guard) {
                                scope.launch { drawerState.open() }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                content()
                if (anchor != null) EdgeGestureGuard()
            }
        }
    }
}

/**
 * 告诉系统别在左边缘抢走手势（Android 10+ 的手势导航默认吃掉两侧边缘）。
 *
 * 挂在一个**没有任何 pointerInput 的空 Box** 上：Compose 的命中测试只走 pointerInput 节点，
 * 所以它不会挡住终端的触摸。系统对每条边的排除高度上限 200dp，做不到整条边优先，
 * 于是排在垂直居中——那里最顺手，也避开了顶栏和附加键栏。API < 29 自动 no-op。
 */
@Composable
private fun BoxScope.EdgeGestureGuard() {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .width(EDGE_WIDTH)
            .height(200.dp)
            .systemGestureExclusion()
    )
}

private val EDGE_WIDTH = 24.dp
