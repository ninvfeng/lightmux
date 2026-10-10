# 参与贡献

欢迎 issue 和 PR。这个项目是 MIT 开源的，能协作是它开源的意义之一。

**issue 和 PR 请提到 GitHub [ninvfeng/lightmux](https://github.com/ninvfeng/lightmux)。**
[CNB](https://cnb.cool/ninvfeng/lighttools/lightmux) 上是同一份代码，它负责构建、签名和发布
APK；协作入口只有 GitHub 一个，免得两边各回一半。安全问题走 [SECURITY.md](./SECURITY.md)，
不要开公开 issue。

不承诺响应 SLA——业余时间维护，PR 可能过几天才看。范围之外的功能（本地终端、云同步与账号、
桌面端，见 [README](./README.md#功能)）会直接关掉，不是因为想法不好，
是因为维护面必须收窄才活得下去。

## 技术栈与模块

Kotlin 2.1 + Jetpack Compose + Material 3，AGP 8.7，minSdk 24（Android 7.0），targetSdk 35。

| 模块 | 职责 | 许可 |
|---|---|---|
| `app` | 产品层：UI、会话编排、SSH 传输、tmux 侧通道 | MIT |
| `terminal-emulator` | 终端解析内核，取自 [termux-app](https://github.com/termux/termux-app) v0.118.0，做了传输改造 | Apache-2.0 |
| `terminal-view` | 终端渲染 View，取自 termux-app，未修改 | Apache-2.0 |

termux 的 `LICENSE.md` 对这两个模块给了明确例外，所以它们继续以 Apache-2.0 分发；
改动逐条列在 [NOTICE](./NOTICE)。依赖清单见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)，
依赖树里没有 GPL / LGPL / AGPL。产品设计与取舍见 [PRD.md](./PRD.md)。

## 几个关键的实现取舍

README 只讲用户看得见的效果，背后的做法在这里：

- **tmux 操作全走带外 `exec` 侧通道**，复用同一条 SSH 连接另开 channel，绝不向前台终端注入按键——
  前台可能是全屏 TUI，也可能压根没 attach
- **主页缓存优先**：冷启动直接画上次探测到的树并标「3 分钟前」，不发任何网络请求；展开某台主机才去探测
- **重连不丢滚屏**：断线后只换传输，emulator 原样保留；重连后 attach 回原来那个 tmux 会话。
  退避是指数式的，网络恢复时立即重试
- **会话常驻**：`SessionManager` 在 Application 作用域，前台服务保活，来回切页滚屏历史都在
- **监控以直读 `/proc` 为主**，一次 `exec` 采全量，不依赖 `top` / `free` 这类输出因发行版而异的工具；
  采不到的项如实标「不可用」，不拿 0 充数。目前只支持 Linux
- **端口转发只绑 `127.0.0.1`**，不会把内网服务摊给同一个 Wi-Fi；转发页面在 app 内打开，
  因为切去外部浏览器的那一下正是省电策略掐掉本应用后台联网的时机
- **凭据**经 Android Keystore（AES-GCM）加密；主机密钥 TOFU，变更时阻断式告警
- **应用内更新**没有后台轮询；下载后校验包名、版本号、签名与已安装应用一致，任何一项不过就删包
- **内置 JetBrains Mono**（OFL 1.1）：系统等宽字体不含制表符，TUI 框线会跨字体回退后被拉变形

## 构建与测试

需要 **JDK 17** 与包含 platform 35 的 Android SDK。

```bash
./gradlew assembleDebug                          # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew test                                   # 全部单测
./gradlew :app:testDebugUnitTest                 # app 纯逻辑单测
./gradlew :terminal-emulator:testDebugUnitTest   # vendored 内核的上游单测
python3 scripts/check_strings.py                 # 双语文案校验
./gradlew app:dependencies                       # 复核依赖许可
```

提 PR 前请至少跑通：`./gradlew assembleDebug test` 和 `python3 scripts/check_strings.py`。
CI 跑的就是这三条命令，本地过了 CI 基本不会红。

改了 release 相关配置还要跑一次 `./gradlew assembleRelease`——
release 构建会跑 `lintVital`，debug 抓不到，打 tag 时才炸就晚了。

## 提交信息

[Conventional Commits](https://www.conventionalcommits.org/)，**描述用中文**：

```
feat(tmux): 主页会话树支持展开窗口
fix(sftp): 上传中途断线不再把队列卡死
docs: 补充 README 的许可结构说明
```

常用 type：`feat` / `fix` / `refactor` / `docs` / `test` / `chore`。
scope 用模块名（`tmux` / `ssh` / `sftp` / `monitor` / `session` / `ui` / `settings`）。

注释写「为什么」，不写「是什么」——「是什么」代码里已经有了。中文注释。

## 加依赖之前

**包体是 lightmux 的卖点**（当前约 3.8MB）。加库前先掂量它对 APK 的影响，
十几行能自己写的就别拉一个库进来；顺手看一眼许可：GPL / LGPL / AGPL 不引，
**传递依赖也算**，跑 `./gradlew app:dependencies` 看清楚它拖了什么进来。
增删依赖同步 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。

`terminal-emulator/` 和 `terminal-view/` 是 vendored 的 Apache-2.0 代码，非必要不改；
真改了，每处加 `// [lightmux] 为什么改` 注释，并在 [NOTICE](./NOTICE) 里补一条。

## 用户可见文案必须中英同步

所有用户能看到的字都走 `strings.xml`，且 `values/` 与 `values-zh/` 的 **key 集合必须完全一致**。

`python3 scripts/check_strings.py` 会校验四件事，任何一条不过 CI 就红：

1. 两个文件的 key 集合完全相同
2. **每条文案的格式化占位符完全一致**（`%1$s` / `%1$d` 的编号、数量、类型都要对得上）
3. 英文文件里不含中文字符（漏翻的信号）
4. 中文文件里没有整条原样照搬英文的（键帽 `Esc`/`Tab`/`Ctrl`/`Alt`、专有名词、许可正文在脚本里白名单掉了）

第 2 条是这个脚本存在的主要理由：占位符对不上会在 `String.format` 时抛
`IllegalFormatException` **直接崩溃**，而且只在那条文案真被用到时才崩——编译不报错，
单测也覆盖不到每一条文案，只能靠脚本兜。

脚本还会拦未转义的引号：`"` 会被 aapt2 当作「保留空白」的定界符**静默吞掉**
（编译不报错，装到手机上才发现引号没了），`'` 则直接编译失败。都要写成 `\"` / `\'`。

## `terminal-emulator/src/test/` 的 138 个单测必须保持全绿

这些测试是从 termux 上游**原样搬来**的，一行没改，它们是 vendored 内核没被改坏的护栏。

lightmux 对 `TerminalSession` 做了传输无关改造（拆掉 forkpty、加 `reconnect()`），
一旦不小心动到 ANSI 解析、reflow、滚屏这些真正的内核逻辑，本项目自己的测试是发现不了的——
产品层测试只覆盖 `Tmux` / `HostFacts` / `SftpPath` 这类纯逻辑，摸不到 emulator。

所以：**任何情况下都不要为了让改动通过而修改这 138 个测试。** 它们红了，说明内核被改坏了，
该改的是你的改动。

```bash
./gradlew :terminal-emulator:testDebugUnitTest
```

## 代码结构约定

改之前先读 [CLAUDE.md](./CLAUDE.md) 的「架构要点」和 [PRD.md](./PRD.md) 的 §4、§6，
下面几条最容易踩：

- **会话常驻**：`SessionManager` 活在 Application 作用域，不随导航销毁。破了这条 = 多会话卖点消失
- **导航是中央页面栈**（`ui/Navigation.kt`），`LightmuxRoot` 里唯一一个 `BackHandler` 统管返回。
  **不要在页面内部自己写 `BackHandler`**——漏补返回路径的后果是直接退出 app，不是「返回不生效」
- **主页状态放 ViewModel**：展开了哪些主机、滚动位置、探测缓存都不能放页面 `remember`
- **纯逻辑与 UI 分离**：可测的东西抽成无 Android 依赖的 object，单测放 `app/src/test/`。
  本项目 CI 里没有真机，纯 Kotlin 逻辑是唯一能自动验证的部分，所以新加逻辑请一并加测试
- **tmux 侧通道纪律**见 `CLAUDE.md`，其中「绝不向前台终端注入按键」和
  「解析失败返回 `Malformed` 而不是空列表」是踩过坑的结论

## 两个仓库

同一份代码在 [GitHub](https://github.com/ninvfeng/lightmux)（issue 与 PR）与
[CNB](https://cnb.cool/ninvfeng/lighttools/lightmux)（构建、签名、发布 APK）各一份，保持同步。

## 发布

维护者操作：`versionCode` +1、`versionName` 与 tag 对齐，打 `vX.Y.Z` 推送，
CNB 流水线出签名 APK 并挂到 Release。每个分发出去的 APK 都必须递增 `versionCode`。
同时更新 [CHANGELOG.md](./CHANGELOG.md)。

提交要推两边（GitHub 与 CNB），但 **tag 只打在 CNB**：tag_push 是发版构建的触发器，
GitHub 上补一个同名 tag 既不出包，还会让「哪个 tag 对应哪个已发布 APK」多出一个说法。

发版提交标题带上 `[skip ci]`（`chore(release): X.Y.Z [skip ci]`）：它和 tag 指向同一个
commit，不加就会让 main push 和 tag_push 各跑一条内容相同的流水线。`[skip ci]` 只对
push 事件生效，tag 那条照常出包。

## 不入库

`local.properties`、任何 keystore（`*.jks` / `*.keystore`）、构建产物、内部文档。
`.gitignore` 已经覆盖，别用 `git add -f` 绕过去。
