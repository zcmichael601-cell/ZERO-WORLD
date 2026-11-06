import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { progressMarkup, progressWorkflow } from '../../main/resources/static/skill-progress.mjs';
import { SkillTimingProgress, estimateDescription, formatTime, stageTimingRows } from '../../main/resources/static/skill-timing.mjs';
import { escapeHtml as escape } from '../../main/resources/static/skill-utils.mjs';
import { skillInputsMarkup, applyUser, supportsUserDataMode } from '../../main/resources/static/skill-users.mjs';

const source = (await readFile(new URL('../../main/resources/static/skills.js', import.meta.url), 'utf8'))
  .replace(/^import .*;\n/gm, '').replace(/\nloadSkills\(\);\s*$/, '');

function view(overrides = {}) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, {
        hidden: true, textContent: '', innerHTML: '', scrollTop: 0, scrollHeight: 100, clientHeight: 100,
        classList: { toggle() {} }, listeners: {}, addEventListener(type, handler) { this.listeners[type] = handler; },
        style: {}, dataset: {}, attributes: {},
        firstElementChild: { textContent: '' }, querySelectorAll() { return []; }, elements: { namedItem() { return null; } },
        setAttribute(name, value) { this.attributes[name] = value; },
        removeAttribute(name) { delete this.attributes[name]; },
        append(node) { assert.equal(node.nodeType, 3); this.textContent += node.textContent; }
      });
      return elements.get(id);
    },
    createTextNode(text) { return { nodeType: 3, textContent: text }; }
  };
  const context = vm.createContext({ document, progressMarkup, progressWorkflow, clearInterval, performance: { now: () => 1000 },
    SkillTimingProgress, estimateDescription, formatTime, stageTimingRows, escape, skillInputsMarkup, applyUser, supportsUserDataMode,
    AbortSignal, Event, window: { dispatchEvent() {} }, ...overrides });
  vm.runInContext(source, context);
  return { context, element: id => document.getElementById(id) };
}

test('empty recommendations show server selection reason without claiming retrieval returned nothing', () => {
  const { context, element } = view();
  context.renderProducts([], null, [], null, '已检索到6件候选商品，但模型未选出推荐。筛选说明：需求与候选不符');
  assert.match(element('products-content').innerHTML, /已检索到6件候选商品/);
  assert.doesNotMatch(element('products-content').innerHTML, /暂无匹配商品/);
  context.renderProducts([], null);
  assert.match(element('products-content').innerHTML, /本次未生成商品推荐/);
  assert.doesNotMatch(element('products-content').innerHTML, /目录.*暂无匹配/);
  context.renderProducts([], null, [], null, '<img src=x onerror=alert(1)>');
  assert.doesNotMatch(element('products-content').innerHTML, /<img/);
  assert.match(element('products-content').innerHTML, /&lt;img/);
});

test('renders model selected SKU with its original reason and score', () => {
  const { context, element } = view({ productSourceMarkup: () => '', productComparisonMarkup: () => '', confidence: value => value ?? 0 });
  const product = { id: '100017657298', category: '耳机', icon: '', name: '候选商品', description: '规格请核实', price: 99 };
  context.renderProducts([{ product, score: 82, reason: '模型理由', selectionSource: 'MODEL' }], null);
  assert.match(element('products-content').innerHTML, /匹配分 82\/100/);
  assert.match(element('products-content').innerHTML, /候选商品/);
  assert.match(element('products-content').innerHTML, /模型理由/);
  assert.doesNotMatch(element('products-content').innerHTML, /检索备选/);
});

