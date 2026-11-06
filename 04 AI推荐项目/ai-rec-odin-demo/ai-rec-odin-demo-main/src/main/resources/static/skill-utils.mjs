export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character]);
}

export function formatJson(value) {
  return escapeHtml(JSON.stringify(displayValue(value), null, 2));
}

export function displayValue(value) {
  if (Array.isArray(value)) return value.map(displayValue);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value)
    .filter(([key, entry]) => !(key === 'source' && entry === 'MOCK')
      && !(key === 'notice' && value.source === 'MOCK')
      && !(key === 'dataMode' && entry === 'MOCK')
      && !(key === 'mode' && typeof entry === 'string' && entry.startsWith('LIVE_MODEL_')))
    .map(([key, entry]) => [key, displayValue(entry)]));
}

export function confidence(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.min(100, Math.round(number))) : 0;
}

export async function requestJson(url, options = {}) {
  const { timeoutMs = 30000, ...requestOptions } = options;
  const controller = new AbortController();
  const timer = options.signal ? null : setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...requestOptions, signal: options.signal ?? controller.signal });
    let payload;
    try {
      payload = await response.json();
    } catch {
      throw new Error(`服务返回了非 JSON 响应（HTTP ${response.status}），请确认后端已启动。`);
    }
    if (!response.ok || payload?.code !== 0) {
      const error = new Error(payload?.msg || `请求失败（HTTP ${response.status}）`);
      error.run = payload?.data?.runId ? payload.data : null;
      throw error;
    }
    return payload.data;
  } catch (error) {
    if (!options.signal && controller.signal.aborted) throw new Error(`请求超时（${timeoutMs / 1000} 秒），请检查服务是否启动完成及网络连接。`);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
