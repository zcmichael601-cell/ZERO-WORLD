import { escapeHtml as escape, formatJson, displayValue, confidence, requestJson } from './skill-utils.mjs?v=20261103-single-cid';
import { shoppingPredictionMarkup } from './shopping-prediction.mjs?v=20261103-single-cid';
import { beijingTravelMarkup } from './beijing-travel.mjs?v=20261103-single-cid';
import { skillInputsMarkup, applyUser, supportsUserDataMode } from './skill-users.mjs?v=20261103-single-cid';
import { requestSkillProgress, progressWorkflow, progressMarkup, skillFailureMessage } from './skill-progress.mjs?v=20261103-single-cid';
import { productComparisonMarkup } from './product-comparison.mjs?v=20261103-single-cid';
import { productSourceMarkup } from './product-source.mjs?v=20261103-single-cid';
import { SkillTimingProgress, estimateDescription, formatTime, stageTimingRows } from './skill-timing.mjs?v=20261103-single-cid';

const element = id => document.getElementById(id);
const form = element('skill-form');
const select = element('skill-select');
const runButton = element('run-button');
let skills = [];
let users = [];
let selectedUsername = '';
let demoUsers = [];
let shoppingUserMode = 'REAL';
let realUserUuid = '';
let demoUsername = '';
let progressEvents = [];
let busy = false;
let timing = null;
let timingTimer;
let preflightEstimate = null;
let estimateRequest = 0;
let clientTimeoutMs = 1290000;
const interactiveSkillId = 'shopping-recommendation-jev-cid';
let interactionState = {
  screenNumber: 1,
  selected: new Map(),
  history: [],
  committedScreens: new Set(),
  priors: [],
  initialInterests: [],
  relatedCid2: [],
  interestBaselineScreen: 0,
  products: [],
  gallery: [],
  lastInput: null,
  lastProfile: null,
  bypassInterests: [],
  bypassStatus: '',
  bypassSummary: '',
  bypassDurationMs: null,
  rounds: [],
  usedQueries: [],
  contextCollapsed: false,
};
const interestScoreMemory = new Map();
let bypassTimer;
let bypassRequest = 0;

async function refreshEstimate() {
  const skillId = currentSkill()?.id;
  if (!skillId) return;
  const request = ++estimateRequest;
  preflightEstimate = null;
  element('skill-estimate').textContent = '正在估算执行耗时…';
  try {
    const modeQuery = supportsUserDataMode(skillId) ? `?userDataMode=${shoppingUserMode}` : '';
    const estimate = await requestJson(`api/skills/${encodeURIComponent(skillId)}/estimate${modeQuery}`, { signal: AbortSignal.timeout(5000), cache: 'no-store' });
    if (request !== estimateRequest || currentSkill()?.id !== skillId) return;
    preflightEstimate = estimate;
    element('skill-estimate').textContent = estimateDescription(estimate);
  } catch {
    if (request === estimateRequest) element('skill-estimate').textContent = '暂无法提前估算，不影响执行；开始后根据服务端计时更新。';
  }
}

function renderTiming() {
  if (!timing) return;
  const view = timing.view(performance.now());
  element('timing-percent').textContent = view.determinate ? `${view.percent}%` : '—';
  const bar = element('skill-progress-bar');
  bar.dataset.state = view.state;
  if (view.determinate) bar.setAttribute('aria-valuenow', String(view.percent));
  else bar.removeAttribute('aria-valuenow');
  bar.setAttribute('aria-valuetext', `${view.determinate ? `${view.percent}%，` : ''}${view.note}`);
  element('skill-progress-fill').style.width = `${view.percent}%`;
  element('timing-elapsed').textContent = formatTime(view.elapsedMs);
  element('timing-remaining').textContent = view.overdue && view.state === 'RUNNING' ? '暂不确定' : formatTime(view.remainingMs);
  element('timing-expected').textContent = formatTime(view.expectedMs);
  element('timing-note').textContent = view.note;
  element('timing-basis').textContent = view.description;
  element('timing-stages').innerHTML = stageTimingRows(view.rows);
  element('timing-error').hidden = view.predictionErrorMs === null;
  element('timing-error').textContent = view.predictionErrorMs === null ? ''
    : `实际用时比开始时预计${view.predictionErrorMs >= 0 ? '多' : '少'} ${formatTime(Math.abs(view.predictionErrorMs))}；成功记录用于校准后续预测。`;
}

function currentSkill() {
  return skills.find(skill => skill.id === select.value);
}

function showStatus(message, error = false) {
  element('status').textContent = message;
  element('status').classList.toggle('error', error);
}

function setBusy(value) {
  busy = value;
  form.querySelectorAll('input, select, button').forEach(control => { control.disabled = value || !skills.length; });
  runButton.firstElementChild.textContent = value ? '正在执行 Skill…' : '运行 Skill';
  element('results').setAttribute('aria-busy', String(value));
  element('run-state').textContent = value ? '执行中' : '等待运行';
  element('run-state').className = value ? 'state-badge running' : 'state-badge';
  updateInteractionControls();
}

function renderWorkflow(traces = []) {
  const names = { Skill: '按需加载技能指令', get_user_profile: '查询用户画像', get_user_behaviors: '查询用户行为', search_products: '检索候选商品', retrieve_product_knowledge: '检索商品知识与对比依据', get_beijing_weather: '查询北京天气', get_beijing_hot_attractions: '获取热门景点' };
  element('workflow-steps').innerHTML = (currentSkill() ? ['Skill', ...currentSkill().tools] : []).map((tool, index) => {
    const trace = traces.findLast(item => item.tool === tool);
    const state = trace?.status === 'SUCCESS' ? 'success' : trace?.status === 'RUNNING' ? 'running'
      : trace?.status === 'UNKNOWN' ? 'unknown' : trace ? 'failed' : '';
    const label = state === 'success' ? '已完成' : state === 'running' ? '执行中' : state === 'unknown' ? '状态未确认' : '失败';
    const icon = state === 'success' ? '✓' : state === 'running' ? '…' : state === 'unknown' ? '?' : '!';
    return `<li class="workflow-step ${state}"><div class="step-number">${trace ? icon : String(index + 1).padStart(2, '0')}</div><div class="step-body"><div class="step-heading"><strong>${escape(names[tool] ?? tool)}</strong><span>${trace ? `${trace.modelRound === 0 ? '准备流程' : `第 ${trace.modelRound} 轮`} · ${label}` : (traces.length ? '未调用' : '可调用')}</span></div><code>${escape(tool)}</code></div></li>`;
  }).join('');
}

function selectSkill() {
  const skill = currentSkill();
  if (!skill) return;
  resetInteractionState();
  element('skill-description').textContent = skill.description;
  const shopping = supportsUserDataMode(skill.id);
  const choices = shopping && shoppingUserMode === 'DEMO' ? demoUsers : users;
  const username = shopping ? (shoppingUserMode === 'REAL' ? realUserUuid : demoUsername) : selectedUsername;
  element('input-fields').innerHTML = skillInputsMarkup(skill.requiredInputs, choices, username, shopping ? shoppingUserMode : null);
  applyUser(form, element('user-preview'), shopping && shoppingUserMode === 'REAL'
    ? (username ? { username, source: 'AI_MEMORY' } : undefined)
    : skill.requiredInputs.includes('username') ? choices.find(user => user.username === username) : undefined);
  resetRun();
  refreshEstimate();
}

function resetRun() {
  clearInterval(timingTimer);
  timing = null;
  progressEvents = [];
  element('model-stream-panel').hidden = true;
  element('model-stream-content').textContent = '';
  element('progress-panel').hidden = true;
  element('progress-events').innerHTML = '';
  element('results').hidden = true;
  element('interaction-controls').hidden = true;
  element('interest-panel').hidden = true;
  element('session-panel').hidden = true;
  element('empty-state').hidden = false;
  element('run-state').textContent = '等待运行';
  element('run-state').className = 'state-badge';
  renderWorkflow();
  showStatus('');
}

function resetInteractionState() {
  interactionState = {
    screenNumber: 1,
    selected: new Map(),
    history: [],
    committedScreens: new Set(),
    priors: [],
    initialInterests: [],
    relatedCid2: [],
    interestBaselineScreen: 0,
    products: [],
    gallery: [],
    lastInput: null,
    lastProfile: null,
    bypassInterests: [],
    bypassStatus: '',
    bypassSummary: '',
    bypassDurationMs: null,
    rounds: [],
    usedQueries: [],
    contextCollapsed: false,
  };
  interestScoreMemory.clear();
  bypassRequest += 1;
  clearTimeout(bypassTimer);
}