test('renders direct Sniffer products without inventing model scores or reasons', () => {
  const { context, element } = view({
    productSourceMarkup: product => `<span>${product.source}</span>`,
    productComparisonMarkup: () => '',
  });
  context.renderProducts([{
    skuId: '100235204575',
    category: '电脑、办公/电脑整机/一体机',
    icon: '🛍️',
    name: 'Sniffer 商品',
    description: '测试品牌',
    price: 4799,
    source: 'SNIFFER',
    image: 'jfs/t1/453514/33/5518/test.jpg',
  }], null);
  const html = element('products-content').innerHTML;
  assert.match(html, /Sniffer 商品/);
  assert.match(html, /SNIFFER/);
  assert.match(html, /<img class="product-image"/);
  assert.match(html, /https:\/\/img14\.360buyimg\.com\/n2\/jfs\/t1\/453514\/33\/5518\/test\.jpg/);
  assert.doesNotMatch(html, /匹配分|product-reason|undefined/);
  context.renderProducts([{ name: '不可信图片', image: 'javascript:alert(1)', icon: '🛍️' }], null);
  assert.doesNotMatch(element('products-content').innerHTML, /<img|javascript:/);
});

test('category intent products keep simulated click state and build the next-screen request', () => {
  const { context, element } = view({
    productSourceMarkup: () => '',
    productComparisonMarkup: () => '',
  });
  vm.runInContext(`
    skills = [{ id: 'shopping-recommendation-jev-cid', requiredInputs: ['username'], tools: [] }];
    interactionState.lastInput = { username: '315c70c8f4a25681' };
  `, context);
  element('skill-select').value = 'shopping-recommendation-jev-cid';
  context.applyInteractionOutput({
    status: 'SUCCESS',
    output: {
      initialInterests: [{
        cid1Name: '电脑办公',
        cid2: 670,
        cid2Name: '电脑整机',
        interest: 72,
        confidence: 46,
      }],
      relatedCid2: [{
        cid1Name: '电脑办公',
        cid2: 670,
        cid2Name: '电脑整机',
      }, {
        cid1Name: '电脑办公',
        cid2: 671,
        cid2Name: '电脑配件',
      }],
      interaction: { screenNumber: 1, simulatedClickSkuIds: [], exposedSkuIds: [] },
    },
  });
  context.renderProducts([{
    skuId: '100235204575',
    category: '电脑办公/电脑整机',
    cid2: 670,
    cid2Name: '电脑整机',
    cid3: 31012,
    brand: '测试电脑品牌',
    icon: '🛍️',
    name: '一体机',
    description: '测试品牌',
    price: 4799,
  }, {
    skuId: '100154257817',
    category: '手机通讯/手机',
    cid2: 652,
    cid2Name: '手机',
    cid3: 655,
    brand: '测试手机品牌',
    icon: '📱',
    name: '测试手机',
    description: '另一类兴趣',
    price: 2999,
  }], null);

  assert.match(element('products-content').innerHTML, /data-simulated-click="100235204575"/);
  assert.equal(element('interaction-controls').hidden, false);
  assert.equal(element('interest-panel').hidden, false);
  assert.equal(element('session-panel').hidden, false);
  assert.match(element('session-content').innerHTML, /第 1 屏（当前）：曝光 2 · 点击 0 · 待提交未点击 2/);
  assert.match(element('interest-summary').textContent, /模型当前维护 1 个 cid2 兴趣；本会话曝光 2 次/);
  assert.equal((element('interest-content').innerHTML.match(/class="interest-card"/g) ?? []).length, 2);
  assert.match(element('interest-content').innerHTML, /TOP 1/);
  assert.match(element('interest-content').innerHTML, /电脑整机 · cid2 670/);
  assert.match(element('interest-content').innerHTML, /手机 · cid2 652/);
  assert.match(element('interest-content').innerHTML, /当前兴趣度<strong>72%/);
  assert.match(element('interest-content').innerHTML, /当前置信度<strong>46%/);
  assert.match(element('interest-content').innerHTML, /初始化预判：兴趣 72% · 置信 46%/);
  assert.doesNotMatch(element('interest-content').innerHTML, /种子证据/);
  assert.equal(element('next-screen-button').disabled, false);
  const noClickInput = JSON.parse(vm.runInContext('JSON.stringify(nextScreenInput())', context));
  const {
    initialInterests: noClickInterests,
    currentInterests: noClickCurrentInterests,
    relatedCid2: noClickRelated,
    sessionEvents: noClickEvents,
    ...noClickBase
  } = noClickInput;
  assert.deepEqual(noClickBase, {
    username: '315c70c8f4a25681',
    screenNumber: 2,
    simulatedClickSkuIds: [],
    exposedSkuIds: ['100235204575', '100154257817'],
  });
  assert.equal(noClickInterests.length, 1);
  assert.deepEqual(noClickCurrentInterests, noClickInterests);
  assert.deepEqual(noClickInterests[0], {
    cid1Name: '电脑办公',
    cid2: 670,
    cid2Name: '电脑整机',
    interest: 72,
    confidence: 46,
  });
  assert.deepEqual(noClickRelated, [{
    cid1Name: '电脑办公',
    cid2: 670,
    cid2Name: '电脑整机',
  }, {
    cid1Name: '电脑办公',
    cid2: 671,
    cid2Name: '电脑配件',
  }]);
  assert.deepEqual(noClickEvents, [{
    screenNumber: 1,
    skuId: '100235204575',
    name: '一体机',
    brand: '测试电脑品牌',
    cid2: 670,
    cid3: 31012,
    cid2Name: '电脑整机',
    category: '电脑办公/电脑整机',
    clicked: false,
  }, {
    screenNumber: 1,
    skuId: '100154257817',
    name: '测试手机',
    brand: '测试手机品牌',
    cid2: 652,
    cid3: 655,
    cid2Name: '手机',
    category: '手机通讯/手机',
    clicked: false,
  }]);

  context.toggleSimulatedClick('100235204575');
  assert.match(element('interaction-clicks').innerHTML, /一体机.*100235204575/);
  assert.match(element('session-content').innerHTML, /当前屏点击<strong>1/);
  assert.match(element('session-content').innerHTML, /最近点击种子/);
  assert.match(element('session-content').innerHTML, /一体机 · 100235204575/);
  assert.match(element('interest-content').innerHTML, /电脑整机 · cid2 670/);
  assert.match(element('interest-content').innerHTML, /cid3 31012：点击 1\/1 · 兴趣 100% · 置信 5%/);
  assert.match(element('interest-content').innerHTML, /测试电脑品牌：点击 1\/1 · 兴趣 100% · 置信 5%/);
  assert.match(element('interest-content').innerHTML, /种子证据/);
  assert.match(element('interest-content').innerHTML, /一体机 · 100235204575（点击 1 \/ 曝光 1）/);
  assert.match(element('interest-content').innerHTML, /当前兴趣度<strong>73%/);
  assert.match(element('interest-content').innerHTML, /当前置信度<strong>49%/);
  assert.match(element('interest-content').innerHTML, /本屏曝光 \+1 · 点击 \+1/);

  context.toggleSimulatedClick('100154257817');
  assert.match(element('interest-summary').textContent, /曝光 2 次：点击 2 次，曝光未点击 0 次/);
  assert.match(element('interest-content').innerHTML, /手机 · cid2 652/);
  context.toggleSimulatedClick('100154257817');
  assert.match(element('interest-content').innerHTML, /手机 · cid2 652/);
  assert.match(element('interest-summary').textContent, /曝光 2 次：点击 1 次，曝光未点击 1 次/);
  assert.match(element('interest-content').innerHTML, /点击 0 · 曝光 1 · 曝光未点击 1/);

  assert.equal(element('next-screen-button').disabled, false);
  const input = JSON.parse(vm.runInContext('JSON.stringify(nextScreenInput())', context));
  const { initialInterests, currentInterests, relatedCid2, sessionEvents, ...baseInput } = input;
  assert.deepEqual(baseInput, {
    username: '315c70c8f4a25681',
    screenNumber: 2,
    simulatedClickSkuIds: ['100235204575'],
    exposedSkuIds: ['100235204575', '100154257817'],
  });
  assert.equal(initialInterests.length, 1);
  assert.deepEqual(currentInterests, initialInterests);
  assert.equal(relatedCid2.length, 2);
  assert.equal(sessionEvents.length, 2);
  assert.equal(sessionEvents[0].clicked, true);
  assert.equal(sessionEvents[1].clicked, false);
  context.applyInteractionOutput({
    status: 'SUCCESS',
    output: {
      currentInterests: [{
        cid1Name: '电脑办公',
        cid2: 670,
        cid2Name: '电脑整机',
        interest: 80,
        confidence: 55,
        source: 'MODEL_SESSION_UPDATE',
      }],
      interaction: {
        screenNumber: 2,
        interestBaselineScreen: 1,
        simulatedClickSkuIds: ['100235204575'],
        exposedSkuIds: ['100235204575', '100154257817'],
      },
    },
  });
  assert.match(element('interest-summary').textContent, /已提交曝光 2 次，本屏待提交曝光 0 次/);
  assert.match(element('interest-content').innerHTML, /电脑整机 · cid2 670/);
  assert.match(element('interest-content').innerHTML, /手机 · cid2 652/);
  assert.equal((element('interest-content').innerHTML.match(/class="interest-card"/g) ?? []).length, 2);
  assert.match(element('interest-content').innerHTML, /模型本轮判断：兴趣 80% · 置信 55%/);
  assert.match(element('interest-content').innerHTML, /当前兴趣度<strong>80%/);
  assert.doesNotMatch(element('interest-content').innerHTML, /本屏曝光 \+/);
  assert.match(element('session-content').innerHTML, /已提交曝光<strong>2/);
  assert.match(element('session-content').innerHTML, /已提交点击<strong>1/);
  assert.match(element('session-content').innerHTML, /第 1 屏：曝光 2 · 点击 1 · 未点击 1/);
});

