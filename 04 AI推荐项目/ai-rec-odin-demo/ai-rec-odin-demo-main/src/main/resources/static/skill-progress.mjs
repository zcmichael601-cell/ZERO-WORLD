import { readEvents } from './chat-stream.js?v=20261103-parallel-recall';
import { escapeHtml as escape } from './skill-utils.mjs?v=20261103-parallel-recall';

const progressTypes = new Set(['RUN_STARTED', 'MODEL_STARTED', 'MODEL_WAITING', 'MODEL_RETRYING', 'MODEL_COMPLETED', 'MODEL_FAILED', 'TOOL_STARTED',
  'TOOL_COMPLETED', 'TOOL_FAILED', 'TOOLS_RETURNED', 'VALIDATION_STARTED', 'RUN_COMPLETED', 'RUN_FAILED']);

function failure(message, code) {
  return Object.assign(new Error(message), { code });
}

function newSessionId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export async function readSkillProgress(response, onProgress = () => {}, onContent = () => {}) {
  const diagnostics = { requestId: response.headers.get('X-Request-Id'), runId: null, modelRound: null,
    lastEvent: null, progressSequence: 0, contentSequence: 0 };
  try {
    return await consumeSkillProgress(response, onProgress, onContent, diagnostics);
  } catch (error) {
    const problem = error instanceof Error ? error : failure('执行进度流异常。', 'STREAM_ERROR');
    problem.diagnostics = { ...diagnostics, code: typeof problem.code === 'string' ? problem.code : (problem.name === 'TimeoutError' ? 'CLIENT_TIMEOUT'
      : problem.name === 'AbortError' ? 'CLIENT_ABORT' : 'STREAM_READ_ERROR') };
    throw problem;
  }
}