function renderProgress(event) {
  timing?.accept(event, performance.now());
  renderTiming();
  if (event.type === 'MODEL_STARTED') {
    element('model-stream-content').textContent = '';
    element('model-stream-state').textContent = `第 ${event.modelRound} 轮 · 等待模型返回内容`;
  } else if (event.type === 'MODEL_WAITING') {
    element('model-stream-state').textContent = event.message;
  } else if (event.type === 'MODEL_RETRYING') {
    element('model-stream-content').textContent = '';
    element('model-stream-state').textContent = event.message;
  } else if (event.type === 'MODEL_COMPLETED') {
    element('model-stream-state').textContent = `第 ${event.modelRound} 轮已返回 · 等待后续处理`;
  } else if (event.type === 'VALIDATION_STARTED') {
    element('model-stream-state').textContent = '内容已接收 · 正在校验';
  }
  progressEvents.push(event);
  element('progress-panel').hidden = false;
  element('progress-current').textContent = event.message;
  const completed = progressEvents.filter(item => item.type === 'TOOL_COMPLETED').length;
  element('progress-count').textContent = `已完成 ${completed} 次工具调用`;
  element('progress-events').innerHTML = progressMarkup(progressEvents);
  element('progress-events').scrollTop = element('progress-events').scrollHeight;
  renderWorkflow(progressWorkflow(progressEvents));
  showStatus(event.message, event.status === 'FAILED');
}

function renderContent(delta) {
  const output = element('model-stream-content');
  const follow = output.scrollHeight - output.scrollTop - output.clientHeight < 48;
  output.append(document.createTextNode(delta.text));
  element('model-stream-state').textContent = `第 ${delta.modelRound} 轮 · 正在生成`;
  if (follow) output.scrollTop = output.scrollHeight;
}

function profileMemoryText(memory) {
  if (typeof memory === 'string') return memory;
  return String(memory?.memory ?? memory?.text ?? '');
}

function behaviorText(behavior) {
  if (typeof behavior === 'string') return behavior;
  if (!behavior || typeof behavior !== 'object') return String(behavior ?? '');
  const action = String(behavior.action ?? '').trim();
  const text = String(behavior.text ?? behavior.memory ?? '').trim();
  const skuId = String(behavior.skuId ?? '').trim();
  const count = Number(behavior.count);
  return [
    action ? `[${action}]` : '',
    text || (skuId ? `SKU ${skuId}` : '行为记录'),
    skuId && !text.includes(skuId) ? `· SKU ${skuId}` : '',
    Number.isSafeInteger(count) && count > 1 ? `×${count}` : '',
  ].filter(Boolean).join(' ');
}

function renderProfile(profile) {
  interactionState.lastProfile = profile || null;
  element('profile-panel').hidden = !profile;
  element('history-panel').hidden = !profile;
  if (!profile) {
    element('profile-content').innerHTML = '';
    element('history-content').innerHTML = '';
    return;
  }
  const budget = Number.isFinite(profile.budget) ? `单件预算 ¥${escape(profile.budget)}` : '单件预算未提供';
  const memories = Array.isArray(profile.memories) ? profile.memories : [];
  const profileFacts = [...new Set([
    ...(Array.isArray(profile.interests) ? profile.interests : []),
    ...memories.map(profileMemoryText),
  ].map(fact => String(fact ?? '').trim()).filter(Boolean))];
  const profileFactsMarkup = profileFacts.length
    ? `<div class="tags">${profileFacts.map(fact => `<span class="tag">${escape(fact)}</span>`).join('')}</div>`
    : '<p class="field-hint">当前未返回画像兴趣或长期记忆，不能据此判断用户没有兴趣。</p>';
  element('profile-content').innerHTML = `<div class="profile-header"><div class="avatar">${escape(Array.from(profile.username ?? '?')[0])}</div><div><strong>${profile.source === 'AI_MEMORY' ? '用户 UUID：' : ''}${escape(profile.username)}</strong><p>${escape(profile.segment ?? '用户类型未知')} · ${escape(profile.city ?? '城市未提供')} · ${budget}</p></div></div>${profileFactsMarkup}`;

  const behaviors = Array.isArray(profile.behaviors) ? profile.behaviors : [];
  element('history-content').innerHTML = behaviors.length
    ? `<p class="field-hint">来自 AI Memory 的历史行为聚合，不包含本页面模拟操作。</p><ul class="behavior-list history-behavior-list">${behaviors.map(behavior => `<li>${escape(behaviorText(behavior))}</li>`).join('')}</ul>`
    : '<p class="field-hint">当前未返回行为记忆，不代表没有历史行为。</p>';
}

function syncContextCollapse() {
  const later = Number(interactionState.screenNumber) > 1;
  element('user-context-grid')?.classList.toggle('context-later-screen', later);
  const profile = element('profile-details');
  const history = element('history-details');
  if (!later) {
    interactionState.contextCollapsed = false;
    if (profile) profile.open = true;
    if (history) history.open = true;
    return;
  }
  if (interactionState.contextCollapsed) return;
  if (profile) profile.open = false;
  if (history) history.open = false;
  interactionState.contextCollapsed = true;
}

function renderSessionBehavior() {
  const panel = element('session-panel');
  const visible = isInteractiveSkill()
    && (interactionState.products.length > 0
      || interactionState.history.length > 0
      || interactionState.priors.length > 0);
  panel.hidden = !visible;
  syncContextCollapse();
  if (!visible) {
    element('session-content').innerHTML = '';
    return;
  }

  const committedClicks = interactionState.history.filter(event => event.clicked).length;
  const currentExposures = interactionState.products.length;
  const currentClicks = interactionState.selected.size;
  const screens = new Map();
  interactionState.history.forEach(event => {
    const screen = event.screenNumber ?? 1;
    const summary = screens.get(screen) ?? { exposures: 0, clicks: 0 };
    summary.exposures += 1;
    summary.clicks += event.clicked ? 1 : 0;
    screens.set(screen, summary);
  });
  const screenRows = Array.from(screens.entries())
    .sort((left, right) => right[0] - left[0])
    .map(([screen, summary]) => `<li>第 ${screen} 屏：曝光 ${summary.exposures} · 点击 ${summary.clicks} · 未点击 ${summary.exposures - summary.clicks}</li>`);
  if (currentExposures) {
    screenRows.unshift(`<li>第 ${interactionState.screenNumber} 屏（当前）：曝光 ${currentExposures} · 点击 ${currentClicks} · 待提交未点击 ${currentExposures - currentClicks}</li>`);
  }
  const liveEvents = [
    ...Array.from(interactionState.selected.values()).map(product => ({
      kind: '点击',
      screen: interactionState.screenNumber,
      name: product.name,
      skuId: product.skuId,
    })),
    ...interactionState.products
      .filter(product => !interactionState.selected.has(product.skuId))
      .map(product => ({
        kind: '曝光未点击',
        screen: interactionState.screenNumber,
        name: product.name,
        skuId: product.skuId,
      })),
    ...interactionState.history.slice().reverse().map(event => ({
      kind: event.clicked ? '点击' : '曝光未点击',
      screen: event.screenNumber ?? 1,
      name: event.product?.name,
      skuId: event.product?.skuId,
    })),
  ].slice(0, 16);
  const liveMarkup = liveEvents.length
    ? `<h3>本会话实时记录</h3><ul class="behavior-list session-live-list">${liveEvents.map(event => `<li><em>${escape(event.kind)}</em> 第 ${event.screen} 屏 · ${escape(event.name || event.skuId)}${event.skuId ? ` · ${escape(event.skuId)}` : ''}</li>`).join('')}</ul>`
    : '<p class="field-hint">本会话还没有点击记录；曝光未点击会在翻页后成为已提交短期证据。</p>';
  element('session-content').innerHTML = `<div class="session-stats"><span>已提交曝光<strong>${interactionState.history.length}</strong></span><span>已提交点击<strong>${committedClicks}</strong></span><span>当前屏点击<strong>${currentClicks}</strong></span></div><ul class="behavior-list session-screen-list">${screenRows.join('')}</ul>${liveMarkup}<p class="session-note">仅保存在当前页面会话，不写入真实 AI Memory。</p>`;
}

function renderNeeds(needs) {
  element('needs-panel').hidden = !Array.isArray(needs);
  if (!Array.isArray(needs)) return;
  element('needs-content').innerHTML = needs.map(need => `<article class="need-card"><div class="need-top"><strong>${escape(need.title)}</strong><span>${confidence(need.confidence)} / 100 模型评估</span></div><div class="confidence-bar"><div style="width: ${confidence(need.confidence)}%"></div></div><p>${escape(need.priority)} · ${escape(need.evidence)}</p></article>`).join('') || '<p class="field-hint">暂无需求预测。</p>';
}