test('next screen appends new phone-shelf cards instead of replacing the gallery', () => {
  const { context, element } = view({
    productSourceMarkup: () => '',
    productComparisonMarkup: () => '',
  });
  vm.runInContext(`
    skills = [{ id: 'shopping-recommendation-jev-cid', requiredInputs: ['username'], tools: [] }];
    interactionState.lastInput = { username: '315c70c8f4a25681' };
  `, context);
  element('skill-select').value = 'shopping-recommendation-jev-cid';
  const card = (skuId, name) => ({
    skuId, name, icon: '🛍️', category: '数码/手机', cid2: 653, cid2Name: '手机', brand: '测试', price: 1,
  });
  context.applyInteractionOutput({
    status: 'SUCCESS',
    output: {
      initialInterests: [{ cid1Name: '数码', cid2: 653, cid2Name: '手机', interest: 70, confidence: 40 }],
      interaction: { screenNumber: 1, simulatedClickSkuIds: [], exposedSkuIds: [] },
    },
  });
  context.renderProducts([
    card('100000000001', '首屏一'), card('100000000002', '首屏二'),
    card('100000000003', '首屏三'), card('100000000004', '首屏四'),
    card('100000000005', '首屏五'), card('100000000006', '首屏六'),
    card('100000000007', '首屏七'), card('100000000008', '首屏八'),
  ], null);
  context.applyInteractionOutput({
    status: 'SUCCESS',
    output: {
      currentInterests: [{ cid1Name: '数码', cid2: 653, cid2Name: '手机', interest: 70, confidence: 40 }],
      interaction: {
        screenNumber: 2,
        simulatedClickSkuIds: [],
        exposedSkuIds: [
          '100000000001', '100000000002', '100000000003', '100000000004',
          '100000000005', '100000000006', '100000000007', '100000000008',
        ],
      },
    },
  });
  context.renderProducts([
    card('100000000009', '次屏一'), card('100000000010', '次屏二'),
    card('100000000011', '次屏三'), card('100000000012', '次屏四'),
    card('100000000013', '次屏五'), card('100000000014', '次屏六'),
    card('100000000015', '次屏七'), card('100000000016', '次屏八'),
  ], null);
  const html = element('products-content').innerHTML;
  assert.match(html, /首屏一/);
  assert.match(html, /次屏一/);
  assert.equal(vm.runInContext('interactionState.gallery.length', context), 16);
});

