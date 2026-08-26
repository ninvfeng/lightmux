# 第三方组件与许可

lightmux 以 **MIT** 发布，但仓库内含两个 **Apache-2.0** 的 vendored 模块和一个 **OFL-1.1** 的字体，
整包分发时用户同时受这几份许可约束。本文列出全部第三方组件。感谢这些项目的作者与维护者。

## 许可结构一览

| 路径 | 许可 | 版权 |
|---|---|---|
| `app/`、仓库整体 | MIT | ninvfeng |
| `terminal-emulator/` | Apache-2.0 | Termux / jackpal 及贡献者 |
| `terminal-view/` | Apache-2.0 | Termux / jackpal 及贡献者 |
| `app/src/main/res/font/jetbrains_mono_regular.ttf` | SIL OFL 1.1 | The JetBrains Mono Project Authors |

## Vendored（源码内置）

### terminal-emulator / terminal-view

- **来源**：[termux/termux-app](https://github.com/termux/termux-app) v0.118.0 的同名模块
- **上游来源**：[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)（Jack Palevich 等）
- **许可**：**Apache License 2.0**

> ⚠️ termux-app 仓库**整体是 GPLv3-only**，但其 `LICENSE.md` 对这两个模块给出了明确的 Apache-2.0 例外。
> lightmux **只取这两个模块**，不包含 termux 的任何 GPLv3 代码。详见 [NOTICE](./NOTICE)。

**修改说明**（改动处均标注 `[lightmux]`）：

- `terminal-emulator`：删除 `JNI.java` 与 `src/main/jni/`（本地 PTY 的 C 代码）；
  新增 `TerminalTransport.java`（字节通道抽象）；
  将 `TerminalSession.java` 改写为传输无关（移除 forkpty / waitpid / SIGKILL / `/proc` 读 cwd，
  新增 `reconnect()` 以在保留滚屏历史的前提下换连接）。其余文件与上游一致。
- `terminal-view`：**未修改**。
- 上游单元测试原样保留并在 CI 中运行，用于验证 vendored 内核未被改坏。

### 字体：JetBrains Mono

- **来源**：[JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) v2.304
- **文件**：`app/src/main/res/font/jetbrains_mono_regular.ttf`（Regular 单字重，268KB，**原样内置未作任何修改**）
- **许可**：**SIL Open Font License 1.1** — 全文见 `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`
  （随 APK 分发，满足 OFL 第 2 条「每份副本须附带本许可」）
- **版权**：Copyright 2020 The JetBrains Mono Project Authors

只打包 Regular：粗体走 `Paint.setFakeBoldText`，斜体走 `setTextSkewX`，都不需要额外字重文件。
中日韩字符不在字体内，照旧走系统回退（Android 内置 Noto Sans CJK）。

> 曾一度改为「不打包任何字体、直接用 `Typeface.MONOSPACE`」，后又改回内置，原因有二：
> 一是 `Typeface.MONOSPACE` 在 Android 上是 Droid Sans Mono，**不含制表符**（U+2500 系列），
> TUI 的框线要回退到别的字体、宽度对不上被 `TerminalRenderer` 拉伸，线条会歪；
> 二是它字面偏小（x-height 0.53em vs 0.55em），同样 0.6em 格宽下 `l`、`i` 只剩一根光竖线。
> 当初担心的 OFL 保留字体名称（RFN）问题并不存在——JetBrains Mono 的 OFL 头部没有声明 RFN，
> 何况这里是原样分发、未作子集化或任何修改。「导入本地 TTF」仍在 V2 路线图上。

## 运行期依赖（构建时拉取，未内置源码）

| 组件 | 许可 | 用途 |
|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | Apache-2.0 | SSH 传输与 SFTP |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on`) | MIT-style (Bouncy Castle License) | 加密算法（sshj 依赖） |
| [SLF4J](https://www.slf4j.org/) (`slf4j-api`) | MIT | 日志门面（sshj 依赖） |
| AndroidX / Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Kotlin 标准库与协程 | Apache-2.0 | 语言运行时 |
| AndroidX DataStore | Apache-2.0 | 本地配置存储 |
| JUnit 4 | EPL-1.0（仅测试期，不进 APK） | 单元测试 |

## 明确未包含

- **mosh**（GPLv3）——不集成，`TerminalTransport` 留有扩展位但主 APK 不含任何 GPL 代码。
- 任何 termux-app 的 GPLv3 模块（`app/`、`termux-shared/` 等）。

依赖树中零 GPL / LGPL，可用 `./gradlew app:dependencies` 复核。
