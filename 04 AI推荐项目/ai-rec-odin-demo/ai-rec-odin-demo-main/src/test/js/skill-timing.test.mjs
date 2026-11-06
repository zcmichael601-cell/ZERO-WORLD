import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SkillTimingProgress, estimateDescription, formatTime, stageTimingRows } from '../../main/resources/static/skill-timing.mjs';

const estimate = { skillId: 'test', basis: 'INITIAL', sampleCount: 0, expectedMs: 10000, lowerMs: 6000, upperMs: 16000,
  stages: [{ id: 'planning', label: '模型决策', expectedMs: 2000, upperMs: 4000, expectedCalls: 2 },
    { id: 'synthesis', label: '模型生成', expectedMs: 8000, upperMs: 12000, expectedCalls: 1 }] };

function event(sequence, elapsedMs, stages, type = 'MODEL_STARTED') {
  return { runId: 'run', sequence, type, timing: { elapsedMs, stages, estimate: sequence === 1 ? estimate : null } };
}

function active(id, durationMs = 0, executions = 1) {
  return { id, durationMs, activeElapsedMs: durationMs, executions, active: true, complete: false };
}

test('shows honest preflight ranges and sample basis, gracefully handles unavailable estimates', () => {
  assert.match(estimateDescription(estimate), /暂无历史/);
  assert.match(estimateDescription({ ...estimate, sampleCount: 2 }), /仍需积累/);
  assert.match(estimateDescription({ ...estimate, sampleCount: 15, coldStart: true }), /15 次成功执行.*首次商品索引/);
  assert.match(estimateDescription(null), /暂无法预测/);
  assert.equal(formatTime(0), '0 毫秒');
  assert.equal(formatTime(800), '800 毫秒');
  assert.equal(formatTime(61000), '1 分 1 秒');
  assert.equal(formatTime(null), '—');
  const tracker = new SkillTimingProgress();
  assert.equal(tracker.view(100).determinate, false);
  assert.equal(tracker.view(100).remainingMs, null);
});

test('uses stage weighted budgets and elapsed server snapshots rather than tool count', () => {
  const tracker = new SkillTimingProgress(estimate);
  assert.equal(tracker.view(0).percent, 0);
  tracker.accept(event(1, 0, [active('planning')]), 100);
  assert.equal(tracker.view(600).elapsedMs, 500);
  assert.equal(tracker.view(600).percent, 5);
  tracker.accept(event(2, 2000, [{ ...active('planning', 2000, 2), active: false, complete: true }, active('synthesis')]), 1000);
  const view = tracker.view(5000);
  assert.equal(view.percent, 60);
  assert.equal(view.elapsedMs, 6000);
  assert.equal(view.remainingMs, 4000);
  assert.equal(view.rows[0].executions, 2);
  assert.equal(view.rows[1].durationMs, 4000);
});

test('repeated stages and retry backoff accumulate without resetting progress', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 2000, [{ ...active('planning', 2000), active: false, complete: true }]), 0);
  const previous = tracker.view(0).percent;
  tracker.accept(event(2, 3000, [{ ...active('planning', 2500, 3), activeElapsedMs: 500 }], 'MODEL_RETRYING'), 1000);
  const view = tracker.view(2000);
  assert.ok(view.percent >= previous);
  assert.equal(view.rows[0].durationMs, 3500);
  assert.equal(view.rows[0].executions, 3);
  assert.equal(view.rows[0].status, '进行中');
});

test('overdue work never reaches 100 and does not promise a false countdown', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 2000, [active('synthesis', 1000)]), 0);
  const view = tracker.view(100000);
  assert.ok(view.percent <= 98);
  assert.equal(view.remainingMs, null);
  assert.equal(view.overdue, true);
  assert.match(view.note, /暂不确定/);
});

test('consumed stage budget does not leave a misleading one-second countdown within the broad range', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 9000, [active('synthesis', 8000)]), 0);
  const view = tracker.view(0);
  assert.equal(view.remainingMs, null);
  assert.equal(view.overdue, true);
  assert.match(view.note, /已达到预计值/);
  assert.ok(view.elapsedMs < estimate.upperMs);
});

