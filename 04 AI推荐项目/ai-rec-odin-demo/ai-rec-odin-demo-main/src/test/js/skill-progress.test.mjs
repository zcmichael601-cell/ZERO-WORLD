import test from 'node:test';
import assert from 'node:assert/strict';
import { readSkillProgress, requestSkillProgress, progressMarkup, progressWorkflow, skillFailureMessage } from '../../main/resources/static/skill-progress.mjs';

function progress(sequence, type = 'RUN_STARTED', status = 'RUNNING', extra = {}) {
  return { runId: 'run-1', skillId: 'test', sequence, type, status, message: '正在执行真实步骤 🌟',
    elapsedMs: sequence * 100, modelRound: 1, tool: null, toolCallId: null, ...extra };
}

function frame(type, data) {
  return `event:${type}\ndata:${JSON.stringify(data)}\n\n`;
}

function response(text, chunkSize = 7) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream({ start(controller) {
    for (let offset = 0; offset < bytes.length; offset += chunkSize) controller.enqueue(bytes.slice(offset, offset + chunkSize));
    controller.close();
  } }), { headers: { 'Content-Type': 'text/event-stream' } });
}

const result = { code: 0, data: { runId: 'run-1', status: 'SUCCESS', traces: [] } };

test('protocol repair transitions to the next model round without disconnecting SSE', async () => {
  const events = [];
  const reply = response(frame('progress', progress(1))
    + frame('progress', progress(2, 'MODEL_STARTED', 'RUNNING', { modelRound: 4 }))
    + frame('progress', progress(3, 'MODEL_RETRYING', 'RUNNING', { modelRound: 4 }))
    + frame('progress', progress(4, 'MODEL_STARTED', 'RUNNING', { modelRound: 5 }))
    + frame('progress', progress(5, 'MODEL_COMPLETED', 'SUCCESS', { modelRound: 5 }))
    + frame('result', result));
  const run = await readSkillProgress(reply, event => events.push(event));
  assert.equal(run.status, 'SUCCESS');
  assert.equal(events[2].type, 'MODEL_RETRYING');
  assert.equal(events[3].modelRound, 5);
});

function validationPrefix() {
  return frame('progress', progress(1))
    + frame('progress', progress(2, 'MODEL_STARTED', 'RUNNING', { modelRound: 6 }))
    + frame('content', content(1, '第六轮草稿', { modelRound: 6 }))
    + frame('progress', progress(3, 'MODEL_COMPLETED', 'SUCCESS', { modelRound: 6 }))
    + frame('progress', progress(4, 'VALIDATION_STARTED', 'RUNNING', { modelRound: 6 }));
}

test('final answer validation retries across rounds without confusing completed and active generation', async () => {
  let text = '';
  const events = [];
  const stream = validationPrefix()
    + frame('progress', progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 6, message: '数量超限，重新优选' }))
    + frame('progress', progress(6, 'MODEL_STARTED', 'RUNNING', { modelRound: 7 }))
    + frame('content', content(2, '第七轮草稿', { modelRound: 7 }))
    + frame('progress', progress(7, 'MODEL_COMPLETED', 'SUCCESS', { modelRound: 7 }))
    + frame('progress', progress(8, 'VALIDATION_STARTED', 'RUNNING', { modelRound: 7 }))
    + frame('progress', progress(9, 'MODEL_RETRYING', 'RUNNING', { modelRound: 7, message: '引用需核对' }))
    + frame('progress', progress(10, 'MODEL_STARTED', 'RUNNING', { modelRound: 8 }))
    + frame('content', content(3, '已修正的最终答案', { modelRound: 8 }))
    + frame('progress', progress(11, 'MODEL_COMPLETED', 'SUCCESS', { modelRound: 8 }))
    + frame('progress', progress(12, 'VALIDATION_STARTED', 'RUNNING', { modelRound: 8 }))
    + frame('progress', progress(13, 'RUN_COMPLETED', 'SUCCESS', { modelRound: 8 })) + frame('result', result);
  const run = await readSkillProgress(response(stream), event => {
    events.push(event);
    if (['MODEL_STARTED', 'MODEL_RETRYING'].includes(event.type)) text = '';
  }, delta => { text += delta.text; });
  assert.equal(run.status, 'SUCCESS');
  assert.equal(text, '已修正的最终答案');
  assert.equal(events.filter(event => event.type === 'MODEL_RETRYING').length, 2);
});

