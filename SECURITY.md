# Security Policy · 安全策略

lightmux holds your server credentials (encrypted with the Android Keystore), pins host keys on
first use, and opens SSH tunnels. A bug in any of those is worth reporting privately.

lightmux 存的是你的服务器凭据（用 Android Keystore 加密），做 TOFU 主机密钥固定，还会开 SSH
隧道。这几处的问题请私下报告。

## Reporting · 如何报告

**Do not open a public issue.** 请不要开公开 issue。

- GitHub [Private vulnerability reporting](https://github.com/ninvfeng/lightmux/security/advisories/new)
  （首选 / preferred）
- Email · 邮件：ninvfeng@qq.com

Useful to include · 报告里最好带上：affected version · 受影响版本、reproduction steps · 复现步骤、
what an attacker gets out of it · 攻击者能拿到什么。

## Scope · 范围

In scope · 属于范围内：credential storage and encryption · 凭据存储与加密、host key verification ·
主机密钥校验、port forwarding exposure · 转发端口的暴露面、the update download and verification
path · 更新包的下载与校验链路。

Out of scope · 不属于范围内：issues in OpenSSH / tmux / the servers you connect to · 你所连接的
服务端或 OpenSSH、tmux 自身的问题；attacks that require an already-rooted or already-compromised
phone · 需要先 root 或先攻陷手机才能成立的攻击。

## Supported versions · 支持范围

Only the latest release is maintained. Fixes ship in a new version rather than as a patch to an
older one — see [CHANGELOG.md](./CHANGELOG.md).

只维护最新版本。修复以发新版的形式给出，不会回补到旧版本，见 [CHANGELOG.md](./CHANGELOG.md)。

## Response · 响应

This is maintained in spare time, so there is no SLA. Expect a first reply within about a week;
if a report is confirmed, the fix goes out in the next release and the reporter is credited in the
changelog unless they prefer otherwise.

业余时间维护，不承诺 SLA。一般一周内给第一次回复；确认的问题会在下个版本修掉，
并在 changelog 里署名报告者——除非你不希望署名。