test('appends each content fragment immediately as text, never HTML', () => {
  const { context, element } = view();
  context.renderContent({ modelRound: 2, text: '{"summary":"你好 🌟 ' });
  assert.equal(element('model-stream-content').textContent, '{"summary":"你好 🌟 ');
  context.renderContent({ modelRound: 2, text: '<img src=x onerror=alert(1)>"}\n' });
  assert.equal(element('model-stream-content').textContent, '{"summary":"你好 🌟 <img src=x onerror=alert(1)>"}\n');
  assert.equal(element('model-stream-content').innerHTML, '');
  assert.match(element('model-stream-state').textContent, /第 2 轮 · 正在生成/);
});

test('citation repair clears the rejected draft and displays the next model round', () => {
  const { context, element } = view();
  context.renderContent({ modelRound: 6, text: '未通过引用校验的草稿' });
  context.renderProgress({ type: 'MODEL_RETRYING', status: 'RUNNING', modelRound: 6,
    message: '商品对比中有 1 处引用需核对，正在请模型修正（1/2）；已完成的工具结果保留，不重复检索', elapsedMs: 1000 });
  assert.equal(element('model-stream-content').textContent, '');
  assert.match(element('model-stream-state').textContent, /正在请模型修正/);
  context.renderProgress({ type: 'MODEL_STARTED', status: 'RUNNING', modelRound: 7, message: '重新生成', elapsedMs: 1100 });
  context.renderContent({ modelRound: 7, text: '修正后的答案' });
  assert.equal(element('model-stream-content').textContent, '修正后的答案');
  assert.match(element('model-stream-state').textContent, /第 7 轮/);
});