test('validation retry does not reopen content or waiting until the next MODEL_STARTED', async () => {
  const prefix = validationPrefix() + frame('progress', progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 6 }));
  await assert.rejects(readSkillProgress(response(prefix + frame('content', content(2, '迟到的第六轮内容', { modelRound: 6 })))),
    error => error.diagnostics.code === 'CONTENT_PROTOCOL_ERROR');
  await assert.rejects(readSkillProgress(response(prefix + frame('progress', progress(6, 'MODEL_WAITING', 'RUNNING', { modelRound: 6 })))),
    error => error.diagnostics.code === 'ROUND_MISMATCH');
});

test('rejects future stale and failed validation retries while preserving last trusted diagnostics', async () => {
  for (const event of [progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 5 }),
    progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 7 }),
    progress(5, 'MODEL_RETRYING', 'FAILED', { modelRound: 6 }),
    progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: null })]) {
    await assert.rejects(readSkillProgress(response(validationPrefix() + frame('progress', event))), error => {
      assert.equal(error.diagnostics.code, 'ROUND_MISMATCH');
      assert.equal(error.diagnostics.lastEvent, 'VALIDATION_STARTED');
      assert.equal(error.diagnostics.modelRound, 6);
      assert.equal(error.diagnostics.progressSequence, 4);
      return true;
    });
  }
  const prefix = validationPrefix() + frame('progress', progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 6 }))
    + frame('progress', progress(6, 'MODEL_STARTED', 'RUNNING', { modelRound: 7 }));
  await assert.rejects(readSkillProgress(response(prefix + frame('progress', progress(7, 'MODEL_RETRYING', 'RUNNING', { modelRound: 6 })))),
    error => error.diagnostics.code === 'ROUND_MISMATCH');
});

test('model completion without validation and run termination cannot authorize post-completion retries', async () => {
  const start = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'));
  for (const type of ['MODEL_COMPLETED', 'MODEL_FAILED', 'RUN_FAILED', 'RUN_COMPLETED']) {
    const prefix = start + frame('progress', progress(3, type, type.endsWith('FAILED') ? 'FAILED' : 'SUCCESS'));
    await assert.rejects(readSkillProgress(response(prefix + frame('progress', progress(4, 'MODEL_RETRYING')))),
      error => error.diagnostics.code === 'ROUND_MISMATCH');
  }
});

test('session replay at validation boundary resumes same run without cancelling or duplicating tools', async context => {
  const requests = [];
  const events = [];
  let text = '';
  const suffix = frame('progress', progress(5, 'MODEL_RETRYING', 'RUNNING', { modelRound: 6 }))
    + frame('progress', progress(6, 'MODEL_STARTED', 'RUNNING', { modelRound: 7 }))
    + frame('content', content(2, '新答案', { modelRound: 7 }))
    + frame('progress', progress(7, 'MODEL_COMPLETED', 'SUCCESS', { modelRound: 7 }))
    + frame('result', result);
  context.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(options.method, 'POST', '合法校验重试不应发送DELETE取消会话');
    requests.push(new URL(url, 'http://localhost'));
    return response(validationPrefix() + (requests.length === 1 ? '' : suffix));
  });
  const run = await requestSkillProgress('/api/skills/test/stream', {}, event => {
    events.push(event.sequence);
    if (['MODEL_STARTED', 'MODEL_RETRYING'].includes(event.type)) text = '';
  }, delta => { text += delta.text; }, { retryDelayMs: 0 });
  assert.equal(run.status, 'SUCCESS');
  assert.equal(text, '新答案');
  assert.deepEqual(events, [1, 2, 3, 4, 5, 6, 7]);
  assert.equal(requests.length, 2);
  assert.equal(requests[0].searchParams.get('sessionId'), requests[1].searchParams.get('sessionId'));
  assert.equal(requests[1].searchParams.get('resume'), 'true');
});

