"""TTL-based LRU cache — V4-02: cache hot GLM ranking results."""
import hashlib
import json
import threading
import time
from collections import OrderedDict
from typing import Any, Optional


class TTLCache:
    def __init__(self, maxsize: int = 64, ttl: float = 300.0):
        self._cache: OrderedDict[str, tuple[Any, float]] = OrderedDict()
        self._maxsize = maxsize
        self._ttl = ttl
        self._lock = threading.Lock()
        self._hits = 0
        self._misses = 0

    def make_key(self, *args, **kwargs) -> str:
        raw = json.dumps({"a": args, "k": kwargs}, ensure_ascii=False, sort_keys=True)
        return hashlib.md5(raw.encode()).hexdigest()

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            if key not in self._cache:
                self._misses += 1
                return None
            value, expires_at = self._cache[key]
            if time.monotonic() > expires_at:
                del self._cache[key]
                self._misses += 1
                return None
            self._cache.move_to_end(key)
            self._hits += 1
            return value

    def set(self, key: str, value: Any) -> None:
        with self._lock:
            self._cache[key] = (value, time.monotonic() + self._ttl)
            self._cache.move_to_end(key)
            while len(self._cache) > self._maxsize:
                self._cache.popitem(last=False)

    def stats(self) -> dict:
        now = time.monotonic()
        with self._lock:
            valid = sum(1 for _, (_, exp) in self._cache.items() if exp > now)
            total = self._hits + self._misses
            return {
                "entries": len(self._cache),
                "valid": valid,
                "maxsize": self._maxsize,
                "ttl_s": self._ttl,
                "hits": self._hits,
                "misses": self._misses,
                "hit_rate": round(self._hits / max(total, 1), 3),
            }
