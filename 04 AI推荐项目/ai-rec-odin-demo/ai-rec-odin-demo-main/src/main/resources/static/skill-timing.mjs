import { escapeHtml } from './skill-utils.mjs?v=20261103-parallel-recall';

const nonnegative = value => Number.isFinite(value) ? Math.max(0, value) : 0;
const validEstimate = value => value && Number.isFinite(value.expectedMs) && value.expectedMs > 0
  && Array.isArray(value.stages) && value.stages.length > 0
  && value.stages.every(stage => typeof stage.id === 'string' && Number.isFinite(stage.expectedMs) && stage.expectedMs > 0);

export function formatTime(milliseconds) {
  if (!Number.isFinite(milliseconds)) return '—';
  if (milliseconds < 1000) return `${Math.round(Math.max(0, milliseconds))} 毫秒`;
  const seconds = Math.ceil(milliseconds / 1000);
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}

export function estimateDescription(estimate) {
  if (!validEstimate(estimate)) return '暂无法预测，开始后根据服务端记录更新';
  const basis = estimate.sampleCount === 0 ? '初始估计，暂无历史记录'
    : estimate.sampleCount < 5 ? `结合 ${estimate.sampleCount} 次历史记录，仍需积累数据`
      : `基于近期 ${estimate.sampleCount} 次成功执行`;
  return `预计 ${formatTime(estimate.expectedMs)}（${formatTime(estimate.lowerMs)}～${formatTime(estimate.upperMs)}）· ${basis}`
    + (estimate.coldStart ? ' · 含首次商品索引构建' : '');
}

export class SkillTimingProgress {
  constructor(estimate = null) {
    this.estimate = validEstimate(estimate) ? estimate : null;
    this.snapshot = null;
    this.state = 'PREPARING';
    this.sequence = 0;
    this.percent = 0;
    this.receivedAt = 0;
    this.pausedAt = null;
    this.runId = null;
  }

  accept(event, now) {
    if (!Number.isInteger(event.sequence) || event.sequence <= this.sequence || (this.runId && this.runId !== event.runId)) return;
    this.runId = event.runId;
    this.sequence = event.sequence;
    if (event.timing && Array.isArray(event.timing.stages) && Number.isFinite(event.timing.elapsedMs)) {
      this.snapshot = event.timing;
      if (validEstimate(event.timing.estimate)) this.estimate = event.timing.estimate;
      this.receivedAt = now;
    }
    this.pausedAt = null;
    this.state = event.type === 'RUN_COMPLETED' ? 'AWAITING_RESULT' : event.type === 'RUN_FAILED' ? 'FAILED' : 'RUNNING';
  }

  reconnect(now) {
    this.view(now);
    this.pausedAt ??= now;
    this.state = 'RECONNECTING';
  }

  finish(run) {
    this.state = run?.status === 'SUCCESS' ? 'SUCCESS' : 'FAILED';
    if (run?.timing) {
      this.snapshot = run.timing;
      if (validEstimate(run.timing.estimate)) this.estimate = run.timing.estimate;
    } else if (run && Number.isFinite(run.durationMs)) {
      this.snapshot = { elapsedMs: run.durationMs, stages: [] };
    }
    if (this.state === 'SUCCESS') this.percent = 100;
  }

  view(now) {
    const delta = this.snapshot && this.state === 'RUNNING' ? nonnegative(now - this.receivedAt)
      : this.state === 'RECONNECTING' && this.snapshot ? nonnegative(this.pausedAt - this.receivedAt) : 0;
    const elapsedMs = nonnegative(this.snapshot?.elapsedMs) + delta;
    const final = ['SUCCESS', 'FAILED'].includes(this.state);
    let remainingMs = 0;
    let completedWeight = 0;
    let overdue = Boolean(this.estimate && elapsedMs >= this.estimate.expectedMs);
    const rows = (this.estimate?.stages ?? []).map(prediction => {
      const measured = this.snapshot?.stages?.find(stage => stage.id === prediction.id);
      const active = Boolean(measured?.active && !final && this.state !== 'AWAITING_RESULT');
      const durationMs = nonnegative(measured?.durationMs) + (active ? delta : 0);
      const activeElapsedMs = nonnegative(measured?.activeElapsedMs) + (active ? delta : 0);
      const executions = nonnegative(measured?.executions);
      const complete = Boolean(measured?.complete);
      const expected = prediction.expectedMs;
      const perCall = expected / Math.max(1, nonnegative(prediction.expectedCalls));
      if (!complete || active) {
        remainingMs += active ? Math.max(expected - durationMs, perCall - activeElapsedMs, Math.min(1000, perCall * 0.1))
          : executions ? Math.max(expected - durationMs, perCall * 0.25) : expected;
      }
      if (active && prediction.id !== 'overhead' && (activeElapsedMs >= perCall || durationMs >= expected)) overdue = true;
      completedWeight += expected * (complete && !active ? 1 : Math.min(0.95, durationMs / expected));
      const status = active ? (this.state === 'RECONNECTING' ? '重连中，暂停推算' : '进行中') : complete ? '已完成'
        : executions ? (final ? '已记录，未确认完成' : '已执行，可能继续') : final ? '未执行' : '待执行';
      return { ...prediction, durationMs, executions, active, complete, status };
    });
    if (!final && this.state !== 'RECONNECTING' && this.estimate && this.snapshot) {
      const calculated = this.state === 'AWAITING_RESULT' ? 99 : Math.min(98, 100 * completedWeight / this.estimate.expectedMs);
      this.percent = Math.max(this.percent, calculated);
    }
    let note = '预计进度，不代表固定步骤数；阶段可重复进入，耗时按累计统计。';
    if (this.state === 'RECONNECTING') note = '正在重连原会话，进度推算已暂停，恢复后以服务端记录校准。';
    else if (this.state === 'AWAITING_RESULT') note = '服务端已完成校验，等待完整结果确认。';
    else if (this.state === 'FAILED') note = '执行未完成，保留最后服务端计时；不将中断视为完成。';
    else if (this.state === 'SUCCESS') note = '执行完成，以下为服务端实测耗时。';
    else if (!this.estimate) note = '暂无耗时估计；阶段计时需要更新后的后端支持。';
    else if (overdue) note = '当前阶段或总耗时已达到预计值，剩余时间暂不确定；继续等待真实执行结果。';
    const predictionErrorMs = this.state === 'SUCCESS' && this.estimate ? elapsedMs - this.estimate.expectedMs : null;
    return { state: this.state, percent: Math.floor(this.percent), elapsedMs, rows, note, overdue,
      remainingMs: this.state === 'SUCCESS' ? 0 : final || overdue || this.state === 'RECONNECTING' || this.state === 'AWAITING_RESULT'
        ? null : this.estimate ? Math.ceil(remainingMs) : null,
      expectedMs: this.estimate?.expectedMs ?? null, predictionErrorMs,
      description: estimateDescription(this.estimate), determinate: Boolean(this.estimate || final) };
  }
}

export function stageTimingRows(rows) {
  return rows.map(row => `<tr class="${row.active ? 'timing-active' : ''}"><th scope="row">${escapeHtml(row.label)}</th>`
    + `<td>${escapeHtml(formatTime(row.expectedMs))}</td><td>${escapeHtml(formatTime(row.durationMs))}</td>`
    + `<td>${escapeHtml(row.executions)}</td><td>${escapeHtml(row.status)}</td></tr>`).join('');
}
