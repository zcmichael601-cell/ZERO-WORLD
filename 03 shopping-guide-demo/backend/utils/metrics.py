"""In-memory request metrics — exposed via /metrics endpoint."""
import threading
import time
from collections import defaultdict, deque
from typing import Dict


class _Collector:
    def __init__(self, window: int = 500):
        self._lock = threading.Lock()
        self._total = 0
        self._errors = 0
        self._pipeline: Dict[str, int] = defaultdict(int)
        self._intent: Dict[str, int] = defaultdict(int)
        self._lats: deque = deque(maxlen=window)
        self._started_at = time.time()

    def record(self, pipeline: str, intent_type: str,
               latency_ms: int, is_error: bool = False) -> None:
        with self._lock:
            self._total += 1
            if is_error:
                self._errors += 1
            self._pipeline[pipeline] += 1
            self._intent[intent_type] += 1
            self._lats.append(latency_ms)

    def snapshot(self) -> dict:
        with self._lock:
            lats = sorted(self._lats)
            n = len(lats)

            def pct(p: float) -> int:
                if not lats:
                    return 0
                return lats[min(int(n * p / 100), n - 1)]

            return {
                "uptime_s": round(time.time() - self._started_at),
                "total_requests": self._total,
                "error_count": self._errors,
                "error_rate": round(self._errors / max(self._total, 1), 4),
                "by_pipeline": dict(self._pipeline),
                "by_intent": dict(self._intent),
                "latency_ms": {
                    "p50": pct(50),
                    "p95": pct(95),
                    "p99": pct(99),
                    "samples": n,
                },
            }


_c = _Collector()


def record(pipeline: str, intent_type: str,
           latency_ms: int, is_error: bool = False) -> None:
    _c.record(pipeline, intent_type, latency_ms, is_error)


def snapshot() -> dict:
    return _c.snapshot()