test('session deadline uses backend budget rather than the old 330-second cutoff', async context => {
  const durations = [];
  context.mock.method(AbortSignal, 'timeout', milliseconds => {
    durations.push(milliseconds);
    return new AbortController().signal;
  });
  context.mock.method(globalThis, 'fetch', async () => response(frame('progress', progress(1)) + frame('result', result)));
  await requestSkillProgress('/api/skills/test/stream', { username: 'test' });
  assert.equal(durations[0], 1290000);
  durations.length = 0;
  await requestSkillProgress('/api/skills/test/stream', { username: 'test' }, undefined, undefined, { timeoutMs: 2490000 });
  assert.equal(durations[0], 2490000);
  for (const timeoutMs of [0, -1, 3690001, NaN]) {
    await assert.rejects(requestSkillProgress('/api/skills/test/stream', {}, undefined, undefined, { timeoutMs }), /会话等待时限/);
  }
});

test('interrupted streams expose request, run and last accepted round instead of appearing stuck', async () => {
  const reply = response(frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'))
    + frame('progress', progress(3, 'MODEL_WAITING')));
  reply.headers.set('X-Request-Id', 'request-1');
  await assert.rejects(readSkillProgress(reply), error => {
    assert.deepEqual(error.diagnostics, { requestId: 'request-1', runId: 'run-1', modelRound: 1,
      lastEvent: 'MODEL_WAITING', progressSequence: 3, contentSequence: 0, code: 'STREAM_READ_ERROR' });
    assert.match(skillFailureMessage(error), /RUN run-1/);
    assert.match(skillFailureMessage(error), /REQUEST request-1/);
    assert.match(skillFailureMessage(error), /MODEL_WAITING/);
    return true;
  });
});

test('page rendering failures explicitly explain client-side cancellation without leaking callback details', async () => {
  let cancelled = false;
  const reply = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frame('progress', progress(1))
        + frame('progress', progress(2, 'MODEL_STARTED'))));
    },
    cancel() { cancelled = true; }
  }), { headers: { 'Content-Type': 'text/event-stream' } });
  await assert.rejects(readSkillProgress(reply, event => {
    if (event.type === 'MODEL_STARTED') throw new Error('private-dom-details');
  }), error => {
    assert.equal(error.diagnostics.code, 'CLIENT_RENDER_ERROR');
    assert.equal(error.diagnostics.lastEvent, 'MODEL_STARTED');
    assert.equal(error.diagnostics.runId, 'run-1');
    assert.doesNotMatch(skillFailureMessage(error), /private-dom-details/);
    return true;
  });
  assert.equal(cancelled, true);
});

test('protocol errors retain the last trusted event rather than adopting the mismatched run', async () => {
  const reply = response(frame('progress', progress(1)) + frame('progress', progress(3, 'MODEL_STARTED', 'RUNNING', { runId: 'wrong-run' })));
  await assert.rejects(readSkillProgress(reply), error => {
    assert.equal(error.diagnostics.code, 'PROGRESS_PROTOCOL_ERROR');
    assert.equal(error.diagnostics.runId, 'run-1');
    assert.equal(error.diagnostics.progressSequence, 1);
    return true;
  });
});

test('browser timeout and abort use diagnostic names instead of numeric DOMException codes', async () => {
  for (const [name, code] of [['TimeoutError', 'CLIENT_TIMEOUT'], ['AbortError', 'CLIENT_ABORT']]) {
    const reply = new Response(new ReadableStream({ start(controller) {
      controller.error(new DOMException('private-error-detail', name));
    } }), { headers: { 'Content-Type': 'text/event-stream' } });
    await assert.rejects(readSkillProgress(reply), error => {
      assert.equal(error.diagnostics.code, code);
      assert.equal(error.diagnostics.runId, null);
      assert.doesNotMatch(JSON.stringify(error.diagnostics), /private-error-detail/);
      return true;
    });
  }
});

test('waiting events keep the model round active and do not invent answer content', async () => {
  const events = [];
  const chunks = [];
  const body = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'))
    + frame('progress', progress(3, 'MODEL_WAITING')) + frame('content', content(1, '真实正文'))
    + frame('progress', progress(4, 'MODEL_COMPLETED', 'SUCCESS')) + frame('result', result);
  assert.equal((await readSkillProgress(response(body), event => events.push(event), delta => chunks.push(delta.text))).status, 'SUCCESS');
  assert.deepEqual(chunks, ['真实正文']);
  assert.ok(events.some(event => event.type === 'MODEL_WAITING'));
  const invalid = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_WAITING'));
  await assert.rejects(readSkillProgress(response(invalid)), /等待状态/);
});

function content(sequence, text, extra = {}) {
  return { runId: 'run-1', skillId: 'test', sequence, modelRound: 1, text, ...extra };
}

