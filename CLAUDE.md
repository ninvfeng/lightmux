# lightmux

**轻量极致、为 tmux 而生的 Android SSH 客户端**。安装包 3.6MB，主页即 tmux 会话切换器；
除核心 SSH 外还带 SFTP 文件管理、主机概览、端口转发与内置预览、可自定义的快捷栏。MIT 开源。
产品设计见 [PRD.md](./PRD.md)——改动前先读它，尤其是 §4 信息架构与 §6 技术架构。

## 构建

本机可完整构建（JDK17 + `/opt/android-sdk`），**必须显式带 JAVA_HOME**：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug --offline
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test --offline        # 全部单测
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
```

- `--offline` 走本地 gradle 缓存，快且不受网络影响。**新增依赖时**去掉它，并配代理：
  `export http_proxy=http://192.168.3.101:7890 https_proxy=http://192.168.3.101:7890`
- release 构建会跑 `lintVital`，debug 抓不到。**打 tag 前必须跑一次 `assembleRelease`**。

## 模块

| 模块 | 职责 | 许可 |
|---|---|---|
| `app` | 产品层：Compose UI、会话编排、SSH 传输、tmux 侧通道 | MIT |
| `terminal-emulator` | ANSI 解析 / 终端状态内核（vendored termux + `[lightmux]` 传输改造） | Apache-2.0 |
| `terminal-view` | 终端渲染 View（vendored termux，**未修改**） | Apache-2.0 |

`terminal-emulator/src/test/` 是上游原样保留的 138 个单测，用来兜住 vendored 内核不被改坏——**必须保持全绿**。

## 架构要点

**① `TerminalTransport`**（`terminal-emulator`，Java）——终端字节通道抽象。
`start()` 由 `TerminalSession` 在独立线程调用并**阻塞**到连接结束，期间用 `Listener` 回灌数据。
`TerminalSession.reconnect(transport)` 换传输但**保留 emulator 与滚屏历史**，断线重连走这里。

**② `exec()` 带外侧通道**在 app 层的 `SshConnection` 上，不在 `TerminalTransport` 里——
tmux 列表、监控采集、SFTP 都复用同一条 SSH 连接开新 channel，**绝不向前台终端注入按键**
（前台可能是全屏 TUI，也可能压根没 attach）。

**③ 会话常驻**：`SessionManager` 活在 Application 作用域，**不随导航销毁**。
终端页每次进入重建 `TerminalView` 再 attach 到既有 session；`updateSize` 对已有 emulator **只 resize 不重建**。
这条被打破 = 多会话卖点消失。

**④ 导航**：不用 navigation-compose。`Navigator` 是一个**中央页面栈**（`ui/Navigation.kt`），
`LightmuxRoot` 里唯一一个 `BackHandler` 统管返回。新增页面只需往 `Screen` 加一项，
**不要在页面内部自己写 BackHandler**——中央栈就是为了让「漏补返回路径导致直接退出 app」不可能发生。
主页的展开状态 / 滚动位置 / 探测缓存必须放 ViewModel 层，不能放页面 `remember`。

**⑤ 纯逻辑与 UI 分离**：可测逻辑抽成无 Android 依赖的 object（`Tmux`、`HostFacts`、`SftpPath`、
`ReconnectPolicy`…），单测放 `app/src/test/`。理由很实在：本机跑不了真机，**纯 Kotlin 逻辑是唯一能自测的部分**。

## tmux 侧通道纪律

1. **一次 exec 完成探测 + 列会话 + 列窗口**，别发三次命令。
2. **`-F` 格式串用 `:` 分隔**——TAB 会被 tmux 替换成 `_`；**名字一律放行尾**，数值字段从行尾反解。
3. **会话身份跨连接用 name**（`$id` 只在单个 tmux server 生命周期内有效）；窗口切换用 `@id`。
4. **解析失败返回 `Malformed`，不许伪装成空列表**——空列表会让 UI 显示「没有会话」，用户以为会话丢了。
5. attach 用 `new-session -A -s <name>`（原子，存在则附加），接管加 `-D`。
6. 动作命令包成首行 `__LM_RC__:<code>` + 原始输出（stderr 合入），统一解析退出码。

## 约定

- **包体是卖点**（当前 3.57MB）。加依赖、加资源前先掂量它对 APK 的影响，
  能自己写十几行解决的就别拉一个库进来。
- 用户可见文案一律走 `strings.xml`，`values/` 与 `values-zh/` **条目数必须相等**。
- 注释写「为什么」，不写「是什么」。中文注释。
- vendored 的 `terminal-emulator/`、`terminal-view/` 非必要不改；改了要标 `[lightmux]` 注释并同步 `NOTICE`。
- 加依赖看一眼许可：GPL / LGPL / AGPL 不引，增删依赖同步 `THIRD_PARTY_NOTICES.md`。
- Conventional Commits，描述用中文：`feat(tmux): 主页会话树支持展开窗口`。
- **代码推两个远端**：`origin`（GitHub，协作入口）与 `cnb`（构建发版）。
  **tag 只打 CNB**——tag_push 是发版触发器，GitHub 上补同名 tag 不出包，只会让版本对应关系多一个说法。
- 每个分发出去的 APK 必须递增 `versionCode`。
- **发版提交标题必须带 `[skip ci]`**：`chore(release): 0.1.47 [skip ci]`。
  发版提交和 tag 指向同一个 commit，不加就是 main push 和 tag_push 各跑一条一样的流水线；
  `[skip ci]` 只压制 push 事件，tag_push 照常出包（见 `.cnb.yml` 头部注释）。
- 不入库：`local.properties`、keystore、内部文档。
