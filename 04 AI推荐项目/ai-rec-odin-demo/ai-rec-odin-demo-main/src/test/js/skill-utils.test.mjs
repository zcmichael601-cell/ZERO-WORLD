import test from 'node:test';
import assert from 'node:assert/strict';
import { escapeHtml, formatJson, displayValue, confidence, requestJson } from '../../main/resources/static/skill-utils.mjs';
import { readFileSync } from 'node:fs';

test('escapes user and tool content before inserting HTML', () => {
  assert.equal(escapeHtml('<img src=x onerror="alert(1)">\'&'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&#39;&amp;');
  assert.equal(escapeHtml(null), '');
  assert.ok(!formatJson({ username: '<script>' }).includes('<script>'));
});

test('display view removes internal source flags from traces and raw JSON without changing diagnostics', () => {
  const raw = { mode: 'LIVE_MODEL_MOCK_DATA', output: { profile: { username: 'xiaoming', source: 'MOCK', notice: '预置样本' } },
    traces: [{ output: { source: 'MOCK', products: [{ id: 'SKU-001', price: 399 }] } }], tool: { dataMode: 'MOCK' } };
  const view = displayValue(raw);
  assert.doesNotMatch(JSON.stringify(view), /mock|预置样本/i);
  assert.doesNotMatch(formatJson(raw), /mock|预置样本/i);
  assert.equal(view.traces[0].output.products[0].id, 'SKU-001');
  assert.equal(view.traces[0].output.products[0].price, 399);
  assert.equal(raw.output.profile.source, 'MOCK');
  assert.equal(raw.mode, 'LIVE_MODEL_MOCK_DATA');
});

test('display view preserves provider attribution and evidence references', () => {
  const value = { source: { provider: 'Open-Meteo', dataMode: 'LIVE', url: 'https://api.open-meteo.com/v1/forecast' },
    notice: '城区预报不代表山区天气', evidence: [{ source: 'get_user_profile', path: '/interests' }] };
  assert.deepEqual(displayValue(value), value);
});

test('does not rewrite user text or unrelated business fields', () => {
  const value = { interests: ['Mock interview'], summary: '当前信息不足，不能确认购买意愿', mode: 'shopping' };
  assert.deepEqual(displayValue(value), value);
});

test('page labels and accessibility text avoid internal implementation wording', () => {
  const html = readFileSync(new URL('../../main/resources/static/skills.html', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /mock|模拟商品|演示价格|预置样本/i);
  assert.ok(html.includes('id="user-preview"'));
  assert.ok(html.includes('CC BY 4.0'));
});

test('normalizes confidence values used in CSS', () => {
  assert.equal(confidence(94.4), 94);
  assert.equal(confidence(500), 100);
  assert.equal(confidence(-10), 0);
  assert.equal(confidence('10%;color:red'), 0);
  assert.equal(confidence(Infinity), 0);
});

test('unwraps successful API responses', async context => {
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: 0, data: { result: 1 } })));
  assert.deepEqual(await requestJson('/api/skills'), { result: 1 });
});

test('retains failed-run traces from HTTP errors', async context => {
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ code: 500, msg: '执行失败', data: { runId: 'run-1', traces: [] } }), { status: 500 }));
  await assert.rejects(requestJson('/run'), error => error.message === '执行失败' && error.run.runId === 'run-1');
});

test('reports non-JSON responses clearly', async context => {
  context.mock.method(globalThis, 'fetch', async () => new Response('<html>Error</html>', { status: 502 }));
  await assert.rejects(requestJson('/run'), /非 JSON.*502/);
});

test('propagates network failure and rejects business errors', async context => {
  const fetch = context.mock.method(globalThis, 'fetch', async () => { throw new TypeError('Failed to fetch'); });
  await assert.rejects(requestJson('/run'), /Failed to fetch/);
  fetch.mock.mockImplementation(async () => new Response(JSON.stringify({ code: 400, msg: '输入错误', data: null })));
  await assert.rejects(requestJson('/run'), /输入错误/);
});

test('initialization requests have bounded timeout without AbortSignal.timeout support', async context => {
  context.mock.method(AbortSignal, 'timeout', () => { throw new Error('unsupported browser API'); });
  context.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(options.cache, 'no-store');
    assert.equal(options.timeoutMs, undefined);
    return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  });
  await assert.rejects(requestJson('/api/skills', { timeoutMs: 10, cache: 'no-store' }), /请求超时/);
});

test('respects explicit cancellation and rejects null API envelopes safely', async context => {
  const controller = new AbortController();
  const fetch = context.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(options.signal, controller.signal);
    throw new Error('cancelled');
  });
  await assert.rejects(requestJson('/api/skills', { signal: controller.signal }), /cancelled/);
  fetch.mock.mockImplementation(async () => new Response('null'));
  await assert.rejects(requestJson('/api/skills'), /请求失败/);
});