function productImageUrl(value) {
  const path = String(value ?? '').trim().replace(/^\/+/, '');
  return /^jfs\/[a-zA-Z0-9._/-]+$/.test(path) ? `https://img14.360buyimg.com/n2/${path}` : '';
}

function isInteractiveSkill() {
  return currentSkill()?.id === interactiveSkillId;
}

function placeContextGrid(phoneShelf) {
  const grid = element('user-context-grid');
  const host = element('user-context-host');
  const stage = element('products-panel')?.querySelector?.('.product-stage');
  if (!grid || !host) return;
  const target = phoneShelf && stage ? stage : host;
  if (grid.parentElement !== target) target.append(grid);
}

function productValue(entry) {
  return entry?.product ?? entry;
}

function productSku(product) {
  const value = String(product?.skuId ?? product?.id ?? '');
  return /^[1-9][0-9]{5,19}$/.test(value) ? value : '';
}

function interestId(value) {
  const text = String(value ?? '').trim();
  return /^[1-9][0-9]*$/.test(text) ? text : '';
}

function normalizedInterests(values) {
  if (!Array.isArray(values)) return [];
  return values.map((prior, rank) => ({
    cid1Name: String(prior?.cid1Name ?? ''),
    cid2: interestId(prior?.cid2),
    cid2Name: String(prior?.cid2Name ?? ''),
    interest: Math.max(0, Math.min(100, Math.round(Number(prior?.interest)))),
    confidence: Math.max(0, Math.min(100, Math.round(Number(prior?.confidence)))),
    source: String(prior?.source ?? ''),
    brand: String(prior?.brand ?? ''),
    query: String(prior?.query ?? ''),
    rank,
  })).filter(prior => prior.cid2
    && Number.isFinite(prior.interest)
    && Number.isFinite(prior.confidence));
}

function compactInterest(prior) {
  return {
    cid2: Number(prior.cid2),
    cid2Name: prior.cid2Name,
    interest: prior.interest,
    confidence: prior.confidence,
    ...(prior.cid1Name ? { cid1Name: prior.cid1Name } : {}),
    ...(prior.brand ? { brand: prior.brand } : {}),
    ...(prior.query ? { query: prior.query } : {}),
  };
}

function interestConfidence(evidenceCount) {
  return Math.round((1 - Math.exp(-evidenceCount / 20)) * 100);
}

function interestRate(clicks, exposures) {
  const skips = Math.max(0, exposures - clicks);
  if (clicks === 0) return Math.max(0, 45 - skips * 18);
  return Math.max(0, Math.min(100, 70 + clicks * 8 - Math.max(0, skips - clicks) * 12));
}

function finalizedInterestEvidence(value) {
  return {
    clicks: value.clicks ?? ((value.committedClicks ?? 0) + (value.pendingClicks ?? 0)),
    exposures: value.exposures ?? ((value.committedExposures ?? 0) + (value.pendingExposures ?? value.pendingClicks ?? 0)),
  };
}

function currentInterestEvidence(value) {
  return {
    clicks: (value.scoreCommittedClicks ?? 0) + (value.scorePendingClicks ?? 0),
    exposures: (value.scoreCommittedExposures ?? 0) + (value.scorePendingExposures ?? value.pendingExposures ?? 0),
  };
}

function valueInterest(value) {
  const evidence = finalizedInterestEvidence(value);
  return interestRate(evidence.clicks, evidence.exposures);
}

function valueConfidence(value) {
  return interestConfidence(finalizedInterestEvidence(value).exposures);
}

function rankedInterestValues(values, limit = 3) {
  return Array.from(values.values())
    .sort((left, right) => valueInterest(right) - valueInterest(left)
      || finalizedInterestEvidence(right).clicks - finalizedInterestEvidence(left).clicks
      || finalizedInterestEvidence(right).exposures - finalizedInterestEvidence(left).exposures
      || right.lastSeen - left.lastSeen)
    .slice(0, limit)
    .map(value => ({
      ...value,
      interest: valueInterest(value),
      confidence: valueConfidence(value),
    }));
}

function recordInterestValue(values, key, label, eventIndex, clicked, pending) {
  if (!key) return;
  const current = values.get(key) ?? {
    label,
    exposures: 0,
    clicks: 0,
    committedExposures: 0,
    committedClicks: 0,
    pendingExposures: 0,
    pendingClicks: 0,
    lastSeen: eventIndex,
  };
  current.label = label || current.label;
  current.exposures += 1;
  current.clicks += clicked ? 1 : 0;
  current.committedExposures += pending ? 0 : 1;
  current.committedClicks += !pending && clicked ? 1 : 0;
  current.pendingExposures += pending ? 1 : 0;
  current.pendingClicks += pending && clicked ? 1 : 0;
  current.lastSeen = eventIndex;
  values.set(key, current);
}

function bypassScore(cid2) {
  return interactionState.bypassInterests.find(item => String(item.cid2) === String(cid2));
}

function localGroupInterest(group) {
  const evidence = currentInterestEvidence(group);
  return interestRate(evidence.clicks, evidence.exposures);
}

function localGroupConfidence(group) {
  return interestConfidence(currentInterestEvidence(group).exposures);
}

function groupInterest(group) {
  const bypass = bypassScore(group.cid2);
  if (bypass && Number.isFinite(Number(bypass.interest))) {
    return Math.max(0, Math.min(100, Math.round(Number(bypass.interest))));
  }
  return localGroupInterest(group);
}

function groupConfidence(group) {
  const bypass = bypassScore(group.cid2);
  if (bypass && Number.isFinite(Number(bypass.confidence))) {
    return Math.max(0, Math.min(100, Math.round(Number(bypass.confidence))));
  }
  return localGroupConfidence(group);
}

function compactBypassContext(profile) {
  if (!profile || typeof profile !== 'object') {
    return { profile: {}, historyBehaviors: [] };
  }
  const memories = (Array.isArray(profile.memories) ? profile.memories : [])
    .map(profileMemoryText)
    .map(text => String(text ?? '').trim())
    .filter(Boolean)
    .slice(0, 8);
  const interests = (Array.isArray(profile.interests) ? profile.interests : [])
    .map(item => String(item ?? '').trim())
    .filter(Boolean)
    .slice(0, 12);
  const historyBehaviors = (Array.isArray(profile.behaviors) ? profile.behaviors : [])
    .map(behaviorText)
    .map(text => String(text ?? '').trim())
    .filter(Boolean)
    .slice(0, 20);
  return {
    profile: {
      username: profile.username ?? '',
      segment: profile.segment ?? '',
      city: profile.city ?? '',
      budget: Number.isFinite(Number(profile.budget)) ? Number(profile.budget) : null,
      interests,
      memories,
    },
    historyBehaviors,
  };
}

function collectInterestEvents() {
  const committed = interactionState.history.map(event => ({
    skuId: event.product?.skuId ?? '',
    name: event.product?.name ?? '',
    brand: event.product?.brand ?? '',
    cid2: Number(event.product?.cid2) || 0,
    cid2Name: event.product?.cid2Name ?? '',
    cid1Name: event.product?.cid1Name ?? '',
    clicked: Boolean(event.clicked),
    pending: false,
    screenNumber: event.screenNumber ?? 1,
  }));
  const pending = interactionState.products.map(product => ({
    skuId: product.skuId ?? '',
    name: product.name ?? '',
    brand: product.brand ?? '',
    cid2: Number(product.cid2) || 0,
    cid2Name: product.cid2Name ?? '',
    cid1Name: product.cid1Name ?? '',
    clicked: interactionState.selected.has(product.skuId),
    pending: true,
    screenNumber: interactionState.screenNumber,
  }));
  return [...committed, ...pending].filter(event => event.cid2 > 0);
}

function scheduleBypassInterest() {
  if (!isInteractiveSkill()) return;
  clearTimeout(bypassTimer);
  bypassTimer = setTimeout(refreshBypassInterest, 400);
}

async function refreshBypassInterest() {
  if (!isInteractiveSkill()) return;
  const events = collectInterestEvents();
  if (!events.length && interactionState.bypassInterests.length === 0) return;
  const request = ++bypassRequest;
  interactionState.bypassStatus = 'loading';
  renderInterestPanel();
  try {
    const context = compactBypassContext(interactionState.lastProfile);
    const started = performance.now();
    const data = await requestJson('/api/skills/session-interest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      timeoutMs: 45000,
      body: JSON.stringify({
        previous: interactionState.bypassInterests,
        events,
        profile: context.profile,
        historyBehaviors: context.historyBehaviors,
      }),
    });
    if (request !== bypassRequest) return;
    interactionState.bypassDurationMs = Math.round(performance.now() - started);
    interactionState.bypassInterests = Array.isArray(data?.interests) ? data.interests : [];
    interactionState.bypassSummary = String(data?.summary ?? '');
    interactionState.bypassStatus = '';
  } catch (error) {
    if (request !== bypassRequest) return;
    interactionState.bypassStatus = 'error';
    interactionState.bypassSummary = String(error?.message ?? '旁路模型判别失败');
  }
  renderInterestPanel();
}

