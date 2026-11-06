import assert from 'node:assert/strict';
import { readSkillProgress } from '../ai-rec-odin-demo-main/src/main/resources/static/skill-progress.mjs';
import { displayValue } from '../ai-rec-odin-demo-main/src/main/resources/static/skill-utils.mjs';

const argumentsList = process.argv.slice(2);
const base = (argumentsList.find(value => value.startsWith('http')) ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const live = argumentsList.includes('--live');
const streaming = argumentsList.includes('--stream');
const demo = argumentsList.includes('--demo');
const skillId = argumentsList.includes('--travel') ? 'beijing-weather-travel'
  : argumentsList.includes('--prediction') ? 'shopping-behavior-prediction'
  : argumentsList.includes('--insight') ? 'user-insight' : 'shopping-recommendation';
assert.ok(!demo || skillId === 'shopping-recommendation', '--demo currently supports the shopping recommendation smoke');

const catalog = await (await fetch(`${base}/api/skills`)).json();
assert.equal(catalog.code, 0);
assert.ok(catalog.data.some(skill => skill.id === skillId));
const users = await (await fetch(`${base}/api/skills/${demo ? 'demo-users' : 'users'}`)).json();
assert.equal(users.code, 0);
assert.ok(users.data.length > 0);
assert.equal(new Set(users.data.map(user => user.username)).size, users.data.length);

const requestedUsername = argumentsList.find(value => value.startsWith('--username='))?.slice('--username='.length);
const selectedUser = requestedUsername ? (demo ? users.data.find(user => user.username === requestedUsername) : { username: requestedUsername }) : users.data[0];
assert.ok(selectedUser, 'Choose a known demo username or a real AI Memory UUID');
const runtime = await (await fetch(`${base}/api/skills/runtime`)).json();
assert.equal(runtime.data.mode, 'LIVE_MODEL_MIXED_DATA');
assert.ok(runtime.data.model);
assert.ok(!JSON.stringify(runtime).includes('api-key'));
const instructions = await (await fetch(`${base}/api/skills/${skillId}/instructions`)).json();
assert.ok(instructions.data.content.startsWith('---'));

for (const [path, body, expected] of [
  [skillId, '{"input":{"username":" "}}', 400],
  [skillId, '{"input":{"username":null}}', 400],
  [skillId, '{', 400],
  [skillId, JSON.stringify({input: {username: 'u'.repeat(65)}}), 400],
  ['missing', '{"input":{"username":"xiaoming"}}', 404]
]) {
  const response = await fetch(`${base}/api/skills/${path}/${streaming ? 'stream' : 'run'}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body
  });
  assert.equal(response.status, expected);
  assert.equal((await response.json()).code, expected);
}

for (const [file, type] of [['skills.html', 'text/html'], ['workspace.js', 'javascript'], ['workspace.css', 'text/css'], ['chat.js', 'javascript'], ['chat.css', 'text/css'], ['skills.css', 'text/css'], ['skills.js', 'javascript'], ['skill-utils.mjs', 'javascript'], ['skill-users.mjs', 'javascript'], ['skill-progress.mjs', 'javascript'], ['chat-stream.js', 'javascript'], ['shopping-prediction.mjs', 'javascript'], ['beijing-travel.mjs', 'javascript']]) {
  const response = await fetch(`${base}/${file}`);
  assert.equal(response.status, 200);
  assert.ok(response.headers.get('content-type')?.includes(type));
  const content = await response.text();
  assert.ok(content.length > 100);
  if (file === 'skills.html') assert.doesNotMatch(content, /mock|模拟商品|演示价格|预置样本/i);
}
for (const path of ['/rag.html', '/api/rag/status', '/chat/base', '/agent', '/order']) {
  assert.equal((await fetch(base + path)).status, 404, path);
}
const home = await fetch(base + '/', { redirect: 'manual' });
assert.equal(home.status, 302);
assert.equal(new URL(home.headers.get('location'), base).href, `${base}/skills.html`);
console.log(`HTTP smoke passed. Configured model: ${runtime.data.model}.`);

if (live) {
  console.log(`Calling the configured model for ${skillId}; this consumes real model tokens.`);
  const start = performance.now();
  const updates = [];
  const response = await fetch(`${base}/api/skills/${skillId}/${streaming ? 'stream' : 'run'}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ input: { username: selectedUser.username, ...(skillId === 'beijing-weather-travel' ? { interests: (selectedUser.travelInterests ?? ['历史文化', '户外']).join('、') } : {}), ...(demo ? {userDataMode: 'DEMO'} : {}) } }), signal: AbortSignal.timeout(330000)
  });
  let run;
  if (streaming) {
    const deltas = [];
    let firstContentAt;
    run = await readSkillProgress(response, event => {
      if (event.type === 'MODEL_RETRYING') {
        for (let index = deltas.length - 1; index >= 0; index--) {
          if (deltas[index].modelRound === event.modelRound) deltas.splice(index, 1);
        }
      }
      updates.push(event);
      console.log(`[${((performance.now() - start) / 1000).toFixed(2)}s] ${event.type}: ${event.message}`);
    }, delta => {
      if (firstContentAt === undefined) {
        firstContentAt = performance.now() - start;
        console.log(`[${(firstContentAt / 1000).toFixed(2)}s] 首段模型内容已收到（第 ${delta.modelRound} 轮）`);
      }
      assert.ok(['MODEL_STARTED', 'MODEL_WAITING', 'MODEL_RETRYING'].includes(updates.at(-1).type));
      assert.equal(updates.at(-1).modelRound, delta.modelRound);
      deltas.push(delta);
    });
    assert.ok(deltas.length > 1, 'Expected multiple real model content chunks');
    const finalText = deltas.filter(delta => delta.modelRound === run.modelCalls).map(delta => delta.text).join('');
    assert.ok(finalText.includes(run.output.summary));
    assert.doesNotMatch(finalText, /mock|模拟资料|模拟数据|预置样本/i);
    console.log(`已接收 ${deltas.length} 段模型内容，最终结果在 ${((performance.now() - start) / 1000).toFixed(2)}s 收到`);
    assert.equal(updates[0].type, 'RUN_STARTED');
    assert.equal(updates.at(-1).type, 'RUN_COMPLETED');
    for (const trace of run.traces) {
      assert.deepEqual(updates.filter(event => event.toolCallId === trace.id).map(event => event.type), ['TOOL_STARTED', 'TOOL_COMPLETED']);
    }
    assert.equal(updates.filter(event => event.type === 'MODEL_COMPLETED').length, run.modelCalls);
  } else {
    const payload = await response.json();
    assert.equal(response.status, 200, JSON.stringify(payload));
    run = payload.data;
  }
  assert.equal(run.status, 'SUCCESS');
  assert.equal(run.mode, demo ? 'LIVE_MODEL_MIXED_DATA' : 'LIVE_MODEL_LIVE_DATA');
  assert.equal(run.model, runtime.data.model);
  assert.ok(run.modelCalls >= 3);
  assert.equal(run.modelCalls, run.modelTurns.length);
  assert.equal(run.traces[0].tool, 'Skill');
  assert.ok(run.output.summary.length > 0);
  assert.doesNotMatch(JSON.stringify(run.output), /mock|模拟资料|模拟数据|预置样本/i);
  assert.doesNotMatch(JSON.stringify(displayValue(run)), /mock|模拟资料|模拟数据|预置样本/i);
  if (skillId === 'shopping-behavior-prediction') {
    assert.ok(run.output.predictions.length > 0 && run.output.predictions.length <= 3);
    assert.ok(run.output.preferences.length > 0 && run.output.preferences.length <= 4);
    assert.ok(run.output.uncertainties.length > 0);
    for (const insight of [...run.output.predictions, ...run.output.preferences]) {
      assert.ok(Number.isInteger(insight.confidence) && insight.confidence >= 0 && insight.confidence <= 100);
      assert.ok(insight.evidence.length > 0);
      for (const evidence of insight.evidence) {
        const trace = run.traces.findLast(item => item.tool === evidence.source);
        assert.ok(trace);
        const value = evidence.path.slice(1).split('/').reduce((value, key) => value[key], trace.output);
        assert.deepEqual(evidence.value, value);
      }
    }
  } else if (skillId === 'beijing-weather-travel') {
    const weather = run.traces.findLast(trace => trace.tool === 'get_beijing_weather')?.output;
    const attractions = run.traces.findLast(trace => trace.tool === 'get_beijing_hot_attractions')?.output;
    assert.equal(weather?.source.dataMode, 'LIVE');
    assert.equal(attractions?.source.dataMode, 'LIVE');
    assert.equal(weather.forecast.length, 5);
    assert.ok(attractions.attractions.length > 0);
    assert.deepEqual(run.output.weather, weather);
    assert.deepEqual(run.output.attractions, attractions);
    assert.ok(run.output.recommendations.length > 0 && run.output.recommendations.length <= 4);
    for (const recommendation of run.output.recommendations) {
      assert.deepEqual(recommendation.attraction, attractions.attractions.find(item => item.id === recommendation.attractionId));
      assert.deepEqual(recommendation.weather, weather.forecast.find(day => day.date === recommendation.date));
    }
    assert.ok(run.output.travelTips.length > 0);
  } else {
    assert.ok(run.output.needs.length > 0);
  }
  assert.equal(run.output.profile.username, selectedUser.username);
  assert.deepEqual(run.output.profile.interests, selectedUser.interests);
  assert.deepEqual(run.output.profile.behaviors, selectedUser.behaviors);
  for (const trace of run.traces) {
    assert.equal(trace.status, 'SUCCESS');
    const round = run.modelTurns.find(turn => turn.round === trace.modelRound);
    assert.ok(round.toolCalls.some(call => call.id === trace.id && call.name === trace.tool));
  }
  if (skillId === 'shopping-recommendation') {
    const candidates = run.traces.filter(trace => trace.tool === 'search_products').flatMap(trace => trace.output.products);
    const knowledge = run.traces.filter(trace => trace.tool === 'retrieve_product_knowledge').flatMap(trace => trace.output.products);
    assert.ok(run.output.comparisonSummary);
    assert.ok(Array.isArray(run.output.knowledgeSources));
    assert.ok(run.output.products.length > 0 && run.output.products.length <= 4);
    for (const recommendation of run.output.products) {
      assert.ok(candidates.some(product => product.id === recommendation.product.id));
      if (run.output.profile.budget != null && recommendation.product.price != null) assert.ok(recommendation.product.price <= run.output.profile.budget);
      assert.ok(recommendation.reason.length > 0);
      const evidence = knowledge.filter(item => item.productId === recommendation.productId).flatMap(item => item.evidence);
      assert.ok(knowledge.some(item => item.productId === recommendation.productId));
      const comparison = recommendation.comparison;
      assert.ok(['SUPPORTED', 'PARTIAL_EVIDENCE', 'INSUFFICIENT_EVIDENCE'].includes(comparison.status));
      assert.match(recommendation.productId, /^[1-9][0-9]{5,19}$/);
      assert.equal(recommendation.product.sourceUrl, `https://item.jd.com/${recommendation.productId}.html`);
      assert.equal(recommendation.product.priceType, 'PAGE_SNAPSHOT');
      assert.notEqual(recommendation.product.availability, 'UNAVAILABLE');
      assert.ok(recommendation.product.capturedAt);
      assert.ok(comparison.fit && comparison.uncertainty);
      for (const field of ['strengths', 'limitations']) {
        assert.ok(Array.isArray(comparison[field]));
        if (comparison.status !== 'PARTIAL_EVIDENCE') assert.equal(comparison[field].length > 0, comparison.status === 'SUPPORTED');
        for (const claim of comparison[field]) {
          const source = evidence.find(item => item.id === claim.evidenceId);
          assert.ok(source?.text.includes(claim.quote));
          assert.equal(source.productId, recommendation.productId);
          assert.equal(source.sourceUrl, recommendation.product.sourceUrl);
          assert.ok(run.output.knowledgeSources.some(item => item.id === source.id));
        }
      }
    }
  } else {
    assert.equal(run.output.products, undefined);
    assert.ok(!run.traces.some(trace => trace.tool === 'search_products'));
  }
  console.log(JSON.stringify({ runId: run.runId, model: run.model, modelCalls: run.modelCalls,
    tools: run.traces.map(trace => trace.tool), responseIds: run.modelTurns.map(turn => turn.responseId),
    durationMs: run.durationMs, summary: run.output.summary,
    products: run.output.products, comparisonSummary: run.output.comparisonSummary,
    predictions: run.output.predictions, preferences: run.output.preferences, uncertainties: run.output.uncertainties,
    weather: run.output.weather, attractions: run.output.attractions, recommendations: run.output.recommendations }, null, 2));
} else {
  console.log('No model requests made. Add --live for real model calls, --stream for live progress; --insight, --prediction or --travel selects the skill. Use --username=<UUID> for AI Memory, or --demo --username=xiaoyu for a shopping demonstration.');
}