test('delivers actual content before final result without losing Unicode, whitespace or JSON fragments', async () => {
  let streamController;
  let received;
  let finished = false;
  const firstContent = new Promise(resolve => { received = resolve; });
  const chunks = [];
  const stream = new Response(new ReadableStream({ start(controller) { streamController = controller; } }), {
    headers: { 'Content-Type': 'text/event-stream' }
  });
  const pending = readSkillProgress(stream, () => {}, delta => {
    chunks.push(delta.text);
    received();
  }).then(run => { finished = true; return run; });
  const prefix = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'))
    + frame('content', content(1, '{"summary":"北京 🌟'));
  for (const byte of new TextEncoder().encode(prefix)) streamController.enqueue(new Uint8Array([byte]));
  await firstContent;
  assert.equal(finished, false);
  assert.deepEqual(chunks, ['{"summary":"北京 🌟']);
  streamController.enqueue(new TextEncoder().encode(frame('content', content(2, ' 出游"}\n'))
    + frame('progress', progress(3, 'MODEL_COMPLETED', 'SUCCESS')) + frame('result', result)));
  assert.equal((await pending).status, 'SUCCESS');
  assert.equal(chunks.join(''), '{"summary":"北京 🌟 出游"}\n');
});

test('rejects mismatched, out-of-order and late model content', async () => {
  const prefix = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'));
  for (const delta of [content(1, 'a', { runId: 'other' }), content(1, 'a', { skillId: 'other' }),
    content(1, 'a', { modelRound: 2 }), content(2, 'a'), content(1, ''), content(1, null)]) {
    await assert.rejects(readSkillProgress(response(prefix + frame('content', delta))), /模型内容流顺序或标识无效/);
  }
  await assert.rejects(readSkillProgress(response(frame('content', content(1, 'a')))), /标识无效/);
  await assert.rejects(readSkillProgress(response(prefix + frame('progress', progress(3, 'MODEL_COMPLETED', 'SUCCESS'))
    + frame('content', content(1, 'late')))), /标识无效/);
});

test('draft content never confirms success on EOF or final validation failure', async () => {
  const prefix = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'))
    + frame('content', content(1, '{"summary":"未校验"}'));
  const chunks = [];
  await assert.rejects(readSkillProgress(response(prefix), () => {}, delta => chunks.push(delta.text)), /连接已中断/);
  assert.deepEqual(chunks, ['{"summary":"未校验"}']);
  await assert.rejects(readSkillProgress(response(prefix + frame('result', {
    code: 500, msg: '结果校验失败', data: { runId: 'run-1', status: 'FAILED' }
  }))), error => error.run.status === 'FAILED' && error.message === '结果校验失败');
});

test('keeps content numbering across rounds without mixing round identifiers', async () => {
  const chunks = [];
  await readSkillProgress(response(frame('progress', progress(1))
    + frame('progress', progress(2, 'MODEL_STARTED')) + frame('content', content(1, '第一轮'))
    + frame('progress', progress(3, 'MODEL_COMPLETED', 'SUCCESS'))
    + frame('progress', progress(4, 'MODEL_STARTED', 'RUNNING', { modelRound: 2 }))
    + frame('content', content(2, '第二轮', { modelRound: 2 })) + frame('result', result)),
  () => {}, delta => chunks.push(delta));
  assert.deepEqual(chunks.map(delta => delta.modelRound), [1, 2]);
});

test('decodes split UTF-8 progress and resolves only on final result', async () => {
  const events = [];
  const run = await readSkillProgress(response(frame('progress', progress(1))
    + frame('progress', progress(2, 'RUN_COMPLETED', 'SUCCESS')) + frame('result', result), 1), event => events.push(event));
  assert.equal(run.status, 'SUCCESS');
  assert.equal(events.length, 2);
  assert.ok(events[0].message.includes('🌟'));
});

test('delivers progress while stream remains open and model has not finished', async () => {
  let streamController;
  let delivered;
  let finished = false;
  const progressReceived = new Promise(resolve => { delivered = resolve; });
  const stream = new Response(new ReadableStream({ start(controller) { streamController = controller; } }), {
    headers: { 'Content-Type': 'text/event-stream' }
  });
  const pending = readSkillProgress(stream, delivered).then(run => { finished = true; return run; });
  streamController.enqueue(new TextEncoder().encode(frame('progress', progress(1))));
  await progressReceived;
  assert.equal(finished, false);
  streamController.enqueue(new TextEncoder().encode(frame('result', result)));
  assert.equal((await pending).status, 'SUCCESS');
});