function renderInterestPanel() {
  const panel = element('interest-panel');
  const visible = isInteractiveSkill()
    && (interactionState.products.length > 0
      || interactionState.history.length > 0
      || interactionState.priors.length > 0);
  panel.hidden = !visible;
  if (!visible) return;

  const committed = interactionState.history.map(event => ({ ...event, pending: false }));
  const pending = interactionState.products.map(product => ({
    product,
    clicked: interactionState.selected.has(product.skuId),
    pending: true,
    screenNumber: interactionState.screenNumber,
  }));
  const events = [...committed, ...pending];
  if (!events.length && !interactionState.priors.length) {
    element('interest-summary').textContent = '尚未产生模拟曝光证据。';
    element('interest-content').innerHTML = '<p class="interest-empty">商品曝光后，这里会展示点击与曝光未点击的变化。</p>';
    return;
  }

  const cid2Groups = new Map();
  events.forEach(({ product, clicked, pending: isPending, screenNumber }, eventIndex) => {
    const cid2 = interestId(product.cid2);
    const cid2Name = String(product.cid2Name ?? '').trim();
    const rawCategory = String(product.category ?? '').trim();
    const categoryName = cid2Name || (/^(模拟点击|曝光探索)\/相似商品$/.test(rawCategory) ? '' : rawCategory);
    const key = cid2 ? `cid2:${cid2}` : `category:${categoryName || 'unknown'}`;
    const group = cid2Groups.get(key) ?? {
      cid2,
      label: categoryName || (cid2 ? `cid2 ${cid2}` : '类目待识别'),
      exposures: 0,
      clicks: 0,
      committedExposures: 0,
      committedClicks: 0,
      pendingExposures: 0,
      pendingClicks: 0,
      scoreCommittedExposures: 0,
      scoreCommittedClicks: 0,
      scorePendingExposures: 0,
      scorePendingClicks: 0,
      prior: null,
      lastSeen: eventIndex,
      cid3Values: new Map(),
      brandValues: new Map(),
      seedValues: new Map(),
    };
    group.exposures += 1;
    group.clicks += clicked ? 1 : 0;
    group.committedExposures += isPending ? 0 : 1;
    group.committedClicks += !isPending && clicked ? 1 : 0;
    group.pendingExposures += isPending ? 1 : 0;
    group.pendingClicks += isPending && clicked ? 1 : 0;
    if (Number(screenNumber) > interactionState.interestBaselineScreen) {
      group.scoreCommittedExposures += isPending ? 0 : 1;
      group.scoreCommittedClicks += !isPending && clicked ? 1 : 0;
      group.scorePendingExposures += isPending ? 1 : 0;
      group.scorePendingClicks += isPending && clicked ? 1 : 0;
    }
    group.lastSeen = eventIndex;
    if (categoryName) group.label = categoryName;

    const cid3 = interestId(product.cid3);
    const cid3Name = String(product.cid3Name ?? '').trim();
    recordInterestValue(
      group.cid3Values,
      cid3 ? `cid3:${cid3}` : '',
      cid3Name || (cid3 ? `cid3 ${cid3}` : ''),
      eventIndex,
      clicked,
      isPending,
    );
    const brand = String(product.brand ?? '').trim();
    recordInterestValue(
      group.brandValues,
      brand ? `brand:${brand}` : '',
      brand,
      eventIndex,
      clicked,
      isPending,
    );
    const skuId = productSku(product);
    const productName = String(product.name ?? '').trim();
    recordInterestValue(
      group.seedValues,
      skuId ? `sku:${skuId}` : '',
      productName ? `${productName} · ${skuId}` : skuId,
      eventIndex,
      clicked,
      isPending,
    );
    cid2Groups.set(key, group);
  });

  interactionState.bypassInterests.forEach((item, index) => {
    const cid2 = interestId(item.cid2);
    if (!cid2) return;
    const key = `cid2:${cid2}`;
    const group = cid2Groups.get(key) ?? {
      cid2,
      label: item.cid2Name || `cid2 ${cid2}`,
      exposures: 0,
      clicks: 0,
      committedExposures: 0,
      committedClicks: 0,
      pendingExposures: 0,
      pendingClicks: 0,
      scoreCommittedExposures: 0,
      scoreCommittedClicks: 0,
      scorePendingExposures: 0,
      scorePendingClicks: 0,
      prior: null,
      lastSeen: -index - 1,
      cid3Values: new Map(),
      brandValues: new Map(),
      seedValues: new Map(),
    };
    if (item.cid2Name) group.label = item.cid2Name;
    cid2Groups.set(key, group);
  });

  const previousOrder = new Map(interactionState.bypassInterests.map((item, index) => [interestId(item.cid2), index]));
  const groups = Array.from(cid2Groups.values())
    .sort((left, right) => {
      const leftSeen = previousOrder.has(left.cid2) ? 0 : 1;
      const rightSeen = previousOrder.has(right.cid2) ? 0 : 1;
      return leftSeen - rightSeen
        || (groupInterest(right) ?? -1) - (groupInterest(left) ?? -1)
        || (previousOrder.get(left.cid2) ?? 99) - (previousOrder.get(right.cid2) ?? 99)
        || finalizedInterestEvidence(right).clicks - finalizedInterestEvidence(left).clicks
        || right.lastSeen - left.lastSeen;
    })
    .slice(0, 8);
  const clickCount = events.filter(event => event.clicked).length;
  const pendingClicks = pending.filter(event => event.clicked).length;
  const status = interactionState.bypassStatus === 'loading'
    ? '旁路模型判别中…'
    : interactionState.bypassStatus === 'error'
    ? '旁路模型判别失败，仍显示上次结果。'
    : (interactionState.bypassSummary || '旁路大模型结合画像、历史行为和本会话点击/曝光未点击独立判别，不写入推荐 Agent。');
  const bypassTiming = Number.isFinite(interactionState.bypassDurationMs)
    ? `旁路模型耗时 ${formatDuration(interactionState.bypassDurationMs)}。`
    : '';
  element('interest-summary').textContent = `${status} ${bypassTiming} 本会话曝光 ${events.length} 次：点击 ${clickCount} 次，曝光未点击 ${events.length - clickCount} 次。`.replace(/\s+/g, ' ').trim();
  element('interest-content').innerHTML = groups.map((group, index) => {
    const interest = groupInterest(group);
    const confidenceScore = groupConfidence(group);
    const cid3Text = rankedInterestValues(group.cid3Values)
      .map(value => `${escape(value.label)}：点击 ${value.clicks}/${value.exposures} · 兴趣 ${value.interest}% · 置信 ${value.confidence}%`).join('；') || '暂无';
    const brandText = rankedInterestValues(group.brandValues)
      .map(value => `${escape(value.label)}：点击 ${value.clicks}/${value.exposures} · 兴趣 ${value.interest}% · 置信 ${value.confidence}%`).join('；') || '暂无';
    const clickedSeedText = rankedInterestValues(group.seedValues)
      .filter(value => value.clicks > 0)
      .map(value => `${escape(value.label)}（点击 ${value.clicks} / 曝光 ${value.exposures}）`).join('；');
    const seedMarkup = clickedSeedText
      ? `<div><dt>种子证据</dt><dd>${clickedSeedText}</dd></div>`
      : '';
    const title = group.cid2 && !group.label.includes(group.cid2)
      ? `${escape(group.label)} · cid2 ${escape(group.cid2)}`
      : escape(group.label);
    const pendingBadge = group.pendingExposures
      ? `<span class="interest-pending">本屏曝光 +${group.pendingExposures} · 点击 +${group.pendingClicks}</span>`
      : '';
    const scoreKey = group.cid2 ? `cid2:${group.cid2}` : `label:${group.label}`;
    const previous = interestScoreMemory.get(scoreKey);
    const changed = previous != null && interest != null && previous !== interest;
    if (interest != null) interestScoreMemory.set(scoreKey, interest);
    const interestText = `${interest}%`;
    const confidenceText = `${confidenceScore}%`;
    const judged = Boolean(bypassScore(group.cid2));
    const sourceText = judged
      ? '旁路大模型 · 画像 + 历史行为 + 实时行为'
      : (interactionState.bypassStatus === 'loading'
        ? '本地公式 · 旁路大模型判别中'
        : '本地公式 · 旁路大模型未返回');
    return `<article class="interest-card${changed ? ' interest-changed' : ''}"><div class="interest-card-heading"><div><span class="interest-rank">TOP ${index + 1}</span><h3>${title}</h3></div>${pendingBadge}</div><div class="interest-metrics"><span>当前兴趣度<strong>${interestText}</strong></span><span>当前置信度<strong>${confidenceText}</strong></span></div><p class="interest-prior">${sourceText}</p><p class="interest-evidence">点击 ${group.clicks} · 曝光 ${group.exposures} · 曝光未点击 ${group.exposures - group.clicks}</p><div class="interest-progress" aria-label="兴趣度 ${interestText}"><span style="width:${interest}%"></span></div><dl class="interest-details"><div><dt>cid3 证据</dt><dd>${cid3Text}</dd></div><div><dt>品牌证据</dt><dd>${brandText}</dd></div>${seedMarkup}</dl></article>`;
  }).join('');
}