test('new rounds clear previous draft and validation never prematurely clears content', () => {
  const { context, element } = view();
  context.renderContent({ modelRound: 1, text: '上一轮内容' });
  context.renderProgress({ type: 'MODEL_STARTED', modelRound: 2, status: 'RUNNING', message: '正在分析' });
  assert.equal(element('model-stream-content').textContent, '');
  context.renderContent({ modelRound: 2, text: '{"summary":"新的结果"}' });
  context.renderProgress({ type: 'VALIDATION_STARTED', modelRound: 2, status: 'RUNNING', message: '正在校验' });
  assert.equal(element('model-stream-content').textContent, '{"summary":"新的结果"}');
  assert.match(element('model-stream-state').textContent, /正在校验/);
  context.resetRun();
  assert.equal(element('model-stream-panel').hidden, true);
  assert.equal(element('model-stream-content').textContent, '');
});

test('auto-scroll follows newest content without moving users reading earlier content', () => {
  const { context, element } = view();
  const output = element('model-stream-content');
  output.scrollHeight = 1000;
  output.clientHeight = 200;
  output.scrollTop = 0;
  context.renderContent({ modelRound: 1, text: '新增内容' });
  assert.equal(output.scrollTop, 0);
  output.scrollTop = 800;
  context.renderContent({ modelRound: 1, text: '继续生成' });
  assert.equal(output.scrollTop, 1000);
});

test('timing DOM follows real events, reconnects and confirmed final results', () => {
  const { context, element } = view();
  vm.runInContext('timing = new SkillTimingProgress()', context);
  context.renderTiming();
  assert.equal(element('timing-percent').textContent, '—');
  assert.equal(element('skill-progress-bar').attributes['aria-valuenow'], undefined);
  const estimate = { expectedMs: 10000, lowerMs: 6000, upperMs: 15000, sampleCount: 0,
    stages: [{ id: 'synthesis', label: '结果生成', expectedMs: 10000, upperMs: 15000, expectedCalls: 1 }] };
  context.renderProgress({ sequence: 1, runId: 'run', type: 'MODEL_STARTED', modelRound: 3, message: '正在分析',
    timing: { estimate, elapsedMs: 5000, stages: [{ id: 'synthesis', durationMs: 5000, activeElapsedMs: 5000, active: true, executions: 1 }] } });
  assert.equal(element('timing-percent').textContent, '50%');
  assert.equal(element('skill-progress-fill').style.width, '50%');
  assert.equal(element('skill-progress-bar').attributes['aria-valuenow'], '50');
  assert.equal(element('timing-elapsed').textContent, '5 秒');
  assert.match(element('timing-stages').innerHTML, /结果生成/);
  vm.runInContext('timing.reconnect(1000); renderTiming()', context);
  assert.equal(element('skill-progress-bar').dataset.state, 'RECONNECTING');
  assert.equal(element('timing-remaining').textContent, '—');
  vm.runInContext('timing.finish({status: "SUCCESS", durationMs: 11000}); renderTiming()', context);
  assert.equal(element('skill-progress-fill').style.width, '100%');
  assert.equal(element('skill-progress-bar').dataset.state, 'SUCCESS');
  assert.equal(element('timing-elapsed').textContent, '11 秒');
  assert.equal(element('timing-error').hidden, false);
  assert.match(element('timing-error').textContent, /多 1 秒/);
});

