#!/usr/bin/env python3
"""从 CHANGELOG.md 抽出某个版本的段落，作为 Release 说明。

存在的理由：Release 的描述以前是写死在 `.cnb.yml` 里的一段套话，每个版本一模一样。
应用内「检查更新」弹出的就是这段文字，用户看到的永远是「变更详情见 CHANGELOG.md」——
一个只能在手机上点「下载安装」的对话框，却让人去别处翻变更，等于没说。

抽不到就非零退出：CHANGELOG 里没写这版 = 发版流程漏了一步，
宁可在打包前红，也不要发一个说明为空的版本出去。

用法：
    python3 scripts/release_notes.py v0.1.15 > RELEASE_NOTES.md
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"

# 附件与安装方式和具体版本无关，每版都要说一遍，跟在变更内容后面
FOOTER = """\
---

允许「安装未知来源应用」后可直接覆盖升级（固定签名）。APK 见下方附件。
"""


def extract(text: str, version: str) -> str | None:
    """取 `## [version]` 到下一个 `## ` 之间的内容。找不到返回 None。"""
    lines = text.splitlines()
    # 标题形如 `## [0.1.15] - 2026-08-15`，日期可有可无
    head = re.compile(r"^##\s+\[?" + re.escape(version) + r"\]?(\s|$)")
    start = next((i for i, line in enumerate(lines) if head.match(line)), None)
    if start is None:
        return None
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    body = "\n".join(lines[start + 1:end]).strip()
    return body or None


def main() -> int:
    if len(sys.argv) != 2:
        print(f"用法: {sys.argv[0]} <版本号，如 v0.1.15 或 0.1.15>", file=sys.stderr)
        return 2
    version = sys.argv[1].strip().lstrip("vV")
    body = extract(CHANGELOG.read_text(encoding="utf-8"), version)
    if body is None:
        print(f"❌ CHANGELOG.md 里没有 {version} 的段落，先补上再发版", file=sys.stderr)
        return 1
    print(body)
    print()
    print(FOOTER)
    return 0


if __name__ == "__main__":
    sys.exit(main())
