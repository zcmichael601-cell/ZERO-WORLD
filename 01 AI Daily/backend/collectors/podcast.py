"""
Podcast 采集器 — 基于 RSS Feed，无需 API Key
"""

import os
import uuid
import feedparser
from datetime import datetime, timezone, timedelta
from processors.summarizer import summarize


def collect(hours_lookback: int = 24, max_items: int = 10) -> list[dict]:
    from config import PODCAST_FEEDS

    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours_lookback)
    results = []

    for feed_cfg in PODCAST_FEEDS:
        print(f"  [podcast] 抓取: {feed_cfg['name']}")
        try:
            feed = feedparser.parse(feed_cfg["rss"])
            for entry in feed.entries[:20]:
                pub = _parse_date(entry)
                if pub and pub < cutoff:
                    continue

                title = entry.get("title", "").strip()
                if not title:
                    continue

                # 原文内容：优先拿 summary/description，越长越好
                raw = (
                    entry.get("summary", "")
                    or entry.get("description", "")
                    or ""
                ).strip()
                # 去掉 HTML 标签
                import re
                raw = re.sub(r"<[^>]+>", " ", raw).strip()

                if len(raw) < 30:
                    raw = title  # 实在没描述就用标题

                url = entry.get("link", "")
                audio_url = _get_audio_url(entry)

                print(f"    → {title[:60]}...")
                summary_data = summarize(title, raw, "Podcast")

                results.append({
                    "id": f"pod-{uuid.uuid4().hex[:8]}",
                    "platform": "podcast",
                    "datetime": pub.isoformat() if pub else datetime.now(timezone.utc).isoformat(),
                    "title": title,
                    "tags": summary_data["tags"],
                    "summary": summary_data["summary"],
                    "core_content": summary_data["core_content"],
                    "url": url,
                    "original_url": audio_url or url,
                    "source_name": feed_cfg["name"],
                })

                if len(results) >= max_items:
                    return results

        except Exception as e:
            print(f"  [podcast] {feed_cfg['name']} 失败: {e}")

    return results


def _parse_date(entry) -> datetime | None:
    for field in ("published_parsed", "updated_parsed"):
        t = entry.get(field)
        if t:
            try:
                return datetime(*t[:6], tzinfo=timezone.utc)
            except Exception:
                pass
    return None


def _get_audio_url(entry) -> str:
    for enc in entry.get("enclosures", []):
        if enc.get("type", "").startswith("audio"):
            return enc.get("href", enc.get("url", ""))
    return ""
