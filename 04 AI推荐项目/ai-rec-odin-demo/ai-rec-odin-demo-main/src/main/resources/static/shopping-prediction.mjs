import { escapeHtml as escape, confidence } from './skill-utils.mjs?v=20261103-parallel-recall';

const actions = { browse: '继续浏览', compare: '对比筛选', favorite: '收藏', add_to_cart: '加购', purchase: '购买', repurchase: '复购' };

function renderEvidence(items) {
  return `<ul class="prediction-evidence">${items.map(item => {
    const value = Array.isArray(item.value) ? item.value.join('、') : String(item.value ?? '');
    const source = item.source === 'get_user_behaviors' ? '行为' : '画像';
    return `<li><span>${source}</span> ${escape(value)}<small>${escape(item.source)} ${escape(item.path)}</small></li>`;
  }).join('')}</ul>`;
}

export function shoppingPredictionMarkup(output) {
  if (!Array.isArray(output?.predictions) || !Array.isArray(output?.preferences) || !Array.isArray(output?.uncertainties)) return '';
  const predictions = output.predictions.map((prediction, index) => `<article class="panel prediction-card">
    <div class="section-heading"><span class="tag">${index + 1} · ${escape(actions[prediction.action] ?? prediction.action)}</span><span class="prediction-score">${confidence(prediction.confidence)} / 100 信心分</span></div>
    <h3>${escape(prediction.category)} · ${escape(prediction.description)}</h3>
    <p class="prediction-window">观察窗口：${escape(prediction.timeWindow)}</p>
    <p>${escape(prediction.reasoning)}</p>
    ${renderEvidence(prediction.evidence ?? [])}
    <p class="prediction-alternative"><strong>另一种可能</strong> ${escape(prediction.alternative)}</p>
  </article>`).join('') || '<p class="field-hint">现有证据不足，模型未给出具体动作预测。</p>';
  const preferences = output.preferences.map(preference => `<article class="panel preference-card">
    <div class="section-heading"><h3>${escape(preference.dimension)}</h3><span class="prediction-score">${confidence(preference.confidence)} / 100 信心分</span></div>
    <h4>${escape(preference.preference)}</h4><p>${escape(preference.reasoning)}</p>
    ${renderEvidence(preference.evidence ?? [])}
  </article>`).join('') || '<p class="field-hint">现有证据不足，模型未给出购物偏好结论。</p>';
  return `<div class="section-heading"><h2>下一步可能做什么</h2><span class="subtle">预测假设 · 不执行购物动作</span></div>
    <p class="field-hint">按模型判断的可能性排列；信心分不是购买概率，观察窗口不是承诺发生时间。</p>
    <div class="prediction-grid">${predictions}</div>
    <div class="section-heading"><h2>可能的购物偏好</h2><span class="subtle">结合画像与行为</span></div>
    <div class="preference-grid">${preferences}</div>
    <section class="panel prediction-limitations"><h2>哪些信息仍不确定</h2><ul>${output.uncertainties.map(item => `<li>${escape(item)}</li>`).join('')}</ul></section>`;
}
