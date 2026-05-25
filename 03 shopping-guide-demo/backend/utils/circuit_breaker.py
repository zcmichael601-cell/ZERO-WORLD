import time
from enum import Enum


class _State(Enum):
    CLOSED    = "closed"
    OPEN      = "open"
    HALF_OPEN = "half_open"


class CircuitBreaker:
    """
    三状态熔断器。
    CLOSED → 正常，每次失败计数 +1
    OPEN   → 已熔断，拒绝请求；到 recovery_timeout 后切 HALF_OPEN
    HALF_OPEN → 试探，成功则 CLOSED，失败则回 OPEN
    """

    def __init__(self, failure_threshold: int = 3, recovery_timeout: int = 30):
        self.failure_threshold = failure_threshold
        self.recovery_timeout  = recovery_timeout
        self._state            = _State.CLOSED
        self._failure_count    = 0
        self._last_failure_ts  = 0.0

    @property
    def is_open(self) -> bool:
        if self._state == _State.OPEN:
            if time.monotonic() - self._last_failure_ts >= self.recovery_timeout:
                self._state = _State.HALF_OPEN
                return False
            return True
        return False

    @property
    def state(self) -> str:
        return self._state.value

    def record_success(self) -> None:
        self._state         = _State.CLOSED
        self._failure_count = 0

    def record_failure(self) -> None:
        self._failure_count  += 1
        self._last_failure_ts = time.monotonic()
        if self._failure_count >= self.failure_threshold:
            self._state = _State.OPEN
