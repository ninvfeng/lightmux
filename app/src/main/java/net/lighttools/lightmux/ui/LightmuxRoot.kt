package net.lighttools.lightmux.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import net.lighttools.lightmux.BuildConfig
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.ui.common.LocalSwipeOpenGuard
import net.lighttools.lightmux.ui.common.SwipeSide
import net.lighttools.lightmux.ui.common.longSwipe
import net.lighttools.lightmux.ui.drawer.SessionDrawer
import net.lighttools.lightmux.ui.drawer.SwitcherAnchor
import net.lighttools.lightmux.ui.files.FileEditScreen
import net.lighttools.lightmux.ui.files.FileEditViewModel
import net.lighttools.lightmux.ui.files.FilesScreen
import net.lighttools.lightmux.ui.files.FilesSheet
import net.lighttools.lightmux.ui.files.FilesViewModel
import net.lighttools.lightmux.ui.forward.ForwardScreen
import net.lighttools.lightmux.ui.forward.ForwardSheet
import net.lighttools.lightmux.ui.forward.ForwardViewModel
import net.lighttools.lightmux.ui.home.HomeScreen
import net.lighttools.lightmux.ui.home.HomeViewModel
import net.lighttools.lightmux.ui.host.HostEditScreen
import net.lighttools.lightmux.ui.host.HostEditViewModel
import net.lighttools.lightmux.ui.monitor.MonitorScreen
import net.lighttools.lightmux.ui.monitor.MonitorViewModel
import net.lighttools.lightmux.ui.settings.AboutScreen
import net.lighttools.lightmux.ui.keys.KeysScreen
import net.lighttools.lightmux.ui.keys.KeysViewModel
import net.lighttools.lightmux.ui.settings.SettingsScreen
import net.lighttools.lightmux.ui.settings.SettingsViewModel
import net.lighttools.lightmux.ui.terminal.TerminalScreen
import net.lighttools.lightmux.ui.web.WebScreen
import net.lighttools.lightmux.ui.web.WebViewModel
import net.lighttools.lightmux.update.Repo

/**
 * 取一个自己 new 出来的 ViewModel。
 *
 * 这里的每个 VM 都要手喂依赖（没有 DI），`viewModel(factory = viewModelFactory { initializer { … } })`
 * 这串样板在下面出现十来次，唯一变的只有 key 和那一行构造。包成一行，新增页面就不用再抄一遍。
 *
 * @param key 同一个 VM 类型要按参数各存一份时给（比如按 hostId 分键，换一台主机不接着上一台看）；
 *            不给 = 整个 app 共用一个实例
 */
@Composable
private inline fun <reified VM : ViewModel> rememberVm(
    key: String? = null,
    crossinline create: () -> VM,
): VM = viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/**
 * 应用根。所有页面切换都在这里，唯一的返回入口也在这里。
 */
