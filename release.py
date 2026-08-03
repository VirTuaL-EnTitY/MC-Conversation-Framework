#!/usr/bin/env python3
"""
一键发布脚本：从 gradle.properties 读取当前 mod_version，打对应的 v*.*.* tag
并推送到远程，触发 GitHub Actions 自动创建源码 Release。

0.16.4 起 GitHub Release 只包含源码（GitHub 自动生成的 zip/tar）+ 从 README
更新日志提取的 Release Notes，**不含编译好的 jar**——jar 的发布渠道统一收敛
到 Modrinth。workflow 仍然会跑编译验证（确认代码能编译通过），但不再把 jar
上传到 Release。

为什么用 Python 而不是 shell 脚本：
- 跨平台：Windows / macOS / Linux 都能直接跑，不需要担心 PowerShell vs Bash
  语法差异、字符编码（版本号是纯 ASCII 但 shell 处理空格/特殊字符总是有坑）。
- 错误处理更清晰：能给出明确的失败原因（tag 已存在 / 未提交修改 / 远程
  拒绝等），而不是 shell 那种"非零退出码 + 一堆混在一起的 stderr"。
- 已经有 .github/scripts/extract_changelog.py 这个 Python 先例，工具链一致。

用法：
    python release.py              # 用 gradle.properties 里的当前 mod_version
    python release.py --check      # 只打印将要执行的命令，不实际执行（dry-run）

前置条件：
1. 当前目录是项目根目录（有 gradle.properties）。
2. 工作区干净（git status 没有未提交修改）——防止"改了一半代码就发版"。
3. 当前分支已经推到远程（tag 必须基于远程已有的 commit）。
4. gradle.properties 里的 mod_version 和 README 更新日志里有对应版本号条目
   （workflow 里的 extract_changelog.py 会做这个校验，这里不重复检查）。

脚本不做的事：
- 不自动 bump 版本号——版本号由用户在开发过程中按 9.1 规则手动维护，
  发版只是把已经写好的版本号"公布"出去。自动 bump 容易跳号或和 README
  更新日志脱节。
- 不自动 commit——理由同上，发版应该是"确认这版可以发了"的显式动作。
- 不直接创建 Release——交给 GitHub Actions workflow 处理，保持单一职责。
- 不上传 jar 到 Modrinth——Modrinth 目前是手动上传，脚本只负责触发 GitHub
  Release workflow，Modrinth 的 jar 上传需要你手动操作。
"""
import argparse
import re
import subprocess
import sys


def run(cmd: list[str], *, check: bool = True, capture: bool = False) -> str:
    """运行命令，check=True 时失败抛异常，capture=True 时返回 stdout。"""
    print(f"$ {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and result.returncode != 0:
        if capture:
            print(result.stderr, file=sys.stderr)
        raise SystemExit(f"命令失败（退出码 {result.returncode}）：{' '.join(cmd)}")
    return result.stdout.strip() if capture else ""


def main() -> None:
    parser = argparse.ArgumentParser(description="一键打 tag 并推送，触发 GitHub Release workflow。")
    parser.add_argument("--check", action="store_true", help="只打印将执行的命令，不实际执行（dry-run）")
    args = parser.parse_args()

    # 1. 读 gradle.properties 里的 mod_version
    try:
        with open("gradle.properties", "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        raise SystemExit("错误：当前目录下找不到 gradle.properties，请在项目根目录运行此脚本。")

    match = re.search(r"^mod_version=(.+)$", content, re.MULTILINE)
    if not match:
        raise SystemExit("错误：gradle.properties 里找不到 mod_version 字段。")
    version = match.group(1).strip()
    tag = f"v{version}"
    print(f"将发布版本：{version}（tag: {tag}）\n")

    # 2. 检查工作区是否干净
    status = run(["git", "status", "--porcelain"], capture=True)
    if status:
        print("警告：工作区有未提交的修改：")
        print(status)
        raise SystemExit("错误：请先 commit 或 stash 未提交的修改再发版。")
    print("工作区干净。")

    # 3. 检查 tag 是否已存在
    existing_tags = run(["git", "tag", "-l", tag], capture=True)
    if existing_tags:
        raise SystemExit(f"错误：tag {tag} 已存在。如果是要重新发布，请先手动删除：git tag -d {tag} && git push origin :refs/tags/{tag}")

    # 4. 检查当前分支是否已推到远程
    branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"], capture=True)
    if branch == "HEAD":
        raise SystemExit("错误：当前处于 detached HEAD 状态，请切到一个分支再发版。")
    remote_refs = run(["git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"], capture=True, check=False)
    if not remote_refs:
        raise SystemExit(f"错误：当前分支 {branch} 没有设置上游分支，请先 git push -u origin {branch}。")

    # 5. 打 tag + push
    print()
    if args.check:
        print("[dry-run] 以下命令将执行：")
        print(f"  git tag {tag}")
        print(f"  git push origin {tag}")
        print("\n实际执行请去掉 --check 参数。")
        return

    run(["git", "tag", tag])
    run(["git", "push", "origin", tag])
    print(f"\n完成！tag {tag} 已推送，GitHub Actions 正在构建。")
    print("去仓库的 Actions 页面查看进度，Release 会在构建完成后自动创建。")


if __name__ == "__main__":
    main()