function updateInteractionControls() {
  const controls = element('interaction-controls');
  const enabled = isInteractiveSkill() && interactionState.products.length > 0;
  controls.hidden = !enabled;
  if (!enabled) return;
  element('interaction-screen').textContent = `第 ${interactionState.screenNumber} 屏`;
  const selected = Array.from(interactionState.selected.values());
  const exposures = interactionState.products.length;
  element('interaction-status').textContent = selected.length
    ? `已点击 ${selected.length} 个，展示更多将按点击商品做 i2i`
    : `本屏未点击 · 展示更多将探索新颖类目（曝光 ${exposures}）`;
  element('interaction-clicks').hidden = selected.length === 0;
  element('interaction-clicks').innerHTML = selected
    .map(product => `<span title="${escape(product.name)}">${escape(product.name)} · ${escape(product.skuId)}</span>`).join('');
  element('next-screen-button').hidden = true;
  element('next-screen-button').disabled = busy;
  const showMore = element('show-more-button');
  if (showMore) showMore.disabled = busy;
}

function clickedSkuIds() {
  const ids = new Set(interactionState.selected.keys());
  interactionState.history.forEach(event => {
    if (event.clicked && event.product?.skuId) ids.add(event.product.skuId);
  });
  return ids;
}

function syncInteractionButtons() {
  const clicked = clickedSkuIds();
  element('products-content').querySelectorAll?.('[data-simulated-click]').forEach(node => {
    const selected = clicked.has(node.dataset.simulatedClick);
    if (node.tagName === 'BUTTON' || node.matches?.('button')) {
      node.setAttribute('aria-pressed', String(selected));
      node.textContent = selected ? '✓ 已模拟点击' : '模拟点击';
    }
    const card = node.classList?.contains?.('product-card') ? node : node.closest?.('.product-card');
    card?.classList?.toggle?.('simulated-clicked', selected);
  });
}

function flashClickedPage(skuId) {
  const card = element('products-content').querySelector?.(`[data-sku-id="${skuId}"]`);
  if (!card?.classList?.add || !card.classList.remove) return;
  card.classList.remove('click-flash');
  void card.offsetWidth;
  card.classList.add('click-flash');
}

function toggleSimulatedClick(skuId) {
  if (!isInteractiveSkill() || busy || !skuId) return;
  const current = interactionState.products.find(item => item.skuId === skuId);
  if (current) {
    if (interactionState.selected.has(skuId)) {
      interactionState.selected.delete(skuId);
    } else {
      if (interactionState.selected.size >= 10) {
        showStatus('每一屏最多选择 10 个模拟点击商品。', true);
        return;
      }
      interactionState.selected.set(skuId, current);
      showStatus('已记录点击，继续下滑点「展示更多」将按该商品做 i2i。');
    }
  } else {
    const event = interactionState.history.find(item => item.product?.skuId === skuId);
    if (!event) return;
    event.clicked = !event.clicked;
    showStatus(event.clicked ? '已记录点击，展示更多将按该商品做 i2i。' : '');
  }
  flashClickedPage(skuId);
  syncInteractionButtons();
  updateInteractionControls();
  renderInterestPanel();
  renderSessionBehavior();
  scheduleBypassInterest();
}

function bindProductClicks(content) {
  content.querySelectorAll?.('[data-sku-id]').forEach(card => {
    card.addEventListener('click', event => {
      if (event.target.closest?.('#show-more-button')) return;
      event.preventDefault();
      toggleSimulatedClick(card.dataset.skuId);
    });
  });
}

