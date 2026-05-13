"""
YouTube 采集器 — 基于 YouTube Data API v3 + youtube-transcript-api
需要 YOUTUBE_API_KEY
"""

import os
import uuid
from datetime import datetime, timezone, timedelta
from googleapiclient.discovery import build
from processors.summarizer import summarize


def collect(hours_lookback: int = 24, max_items: int = 10) -> list[dict]:
    from config import YOUTUBE_CHANNELS, YOUTUBE_SEARCH_QUERIES

    api_key = os.environ.get("YOUTUBE_API_KEY", "")
    if not api_key:
        print("  [youtube] 跳过：未配置 YOUTUBE_API_KEY")
        return []

    youtube = build("youtube", "v3", developerKey=api_key)
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours_lookback)
    published_after = cutoff.strftime("%Y-%m-%dT%H:%M:%SZ")

    results = []
    seen_ids = set()

    # 1. 订阅频道的最新视频
    for ch in YOUTUBE_CHANNELS:
        if len(results) >= max_items:
            break
        print(f"  [youtube] 频道: {ch['name']}")
        try:
            resp = youtube.search().list(
                channelId=ch["id"],
                type="video",
                part="id,snippet",
                order="date",
                publishedAfter=published_after,
                maxResults=5,
            ).execute()
            for item in resp.get("items", []):
                vid = item["id"]["videoId"]
                if vid in seen_ids:
                    continue
                seen_ids.add(vid)
                entry = _build_entry(youtube, vid, item["snippet"])
                if entry:
                    results.append(entry)
                if len(results) >= max_items:
                    break
        except Exception as e:
            print(f"    失败: {e}")

    # 2. 关键词搜索补充
    for query in YOUTUBE_SEARCH_QUERIES:
        if len(results) >= max_items:
            break
        print(f"  [youtube] 搜索: {query}")
        try:
            resp = youtube.search().list(
                q=query,
                type="video",
                part="id,snippet",
                order="date",
                publishedAfter=published_after,
                maxResults=5,
                relevanceLanguage="en",
            ).execute()
            for item in resp.get("items", []):
                vid = item["id"]["videoId"]
                if vid in seen_ids:
                    continue
                seen_ids.add(vid)
                entry = _build_entry(youtube, vid, item["snippet"])
                if entry:
                    results.append(entry)
                if len(results) >= max_items:
                    break
        except Exception as e:
            print(f"    失败: {e}")

    return results


def _build_entry(youtube_client, video_id: str, snippet: dict) -> dict | None:
    title = snippet.get("title", "").strip()
    if not title:
        return None

    url = f"https://www.youtube.com/watch?v={video_id}"
    pub_str = snippet.get("publishedAt", "")
    try:
        pub = datetime.fromisoformat(pub_str.replace("Z", "+00:00"))
    except Exception:
        pub = datetime.now(timezone.utc)

    # 优先用字幕，fallback 用描述
    raw = _get_transcript(video_id) or snippet.get("description", title)

    print(f"    → {title[:60]}...")
    summary_data = summarize(title, raw, "YouTube")

    return {
        "id": f"yt-{uuid.uuid4().hex[:8]}",
        "platform": "youtube",
        "datetime": pub.isoformat(),
        "title": title,
        "tags": summary_data["tags"],
        "summary": summary_data["summary"],
        "core_content": summary_data["core_content"],
        "url": url,
        "original_url": url,
        "source_name": snippet.get("channelTitle", "YouTube"),
    }


def _get_transcript(video_id: str) -> str:
    try:
        from youtube_transcript_api import YouTubeTranscriptApi
        transcript = YouTubeTranscriptApi.get_transcript(
            video_id, languages=["en", "zh-Hans", "zh-Hant"]
        )
        # 取前 3000 字符（约 10 分钟内容）
        full = " ".join(t["text"] for t in transcript)
        return full[:3000]
    except Exception:
        return ""
