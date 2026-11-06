import test from 'node:test';
import assert from 'node:assert/strict';
import { beijingTravelMarkup } from '../../main/resources/static/beijing-travel.mjs';

function fixture() {
  const weather = { date: '2026-09-30', condition: '晴', temperatureMinC: 15, temperatureMaxC: 24,
    precipitationProbabilityPercent: 10, precipitationMm: 0, windMaxKmh: 12 };
  const attraction = { id: '75595', name: '故宫博物院', rank: 1, description: '皇家宫殿群', location: '北京',
    hotScore: '10', rating: '4.9', reviewCountDisplay: '20.2w', url: 'https://m.ctrip.com/example' };
  return { interests: '历史、摄影', weather: { forecast: [weather], scope: '城区预报不是山区预报',
    source: { provider: 'Open-Meteo', url: 'https://api.open-meteo.com/v1/forecast', fetchedAt: '2026-09-29T04:00:00Z' } },
    attractions: { attractions: [attraction], popularityMeaning: '携程平台榜单，非实时客流',
      source: { provider: '携程', url: 'https://m.ctrip.com/ranking', fetchedAt: '2026-09-29T04:00:00Z', providerUpdatedAt: '2026-09-28T04:00:00Z' } },
    recommendations: [{ attractionId: '75595', date: weather.date, attraction, weather, interestReason: '喜欢历史',
      weatherReason: '晴天适合步行', precautions: '查询官方预约', alternative: '雨天改期' }], travelTips: ['出发前复查天气'] };
}

test('hides travel renderer for unrelated or failed output', () => {
  for (const output of [undefined, null, {}, { predictions: [] }, { recommendations: [] }]) {
    assert.equal(beijingTravelMarkup(output), '');
  }
});

test('renders forecast evidence, interest, ranking and source times', () => {
  const html = beijingTravelMarkup(fixture());
  for (const text of ['故宫博物院', '2026-09-30', '15~24 °C', '降水概率 10%', '12 km/h', '历史、摄影',
    '非实时客流', '榜单更新时间', '2026-09-29T04:00:00Z', 'Open-Meteo', '查询官方预约']) {
    assert.ok(html.includes(text), text);
  }
  assert.ok(html.includes('rel="noopener noreferrer"'));
  assert.doesNotMatch(html, /mock|模拟|预置|样本/i);
});

test('escapes external text and model generated content', () => {
  const data = fixture();
  data.recommendations[0].interestReason = '<script>alert(1)</script>';
  data.attractions.attractions[0].name = '<img src=x onerror=alert(1)>';
  data.travelTips = ['<iframe>'];
  data.interests = '<svg/onload=alert(1)>';
  const html = beijingTravelMarkup(data);
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img '));
  assert.ok(!html.includes('<iframe>'));
  assert.ok(!html.includes('<svg/'));
  assert.ok(html.includes('&lt;script&gt;'));
});

test('blocks unsafe, misleading and unexpected source links', () => {
  for (const url of ['javascript:alert(1)', 'http://m.ctrip.com', 'https://m.ctrip.com.evil.test',
    'https://evil.test', 'https://user:password@m.ctrip.com', 'https://m.ctrip.com:123', '/relative']) {
    const data = fixture();
    data.attractions.attractions[0].url = url;
    data.attractions.source.url = url;
    data.weather.source.url = url;
    assert.ok(!beijingTravelMarkup(data).includes('<a '), url);
  }
});

test('renders empty recommendation gracefully while retaining real evidence', () => {
  const data = fixture();
  data.recommendations = [];
  const html = beijingTravelMarkup(data);
  assert.ok(html.includes('建议调整安排'));
  assert.ok(html.includes('故宫博物院'));
  assert.ok(html.includes('北京未来天气'));
});

test('shows missing provider ratings honestly', () => {
  const data = fixture();
  data.attractions.attractions[0].rating = null;
  data.attractions.attractions[0].reviewCountDisplay = null;
  const html = beijingTravelMarkup(data);
  assert.ok(html.includes('评分 未提供'));
  assert.ok(html.includes('评论 未提供'));
});