async function consumeSkillProgress(response, onProgress, onContent, diagnostics) {
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`;
    try { message = (await response.json()).msg || message; } catch {}
    throw Object.assign(failure(message, 'HTTP_ERROR'), { httpStatus: response.status });
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream')) {
    throw failure('服务未返回执行进度流，请确认已重启更新后的后端。', 'CONTENT_TYPE_ERROR');
  }
  let run;
  let runId;
  let sequence = 0;
  let contentSequence = 0;
  let activeRound;
  let completedRound;
  let validationRound;
  let skillId;
  await readEvents(response, (type, data) => {
    let payload;
    try { payload = JSON.parse(data); } catch { throw failure('执行进度数据格式无效，尚未确认执行完成。', 'INVALID_JSON'); }
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      throw failure('执行进度数据格式无效，尚未确认执行完成。', 'INVALID_JSON');
    }
    if (type === 'progress') {
      if (!payload.runId || (runId && runId !== payload.runId) || !Number.isInteger(payload.sequence)
          || payload.sequence !== sequence + 1 || !progressTypes.has(payload.type)
          || !['RUNNING', 'SUCCESS', 'FAILED'].includes(payload.status) || typeof payload.message !== 'string') {
        throw failure('执行进度顺序或标识无效，尚未确认执行完成。', 'PROGRESS_PROTOCOL_ERROR');
      }
      runId = payload.runId;
      skillId = payload.skillId;
      sequence = payload.sequence;
      if (payload.type === 'MODEL_STARTED') {
        if (!Number.isInteger(payload.modelRound) || payload.modelRound < 1 || payload.status !== 'RUNNING') {
          throw failure('模型开始事件的轮次或状态无效。', 'ROUND_MISMATCH');
        }
        activeRound = payload.modelRound;
        completedRound = undefined;
        validationRound = undefined;
      }
      if (payload.type === 'MODEL_WAITING' && (!Number.isInteger(payload.modelRound) || payload.modelRound < 1
          || payload.modelRound !== activeRound || payload.status !== 'RUNNING')) {
        throw failure('模型等待状态与当前轮次不匹配。', 'ROUND_MISMATCH');
      }
      if (payload.type === 'MODEL_RETRYING' && (payload.status !== 'RUNNING'
          || !Number.isInteger(payload.modelRound) || payload.modelRound < 1
          || (payload.modelRound !== activeRound && payload.modelRound !== validationRound))) {
        throw failure('模型重试状态与当前生成或校验轮次不匹配。', 'ROUND_MISMATCH');
      }
      if (payload.type === 'MODEL_COMPLETED') {
        completedRound = payload.status === 'SUCCESS' && payload.modelRound === activeRound ? activeRound : undefined;
        activeRound = undefined;
      }
      if (payload.type === 'VALIDATION_STARTED') {
        validationRound = payload.status === 'RUNNING' && payload.modelRound === completedRound ? completedRound : undefined;
      }
      if (['MODEL_FAILED', 'RUN_FAILED', 'RUN_COMPLETED'].includes(payload.type)) {
        activeRound = undefined;
        completedRound = undefined;
        validationRound = undefined;
      }
      Object.assign(diagnostics, { runId, modelRound: payload.modelRound, lastEvent: payload.type, progressSequence: sequence });
      try { onProgress(payload); } catch (error) {
        if (error?.code === 'SESSION_MISMATCH') throw error;
        throw failure('页面更新失败，请刷新页面后重试。', 'CLIENT_RENDER_ERROR');
      }
    } else if (type === 'content') {
      if (!runId || payload.runId !== runId || payload.skillId !== skillId
          || !Number.isInteger(payload.sequence) || payload.sequence !== contentSequence + 1
          || !Number.isInteger(payload.modelRound) || payload.modelRound !== activeRound
          || typeof payload.text !== 'string' || !payload.text.length) {
        throw failure('模型内容流顺序或标识无效，尚未确认执行完成。', 'CONTENT_PROTOCOL_ERROR');
      }
      contentSequence = payload.sequence;
      diagnostics.contentSequence = contentSequence;
      try { onContent(payload); } catch { throw failure('页面更新失败，请刷新页面后重试。', 'CLIENT_RENDER_ERROR'); }
    } else if (type === 'result') {
      run = payload.data;
      if (!runId || run?.runId !== runId || !['SUCCESS', 'FAILED'].includes(run.status)) {
        throw failure('最终结果与执行进度不匹配。', 'RESULT_MISMATCH');
      }
      if (payload.code !== 0 || run.status !== 'SUCCESS') {
        const error = failure(payload.msg || run.error || 'Skill 执行失败', 'RUN_FAILED');
        error.run = run;
        throw error;
      }
      return true;
    } else if (type === 'error') {
      throw failure(payload.message || '执行进度流异常，未收到完整结果。', 'SERVER_STREAM_ERROR');
    }
    return false;
  });
  return run;
}

export function skillFailureMessage(error) {
  const message = error.name === 'TimeoutError' ? '等待结果超时，未确认执行完成；连接已关闭，请检查后重试。'
    : `执行失败或连接中断：${error.message}`;
  const detail = error.diagnostics;
  if (!detail) return message;
  const location = [detail.code, detail.modelRound ? `第 ${detail.modelRound} 轮` : null,
    detail.lastEvent ? `最后进度 ${detail.lastEvent}` : null, detail.runId ? `RUN ${detail.runId}` : null,
    detail.requestId ? `REQUEST ${detail.requestId}` : null, detail.sessionId ? `SESSION ${detail.sessionId}` : null].filter(Boolean).join(' · ');
  return `${message}（${location}）`;
}

export async function requestSkillProgress(url, input, onProgress = () => {}, onContent = () => {}, options = {}) {
  const { maxAttempts = 3, retryDelayMs = 1000, onReconnect = () => {}, timeoutMs = 1290000 } = options;
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 3690000) {
    throw new Error('会话等待时限需为 1000~3690000 毫秒。');
  }
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 3 || !Number.isFinite(retryDelayMs) || retryDelayMs < 0 || retryDelayMs > 5000) {
    throw new Error('连接尝试次数需为1~3，初始等待需为0~5000毫秒。');
  }
  const sessionId = newSessionId();
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs);
  const sessionUrl = url.replace(/\/stream$/, '/session-stream');
  const cancelUrl = url.replace(/\/stream$/, `/sessions/${sessionId}`);
  let progressSequence = 0;
  let contentSequence = 0;
  let runId;
  try {
    for (let attempt = 1; ; attempt++) {
      signal.throwIfAborted();
      let response;
      try {
        const connection = new AbortController();
        const connectTimer = setTimeout(() => connection.abort(new DOMException('连接建立超时', 'TimeoutError')), 5000);
        try {
          response = await fetch(`${sessionUrl}?sessionId=${sessionId}&resume=${attempt > 1}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
            body: JSON.stringify({ input }), signal: AbortSignal.any([signal, connection.signal])
          });
        } finally { clearTimeout(connectTimer); }
        return await readSkillProgress(response, event => {
          if (runId && runId !== event.runId) throw failure('重连返回了其他运行，已停止而非重新执行。', 'SESSION_MISMATCH');
          runId = event.runId;
          if (event.sequence > progressSequence) {
            progressSequence = event.sequence;
            onProgress(event);
          }
        }, delta => {
          if (delta.sequence > contentSequence) {
            contentSequence = delta.sequence;
            onContent(delta);
          }
        });
      } catch (error) {
        const code = error.diagnostics?.code;
        const retryable = !response || code === 'STREAM_READ_ERROR'
          || (code === 'HTTP_ERROR' && [408, 429, 500, 502, 503, 504].includes(error.httpStatus));
        error.diagnostics = { ...error.diagnostics, sessionId, connectionAttempts: attempt, runId: error.diagnostics?.runId || runId };
        if (!retryable || signal.aborted || attempt >= maxAttempts) throw error;
        const delay = retryDelayMs * (2 ** (attempt - 1));
        onReconnect({ attempt: attempt + 1, maxAttempts, delayMs: delay, sessionId });
        await new Promise((resolve, reject) => {
          const abort = () => { clearTimeout(timer); reject(signal.reason); };
          const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, delay);
          signal.addEventListener('abort', abort, { once: true });
          if (signal.aborted) abort();
        });
      }
    }
  } catch (error) {
    fetch(cancelUrl, { method: 'DELETE', keepalive: true, signal: AbortSignal.timeout(5000) }).catch(() => {});
    const problem = error instanceof Error ? error : new Error('会话重连异常，已停止。');
    problem.diagnostics ??= { sessionId, runId, code: problem.name === 'AbortError' ? 'CLIENT_ABORT'
      : problem.name === 'TimeoutError' ? 'CLIENT_TIMEOUT' : 'CLIENT_ERROR' };
    throw problem;
  }
}

export function progressWorkflow(events, interrupted = false) {
  return events.filter(event => ['TOOL_STARTED', 'TOOL_COMPLETED', 'TOOL_FAILED'].includes(event.type))
    .map(event => ({ id: event.toolCallId, tool: event.tool,
      status: interrupted && event.status === 'RUNNING' ? 'UNKNOWN' : event.status, modelRound: event.modelRound }));
}

export function progressMarkup(events) {
  return events.map(event => {
    const status = event.status === 'FAILED' ? 'failed' : event.status === 'SUCCESS' ? 'success' : 'running';
    const elapsed = Number.isFinite(event.elapsedMs) ? `${(event.elapsedMs / 1000).toFixed(1)} s` : '—';
    return `<li class="progress-event ${status}"><span class="progress-icon" aria-hidden="true">${status === 'success' ? '✓' : status === 'failed' ? '!' : '→'}</span><span>${escape(event.message)}<small>${escape(elapsed)}${event.modelRound ? ` · 第 ${escape(event.modelRound)} 轮` : ''}</small></span></li>`;
  }).join('');
}
