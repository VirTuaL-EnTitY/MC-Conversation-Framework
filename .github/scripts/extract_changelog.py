#!/usr/bin/env python3
"""
从 README.md 的"八、更新日志"章节里，提取指定版本号对应的那一段，
供 GitHub Actions 在打 tag 发布 Release 时自动填充 Release Notes 用。

为什么单独写成脚本而不是在 workflow yml 里用一行 awk/sed 解决：
更新日志的标题格式是 `### YYYY-MM-DD　<版本号> <中文描述>`（日期和版本号之间是
全角空格，不是普通空格），用 shell 内联处理这种格式容易因为终端/shell 的
locale、全角字符编码问题出错，而且找不到匹配版本时需要给出清晰的失败信息
（而不是让 workflow 静默生成一个空的 Release Notes），用 Python 写更可控、
更容易本地调试。

用法：
    python3 extract_changelog.py <version> [readme_path]

    <version>     纯版本号，不带 v 前缀，例如 "0.8.0"
    [readme_path] 默认 README.md（相对于运行目录）

行为：
- 找到第一个标题行匹配 `### ... <version> ...` 的位置（版本号必须是完整的
  数字段匹配，不能是子串——比如查询 "0.7.0" 不能误匹配到 "0.7.0" 中间某个
  数字组合看起来像但其实是别的版本，用 \b 边界保证精确匹配）。
- 截取从该标题到下一个 `### ` 开头的标题行（或文件末尾）之间的内容。
- 去掉开头的标题行本身（Release 页面已经显示了 tag 名，不需要重复），
  保留其余描述性内容原样输出到 stdout。
- 找不到对应版本时，退出码非 0 并把错误信息打印到 stderr，让 workflow 步骤
  显式失败，而不是生成一个看起来"成功但内容为空"的 Release。
"""
import re
import sys


def extract(version: str, readme_path: str) -> str:
    with open(readme_path, encoding="utf-8") as f:
        content = f.read()

    # 标题行格式：### 2026-07-30　0.7.0 修复：...
    # 用 \b 保证版本号是完整匹配（避免 "0.7.0" 误匹配到假设存在的 "0.7.0-beta" 之类），
    # 版本号前后允许任意非换行字符（日期、全角空格、描述文字）。
    heading_pattern = re.compile(
        r"^### .*?\b" + re.escape(version) + r"\b.*$",
        re.MULTILINE,
    )

    match = heading_pattern.search(content)
    if not match:
        print(
            f"error: 在 README.md 的更新日志里没有找到版本 {version} 对应的条目。\n"
            f"请确认 README.md 的\"八、更新日志\"章节里已经写好这个版本号的记录，"
            f"格式形如：### 2026-07-30　{version} <描述>",
            file=sys.stderr,
        )
        sys.exit(1)

    start = match.end()
    # 找下一个 "### " 开头的标题行（下一条更新日志），作为本段结尾。
    next_heading = re.search(r"^### ", content[start:], re.MULTILINE)
    end = start + next_heading.start() if next_heading else len(content)

    body = content[start:end].strip()
    if not body:
        print(
            f"error: 找到了版本 {version} 的标题行，但正文内容为空，"
            f"这看起来不太对，请检查 README.md。",
            file=sys.stderr,
        )
        sys.exit(1)

    return body


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: extract_changelog.py <version> [readme_path]", file=sys.stderr)
        sys.exit(2)

    version_arg = sys.argv[1].lstrip("vV")  # 兼容传入 "v0.8.0" 或 "0.8.0"
    readme_arg = sys.argv[2] if len(sys.argv) > 2 else "README.md"

    print(extract(version_arg, readme_arg))
