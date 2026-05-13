"""
X / Twitter 采集器
方案 A（默认）：Nitter RSS — 免费，无需 API Key，但实例可能不稳定
方案 B（可选）：官方 Twitter API v2 — 稳定，但免费层每月只能读 500 条
"""

import os
import uuid
import requests
import feedparser
from datetime import datetime, timezone, timedelta
from processors.summarizer import summarize
from config import AI_KEYWORDS


def collect(hours_lookback: int = 24, max_items: int = 10) -> list[dict]:
    from config import X_ACCOUNTS

    # 优先尝试官方 API
    bearer = os.environ.get("X_BEARER_TOKEN", "")
    if bearer:
        return _collect_official(bearer, X_ACCOUNTS, hours_lookback, max_items)
    else:
        return _collect_nitter(X_ACCOUNTS, hours_lookback, max_items)


# ── 方案 A：Nitter RSS ──────────────────────────────────────

def _collect_nitter(accounts: list, hours_lookback: int, max_items: int) -> list[dict]:
    from config import NITTER_INSTANCES

    base = _pick_nitter_instance(NITTER_INSTANCES)
    if not base:
        print("  [x] 所有 Nitter 实例不可用，跳过")
        return []

    print(f"  [x] 使用 Nitter: {base}")
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours_lookback)
    results = []

    for account in accounts:
        if len(results) >= max_items:
            break
        try:
            rss_url = f"{base}/{account}/rss"
            feed = feedparser.parse(rss_url)
            for entry in feed.entries[:20]:
                pub = _parse_nitter_date(entry)
                if pub and pub < cutoff:
                    continue

                text = _clean_tweet(entry.get("summary", entry.get("title", "")))
                if not text or not _is_ai_related(text):
                    continue
                if len(text) < 30:
                    continue

                url = entry.get("link", f"https://x.com/{account}")
                print(f"    → @{account}: {text[:60]}...")
                summary_data = summarize(text[:200], text, "X")

                results.append({
                    "id": f"x-{uuid.uuid4().hex[:8]}",
                    "platform": "x",
                    "datetime": pub.isoformat() if pub else datetime.now(timezone.utc).isoformat(),
                    "title": _make_title(text, account),
                    "tags": summary_data["tags"],
                    "summary": summary_data["summary"],
                    "core_content": summary_data["core_content"],
                    "url": url,
                    "original_url": url,
                    "source_name": f"@{account}",
                })

                if len(results) >= max_items:
                    break
        except Exception as e:
            print(f"    @{account} 失败: {e}")

    return results


def _pick_nitter_instance(instances: list) -> str:
    for inst in instances:
        try:
            r = requests.get(f"{inst}/robots.txt", timeout=5)
            if r.status_code < 400:
                return inst
        except Exception:
            continue
    return ""


def _parse_nitter_date(entry) -> datetime | None:
    for field in ("published_parsed", "updated_parsed"):
        t = entry.get(field)
        if t:
            try:
                return datetime(*t[:6], tzinfo=timezone.utc)
            except Exception:
                pass
    return None


def _clean_tweet(html: str) -> str:
    import re
    text = re.sub(r"<[^>]+>", " ", html)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _is_ai_related(text: str) -> bool:
    t = text.lower()
    return any(kw.lower() in t for kw in AI_KEYWORDS)


def _make_title(text: str, account: str) -> str:
    first_sentence = text.split("。")[0].split(". ")[0].split("\n")[0]
    title = first_sentence[:80].strip()
    if len(first_sentence) > 80:
        title += "…"
    return f"@{account}: {title}"


# ── 方案 B：官方 Twitter API v2 ─────────────────────────────

def _collect_official(bearer: str, accounts: list, hours_lookback: int, max_items: int) -> list[dict]:
    from config import AI_KEYWORDS

    print("  [x] 使用官方 Twitter API v2")
    headers = {"Authorization": f"Bearer {bearer}"}
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours_lookback)
    start_time = cutoff.strftime("%Y-%m-%dT%H:%M:%SZ")
    results = []

    for account in accounts:
        if len(results) >= max_items:
            break
        try:
            # 先查 user_id
            r = requests.get(
                f"https://api.twitter.com/2/users/by/username/{account}",
                headers=headers, timeout=10
            )
            if r.status_code != 200:
                continue
            user_id = r.json()["data"]["id"]

            # 拉最新推文
            r2 = requests.get(
                f"https://api.twitter.com/2/users/{user_id}/tweets",
                headers=headers,
                params={
                    "max_results": 10,
                    "start_time": start_time,
                    "tweet.fields": "created_at,text",
                    "exclude": "retweets,replies",
                },
                timeout=10
            )
            if r2.status_code != 200:
                continue

            for tweet in r2.json().get("data", []):
                text = tweet.get("text", "")
                if not _is_ai_related(text) or len(text) < 30:
                    continue

                pub_str = tweet.get("created_at", "")
                try:
                    pub = datetime.fromisoformat(pub_str.replace("Z", "+00:00"))
                except Exception:
                    pub = datetime.now(timezone.utc)

                tweet_id = tweet["id"]
                url = f"https://x.com/{account}/status/{tweet_id}"
                print(f"    → @{account}: {text[:60]}...")
                summary_data = summarize(text[:200], text, "X")

                results.append({
                    "id": f"x-{uuid.uuid4().hex[:8]}",
                    "platform": "x",
                    "datetime": pub.isoformat(),
                    "title": _make_title(text, account),
                    "tags": summary_data["tags"],
                    "summary": summary_data["summary"],
                    "core_content": summary_data["core_content"],
                    "url": url,
                    "original_url": url,
                    "source_name": f"@{account}",
                })

                if len(results) >= max_items:
                    break
        except Exception as e:
            print(f"    @{account} 失败: {e}")

    return results
