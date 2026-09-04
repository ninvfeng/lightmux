# lightmux

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![CI](https://github.com/ninvfeng/lightmux/actions/workflows/ci.yml/badge.svg)](https://github.com/ninvfeng/lightmux/actions/workflows/ci.yml)
[![CNB](https://img.shields.io/badge/CNB-lightmux-0052D9.svg)](https://cnb.cool/ninvfeng/lighttools/lightmux)

**一个轻量极致、为 tmux 而生的 Android SSH 客户端。**

[English](./README.en.md)

---

- **3.6MB**：这是整包大小——不塞 shell，不带统计 SDK，不夹带用不上的东西。
- **简约而不简单**：除核心 SSH 外，还有 SFTP 文件管理、主机概览、端口转发与内置预览，
  以及一条可以按自己习惯重排的快捷栏。

在手机上用 SSH 的人，真实工作流几乎都是 tmux：连上去 → `tmux ls` → `tmux a -t xxx` → 找窗口。
其他 Android SSH 客户端把 tmux 当成**终端里的一个功能**——先进终端，再开面板，再选会话。
这在桌面没问题，在手机上是三次导航换一次上下文切换。

lightmux 把这个层级倒过来：主页直接列出 **主机 → tmux 会话 → 窗口**，展开、点击、进去。
终端是结果，不是入口。

从主页进到某个 tmux 窗口**最多 3 次点击**，主机已展开时 1 次；
在终端里侧滑就能拉出同一棵树，切到另一个会话再点一下即可。

## 下载

签名 APK 发布在 [CNB Releases](https://cnb.cool/ninvfeng/lighttools/lightmux/-/releases)，
需要 Android 7.0 及以上（minSdk 24）。当前未上架任何应用商店，安装时需要放行一次「未知来源」。

代码在 GitHub 与 CNB 各有一份，但发版流水线只跑在 CNB，所以包在那边。见下方[仓库](#仓库)。

## 功能

以下全部是已实现的内容。

**tmux 会话切换器（主页）**
- 三级树：主机 → 会话 → 窗口，就地展开
- 缓存优先：冷启动直接渲染上次探测到的树，带「3 分钟前」时间戳，**不发起任何网络请求**；
  展开某台主机时才去探测它
- 会话：attach / 新建 / 重命名 / 断开其他客户端 / 结束
- 窗口：列出与切换
- 本 app 开的非 tmux「裸会话」与 tmux 会话平级挂在主机下
- 所有 tmux 动作都走带外 `exec` 侧通道，**绝不向前台终端注入按键**——
  前台可能是全屏 TUI，也可能压根没 attach

**终端**
- 多会话并存，由 Application 作用域的 `SessionManager` 持有，不随导航销毁，来回切页滚屏历史仍在
- 前台服务保活，切后台后会话与传输继续跑
- 断线自动重连（指数退避 + 网络恢复立即重试），**重连不丢滚屏**——只换传输，emulator 原样保留
- 重连后自动 attach 回原来那个 tmux 会话，而不是新开一个
- 复制 / 粘贴、捏合缩放、滚屏历史
- 快捷栏：`Esc` `Tab` `Ctrl` `Alt` `↑↓←→` `-` `|` `~` `/`——
  没有 `Ctrl`，Android 软键盘打不出 tmux 前缀键，所以这属于核心功能而非锦上添花。
  `Ctrl` / `Alt` 是粘滞修饰键（先点它，再点下一个键），按下期间高亮
- 快捷栏可自定义：键位增删、拖动排序、键帽大小可调；另有一格「命令」收着自建的快捷命令，
  点一下把整条命令送进终端（可选自动回车）
- 自定义键：一格发一串文本，或发一串按键序列（`C-b d`、`M-.`、`F5`、`Up Up Enter`，
  点选 Ctrl / Alt / Shift 与键帽拼出来），把带前缀的 tmux 两击顶成一键

**主机管理**
- 名称、host、端口、用户、分组；密码 / 私钥（PEM）认证
- 密钥集中管理：导入一次，多台主机共用一把；改一处，用到它的主机跟着变
- 凭据经 Android Keystore（AES-GCM）加密存储，不落明文
- 主机密钥 TOFU 校验：首次连接记录指纹，变更时阻断式告警
- ProxyJump（跳板机）
- 登录后自动执行命令

**主机监控**
- 发行版 / 内核 / 主机名 / 运行时长 / 负载 / CPU（总体 + 每核）/ 内存 / 交换 / 磁盘 / 网卡 /
  容器 / 进程排行；本地与公网 IP 可点击复制
- 一次 `exec` 采全量，以直读 `/proc` 为主——不依赖 `top`、`free` 这类输出因发行版而异的工具；
  采不到的项如实标「不可用」，**不拿 0 充数**
- 容器与进程可按 CPU 或内存倒序，一眼看出谁在吃资源
- 停留在页面时每 5 秒刷新，离开立即停
- 目前只支持 Linux，没有 `/proc/stat` 的主机直说「暂不支持」，不给错数字

**文件管理（SFTP）**
- 复用同一条 SSH 连接：浏览 / 上传 / 下载 / 新建目录 / 重命名 / 删除
- 小文本文件可在 app 内编辑保存（二进制与超大文件会被拒绝）
- 传输走 Application 级后台队列，进度可见、可取消

**端口转发**
- 等价于 `ssh -L`：服务器上跑在 3000 端口的东西，手机浏览器里直接开 `http://127.0.0.1:3000`
- 打开转发就列出远端在监听哪些端口（`ss -tlnp`），只绑 `127.0.0.1` 的排在最前——那些正是非转发不可的；
  端口太多就用搜索框按端口号 / 进程名就地过滤
- **只绑环回口**：转发出来的端口只有这台手机连得上，不会顺手把内网服务摊给同一个 Wi-Fi
- 终端输出里出现 `http://127.0.0.1:3000` 这类地址时，底部弹一条提示，点一下就转发并打开——
  服务刚起来那一刻正是要开它的时候，不用再跑一趟转发页
- 断线自己退避重连，活着的转发会让前台服务继续保活
- 转发出来的页面**在 app 内打开**（内置迷你浏览器）：切去外部浏览器的那一下，
  恰恰是省电策略掐掉本应用后台联网的时机，隧道会当着面断掉。
  地址栏可改（只打 `3000` 就是 `127.0.0.1:3000`），返回键即后退，
  操作栏在底部且只有 44dp 高，屏幕尽量让给网页，「用外部浏览器打开」收在这条栏的「⋮」里

**应用内更新**
- 支持应用内检查更新，release 挂在 CNB 仓库，**没有后台轮询**
- 下载后校验包名、版本确实更新、签名与已安装应用一致，通过才拉起系统安装器；
  任何一项不过就删掉下载的文件

**基础**
- 中英双语，跟随系统，设置内可切
- 亮色 / 暗色 / 跟随系统主题
- 3 套终端配色：默认（xterm）、Solarized Dark、Gruvbox Dark；终端字号可调
- 内置 JetBrains Mono Regular（OFL 1.1）作终端字体——系统等宽字体不含制表符，
  TUI 框线会跨字体回退后被拉变形；CJK 仍走系统回退
- 后台联网被系统限制时会在设置页直说，并给一键跳转去放行

## 不做的事

- 本地终端 / 内置 shell（那是 termux 的地盘）、字体下载中心、云同步 / 账号体系、桌面端。

## 截图

还没有——本机没有真机，等在真机上截好再补到这一节。

## 构建

需要 **JDK 17** 与包含 platform 35 的 Android SDK。

```bash
./gradlew assembleDebug                    # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew test                             # 全部单测
./gradlew :app:testDebugUnitTest           # app 纯逻辑单测（393 个）
./gradlew :terminal-emulator:testDebugUnitTest   # 上游 emulator 单测（138 个）
python3 scripts/check_strings.py           # 双语文案校验
```

minSdk 24（Android 7.0），targetSdk 35，Kotlin 2.1 + Jetpack Compose + Material 3，AGP 8.7。

## 许可

**MIT**，见 [LICENSE](./LICENSE)。

[`terminal-emulator/`](./terminal-emulator/LICENSE) 与 [`terminal-view/`](./terminal-view/LICENSE)
取自 [termux-app](https://github.com/termux/termux-app) v0.118.0，继续以 **Apache-2.0** 分发
（termux 的 `LICENSE.md` 对这两个模块给出了明确例外）。对它们的改动标注 `[lightmux]` 注释，
逐条列在 [NOTICE](./NOTICE)。依赖清单见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)——
依赖树里没有 GPL / LGPL / AGPL。

## 仓库

同一份代码在两处，保持同步：

| | |
|---|---|
| [github.com/ninvfeng/lightmux](https://github.com/ninvfeng/lightmux) | issue 与 PR 提到这里 |
| [cnb.cool/ninvfeng/lighttools/lightmux](https://cnb.cool/ninvfeng/lighttools/lightmux) | 构建、签名并发布 release APK |

版本 tag 只打在 CNB——那边的 tag 就是发版构建的触发器。

## 参与贡献

欢迎 PR，动手前先读 [CONTRIBUTING.md](./CONTRIBUTING.md)。
安全问题见 [SECURITY.md](./SECURITY.md)，**请不要开公开 issue**。

产品设计与取舍理由见 [PRD.md](./PRD.md)，版本历史见 [CHANGELOG.md](./CHANGELOG.md)。