test('failed initialization identifies the failed endpoint and exposes retry rather than keeping loading labels', async () => {
  const dispatched = [];
  const { context, element } = view({
    window: { dispatchEvent(event) { dispatched.push(event.type); } },
    requestJson: async (path, options) => {
      assert.equal(options.timeoutMs, 10000);
      assert.equal(options.cache, 'no-store');
      if (path === 'api/skills/users') throw new Error('请求超时');
      return [];
    }
  });
  await context.loadSkills();
  assert.match(element('status').textContent, /api\/skills\/users.*请求超时/);
  assert.equal(element('retry-load').hidden, false);
  assert.equal(element('model-label').textContent, '配置加载失败');
  assert.match(element('skill-estimate').textContent, /配置恢复后/);
  assert.deepEqual(dispatched, ['skill-page-initialized']);
});

test('successful initialization loads selectable skills and completes independent bootstrap', async () => {
  const dispatched = [];
  const { context, element } = view({
    window: { dispatchEvent(event) { dispatched.push(event.type); } },
    requestJson: async path => {
      if (path === 'api/skills') return [{ id: 'test', name: '测试', description: 'test', requiredInputs: [], tools: [] }];
      if (path === 'api/skills/users') return [{ username: 'test' }];
      if (path === 'api/skills/runtime') return { model: 'configured-model', clientTimeoutMs: '2490000' };
      if (path.endsWith('/estimate')) return null;
      return [];
    }
  });
  element('skill-select').value = 'test';
  await context.loadSkills();
  assert.match(element('skill-select').innerHTML, /value="test"/);
  assert.match(element('model-label').textContent, /configured-model/);
  assert.equal(vm.runInContext('clientTimeoutMs', context), 2490000);
  assert.equal(element('retry-load').hidden, true);
  assert.deepEqual(dispatched, ['skill-page-initialized']);
  assert.equal(element('status').textContent, '');
});

test('real profile shows unknown fields and empty memory explicitly without fake numbers', () => {
  const { context, element } = view();
  context.renderProfile({ username: 'ctlihua', source: 'AI_MEMORY', budget: null, segment: null, city: null,
    interests: [], memories: [], behaviors: [] });
  const html = element('profile-content').innerHTML;
  const historyHtml = element('history-content').innerHTML;
  assert.match(html, /用户 UUID：ctlihua/);
  assert.match(html, /单件预算未提供/);
  assert.match(html, /未返回画像兴趣或长期记忆/);
  assert.match(historyHtml, /未返回行为记忆/);
  assert.equal(element('profile-panel').hidden, false);
  assert.equal(element('history-panel').hidden, false);
  assert.doesNotMatch(html, /¥null|undefined|800|上海/);
  context.renderProfile({
    username: 'ctlihua',
    source: 'AI_MEMORY',
    interests: ['共同偏好'],
    memories: [{ memory: '共同偏好' }, { memory: '家庭结构:家有幼儿' }, { memory: '<img src=x onerror=alert(1)>' }],
    behaviors: [{ action: 'click', text: '浏览耳机', skuId: '100235204575', count: 2 }],
  });
  const populatedProfileHtml = element('profile-content').innerHTML;
  assert.doesNotMatch(populatedProfileHtml, /<img/);
  assert.doesNotMatch(populatedProfileHtml, /长期记忆画像/);
  assert.equal(populatedProfileHtml.match(/共同偏好/g)?.length, 1);
  assert.match(populatedProfileHtml, /家庭结构:家有幼儿/);
  assert.match(element('history-content').innerHTML, /\[click\].*浏览耳机.*SKU 100235204575.*×2/);
});