@Composable
fun LightmuxRoot() {
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    val context = LocalContext.current
    val app = context.applicationContext as LightmuxApp

    /**
     * 页面级返回拦截。
     *
     * 有些页面的返回不是「关掉这一页」：文件页在子目录里要先回上一级，编辑器有未保存改动时要先问一句。
     * 这些**不能**靠页面自己再加一个 `BackHandler`——两个 handler 的优先级取决于组合顺序，
     * 中央栈就此失去「所有返回路径都在一处」的保证（CLAUDE.md 架构要点 ④）。
     * 所以让页面往这里注册一个回调：返回 true 表示这次返回它自己消化了，栈不动。
     */
    var backIntercept by remember { mutableStateOf<(() -> Boolean)?>(null) }

    val scope = rememberCoroutineScope()
    val screen = nav.current
    val sessions by app.sessionManager.sessions.collectAsState()

    /*
     * 终端页上叠着的文件面板：**不进导航栈**，是一层半屏 sheet。
     *
     * 从终端点开文件多半是为了「挑个路径填进正在敲的命令」，整页跳走会把命令上下文藏起来；
     * 面板停在终端上方，要翻目录再往上一拖变全屏（见 [FilesSheet]）。
     * 非空 = 面板开着，值是它属于哪个终端会话。
     */
    var filesSheet by remember { mutableStateOf<String?>(null) }
    val sheetSession = remember(sessions, filesSheet) { sessions.firstOrNull { it.id == filesSheet } }

    /*
     * 同一层的转发面板。值同样是它属于哪个终端会话。
     *
     * 没有「跳走还留着」的例外：文件面板留着是因为它会自己跳去编辑器再回来，转发面板不跳任何地方。
     */
    var forwardSheet by remember { mutableStateOf<String?>(null) }

    /*
     * 后台浏览器：从终端里点开的那一页网页。非空 = 它存在，[webShown] 决定它是盖在终端上
     * 还是收在屏幕右侧之外。一次只留一页——再开一个端口就换掉它。
     *
     * 不进导航栈，理由就是「后台」二字：进栈的页面一返回就被销毁，WebView 一销毁，
     * 登录态、滚动位置、正在跑的 SPA 全部从头再来，用户看到的是「回终端敲一行命令，
     * 网页就重开了一遍」。这一页因此始终留在组合树里，收起来只是被挪出屏幕。
     */
    var webUrl by remember { mutableStateOf<String?>(null) }
    var webShown by remember { mutableStateOf(false) }
    val openWebPane: (String) -> Unit = { url ->
        webUrl = url
        webShown = true
    }
    // 按地址分键：换个端口就是另一页，VM 里那份地址与进度不能接着上一页用
    val webVm = webUrl?.let { url -> rememberVm("webPane:$url") { WebViewModel(url) } }

    // 跳去别的页面就当用户不要面板了。只有「去编辑器」这一条路留着文件面板——那是面板自己发起的跳转，
    // 存完退回来该还在原地。
    LaunchedEffect(screen) {
        val sessionId = (screen as? Screen.Terminal)?.sessionId
        filesSheet?.let { open ->
            if (screen !is Screen.FileEdit && sessionId != open) filesSheet = null
        }
        if (forwardSheet != null && sessionId != forwardSheet) forwardSheet = null
        // 网页只在终端页上盖着（那是唯一能把它划出来的地方）。收起来而不是关掉：它还在后台。
        if (screen !is Screen.Terminal) webShown = false
    }

    /*
     * 快速切换抽屉的状态必须活在 `when` 之外：终端分支的 `key(sessionId)` 会连根重建子树，
     * 放进去等于「切一次会话抽屉就被清空」，跨页面切换同理会把关闭动画拦腰卸掉。
     */
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 主页与抽屉共用同一个实例（factory 没传 key）——抽屉是同一棵树的第二个入口，不是第二份事实来源。
    val homeVm = rememberVm {
        HomeViewModel(
            app.hostStore,
            app.sessionManager,
            app.tmuxRepository,
            app.forwardManager,
            app.forwardStore,
        )
    }
    val terminalHandle = remember(sessions, screen) {
        (screen as? Screen.Terminal)?.let { s -> sessions.firstOrNull { it.id == s.sessionId } }
    }
    /*
     * null = 这一页不提供抽屉。
     * 主页本身就是这棵树；FileEdit 是**故意**排除的——编辑器里有未保存改动，抽屉一点就整栈替换成
     * [Home, Terminal]，改的东西静默蒸发，vm.requestExit 的确认框根本没机会弹。
     *
     * 网页盖在上面时也不给：抽屉和「把网页收回后台」都是从左往右划，两个手势必须只剩一个。
     */
    val anchor: SwitcherAnchor? = if (webShown) null else when (screen) {
        is Screen.Terminal -> terminalHandle?.let { SwitcherAnchor(it.host.id, it.tmuxSession) }
        is Screen.Monitor -> SwitcherAnchor(screen.hostId)
        is Screen.Forward -> SwitcherAnchor(screen.hostId)
        is Screen.Files -> SwitcherAnchor(screen.hostId)
        else -> null
    }

    /*
     * 四段优先级：抽屉 > 后台浏览器 > 页面拦截 > 出栈。
     *
     * 前两段**不能**走 backIntercept——那是个「后写覆盖先写」的单变量，根层先写、
     * Files 分支的 `backIntercept = vm::goUp` 后写，会把包装整个盖掉。
     * 它的设计前提是「页面向根注册」，而这两者本来就在根这层。
     * 浏览器那一段更不能注册进去：它收在后台时也还在组合树里，注册了会连终端页的返回键
     * 一起吃掉——用户在终端按返回，退的却是一个看不见的网页。
     */
    BackHandler(enabled = nav.canPop || webShown) {
        when {
            // targetValue 而非 isOpen：拉开动画途中 currentValue 还是 Closed，
            // 这时按返回该关抽屉，不该退页面
            drawerState.targetValue == DrawerValue.Open -> scope.launch { drawerState.close() }
            // 网页自己还有历史就先退一页，退到头把它收回后台——**不销毁**，划回来还是原样
            webShown -> if (webVm?.goBack() != true) webShown = false
            backIntercept?.invoke() == true -> Unit
            else -> nav.pop()
        }
    }

    // 文件页、面板与编辑器共用浏览通道；这三处都不用它了，通道就该关。
    // 放在这里而不是页面的 onDispose：进编辑器时文件页会被销毁，那样会白白断一次连接再重连。
    val fileHosts = remember(nav.stack, sheetSession) {
        nav.stack.mapNotNullTo(mutableSetOf()) { screen ->
            when (screen) {
                is Screen.Files -> screen.hostId
                is Screen.FileEdit -> screen.hostId
                else -> null
            }
        }.apply { sheetSession?.let { add(it.host.id) } }
    }
    LaunchedEffect(fileHosts) { app.sftpRepository.closeBrowsingExcept(fileHosts) }

    SessionDrawer(
        vm = homeVm,
        anchor = anchor,
        drawerState = drawerState,
        // 抽屉里装的就是主页本身，唯一的差别是跳走之后顺手把抽屉关上
        drawerContent = { HomeRoute(homeVm, nav) { scope.launch { drawerState.close() } } },
    ) {
        // 终端底部那条快捷栏自己要横滚，划它的时候上面的手势得让开（见 [SwipeOpenGuard]）
        val swipeGuard = LocalSwipeOpenGuard.current

        when (screen) {
            is Screen.Home -> HomeRoute(homeVm, nav)

            is Screen.Terminal -> {
                if (terminalHandle == null) {
                    // 会话被关掉了（主页长按关闭、或进程恢复后不存在），别停在空壳终端上。
                    LaunchedEffect(screen.sessionId) { nav.popTo(Screen.Home) }
                } else {
                    /*
                     * key(sessionId)：切会话时强制重建整棵子树，避免 AndroidView 残留上一个
                     * TerminalView。下面那条手势也**必须**在 key 里面——`pointerInput` 只在 key 变时
                     * 重启协程，重组换掉的新 lambda 它不认，包在外面的话切一次会话就永远拿着
                     * 上一个 sessionId 不放，划出来的面板属于一个已经不在眼前的会话。
                     */
                    key(screen.sessionId) {
                        /*
                         * 从右往左划：后台有网页就把它拉出来，没有就掀转发面板。
                         *
                         * 这两件事是同一个意图的两半——「刚起来的服务，我要看看」。有网页时它就是答案；
                         * 没有时下一步必然是去配转发，中间那次「点快捷栏的转发键」是多余的一下。
                         * 手势挂在终端的**父节点**上（同抽屉那条，理由见 [longSwipe]）。
                         */
                        Box(
                            Modifier.fillMaxSize().longSwipe(SwipeSide.Right, swipeGuard) {
                                if (webUrl != null) webShown = true else forwardSheet = screen.sessionId
                            }
                        ) {
                            TerminalScreen(
                                handle = terminalHandle,
                                onBack = { nav.pop() },
                                onEditHost = { nav.push(Screen.HostEdit(it)) },
                                onOpenFiles = { filesSheet = screen.sessionId },
                                // 转发从终端进来也是半屏面板（理由见 [ForwardSheet]）：配转发的那一刻
                                // 服务多半刚在这个终端里起来，日志还在滚，整页跳走会把它盖住。
                                onOpenForward = { forwardSheet = screen.sessionId },
                                onOpenWeb = openWebPane,
                                drawerOpening = drawerState.targetValue == DrawerValue.Open,
                                sheetOpen = webShown ||
                                    filesSheet == screen.sessionId ||
                                    forwardSheet == screen.sessionId,
                            )
                        }
                    }
                }
            }

            is Screen.HostEdit -> {
                // 按 hostId 分键：不这样的话，新建后再去编辑另一台会拿到上一次的表单。
                val vm = rememberVm("hostEdit:${screen.hostId.orEmpty()}") {
                    HostEditViewModel(app.hostStore, app.keyStore, screen.hostId)
                }
                // 管理密钥的入口从表单里进来：跳去导完新钥匙回来，表单还在原样
                HostEditScreen(
                    vm = vm,
                    onDone = { nav.pop() },
                    onOpenKeys = { nav.push(Screen.Keys) },
                )
            }

            is Screen.Monitor -> {
                // 按 hostId 分键：不这样的话，看完这台再看那台会拿到上一台的读数
                val vm = rememberVm("monitor:${screen.hostId}") {
                    MonitorViewModel(app.monitorRepository, app.hostStore, screen.hostId)
                }
                MonitorScreen(vm = vm, onBack = { nav.pop() })
            }

            is Screen.Files -> {
                // 按 hostId 分键：换一台主机不该接着上一台的目录看
                val vm = rememberVm("files:${screen.hostId}") {
                    FilesViewModel(app.sftpRepository, app.hostStore, screen.hostId, screen.path)
                }
                // 在子目录里，返回 = 回上一级；回到起点才让中央栈关掉页面（文件管理器的通用预期）
                DisposableEffect(vm) {
                    backIntercept = vm::goUp
                    onDispose { backIntercept = null }
                }
                FilesScreen(
                    vm = vm,
                    queue = app.transferQueue,
                    onBack = { if (!vm.goUp()) nav.pop() },
                    onEditFile = { nav.push(Screen.FileEdit(screen.hostId, it)) },
                )
            }

            is Screen.FileEdit -> {
                val vm = rememberVm("fileEdit:${screen.hostId}:${screen.path}") {
                    FileEditViewModel(app.sftpRepository, app.hostStore, screen.hostId, screen.path)
                }
                // 有未保存改动时先弹确认框，别让一次误触把改的东西吃掉
                DisposableEffect(vm) {
                    backIntercept = vm::requestExit
                    onDispose { backIntercept = null }
                }
                FileEditScreen(vm = vm, onBack = { nav.pop() })
            }

            is Screen.Settings -> {
                val vm = rememberVm {
                    SettingsViewModel(
                        store = app.settingsStore,
                        checker = app.updateChecker,
                        installer = app.updateInstaller,
                        currentVersion = BuildConfig.VERSION_NAME,
                    )
                }
                SettingsScreen(
                    vm = vm,
                    onBack = { nav.pop() },
                    onOpenAbout = { nav.push(Screen.About) },
                    onOpenKeys = { nav.push(Screen.Keys) },
                    onOpenProject = { openUrl(context, Repo.HOME_URL) },
                )
            }

            is Screen.Forward -> {
                // 按 hostId 分键：换一台主机不该看到上一台的转发列表
                val vm = rememberVm("forward:${screen.hostId}") {
                    ForwardViewModel(app.forwardStore, app.forwardManager, app.hostStore, screen.hostId)
                }
                // 搜索框开着时返回键先收框，收完了才退页面（同文件页的「先回上一级」）
                DisposableEffect(vm) {
                    backIntercept = vm::onBack
                    onDispose { backIntercept = null }
                }
                ForwardScreen(
                    vm = vm,
                    onBack = { nav.pop() },
                    onOpenWeb = { nav.push(Screen.Web(it)) },
                )
            }

            is Screen.Web -> {
                // 按地址分键：同时转发了两个端口时，两页各记各的
                val vm = rememberVm("web:${screen.url}") { WebViewModel(screen.url) }
                // 网页自己还有历史就先退一页，退到头才让中央栈关掉这一页（浏览器的通用预期）
                DisposableEffect(vm) {
                    backIntercept = vm::goBack
                    onDispose { backIntercept = null }
                }
                WebScreen(
                    vm = vm,
                    // 整页版本没有「收在后台」这回事：不是栈顶就压根不在组合树里，WebView 已经销毁了
                    visible = true,
                    onClose = { nav.pop() },
                    onOpenExternal = { openUrl(context, it) },
                )
            }

            is Screen.Keys -> {
                val vm = rememberVm { KeysViewModel(app.keyStore, app.hostStore) }
                KeysScreen(vm = vm, onBack = { nav.pop() })
            }

            is Screen.About -> AboutScreen(
                version = BuildConfig.VERSION_NAME,
                onBack = { nav.pop() },
                onOpenProject = { openUrl(context, Repo.HOME_URL) },
            )
        }

        // 面板叠在终端上，所以只在那个终端页上画：跳去编辑器时先收起来（它是独立窗口，
        // 不收会盖在编辑器上面），退回来 filesSheet 还在，原样再弹一次。
        if (sheetSession != null && (screen as? Screen.Terminal)?.sessionId == filesSheet) {
            val hostId = sheetSession.host.id
            // 和整页文件浏览共用同一个 VM（key 相同）：两边看的是同一台主机的同一次浏览，
            // 各存一份的话「刚才翻到哪」会分叉
            val vm = rememberVm("files:$hostId") {
                FilesViewModel(app.sftpRepository, app.hostStore, hostId, "")
            }
            FilesSheet(
                vm = vm,
                queue = app.transferQueue,
                onEditFile = { nav.push(Screen.FileEdit(hostId, it)) },
                onSendToTerminal = { path ->
                    // 加引号是必须的：带空格的文件名会被 shell 拆成两个参数，
                    // 而 `$(...)` 这种名字原样填进去是会被执行的（见 SftpPath.shellQuote）
                    sheetSession.sendText(SftpPath.shellQuote(path))
                    // 填完就收起来：下一步是接着敲那行命令，终端得露出来
                    filesSheet = null
                },
                onDismiss = { filesSheet = null },
            )
        }

        // 同上：只在它所属的那个终端页上画。
        if (terminalHandle != null && (screen as? Screen.Terminal)?.sessionId == forwardSheet) {
            val hostId = terminalHandle.host.id
            // 和整页转发共用同一个 VM（key 相同）：两边看的是同一台主机的同一份转发，
            // 各存一份的话「刚才扫出来的端口」会分叉
            val vm = rememberVm("forward:$hostId") {
                ForwardViewModel(app.forwardStore, app.forwardManager, app.hostStore, hostId)
            }
            ForwardSheet(
                vm = vm,
                onDismiss = { forwardSheet = null },
                // 先收面板再开网页：面板是独立窗口，不收会盖在网页上面
                onOpenWeb = {
                    forwardSheet = null
                    openWebPane(it)
                },
            )
        }

        /*
         * 后台浏览器这一页。**一直在组合树里**，收起来时被挪到屏幕右侧之外——
         * 换成 AnimatedVisibility 那种「隐藏即移出组合」会连 WebView 一起销毁，
         * 「后台」就无从谈起了。挪出去之后它也不再参与命中测试，终端照常收得到触摸。
         *
         * 画在最后 = 叠在所有页面之上。
         */
        if (webVm != null) {
            val offset by animateFloatAsState(if (webShown) 0f else 1f, label = "webPane")
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = offset * size.width }
                    // 从左往右划收回后台。不给 guard：这一页底下是浏览器的操作栏，不横滚。
                    .longSwipe(SwipeSide.Left) { webShown = false }
            ) {
                // key(url)：换个端口就得是一个全新的 WebView，沿用旧的等于把上一页的
                // 历史与登录态接着往下用（WebScreen 里那个 remember 不认 vm 换了没有）
                key(webUrl) {
                    WebScreen(
                        vm = webVm,
                        // 收在后台时它还在组合树里，只是被平移出屏幕，所以「可见」得自己告诉它：
                        // 没有这一条，划走之后网页的 JS 定时器、视频、轮询会一直跑下去
                        visible = webShown,
                        // 「×」才是真关掉：网页从组合树里摘掉，WebView 跟着 destroy
                        onClose = {
                            webShown = false
                            webUrl = null
                        },
                        onOpenExternal = { openUrl(context, it) },
                    )
                }
            }
        }
    }
}

