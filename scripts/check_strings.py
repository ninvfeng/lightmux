#!/usr/bin/env python3
"""校验 values/ 与 values-zh/ 的文案一致性。

存在的理由是第 2 项检查：格式化占位符对不上会在 String.format 时抛
IllegalFormatException **直接崩溃**，而且只在那条文案真被用到时才崩——
既编译不出错，也没有单测能覆盖到每一条文案。所以用脚本兜底，进 CI。

`<string>` 和 `<plurals>` 走同一套检查。plurals 多一条：每种语言该有的
quantity 档位必须齐全——缺了 `other`，`getQuantityString` 在运行时抛
Resources.NotFoundException，同样是编译期看不见的崩溃。

用法：
    python3 scripts/check_strings.py          # 退出码 0 = 全部通过
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EN = ROOT / "app/src/main/res/values/strings.xml"
ZH = ROOT / "app/src/main/res/values-zh/strings.xml"

# 两种语言下故意写成同一个值的 key。
# 键帽（Esc/Tab/Ctrl/Alt）是键盘上印的字，翻译了反而对不上实体键；
# 字号样例是条真能敲的 shell 命令，翻译完就不是命令了；
# 产品名、配色方案名、语言自称是专有名词（CPU / GPU 这类缩写中文里也照写）；许可正文翻译后没有法律效力。
SAME_VALUE_OK = {
    "app_name",
    "key_esc", "key_tab", "key_ctrl", "key_alt",
    "monitor_cpu", "monitor_gpu", "monitor_sort_cpu",
    "settings_language_zh", "settings_language_en",
    "settings_palette_solarized_dark", "settings_palette_gruvbox_dark",
    "settings_text_size_sample",
    "about_license_body", "about_notice_title", "about_notice_body",
}

# 允许在英文文件里出现中文字符的 key（语言选项要用母语自称）。
CJK_IN_EN_OK = {"settings_language_zh"}

# 每种语言按 CLDR 该有、且只该有的 quantity 档位。
# values 是默认语言（英文）：少了 one 就是「1 sessions」；中文没有单复数之分，只有 other，
# 多写一档 aapt2 不会报错，但那一档永远取不到，属于误导后来人的死资源。
QUANTITIES = {
    "values": {"one", "other"},
    "values-zh": {"other"},
}

CJK = re.compile(r"[一-鿿㐀-䶿　-〿＀-￯]")

# Android 资源里的转义序列（`\"`、`\n`、`\'`…），检查引号前先把它们摘掉
ESCAPE = re.compile(r"\\.")

# java.util.Formatter 语法：%[index$][flags][width][.precision]conversion
FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")


def load(path: Path) -> dict[str, str]:
    """读出 name -> 文本。只取 <string>，plurals 走 [load_plurals]。"""
    if not path.exists():
        fail_hard(f"找不到文件：{path}")
    root = ET.parse(path).getroot()
    out: dict[str, str] = {}
    for node in root.findall("string"):
        name = node.get("name")
        if name is None:
            continue
        if name in out:
            fail_hard(f"{path.name} 里 key 重复：{name}")
        # 带内联标签的文案要把子节点文本也拼进来，否则占位符会漏检
        out[name] = "".join(node.itertext())
    return out


def load_plurals(path: Path) -> dict[str, dict[str, str]]:
    """读出 name -> {quantity: 文本}。"""
    if not path.exists():
        fail_hard(f"找不到文件：{path}")
    root = ET.parse(path).getroot()
    out: dict[str, dict[str, str]] = {}
    for node in root.findall("plurals"):
        name = node.get("name")
        if name is None:
            continue
        if name in out:
            fail_hard(f"{path.name} 里 plurals key 重复：{name}")
        items: dict[str, str] = {}
        for item in node.findall("item"):
            quantity = item.get("quantity")
            if quantity is None:
                continue
            if quantity in items:
                fail_hard(f"{path.name} 里 {name} 的 quantity 重复：{quantity}")
            items[quantity] = "".join(item.itertext())
        out[name] = items
    return out


def fail_hard(msg: str) -> None:
    print(f"check_strings: {msg}", file=sys.stderr)
    sys.exit(2)


def placeholders(text: str) -> tuple[list[str], list[str]]:
    """抽出占位符。

    返回 (带编号的规范形式列表, 未带编号的原样列表)。
    `%%` 是转义的百分号，不是占位符。
    """
    indexed: list[str] = []
    bare: list[str] = []
    for index, _flags, _width, _prec, conv in FORMAT.findall(text):
        if conv == "%":
            continue
        if index:
            indexed.append(f"%{index}${conv}")
        else:
            bare.append(f"%{conv}")
    return sorted(indexed), bare


def strip_format(text: str) -> str:
    """去掉占位符与转义百分号，剩下的才是真正的「文案本体」。"""
    return FORMAT.sub("", text)


def check_keys(kind: str, en_keys: set[str], zh_keys: set[str], errors: list[str]) -> None:
    """① key 集合必须完全相同。"""
    for key in sorted(en_keys - zh_keys):
        errors.append(f"[缺翻译] values-zh 缺少 {kind}: {key}")
    for key in sorted(zh_keys - en_keys):
        errors.append(f"[多余] values 缺少 {kind}: {key}（values-zh 有）")


def check_quotes(lang: str, label: str, text: str, errors: list[str]) -> None:
    """⓪ 未转义的引号。`"` 被 aapt2 当作「保留空白」的定界符**直接吞掉**，
    编译不报错、单测测不到，只有装到手机上才发现引号没了；`'` 则直接编译失败。"""
    bare = ESCAPE.sub("", text)
    if '"' in bare:
        errors.append(
            f"[引号] {lang}/{label} 有未转义的双引号，aapt2 会把它吞掉，写成 \\\" ：{text[:60]!r}"
        )
    if "'" in bare:
        errors.append(f"[引号] {lang}/{label} 有未转义的单引号，aapt2 会编译失败：{text[:60]!r}")


def check_pair(label: str, key: str, en_text: str, zh_text: str, errors: list[str]) -> None:
    """两种语言的同一条文案。[label] 进报错信息，[key] 用来查白名单（plurals 的档位共用一个 key）。"""
    # ② 占位符必须完全一致——这条对不上就是运行时崩溃
    en_indexed, en_bare = placeholders(en_text)
    zh_indexed, zh_bare = placeholders(zh_text)
    for lang, bare in (("values", en_bare), ("values-zh", zh_bare)):
        if bare:
            errors.append(
                f"[占位符] {label} 在 {lang} 里有不带编号的占位符 {bare}："
                f"翻译时语序一变就会取错参数，一律写成 %1$s / %2$d 的形式"
            )
    if en_indexed != zh_indexed:
        errors.append(
            f"[占位符] {label} 两种语言不一致："
            f"values={en_indexed or '无'} vs values-zh={zh_indexed or '无'}"
            f"（运行时会抛 IllegalFormatException）"
        )

    # ③ 英文文件里不该出现中文
    if key not in CJK_IN_EN_OK and CJK.search(en_text):
        hit = "".join(sorted(set(CJK.findall(en_text))))
        errors.append(f"[漏翻] values/{label} 含中文字符：{hit}")

    # ④ 中文文件里不该原样照抄英文
    if key not in SAME_VALUE_OK and en_text == zh_text:
        body = strip_format(en_text)
        # 纯符号 / 纯占位符的文案（如 "%1$s / %2$s"）本来就没什么可翻的
        if re.search(r"[A-Za-z]", body):
            errors.append(f"[漏翻] values-zh/{label} 与英文完全相同：{en_text[:60]!r}")


def check_quantities(lang: str, name: str, items: dict[str, str], errors: list[str]) -> None:
    """⑤ quantity 档位不多不少（见 [QUANTITIES]）。"""
    expected = QUANTITIES[lang]
    for quantity in sorted(expected - set(items)):
        errors.append(
            f"[档位] {lang}/{name} 缺 quantity=\"{quantity}\"："
            f"运行时取不到会抛 Resources.NotFoundException"
        )
    for quantity in sorted(set(items) - expected):
        errors.append(
            f"[档位] {lang}/{name} 多了 quantity=\"{quantity}\"："
            f"这门语言的 CLDR 规则里用不到它，写了也永远取不到"
        )


def main() -> int:
    en, zh = load(EN), load(ZH)
    en_plurals, zh_plurals = load_plurals(EN), load_plurals(ZH)
    errors: list[str] = []

    check_keys("key", set(en), set(zh), errors)
    check_keys("plurals", set(en_plurals), set(zh_plurals), errors)

    for lang, table in (("values", en), ("values-zh", zh)):
        for key, text in sorted(table.items()):
            check_quotes(lang, key, text, errors)

    for lang, table in (("values", en_plurals), ("values-zh", zh_plurals)):
        for name, items in sorted(table.items()):
            check_quantities(lang, name, items, errors)
            for quantity, text in sorted(items.items()):
                check_quotes(lang, f"{name}[{quantity}]", text, errors)

    for key in sorted(set(en) & set(zh)):
        check_pair(key, key, en[key], zh[key], errors)

    for name in sorted(set(en_plurals) & set(zh_plurals)):
        en_items, zh_items = en_plurals[name], zh_plurals[name]
        # 中文只有 other，所以逐档对比只在两边都有的档位上做；英文自己的各档之间
        # 再横向比一次占位符——one 少了 %1$d 是最容易漏的那种错，法语里 0 也走 one。
        for quantity in sorted(set(en_items) & set(zh_items)):
            check_pair(f"{name}[{quantity}]", name, en_items[quantity], zh_items[quantity], errors)
        reference, _ = placeholders(en_items.get("other", ""))
        for quantity, text in sorted(en_items.items()):
            if placeholders(text)[0] != reference:
                errors.append(
                    f"[占位符] values/{name}[{quantity}] 与同一条的 other 档不一致："
                    f"各档必须收下同样的参数，否则某个数量下参数就丢了"
                )

    print(
        f"check_strings: values {len(en)} 条 + {len(en_plurals)} 组复数 · "
        f"values-zh {len(zh)} 条 + {len(zh_plurals)} 组复数"
    )
    if errors:
        print(f"\n发现 {len(errors)} 个问题：\n", file=sys.stderr)
        for err in errors:
            print(f"  ✗ {err}", file=sys.stderr)
        return 1
    print("check_strings: 通过（key 一致、占位符一致、档位齐全、无漏翻）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