test('reconnect freezes prediction across repeated retries and ignores replayed or foreign events', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 1000, [active('synthesis', 1000)]), 100);
  tracker.reconnect(600);
  const paused = tracker.view(600);
  tracker.reconnect(2600);
  tracker.accept(event(1, 0, []), 3000);
  tracker.accept({ ...event(2, 0, []), runId: 'other' }, 3000);
  assert.equal(tracker.view(4000).elapsedMs, paused.elapsedMs);
  assert.equal(tracker.view(4000).percent, paused.percent);
  assert.equal(tracker.view(4000).remainingMs, null);
  assert.equal(tracker.view(4000).state, 'RECONNECTING');
  tracker.accept(event(2, 5000, [active('synthesis', 4000)]), 4000);
  assert.equal(tracker.view(4500).elapsedMs, 5500);
  assert.equal(tracker.view(4500).state, 'RUNNING');
});

test('RUN_COMPLETED stays at 99 until an actual successful final result arrives', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 9000, [] , 'RUN_COMPLETED'), 0);
  assert.equal(tracker.view(100).percent, 99);
  assert.equal(tracker.view(100).remainingMs, null);
  tracker.finish({ status: 'SUCCESS', timing: { elapsedMs: 9500, estimate,
    stages: [{ ...active('synthesis', 7000), active: false, complete: true }] } });
  const view = tracker.view(100000);
  assert.equal(view.percent, 100);
  assert.equal(view.elapsedMs, 9500);
  assert.equal(view.remainingMs, 0);
  assert.equal(view.predictionErrorMs, -500);
  assert.ok(view.rows.every(row => !row.active));
});

test('terminal delivery failure and failed runs are not counted as completion', () => {
  for (const run of [undefined, { status: 'FAILED', durationMs: 10500 }]) {
    const tracker = new SkillTimingProgress(estimate);
    tracker.accept(event(1, 9000, [active('synthesis', 8000)], 'RUN_COMPLETED'), 0);
    tracker.view(0);
    tracker.finish(run);
    const view = tracker.view(10000);
    assert.equal(view.percent, 99);
    assert.equal(view.state, 'FAILED');
    assert.equal(view.remainingMs, null);
    assert.equal(view.predictionErrorMs, null);
    assert.equal(view.elapsedMs, run ? 10500 : 9000);
  }
});

test('missing telemetry does not reset the active snapshot clock', () => {
  const tracker = new SkillTimingProgress(estimate);
  tracker.accept(event(1, 1000, [active('synthesis', 1000)]), 100);
  tracker.accept({ runId: 'run', sequence: 2, type: 'MODEL_WAITING' }, 1000);
  assert.equal(tracker.view(1100).elapsedMs, 2000);
});

test('stage rows escape server labels and UI has accessible progress and required bindings', async () => {
  const html = stageTimingRows([{ label: '<img onerror=alert(1)>', expectedMs: 1000, durationMs: 50, executions: 2, status: '<script>' }]);
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('<script>'));
  assert.match(html, /&lt;img/);
  assert.match(html, /scope="row"/);
  const page = await readFile(new URL('../../main/resources/static/skills.html', import.meta.url), 'utf8');
  const script = await readFile(new URL('../../main/resources/static/skills.js', import.meta.url), 'utf8');
  assert.match(page, /role="progressbar"/);
  for (const id of ['skill-estimate', 'skill-progress-bar', 'skill-progress-fill', 'timing-percent', 'timing-elapsed',
    'timing-remaining', 'timing-expected', 'timing-note', 'timing-basis', 'timing-error', 'timing-stages']) {
    assert.ok(page.includes(`id="${id}"`), id);
    assert.ok(script.includes(`'${id}'`), id);
  }
  assert.match(script, /timing\.finish\(run\)/);
  assert.match(script, /clearInterval\(timingTimer\)/);
});