test('does not treat completion progress as a complete result on truncated connection', async () => {
  await assert.rejects(readSkillProgress(response(frame('progress', progress(1, 'RUN_COMPLETED', 'SUCCESS')))), /连接已中断/);
});

test('failed runs preserve actual model and tool traces', async () => {
  const failed = { code: 500, msg: '天气工具失败', data: { runId: 'run-1', status: 'FAILED', traces: [{ tool: 'weather', status: 'FAILED' }] } };
  await assert.rejects(readSkillProgress(response(frame('progress', progress(1)) + frame('result', failed))), error => {
    assert.equal(error.message, '天气工具失败');
    assert.deepEqual(error.run.traces, failed.data.traces);
    return true;
  });
});

test('rejects mismatched runs, reordered events and malformed data', async () => {
  for (const suffix of [frame('progress', progress(1)), frame('progress', progress(3)),
    frame('progress', progress(2, 'MODEL_STARTED', 'RUNNING', { runId: 'other' })),
    frame('result', { ...result, data: { ...result.data, runId: 'other' } }), 'event:progress\ndata:{\n\n']) {
    await assert.rejects(readSkillProgress(response(frame('progress', progress(1)) + suffix)), /无效|不匹配/);
  }
});

test('handles normal HTTP errors, non-stream responses and safe server error events', async () => {
  await assert.rejects(readSkillProgress(new Response('{"msg":"缺少用户名"}', { status: 400 })), /缺少用户名/);
  await assert.rejects(readSkillProgress(new Response('<html>proxy error</html>', { status: 502 })), /HTTP 502/);
  await assert.rejects(readSkillProgress(new Response('{}', { headers: { 'Content-Type': 'application/json' } })), /未返回执行进度流/);
  await assert.rejects(readSkillProgress(response(frame('error', { message: '进度流超时' }))), /进度流超时/);
});

test('renders escaped server messages and uses real elapsed time', () => {
  const html = progressMarkup([progress(1, 'TOOL_COMPLETED', 'SUCCESS', { message: '已完成 <script>alert(1)</script>', elapsedMs: 1200 })]);
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(html.includes('1.2 s'));
  assert.ok(html.includes('success'));
});

test('tool indicators follow actual events and become unknown on lost connection', () => {
  const events = [progress(1), progress(2, 'TOOL_STARTED', 'RUNNING', { tool: 'profile', toolCallId: 'call-1' }),
    progress(3, 'TOOL_COMPLETED', 'SUCCESS', { tool: 'profile', toolCallId: 'call-1' }),
    progress(4, 'TOOL_STARTED', 'RUNNING', { tool: 'weather', toolCallId: 'call-2' })];
  assert.equal(progressWorkflow(events).findLast(event => event.tool === 'profile').status, 'SUCCESS');
  assert.equal(progressWorkflow(events).findLast(event => event.tool === 'weather').status, 'RUNNING');
  assert.equal(progressWorkflow(events, true).findLast(event => event.tool === 'weather').status, 'UNKNOWN');
  assert.equal(progressWorkflow(events, true).findLast(event => event.tool === 'profile').status, 'SUCCESS');
});