/**
 * 主页的导航接线。**主页和快速切换抽屉装的是同一个 [HomeScreen]**，
 * 差别只有一处：从抽屉里跳走要顺手把抽屉关上。
 *
 * 抽屉当初另写了一棵只读的树，事实证明不值得：同一份会话状态两处渲染、两处维护，
 * 每加一个交互都要抄一遍，迟早各自漂移（见 [SessionDrawer]）。
 */
@Composable
private fun HomeRoute(vm: HomeViewModel, nav: Navigator, onNavigate: () -> Unit = {}) {
    HomeScreen(
        vm = vm,
        onOpenTerminal = {
            nav.switchToTerminal(it)
            onNavigate()
        },
        onAddHost = {
            nav.push(Screen.HostEdit(null))
            onNavigate()
        },
        onEditHost = {
            nav.push(Screen.HostEdit(it))
            onNavigate()
        },
        onOpenMonitor = {
            nav.push(Screen.Monitor(it))
            onNavigate()
        },
        onOpenFiles = {
            // 路径留空 = 由服务端的家目录决定起点，见 FilesViewModel.start
            nav.push(Screen.Files(it, ""))
            onNavigate()
        },
        onOpenForward = {
            nav.push(Screen.Forward(it))
            onNavigate()
        },
        onOpenSettings = {
            nav.push(Screen.Settings)
            onNavigate()
        },
    )
}

/**
 * 打开外部链接。装不下浏览器的设备（精简 ROM、企业管控机）会抛异常，
 * 吞掉即可——为一个「项目地址」链接崩掉整个 app 不值当。
 */
internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