function formatDuration(ms) {
  if (!Number.isFinite(Number(ms))) return '—';
  const value = Math.max(0, Number(ms));
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

function summarizeRound(run, screenNumber) {
  const traces = Array.isArray(run.traces) ? run.traces : [];
  const modelTurns = Array.isArray(run.modelTurns) ? run.modelTurns : [];
  const select = traces.findLast(trace => trace.tool === 'select_cid1');
  const recall = traces.findLast(trace => trace.tool === 'recall_cid2');
  const query = select?.arguments?.query || recall?.arguments?.query || recall?.output?.query || '';
  const cid1 = select?.arguments?.cid1_name || recall?.output?.cid1_name || '';
  const cid2 = recall?.arguments?.cid2_name || recall?.output?.cid2_name || '';
  const brand = recall?.arguments?.brand || '';
  const explore = Array.isArray(recall?.arguments?.explore) ? recall.arguments.explore : [];
  const mode = run.output?.interaction?.mode || '';
  let headline = '本屏召回';
  if (mode === 'CATEGORY_INTENT' || screenNumber === 1) {
    headline = query
      ? `模型决策 ${query}`
      : (cid2 && brand ? `模型决策 ${cid2}|${brand}` : (cid2 ? `模型决策 ${cid2}` : '首屏模型决策查询'));
    if (!query && cid1) headline = `${cid1} / ${headline}`;
  } else if (mode === 'CATEGORY_AND_CLICK_I2I') {
    const seeds = Array.isArray(run.output?.seedSkuIds) ? run.output.seedSkuIds.filter(Boolean) : [];
    headline = seeds.length ? `本屏点击 i2i · ${seeds.length} 个种子` : '本屏点击 i2i';
  } else if (mode === 'CATEGORY_NOVEL') {
    headline = query ? `相关探索 · ${query}` : '相关产品词探索';
  }
  const stages = Array.isArray(run.timing?.stages)
    ? run.timing.stages.filter(stage => Number(stage.durationMs) > 0).map(stage => ({
      label: stage.label || stage.id,
      durationMs: Number(stage.durationMs) || 0,
    }))
    : [];
  return {
    screenNumber,
    durationMs: Number(run.durationMs) || 0,
    modelCalls: Number(run.modelCalls) || 0,
    mode,
    headline,
    query,
    cid1,
    cid2,
    brand,
    explore,
    productCount: Array.isArray(run.output?.products) ? run.output.products.length : 0,
    seedSkuIds: Array.isArray(run.output?.seedSkuIds) ? run.output.seedSkuIds : [],
    modelTurns,
    traces,
    stages,
  };
}

function recordRound(run) {
  if (!isInteractiveSkill() || run?.status !== 'SUCCESS') return;
  const screenNumber = Number(run.output?.interaction?.screenNumber) || interactionState.screenNumber || 1;
  const round = summarizeRound(run, screenNumber);
  const index = interactionState.rounds.findIndex(item => item.screenNumber === screenNumber);
  if (index >= 0) interactionState.rounds[index] = round;
  else interactionState.rounds.push(round);
  const query = String(round.query || '').trim();
  if (query && !interactionState.usedQueries.includes(query)) {
    interactionState.usedQueries.push(query);
  }
}

function renderRoundHistory() {
  const rounds = interactionState.rounds;
  element('model-turns').innerHTML = rounds.map(round => {
    const turns = (round.modelTurns ?? []).map(turn => {
      const result = Array.isArray(turn.toolCalls) && turn.toolCalls.length
        ? formatJson(turn.toolCalls)
        : '本轮无 tool_calls';
      const status = turn.status === 'SUCCESS'
        ? (turn.toolCalls?.length ? '请求工具调用' : '生成最终分析')
        : '模型调用失败';
      return `<details open><summary>模型第 ${turn.round} 轮 · ${escape(turn.model || '')} · ${formatDuration(turn.durationMs)}<span>${status} · token ${turn.promptTokens ?? '—'} / ${turn.completionTokens ?? '—'}</span></summary><pre>${result}</pre></details>`;
    }).join('') || '<p class="field-hint">本屏没有模型调用，由 App 动作直接召回。</p>';
    const traces = (round.traces ?? []).map(trace => `<details><summary>${trace.status === 'SUCCESS' ? '✓' : '!'} ${escape(trace.name || trace.tool)} · ${formatDuration(trace.durationMs)}</summary>${trace.error ? `<p class="status error">${escape(trace.error)}</p>` : ''}<div class="trace-data"><div><h3>INPUT</h3><pre>${formatJson(trace.arguments)}</pre></div><div><h3>OUTPUT</h3><pre>${formatJson(trace.output)}</pre></div></div></details>`).join('');
    const stages = (round.stages ?? []).map(stage => `<span>${escape(stage.label)} ${formatDuration(stage.durationMs)}</span>`).join('');
    return `<article class="round-card"><header class="round-card-heading"><div><span>第 ${round.screenNumber} 屏</span><h3>${escape(round.headline)}</h3></div><strong>${formatDuration(round.durationMs)}</strong></header><p class="round-card-meta">模型 ${round.modelCalls} 轮 · 商品 ${round.productCount} 件${stages ? ` · ${stages}` : ''}</p>${turns}${traces}</article>`;
  }).join('') || '<p class="field-hint">运行后这里会按屏展示模型结果和耗时。</p>';
  element('traces').innerHTML = rounds.flatMap(round => round.traces ?? []).map(trace => `<details><summary>${trace.status === 'SUCCESS' ? '✓' : '!'} ${escape(trace.name)} · ${escape(trace.tool)}<span>${escape(trace.status)} / ${formatDuration(trace.durationMs)}</span></summary>${trace.error ? `<p class="status error">${escape(trace.error)}</p>` : ''}<div class="trace-data"><div><h3>INPUT / 调用参数</h3><pre>${formatJson(trace.arguments)}</pre></div><div><h3>OUTPUT / 返回结果</h3><pre>${formatJson(trace.output)}</pre></div></div></details>`).join('');
}

function phoneRoundBanner(screenNumber, extraClass = '') {
  const round = interactionState.rounds.find(item => item.screenNumber === screenNumber);
  const title = round?.headline || `第 ${screenNumber} 屏`;
  const duration = round ? formatDuration(round.durationMs) : '';
  const cls = extraClass ? `phone-round ${extraClass}` : 'phone-round';
  return `<div class="${cls}"><span>第 ${screenNumber} 屏</span><strong>${escape(title)}</strong>${duration ? `<em>${escape(duration)}</em>` : ''}</div>`;
}

function currentPhoneRoundBanner() {
  const screen = Number(interactionState.screenNumber) || interactionState.rounds.at(-1)?.screenNumber || 1;
  return phoneRoundBanner(screen, 'phone-round-current');
}

function rememberGallery(cards) {
  const stamped = cards.map(card => ({ ...card, screenNumber: interactionState.screenNumber }));
  if (interactionState.screenNumber <= 1) {
    interactionState.gallery = stamped;
    return interactionState.gallery;
  }
  const seen = new Set(interactionState.gallery.map(item => item.skuId).filter(Boolean));
  for (const card of stamped) {
    if (card.skuId && seen.has(card.skuId)) continue;
    interactionState.gallery.push(card);
    if (card.skuId) seen.add(card.skuId);
  }
  return interactionState.gallery;
}

function captureShelfView() {
  const screen = element('products-content').querySelector?.('.iphone-screen');
  const panel = element('products-panel');
  return {
    phoneScrollTop: Number(screen?.scrollTop) || 0,
    panelTop: typeof panel.getBoundingClientRect === 'function' ? panel.getBoundingClientRect().top : null,
    galleryCount: interactionState.gallery.length,
  };
}

function restoreShelfView(snapshot) {
  const screen = element('products-content').querySelector?.('.iphone-screen');
  if (screen) {
    const cards = screen.querySelectorAll?.('.product-card');
    const firstNew = snapshot.galleryCount > 0 ? cards?.[snapshot.galleryCount] : cards?.[0];
    if (firstNew && typeof firstNew.getBoundingClientRect === 'function') {
      screen.scrollTop += firstNew.getBoundingClientRect().top - screen.getBoundingClientRect().top;
    } else {
      screen.scrollTop = snapshot.phoneScrollTop;
    }
  }
  const panel = element('products-panel');
  if (snapshot.panelTop != null
      && typeof panel.getBoundingClientRect === 'function'
      && typeof window.scrollBy === 'function') {
    window.scrollBy(0, panel.getBoundingClientRect().top - snapshot.panelTop);
  }
}

function renderProducts(products, comparisonSummary, sources = [], coverage = null, emptyReason = '') {
  element('products-panel').hidden = !Array.isArray(products);
  if (!Array.isArray(products)) {
    element('products-panel').classList.remove('phone-shelf');
    placeContextGrid(false);
    interactionState.products = [];
    updateInteractionControls();
    renderInterestPanel();
    renderSessionBehavior();
    return;
  }
  element('product-count').textContent = products.length;
  const coverageText = coverage && ['indexedProducts', 'eligibleProducts', 'matchedProducts', 'returnedProducts'].every(field => Number.isSafeInteger(coverage[field]) && coverage[field] >= 0)
    ? `检索范围：索引 ${coverage.indexedProducts} 件，满足预算与快照条件 ${coverage.eligibleProducts} 件，相关候选 ${coverage.matchedProducts} 件，交给模型初选 ${coverage.returnedProducts} 件。` : '';
  element('comparison-summary').textContent = [coverageText, comparisonSummary].filter(Boolean).join('\n');
  element('comparison-summary').hidden = !comparisonSummary && !coverageText;
  const phoneShelf = isInteractiveSkill() && !comparisonSummary;
  element('products-panel').classList.toggle('phone-shelf', phoneShelf);
  element('products-content').classList.toggle('comparison-grid', Boolean(comparisonSummary));
  element('products-content').classList.toggle('phone-shelf', phoneShelf);
  placeContextGrid(phoneShelf);
  const cards = products.map(entry => {
    const product = productValue(entry);
    if (!product || typeof product !== 'object') return null;
    const hasScore = entry?.product && Number.isFinite(Number(entry.score));
    const scoreMarkup = hasScore ? `<span>匹配分 ${confidence(entry.score)}/100</span>` : '';
    const reasonMarkup = entry?.product && typeof entry.reason === 'string' && entry.reason.trim()
      ? `<div class="product-reason">✧ ${escape(entry.reason)}</div>` : '';
    const imageUrl = productImageUrl(product.image);
    const imageMarkup = imageUrl
      ? `<img class="product-image" data-product-image src="${escape(imageUrl)}" alt="${escape(product.name)}" loading="lazy" referrerpolicy="no-referrer">` : '';
    const skuId = productSku(product);
    const clickable = isInteractiveSkill() && skuId
      ? ` data-sku-id="${escape(skuId)}" data-simulated-click="${escape(skuId)}" role="button" tabindex="0"`
      : '';
    return {
      skuId,
      html: `<article class="product-card"${clickable}><div class="product-art"><small>${escape(product.category)}</small><span class="product-icon" aria-hidden="true">${escape(product.icon)}</span>${imageMarkup}</div><div class="product-info"><h3>${escape(product.name)}</h3><p>${escape(product.description)}</p><div class="price-row"><span class="price"><small>${product.priceType === 'PAGE_SNAPSHOT' ? '采集参考价 ¥' : '¥'}</small>${escape(product.price)}</span>${scoreMarkup}</div>${productSourceMarkup(product)}${reasonMarkup}${productComparisonMarkup(entry?.comparison, sources)}</div></article>`,
    };
  }).filter(card => card);
  const content = element('products-content');
  const emptyMarkup = `<p class="field-hint">${escape(emptyReason || "本次未生成商品推荐，请结合筛选结果补充需求后重试。")}</p>`;
  const visible = phoneShelf ? rememberGallery(cards) : cards;
  element('product-count').textContent = visible.length;
  if (phoneShelf) {
    let lastScreen = 0;
    const tiles = [];
    for (const card of visible) {
      const screen = Number(card.screenNumber) || 1;
      if (screen !== lastScreen) {
        lastScreen = screen;
        tiles.push(phoneRoundBanner(screen));
      }
      tiles.push(card.html);
    }
    const more = visible.length > 0
      ? `<div class="phone-more-bar"><button id="show-more-button" class="show-more" type="button">展示更多</button></div>`
      : '';
    const feed = tiles.length
      ? `<div class="phone-feed">${tiles.join('')}</div>${more}`
      : emptyMarkup;
    content.innerHTML = `<div class="iphone-stack">${currentPhoneRoundBanner()}<div class="iphone"><div class="iphone-island" aria-hidden="true"></div><div class="iphone-screen">${feed}</div><div class="iphone-home" aria-hidden="true"></div></div></div>`;
  } else {
    content.innerHTML = visible.map(card => card.html).join('') || emptyMarkup;
  }
  content.querySelectorAll?.('[data-product-image]').forEach(image => {
    image.addEventListener('error', () => image.remove(), { once: true });
  });
  content.querySelector?.('#show-more-button')?.addEventListener('click', loadNextScreen);
  bindProductClicks(content);
  interactionState.products = isInteractiveSkill()
    ? products.map(productValue).map(product => ({
      skuId: productSku(product),
      name: String(product?.name ?? productSku(product)),
      brand: String(product?.brand ?? ''),
      cid1: interestId(product?.cid1),
      cid2: interestId(product?.cid2),
      cid3: interestId(product?.cid3),
      cid1Name: String(product?.cid1Name ?? ''),
      cid2Name: String(product?.cid2Name ?? ''),
      cid3Name: String(product?.cid3Name ?? ''),
      category: String(product?.category ?? ''),
    })).filter(product => product.skuId)
    : [];
  syncInteractionButtons();
  updateInteractionControls();
  renderInterestPanel();
  renderSessionBehavior();
  if (phoneShelf) scheduleBypassInterest();
}

function applyInteractionOutput(run) {
  if (!isInteractiveSkill() || run?.status !== 'SUCCESS') return;
  const interaction = run.output?.interaction;
  const initialInterests = normalizedInterests(run.output?.initialInterests);
  if (initialInterests.length) {
    interactionState.initialInterests = initialInterests.map(interest => ({ ...interest }));
  }
  const currentInterests = normalizedInterests(run.output?.currentInterests);
  if (currentInterests.length) {
    interactionState.priors = currentInterests;
    interactionState.interestBaselineScreen = Number.isInteger(interaction?.interestBaselineScreen)
      ? interaction.interestBaselineScreen
      : Math.max(0, Number(interaction?.screenNumber ?? 1) - 1);
  } else if (initialInterests.length) {
    interactionState.priors = initialInterests;
    interactionState.interestBaselineScreen = 0;
  }
  const relatedCid2 = Array.isArray(run.output?.relatedCid2) ? run.output.relatedCid2 : [];
  if (relatedCid2.length && interactionState.relatedCid2.length === 0) {
    interactionState.relatedCid2 = relatedCid2.map(item => ({ ...item }));
  }
  if (!interaction || !Number.isInteger(interaction.screenNumber)) return;
  if (interaction.screenNumber > 1 && !interactionState.committedScreens.has(interaction.screenNumber)) {
    const clicked = new Set((interaction.simulatedClickSkuIds ?? []).map(String));
    const exposed = Array.isArray(interaction.exposedSkuIds) && interaction.exposedSkuIds.length
      ? interaction.exposedSkuIds.map(String)
      : interactionState.products.map(product => product.skuId);
    for (const skuId of exposed) {
      const product = interactionState.products.find(item => item.skuId === skuId)
        ?? { skuId, name: skuId };
      interactionState.history.push({
        product: { ...product },
        clicked: clicked.has(skuId),
        screenNumber: interaction.screenNumber - 1,
      });
    }
    interactionState.committedScreens.add(interaction.screenNumber);
  }
  interactionState.screenNumber = interaction.screenNumber;
  interactionState.selected.clear();
  interactionState.products = [];
  renderInterestPanel();
  renderSessionBehavior();
}

function renderRun(originalRun) {
  const run = displayValue(originalRun);
  applyInteractionOutput(run);
  recordRound(run);
  element('results').hidden = false;
  element('empty-state').hidden = true;
  element('result-user').textContent = run.output?.profile?.username ?? run.skillName;
  if (isInteractiveSkill() && interactionState.rounds.length) {
    const total = interactionState.rounds.reduce((sum, item) => sum + item.durationMs, 0);
    element('run-meta').textContent = `${interactionState.rounds.length} 屏 · 累计 ${formatDuration(total)} · RUN ${run.runId.slice(0, 8)}`;
  } else {
    element('run-meta').textContent = `${run.modelCalls} 轮模型交互 · ${(run.durationMs / 1000).toFixed(1)} s · RUN ${run.runId.slice(0, 8)}`;
  }
  element('summary-panel').hidden = !run.output?.summary;
  element('model-summary').textContent = run.output?.summary ?? '';
  element('run-state').textContent = run.status === 'SUCCESS' ? '执行完成' : '执行失败';
  element('run-state').className = run.status === 'SUCCESS' ? 'state-badge' : 'state-badge failed';
  renderWorkflow(run.traces);
  renderProfile(run.output?.profile);
  renderNeeds(run.output?.needs);
  renderProducts(run.output?.products, run.output?.comparisonSummary, run.output?.knowledgeSources, run.output?.searchCoverage, run.output?.emptyReason);
  const predictionMarkup = shoppingPredictionMarkup(run.output);
  element('prediction-panel').hidden = !predictionMarkup;
  element('prediction-panel').innerHTML = predictionMarkup;
  const travelMarkup = beijingTravelMarkup(run.output);
  element('travel-panel').hidden = !travelMarkup;
  element('travel-panel').innerHTML = travelMarkup;
  if (isInteractiveSkill()) {
    renderRoundHistory();
  } else {
    element('model-turns').innerHTML = (run.modelTurns ?? []).map(turn => `<details><summary>第 ${turn.round} 轮 · ${escape(turn.model || run.model)} · ${turn.status === 'SUCCESS' ? (turn.toolCalls.length ? '请求工具调用' : '生成最终分析') : '模型调用失败'}<span>${formatDuration(turn.durationMs)} · 输入 ${turn.promptTokens ?? '—'} / 输出 ${turn.completionTokens ?? '—'} tokens</span></summary><p class="field-hint">Response ID: ${escape(turn.responseId || '上游未返回')}</p><pre>${formatJson(turn.toolCalls)}</pre></details>`).join('');
    element('traces').innerHTML = run.traces.map(trace => `<details><summary>${trace.status === 'SUCCESS' ? '✓' : '!'} ${escape(trace.name)} · ${escape(trace.tool)}<span>${escape(trace.status)} / ${formatDuration(trace.durationMs)}</span></summary>${trace.error ? `<p class="status error">${escape(trace.error)}</p>` : ''}<div class="trace-data"><div><h3>INPUT / 调用参数</h3><pre>${formatJson(trace.arguments)}</pre></div><div><h3>OUTPUT / 返回结果</h3><pre>${formatJson(trace.output)}</pre></div></div></details>`).join('');
  }
  element('raw-output').textContent = JSON.stringify(run, null, 2);
}

function validFormInput(input) {
  if (Object.values(input).some(value => !value)) {
    showStatus('请填写所有必填输入，不能只输入空格。', true);
    return false;
  }
  const skillId = currentSkill().id;
  const shopping = supportsUserDataMode(skillId);
  const choices = shopping && input.userDataMode === 'DEMO' ? demoUsers : users;
  if (currentSkill().requiredInputs.includes('username') && !(shopping && input.userDataMode === 'REAL')
      && !choices.some(user => user.username === input.username)) {
    showStatus('请从列表中选择用户。', true);
    return false;
  }
  return true;
}

function nextScreenInput() {
  if (!isInteractiveSkill() || !interactionState.lastInput || interactionState.products.length === 0) return null;
  const input = { ...interactionState.lastInput };
  delete input.screenNumber;
  delete input.simulatedClickSkuIds;
  delete input.exposedSkuIds;
  delete input.initialInterests;
  delete input.currentInterests;
  delete input.relatedCid2;
  delete input.sessionEvents;
  delete input.usedQueries;
  input.screenNumber = interactionState.screenNumber + 1;
  input.simulatedClickSkuIds = Array.from(interactionState.selected.keys());
  input.exposedSkuIds = interactionState.products.map(product => product.skuId);
  const initialInterests = interactionState.initialInterests.length
    ? interactionState.initialInterests
    : interactionState.priors;
  input.initialInterests = initialInterests.map(prior => compactInterest(prior));
  input.currentInterests = initialInterests.map(prior => compactInterest(prior));
  if (interactionState.relatedCid2.length) {
    input.relatedCid2 = interactionState.relatedCid2.map(item => ({
      cid2: Number(item.cid2),
      cid2Name: item.cid2Name,
      ...(item.cid1Name ? { cid1Name: item.cid1Name } : {}),
    }));
  }
  const committed = interactionState.history.map(event => ({
    ...event.product,
    screenNumber: event.screenNumber,
    clicked: event.clicked,
  }));
  const current = interactionState.products.map(product => ({
    ...product,
    screenNumber: interactionState.screenNumber,
    clicked: interactionState.selected.has(product.skuId),
  }));
  if (interactionState.usedQueries.length) {
    input.usedQueries = interactionState.usedQueries.slice(-20);
  }
  input.sessionEvents = [...committed, ...current].slice(-100).map(event => ({
    screenNumber: event.screenNumber,
    skuId: event.skuId,
    name: event.name,
    brand: event.brand,
    cid2: event.cid2 ? Number(event.cid2) : undefined,
    cid3: event.cid3 ? Number(event.cid3) : undefined,
    cid2Name: event.cid2Name || undefined,
    cid3Name: event.cid3Name || undefined,
    category: event.category || undefined,
    clicked: event.clicked,
  }));
  return input;
}

async function executeSkill(input, { nextScreen = false } = {}) {
  const skillId = currentSkill().id;
  progressEvents = [];
  const shelf = nextScreen ? captureShelfView() : null;
  if (nextScreen) {
    const showMore = element('show-more-button');
    if (showMore) {
      showMore.disabled = true;
      showMore.textContent = '加载中…';
    }
  } else {
    element('progress-events').innerHTML = '';
    element('model-stream-content').textContent = '';
    element('model-stream-state').textContent = '等待模型返回内容';
    element('model-stream-panel').hidden = false;
    element('progress-count').textContent = '等待服务端事件';
    element('progress-current').textContent = '正在连接执行进度流…';
    element('progress-panel').hidden = false;
    element('results').hidden = true;
    element('empty-state').hidden = true;
    renderWorkflow();
  }
  setBusy(true);
  clearInterval(timingTimer);
  timing = new SkillTimingProgress(preflightEstimate?.skillId === skillId ? preflightEstimate : null);
  renderTiming();
  timingTimer = setInterval(renderTiming, 500);
  showStatus(nextScreen ? '正在加载更多商品…' : '请求正在发送，等待服务端确认开始执行…');
  let run;
  let failure;
  try {
    run = await requestSkillProgress(
      `api/skills/${encodeURIComponent(skillId)}/stream`,
      input,
      nextScreen
        ? event => {
          timing?.accept(event, performance.now());
          renderTiming();
          progressEvents.push(event);
        }
        : renderProgress,
      nextScreen ? () => {} : renderContent,
      {
      timeoutMs: clientTimeoutMs,
      onReconnect: state => {
        timing.reconnect(performance.now());
        renderTiming();
        const message = `连接中断，${state.delayMs / 1000} 秒后重连原会话（第 ${state.attempt}/${state.maxAttempts} 次），已完成工具不会重复执行…`;
        if (!nextScreen) element('progress-current').textContent = message;
        showStatus(message);
      }
    });
  } catch (error) {
    failure = error;
    run = error.run;
  } finally {
    clearInterval(timingTimer);
    timing.finish(run);
    renderTiming();
    setBusy(false);
    refreshEstimate();
  }
  if (run && !(failure && nextScreen)) renderRun(run);
  if (shelf && run && !failure) restoreShelfView(shelf);
  if (!failure && run?.status === 'SUCCESS' && isInteractiveSkill()) {
    interactionState.lastInput = { ...input };
  }
  if (failure) {
    element('model-stream-state').textContent = '未完成 · 已接收内容仅供预览，不是有效结果';
    if (!run) renderWorkflow(progressWorkflow(progressEvents, true));
    if (!nextScreen) element('empty-state').hidden = Boolean(run);
    element('run-state').textContent = '执行失败';
    element('run-state').className = 'state-badge failed';
    const message = skillFailureMessage(failure);
    console.warn('skill_stream_failed', failure.diagnostics ?? { code: 'REQUEST_FAILED' });
    element('progress-current').textContent = message;
    if (nextScreen) {
      const showMore = element('show-more-button');
      if (showMore) showMore.textContent = '展示更多';
      updateInteractionControls();
    }
    showStatus(message, true);
  } else {
    element('model-stream-panel').hidden = true;
    showStatus(nextScreen
      ? '已追加新商品，可继续往下看。'
      : `已完成 ${run.modelCalls} 轮模型交互、${run.traces.length} 次工具调用。以下是本次分析与推荐。`);
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !currentSkill()) return;
  const input = Object.fromEntries(Array.from(new FormData(form), ([name, value]) => [name, String(value).trim()]));
  if (!validFormInput(input)) return;
  if (isInteractiveSkill()) resetInteractionState();
  await executeSkill(input);
});

