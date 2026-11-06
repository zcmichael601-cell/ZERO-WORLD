import test from 'node:test';
import assert from 'node:assert/strict';
import { shoppingPredictionMarkup } from '../../main/resources/static/shopping-prediction.mjs';

function fixture() {
  return {
    predictions: [{ action: 'compare', category: '跑鞋', description: '对比缓震跑鞋', timeWindow: '未来几天', confidence: 78,
      reasoning: '近期反复浏览', alternative: '可能继续观望', evidence: [{ source: 'get_user_behaviors', path: '/signals/0', value: '浏览跑鞋8次' }] }],
    preferences: [{ dimension: '兴趣', preference: '运动场景', confidence: 68, reasoning: '结合兴趣信号',
      evidence: [{ source: 'get_user_profile', path: '/interests', value: ['跑步', '户外'] }] }],
    uncertainties: ['缺少加购信息']
  };
}

test('renders next actions, preferences, evidence and uncertainty distinctly', () => {
  const html = shoppingPredictionMarkup(fixture());
  for (const text of ['对比筛选', '未来几天', '78 / 100 信心分', '不是购买概率', '可能继续观望', '跑步、户外', '/signals/0', '缺少加购信息']) {
    assert.ok(html.includes(text), text);
  }
});

test('escapes model output and tool evidence before rendering', () => {
  const output = fixture();
  const malicious = '<img src=x onerror="alert(1)">';
  Object.assign(output.predictions[0], { category: malicious, description: malicious, timeWindow: malicious,
    reasoning: malicious, alternative: malicious, action: malicious });
  Object.assign(output.preferences[0], { dimension: malicious, preference: malicious, reasoning: malicious });
  output.preferences[0].evidence[0].value = [malicious];
  output.predictions[0].evidence[0].path = malicious;
  output.uncertainties = [malicious];
  const html = shoppingPredictionMarkup(output);
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;img'));
});

test('does not show stale prediction panels for other skills or failed runs', () => {
  assert.equal(shoppingPredictionMarkup(undefined), '');
  assert.equal(shoppingPredictionMarkup({}), '');
  assert.equal(shoppingPredictionMarkup({ summary: '推荐', needs: [] }), '');
});

test('handles insufficient evidence without inventing predictions', () => {
  const html = shoppingPredictionMarkup({ predictions: [], preferences: [], uncertainties: ['数据不足'] });
  assert.ok(html.includes('未给出具体动作预测'));
  assert.ok(html.includes('未给出购物偏好结论'));
  assert.ok(html.includes('数据不足'));
});

test('bounds displayed confidence and rejects non-numeric display values', () => {
  const output = fixture();
  output.predictions[0].confidence = 300;
  output.preferences[0].confidence = '<script>';
  const html = shoppingPredictionMarkup(output);
  assert.ok(html.includes('100 / 100 信心分'));
  assert.ok(html.includes('0 / 100 信心分'));
  assert.ok(!html.includes('<script>'));
});
