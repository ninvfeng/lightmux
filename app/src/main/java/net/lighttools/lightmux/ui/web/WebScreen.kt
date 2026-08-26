package net.lighttools.lightmux.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.lighttools.lightmux.R
import net.lighttools.lightmux.web.WebUrl

/** 操作栏高度。顶栏那 64dp 在手机上太占地方，压到这个数是为了把屏幕尽量让给网页。 */
private val BarHeight = 44.dp

/** 栏里按钮的外框与图标尺寸，都比 M3 默认（48/24）小一圈，好压进 [BarHeight]。 */
private val ButtonSize = 40.dp
private val IconSize = 20.dp

/**
 * 内置的迷你浏览器：转发出来的端口在 app 里直接看。
 *
 * **为什么不直接扔给系统浏览器**（那条路仍在，挪进了溢出菜单）：转发是靠这个 app 的进程维持的，
 * 切去浏览器就意味着自己退到后台，而国产 ROM 的省电策略正是在那一刻掐掉后台联网——
 * 用户看到的是「刚点开就打不开了」（见 [net.lighttools.lightmux.ui.common.BackgroundLimitBanner]）。
 * 留在 app 内，前台还是自己，隧道不会当着面断掉。
 *
 * 功能只做到「看一眼转发出来的页面」为止：地址栏、前进后退刷新、进度条。
 * 没有标签页、书签、历史库——那些都要落盘、要 UI、要迁移，而这里的用法是「点开、看完、退出」。
 * 用的是系统 `WebView`，一个第三方依赖都不引，APK 只多出这几个类编出来的 dex。
 *
 * 返回键走中央栈的 [net.lighttools.lightmux.ui.LightmuxRoot] `backIntercept`（架构要点 ④）：
 * 网页自己还有历史就退一页，退到头才关掉这一页。
 */
@Composable
fun WebScreen(
    vm: WebViewModel,
    /** 这一页此刻是不是真的露在屏幕上。收进后台（[net.lighttools.lightmux.ui.LightmuxRoot]）也算不可见。 */
    visible: Boolean,
    onClose: () -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    /*
     * WebView 建一次就留着，跟着这棵组合树活。
     *
     * 不放 ViewModel：它持的是 Activity context，塞进去就是连着整个 Activity 一起泄漏。
     * 旋转屏幕不会重建它——MainActivity 在 manifest 里接管了 orientation 等配置变更。
     */
    val web = remember { createWebView(context, vm, onOpenExternal) }

    DisposableEffect(web) {
        vm.controls = object : WebControls {
            override fun load(url: String) = web.loadUrl(url)

            override fun reload() = web.reload()

            override fun goBack(): Boolean {
                if (!web.canGoBack()) return false
                web.goBack()
                return true
            }

            override fun goForward() = web.goForward()
        }
        onDispose { vm.controls = null }
    }

    /*
     * 划回后台就把网页那侧停下：JS 定时器、动画、正在播的音视频都归 onPause 管。
     *
     * **不能只看 Activity 的生命周期**：这一页收进后台时压根没有生命周期事件——它一直留在
     * 组合树里，只是被 graphicsLayer 平移出了屏幕（见 LightmuxRoot 那段「后台浏览器」）。
     * 用户从终端把它划走，Activity 还是 RESUMED，一个挂着轮询或视频的页面就这么在看不见的地方
     * 接着跑，白耗电和流量。
     *
     * `pauseTimers`/`resumeTimers` 是**进程级**的：停的是全进程所有 WebView 的 JS 定时器，
     * 不是这一个实例。本 app 里两个 WebView 是可能同时存在的——后台那一页收起来时并不销毁
     * （`webUrl` 还在），这时从转发整页又推一个 `Screen.Web` 出来就是第二个。
     * 但**不会有可见的页面被冻住**：可见的那个总会在自己进组合时 `resumeTimers`，
     * 而两者不可能同时可见（后台那页只在终端页上露脸，整页版本露脸时栈顶不是终端）。
     * 代价只是「一个藏着的页面被可见的那个顺带唤醒」，退回改动前的老样子而已。
     *
     * **再加第三个 WebView 之前先回来看这一段**：真要做到互不干扰，这两行得改成按可见实例计数，
     * 全都不露着才停。现在没做是因为那个计数还要处理「可见时被销毁」的还账，
     * 而销毁后的 WebView 上任何调用都是未定义行为，为一个眼下不存在的场景不值当。
     */
    LaunchedEffect(web, visible) {
        if (visible) {
            web.onResume()
            web.resumeTimers()
        } else {
            web.onPause()
            web.pauseTimers()
        }
    }

    // Activity 整个切后台时同样要停。ON_RESUME 只在这一页确实露着时才恢复，
    // 否则「收在后台 → 切出 app → 切回来」会把一个看不见的页面重新唤醒，
    // 和上面那段互相打架。
    DisposableEffect(lifecycleOwner, web, visible) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    web.onPause()
                    web.pauseTimers()
                }

                Lifecycle.Event.ON_RESUME -> if (visible) {
                    web.onResume()
                    web.resumeTimers()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        /*
         * 操作栏在底部：网页从状态栏底下一直铺到栏的上沿，中间没有任何一条横杠，观感接近全屏；
         * 顺带那几个键落在拇指够得着的地方，比顶栏顺手。
         */
        bottomBar = { WebBottomBar(vm = vm, onClose = onClose, onOpenExternal = onOpenExternal) },
    ) { padding ->
        AndroidView(
            factory = { web },
            modifier = Modifier.fillMaxSize().padding(padding),
            // 退出这一页就彻底销毁：WebView 不 destroy 会把整个渲染进程和它的内存留在那儿
            onRelease = {
                it.stopLoading()
                it.destroy()
            },
        )
    }
}