async function loadNextScreen() {
  if (busy) return;
  const input = nextScreenInput();
  if (!input) {
    showStatus('当前屏没有可提交的曝光商品。', true);
    return;
  }
  await executeSkill(input, { nextScreen: true });
}

element('next-screen-button').addEventListener('click', loadNextScreen);

select.addEventListener('change', selectSkill);
form.addEventListener('change', event => {
  if (busy) return;
  if (event.target.name === 'userDataMode') {
    shoppingUserMode = event.target.value;
    selectSkill();
    return;
  }
  if (event.target.name !== 'username' || busy) return;
  if (isInteractiveSkill()) resetInteractionState();
  if (supportsUserDataMode(currentSkill()?.id)) {
    if (shoppingUserMode === 'REAL') realUserUuid = event.target.value;
    else demoUsername = event.target.value;
    applyUser(form, element('user-preview'), shoppingUserMode === 'REAL'
      ? (realUserUuid.trim() ? { username: realUserUuid.trim(), source: 'AI_MEMORY' } : undefined)
      : demoUsers.find(user => user.username === demoUsername));
    resetRun();
    return;
  }
  selectedUsername = event.target.value;
  applyUser(form, element('user-preview'), users.find(user => user.username === selectedUsername));
  resetRun();
});
element('show-config').addEventListener('click', async () => {
  element('config-json').textContent = JSON.stringify(currentSkill(), null, 2);
  element('config-instructions').textContent = '正在读取 SKILL.md…';
  element('config-dialog').showModal();
  try {
    const instructions = await requestJson(`api/skills/${encodeURIComponent(currentSkill().id)}/instructions`);
    element('config-instructions').textContent = instructions.content;
  } catch (error) {
    element('config-instructions').textContent = `指令读取失败：${error.message}`;
  }
});

