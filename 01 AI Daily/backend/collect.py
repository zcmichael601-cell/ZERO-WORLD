#!/usr/bin/env python3
"""
AI Daily 采集主脚本
用法：python collect.py [--platform podcast|youtube|x|all]
"""

import os
import sys
import json
import argparse
from datetime import datetime, timezone
from pathlib import Path
from dotenv import load_dotenv

# 加载 .env
env_path = Path(__file__).parent / ".env"
load_dotenv(env_path)

HOURS = int(os.environ.get("HOURS_LOOKBACK", "24"))
MAX_ITEMS = int(os.environ.get("MAX_ITEMS_PER_PLATFORM", "10"))

# 输出路径
DATA_DIR = Path(__file__).parent.parent / "data"
DATA_DIR.mkdir(exist_ok=True)
OUTPUT_JS   = DATA_DIR / "live-data.js"
LINKS_MD    = DATA_DIR / "original-links.md"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", default="all",
                        choices=["all", "podcast", "youtube", "x"])
    args = parser.parse_args()

    all_items = []

    if args.platform in ("all", "podcast"):
        print("\n📻 采集 Podcast...")
        from collectors.podcast import collect as collect_podcast
        items = collect_podcast(HOURS, MAX_ITEMS)
        print(f"   ✓ 获取 {len(items)} 条")
        all_items.extend(items)

    if args.platform in ("all", "youtube"):
        print("\n▶ 采集 YouTube...")
        from collectors.youtube import collect as collect_youtube
        items = collect_youtube(HOURS, MAX_ITEMS)
        print(f"   ✓ 获取 {len(items)} 条")
        all_items.extend(items)

    if args.platform in ("all", "x"):
        print("\n𝕏 采集 X...")
        from collectors.x_twitter import collect as collect_x
        items = collect_x(HOURS, MAX_ITEMS)
        print(f"   ✓ 获取 {len(items)} 条")
        all_items.extend(items)

    if not all_items:
        print("\n⚠ 未采集到任何内容，退出")
        sys.exit(0)

    # 按时间倒序
    all_items.sort(key=lambda x: x.get("datetime", ""), reverse=True)

    # 写 live-data.js（前端直接加载）
    write_js(all_items)

    # 写 original-links.md
    write_links_md(all_items)

    print(f"\n✅ 完成！共 {len(all_items)} 条内容")
    print(f"   → {OUTPUT_JS}")
    print(f"   → {LINKS_MD}")


def write_js(items: list):
    json_str = json.dumps(items, ensure_ascii=False, indent=2)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    content = f"// AI Daily — 自动生成，请勿手动编辑 — {ts}\n"
    content += f"const AI_DIGEST_DATA_LIVE = {json_str};\n"
    OUTPUT_JS.write_text(content, encoding="utf-8")


def write_links_md(items: list):
    today = datetime.now().strftime("%Y-%m-%d")
    lines = [f"# AI 资讯原文链接 — {today}\n"]

    for platform in ("youtube", "podcast", "x"):
        platform_items = [i for i in items if i["platform"] == platform]
        if not platform_items:
            continue
        labels = {"youtube": "YouTube", "podcast": "Podcast", "x": "X"}
        lines.append(f"\n## {labels[platform]}\n")
        for item in platform_items:
            lines.append(f"- [{item['title']}]({item['original_url']})")

    LINKS_MD.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