@Composable
private fun WebBottomBar(vm: WebViewModel, onClose: () -> Unit, onOpenExternal: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        // Scaffold 不管 bottomBar 的 insets，不加这一下整条栏会压进导航栏里（同 ExtraKeysBar）。
        // 键盘弹出时上面那层 imePadding 已经把这段消费掉，不会叠成两层空白。
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            // 进度条贴在栏的上沿，等于给网页画了条下边框；加载完就撤掉，不占那 2dp
            if (vm.progress < 100) {
                LinearProgressIndicator(
                    progress = { vm.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    // 2dp 高画不下 M3 那个收尾圆点和缺口，留着只会在右端糊一小块
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
            /*
             * M3 给每个可点击组件强制 48dp 的最小触摸目标，**布局上**也占满 48dp——
             * 不关掉的话这排按钮会把 44dp 的栏顶破（同 ExtraKeysBar 那处）。
             * 只关这一处：别处的按钮仍守 48dp。
             */
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarIcon(Icons.Default.Close, stringResource(R.string.close), onClick = onClose)

                    val draft = vm.draft
                    if (draft == null) {
                        /*
                         * 中间显示地址而不是网页标题：这个浏览器是给转发端口用的，
                         * 「开着的是哪个口」比「页面叫什么」有用，一行也只放得下一个。
                         *
                         * 整条都可点：手机上「点地址栏改地址」是肌肉记忆，
                         * 只让一个小图标可点等于把入口藏了。
                         */
                        Text(
                            text = WebUrl.display(vm.url),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                // 热区吃满整条栏高，再让文字在里面居中
                                .fillMaxHeight()
                                .clickable(onClick = vm::startEditing)
                                .padding(horizontal = 8.dp)
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    } else {
                        AddressField(
                            initial = draft,
                            onChange = vm::editDraft,
                            onSubmit = vm::submitDraft,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                    }

                    BarIcon(
                        Icons.Default.Refresh,
                        stringResource(R.string.refresh),
                        onClick = vm::reload,
                    )
                    Box {
                        BarIcon(
                            Icons.Default.MoreVert,
                            stringResource(R.string.web_more),
                            onClick = { menuOpen = true },
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // 后退不在这里：返回键就是后退，再摆一个按钮是重复。前进没有对应的手势，
                            // 只好收在菜单里——它也确实是用得最少的那个。
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_go_forward)) },
                                enabled = vm.canGoForward,
                                onClick = {
                                    menuOpen = false
                                    vm.goForward()
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_open_external)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenExternal(vm.url)
                                },
                                leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(ButtonSize)) {
        Icon(icon, description, modifier = Modifier.size(IconSize))
    }
}

/**
 * 地址栏的编辑态。
 *
 * 用 [BasicTextField] 而不是 M3 的 `TextField`：后者自带 56dp 高的容器和标签位，
 * 塞进这条 44dp 的栏里会把整条顶破。
 *
 * 进来就全选：改地址的十次里有九次是要换掉整条（换个端口、回首页），
 * 光标停在末尾的话每次都得先长按全选。
 */
@Composable
private fun AddressField(
    initial: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            onChange(it.text)
        },
        modifier = modifier.focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            // Uri 键盘留着 `/` 和 `.`，还不会自动大写首字母
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        // 敲完回车顺手收键盘：输入框会跟着编辑态一起消失，键盘留在那儿会挡住半个网页
        keyboardActions = KeyboardActions(onGo = {
            keyboard?.hide()
            onSubmit()
        }),
    )
}

/**
 * 建 WebView 并配好那几项设置。
 *
 * 抽成普通函数是为了让 `@SuppressLint` 有地方落——注解挂不到语句上。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    vm: WebViewModel,
    onOpenExternal: (String) -> Unit,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    settings.apply {
        // 要看的是本机转发出来的 dev server / 管理后台，十个里九个是 SPA，关了 JS 就是一片白屏。
        // 风险面也就到这儿为止：地址全是自己转发出来的，等于自己的机器。
        javaScriptEnabled = true
        // 前端框架启动就摸 localStorage，没开的话直接抛异常白屏，且不报任何看得懂的错
        domStorageEnabled = true
        // 后台管理页多半没写 viewport meta，不开这两项会按 980px 桌面宽渲染再截掉，
        // 手机上只看得见左上角那一块
        useWideViewPort = true
        loadWithOverviewMode = true
        // 双指缩放留着（页面窄了要放大看），但不要那两个压在内容上的老式缩放按钮
        builtInZoomControls = true
        displayZoomControls = false
        // 这个浏览器只用来看转发端口，没有任何理由让网页碰到本机文件
        allowFileAccess = false
        allowContentAccess = false
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            // intent://、mailto:、tg: 这些本 app 处理不了，交给系统去挑 app。
            // 不接管的话 WebView 会自己撞上 ERR_UNKNOWN_URL_SCHEME 报错页。
            onOpenExternal(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            vm.onNavigated(url, view.canGoForward())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // SPA 用 pushState 换地址不触发 onPageStarted，地址栏只能靠这一处跟上
            vm.onNavigated(url, view.canGoForward())
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            vm.onProgress(newProgress)
        }
    }

    // WebView 自己不会下载任何东西，不接这一下的话点「导出日志」这类链接是彻底没反应
    setDownloadListener { url, _, _, _, _ -> onOpenExternal(url) }

    loadUrl(vm.url)
}