for (const recommendationSkill of ['shopping-recommendation', 'shopping-recommendation-fast']) {
test(`${recommendationSkill} mode switches preserve manual UUID and demo selection without stale preview`, () => {
  const { context, element } = view({ requestJson: async () => null });
  vm.runInContext(`
    skills = [{ id: '${recommendationSkill}', requiredInputs: ['username'], tools: [] },
      { id: 'user-insight', requiredInputs: ['username'], tools: [] }];
    users = [{ username: 'ctlihua', displayName: 'ctlihua', source: 'AI_MEMORY' }];
    selectedUsername = 'ctlihua';
    demoUsers = [{ username: 'xiaoming', displayName: '小明', interests: ['跑步'], travelInterests: [], behaviors: ['浏览跑鞋'] },
      { username: 'xiaoyu', displayName: '小雨', interests: ['音频'], travelInterests: [], behaviors: ['浏览耳机'] }];
    demoUsername = 'xiaoming';
    realUserUuid = 'ctlihua';
  `, context);
  element('skill-select').value = recommendationSkill;
  context.selectSkill();
  const change = (name, value) => element('skill-form').listeners.change({ target: { name, value } });
  change('username', '315C70c8f4a25681');
  change('userDataMode', 'DEMO');
  assert.match(element('input-fields').innerHTML, /value="xiaoming" selected/);
  assert.match(element('user-preview').innerHTML, /浏览跑鞋/);
  change('username', 'xiaoyu');
  change('userDataMode', 'REAL');
  assert.match(element('input-fields').innerHTML, /value="315C70c8f4a25681"/);
  assert.doesNotMatch(element('user-preview').innerHTML, /浏览耳机|浏览跑鞋/);
  change('userDataMode', 'DEMO');
  assert.match(element('input-fields').innerHTML, /value="xiaoyu" selected/);
  element('skill-select').value = 'user-insight';
  context.selectSkill();
  assert.doesNotMatch(element('input-fields').innerHTML, /userDataMode|name="username"[^>]*value="Custom/);
  assert.match(element('input-fields').innerHTML, /value="ctlihua" selected/);
});
}

test('manual UUID remains available with no configured users and unavailable demo listing', async () => {
  const { context, element } = view({
    requestJson: async path => {
      if (path === 'api/skills') return [{ id: 'shopping-recommendation', requiredInputs: ['username'], tools: [] }];
      if (path === 'api/skills/runtime') return { model: 'configured-model' };
      if (path === 'api/skills/demo-users') throw new Error('暂时不可用');
      return [];
    }
  });
  element('skill-select').value = 'shopping-recommendation';
  await context.loadSkills();
  assert.match(element('input-fields').innerHTML, /<input[^>]+name="username"/);
  assert.equal(element('retry-load').hidden, true);
});

test('submits arbitrary real UUID with its mode but rejects unknown demo accounts', async () => {
  for (const mode of ['REAL', 'DEMO']) {
    const submitted = [];
    const { context, element } = view({
      setInterval,
      FormData: class { [Symbol.iterator]() { return [['username', ' 315c70c8f4a25681 '], ['userDataMode', mode]][Symbol.iterator](); } },
      requestJson: async () => null,
      requestSkillProgress: async (path, input, onProgress, onContent, options) => { submitted.push({ path, input, options }); throw new Error('test stop'); },
      skillFailureMessage: () => 'test stop'
    });
    vm.runInContext("skills = [{ id: 'shopping-recommendation', requiredInputs: ['username'], tools: [] }]; clientTimeoutMs = 2490000", context);
    element('skill-select').value = 'shopping-recommendation';
    await element('skill-form').listeners.submit({ preventDefault() {} });
    assert.equal(submitted.length, mode === 'REAL' ? 1 : 0);
    if (mode === 'REAL') {
      assert.equal(submitted[0].input.username, '315c70c8f4a25681');
      assert.equal(submitted[0].input.userDataMode, 'REAL');
      assert.equal(submitted[0].options.timeoutMs, 2490000);
    } else assert.match(element('status').textContent, /请从列表中选择用户/);
  }
});