async function loadSkills() {
  element('retry-load').hidden = true;
  showStatus('');
  try {
    const load = path => requestJson(path, { cache: 'no-store', timeoutMs: 10000 })
      .catch(error => { throw new Error(`${path}：${error.message}`); });
    const [catalog, tools, runtime, availableUsers, availableDemoUsers] = await Promise.all([
      load('api/skills'), load('api/skills/tools'), load('api/skills/runtime'), load('api/skills/users'),
      load('api/skills/demo-users').catch(() => [])
    ]);
    if (!Array.isArray(availableUsers)) throw new Error('用户列表格式无效');
    users = availableUsers;
    const configuredTimeout = Number(runtime.clientTimeoutMs);
    clientTimeoutMs = Number.isInteger(configuredTimeout) && configuredTimeout >= 1000 && configuredTimeout <= 3690000
      ? configuredTimeout : 1290000;
    demoUsers = Array.isArray(availableDemoUsers) ? availableDemoUsers : [];
    if (!realUserUuid) realUserUuid = users[0]?.username ?? '';
    if (!demoUsers.some(user => user.username === demoUsername)) demoUsername = demoUsers[0]?.username ?? '';
    if (!users.some(user => user.username === selectedUsername)) selectedUsername = users[0]?.username ?? '';
    element('model-label').textContent = `当前模型：${runtime.model} · 原生 Tool Calling`;
    element('chat-model-label').textContent = runtime.model;
    skills = catalog;
    select.innerHTML = skills.map(skill => `<option value="${escape(skill.id)}">${escape(skill.name)}</option>`).join('');
    element('tool-count').textContent = `Skill 加载器 + ${tools.length} 个数据工具`;
    element('show-config').disabled = !skills.length;
    setBusy(false);
    if (skills.length) selectSkill();
    else { select.innerHTML = '<option>暂无启用的 Skill</option>'; showStatus('请在本地配置中启用至少一个 Skill，并重启服务。'); }
  } catch (error) {
    skills = [];
    users = [];
    element('input-fields').innerHTML = '';
    applyUser(form, element('user-preview'), undefined);
    element('show-config').disabled = true;
    setBusy(false);
    select.innerHTML = '<option>配置加载失败</option>';
    element('model-label').textContent = '配置加载失败';
    element('skill-estimate').textContent = '配置恢复后再读取预计耗时';
    element('tool-count').textContent = '工具连接不可用';
    element('retry-load').hidden = false;
    showStatus(`无法加载 Skill：${error.message} 请通过 Java 服务访问本页面。`, true);
  } finally {
    window.dispatchEvent(new Event('skill-page-initialized'));
  }
}

element('retry-load').addEventListener('click', loadSkills);
loadSkills();