test('supports disabling reconnect and cancels the original session on failure', async () => {
  const originalFetch = globalThis.fetch;
  let requests = 0;
  globalThis.fetch = async (url, options) => {
    if (options.method === 'DELETE') return new Response(null, { status: 204 });
    requests++;
    assert.match(url, /^\/api\/skills\/test\/session-stream\?sessionId=/);
    assert.equal(options.method, 'POST');
    assert.equal(options.headers.Accept, 'text/event-stream');
    assert.deepEqual(JSON.parse(options.body), { input: { username: 'xiaoming' } });
    return response(frame('progress', progress(1)));
  };
  try {
    await assert.rejects(requestSkillProgress('/api/skills/test/stream', { username: 'xiaoming' }, undefined, undefined, { maxAttempts: 1 }), /连接已中断/);
    assert.equal(requests, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test('reconnects the same session and suppresses replayed progress and content', async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  const updates = [];
  const chunks = [];
  const notices = [];
  const prefix = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED')) + frame('content', content(1, 'first'));
  globalThis.fetch = async (url, options) => {
    assert.equal(options.method, 'POST');
    requests.push(new URL(url, 'http://localhost'));
    return response(requests.length === 1 ? prefix : prefix + frame('content', content(2, 'second'))
      + frame('progress', progress(3, 'MODEL_COMPLETED', 'SUCCESS')) + frame('result', result));
  };
  try {
    const run = await requestSkillProgress('/api/skills/test/stream', { username: 'xiaoming' }, event => updates.push(event.sequence),
      delta => chunks.push(delta.text), { retryDelayMs: 0, onReconnect: notice => notices.push(notice) });
    assert.equal(run.status, 'SUCCESS');
    assert.deepEqual(updates, [1, 2, 3]);
    assert.deepEqual(chunks, ['first', 'second']);
    assert.equal(requests[0].searchParams.get('sessionId'), requests[1].searchParams.get('sessionId'));
    assert.deepEqual(requests.map(url => url.searchParams.get('resume')), ['false', 'true']);
    assert.equal(notices[0].attempt, 2);
  } finally { globalThis.fetch = originalFetch; }
});

test('limits reconnect attempts and never creates a fresh execution after an uncertain network failure', async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  let cancellations = 0;
  globalThis.fetch = async (url, options) => {
    if (options.method === 'DELETE') { cancellations++; return new Response(null, { status: 204 }); }
    requests.push(new URL(url, 'http://localhost'));
    throw new TypeError('network disconnected');
  };
  try {
    await assert.rejects(requestSkillProgress('/api/skills/test/stream', {}, undefined, undefined, { retryDelayMs: 0 }), error => {
      assert.equal(error.diagnostics.connectionAttempts, 3);
      return true;
    });
    assert.equal(requests.length, 3);
    assert.equal(new Set(requests.map(url => url.searchParams.get('sessionId'))).size, 1);
    assert.deepEqual(requests.map(url => url.searchParams.get('resume')), ['false', 'true', 'true']);
    assert.equal(cancellations, 1);
  } finally { globalThis.fetch = originalFetch; }
});

test('does not reconnect expired, unauthorized, protocol-invalid or business-failed sessions', async () => {
  const originalFetch = globalThis.fetch;
  try {
    for (const reply of [() => new Response('{}', { status: 410 }), () => new Response('{}', { status: 401 }),
      () => response('event:progress\ndata:null\n\n'),
      () => response(frame('progress', progress(2))),
      () => response(frame('progress', progress(1)) + frame('result', { code: 500, data: { runId: 'run-1', status: 'FAILED' }, msg: '校验失败' }))]) {
      let requests = 0;
      globalThis.fetch = async (url, options) => {
        if (options.method === 'DELETE') return new Response(null, { status: 204 });
        requests++;
        return reply();
      };
      await assert.rejects(requestSkillProgress('/api/skills/test/stream', {}, undefined, undefined, { retryDelayMs: 0 }));
      assert.equal(requests, 1);
    }
  } finally { globalThis.fetch = originalFetch; }
});

test('aborting retry backoff stops reconnect and cancels the session', async () => {
  const originalFetch = globalThis.fetch;
  const abort = new AbortController();
  let requests = 0;
  let cancelled = false;
  globalThis.fetch = async (url, options) => {
    if (options.method === 'DELETE') { cancelled = true; return new Response(null, { status: 204 }); }
    requests++;
    return response(frame('progress', progress(1)));
  };
  try {
    await assert.rejects(requestSkillProgress('/api/skills/test/stream', {}, undefined, undefined,
      { signal: abort.signal, onReconnect: () => abort.abort() }), error => error.name === 'AbortError');
    assert.equal(requests, 1);
    assert.equal(cancelled, true);
  } finally { globalThis.fetch = originalFetch; }
});

test('model retry clears the abandoned draft without resetting content sequence', async () => {
  let text = '';
  const prefix = frame('progress', progress(1)) + frame('progress', progress(2, 'MODEL_STARTED'))
    + frame('content', content(1, 'abandoned')) + frame('progress', progress(3, 'MODEL_RETRYING'))
    + frame('content', content(2, 'replacement')) + frame('progress', progress(4, 'MODEL_COMPLETED', 'SUCCESS')) + frame('result', result);
  await readSkillProgress(response(prefix), event => { if (event.type === 'MODEL_RETRYING') text = ''; }, delta => { text += delta.text; });
  assert.equal(text, 'replacement');
});
