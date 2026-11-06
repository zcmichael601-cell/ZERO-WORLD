import { escapeHtml as escape } from './skill-utils.mjs?v=20261103-parallel-recall';

function sourceLink(url, label) {
  try {
    const parsed = new URL(url);
    if (parsed.protocol === 'https:' && ['api.open-meteo.com', 'm.ctrip.com'].includes(parsed.hostname)
        && !parsed.username && !parsed.password && !parsed.port) {
      return `<a href="${escape(parsed.href)}" target="_blank" rel="noopener noreferrer">${escape(label)} ↗</a>`;
    }
  } catch {}
  return escape(label);
}

function sourceMarkup(source) {
  return `<p class="travel-source">真实数据 · ${sourceLink(source?.url, source?.provider ?? '来源未知')}<br>抓取时间：${escape(source?.fetchedAt)}${source?.providerUpdatedAt ? `<br>榜单更新时间：${escape(source.providerUpdatedAt)}` : ''}</p>`;
}

function weatherFacts(day) {
  return `${escape(day.condition)} · ${escape(day.temperatureMinC)}~${escape(day.temperatureMaxC)} °C<br>降水概率 ${escape(day.precipitationProbabilityPercent)}% · 雨量 ${escape(day.precipitationMm)} mm<br>最大风速 ${escape(day.windMaxKmh)} km/h`;
}

export function beijingTravelMarkup(output) {
  if (!Array.isArray(output?.recommendations) || !Array.isArray(output?.weather?.forecast)
      || !Array.isArray(output?.attractions?.attractions)) return '';
  const weather = output.weather;
  const attractions = output.attractions;
  return `<section class="panel travel-weather"><div class="section-heading"><h2>北京未来天气</h2><span class="tag">真实外部调用</span></div>
    <p>本次兴趣：${escape(output.interests)}</p>
    <div class="travel-weather-grid">${weather.forecast.map(day => `<article class="travel-day"><h3>${escape(day.date)}</h3><p>${weatherFacts(day)}</p></article>`).join('')}</div>
    <p class="field-hint">${escape(weather.scope)}</p>${sourceMarkup(weather.source)}</section>
    <div class="section-heading"><h2>适合你的出游地点</h2><span class="subtle">模型建议 · 非预约结果</span></div>
    <div class="travel-grid">${output.recommendations.map(item => `<article class="panel travel-card">
      <div class="section-heading"><span class="tag">${escape(item.date)}</span><span class="subtle">携程榜单 #${escape(item.attraction?.rank)}</span></div>
      <h3>${sourceLink(item.attraction?.url, item.attraction?.name ?? '')}</h3>
      <p class="field-hint">${escape(item.attraction?.location)} · 热度 ${escape(item.attraction?.hotScore)} · 评分 ${escape(item.attraction?.rating ?? '未提供')}</p>
      <p>${escape(item.attraction?.description)}</p>
      <p><strong>兴趣匹配</strong> ${escape(item.interestReason)}</p>
      <p class="travel-weather-evidence">${weatherFacts(item.weather ?? {})}</p>
      <p><strong>天气依据</strong> ${escape(item.weatherReason)}</p>
      <p><strong>出行准备</strong> ${escape(item.precautions)}</p>
      <p><strong>备选安排</strong> ${escape(item.alternative)}</p></article>`).join('') || '<p class="field-hint">当前天气或候选景点不适合，建议调整安排。</p>'}</div>
    <section class="panel travel-ranking"><h2>本次热门景点候选</h2><p class="field-hint">${escape(attractions.popularityMeaning)}</p>
    <ol>${attractions.attractions.map(item => `<li><span>#${escape(item.rank)} ${sourceLink(item.url, item.name)}</span><small>平台热度 ${escape(item.hotScore)} · 评分 ${escape(item.rating ?? '未提供')} · 评论 ${escape(item.reviewCountDisplay ?? '未提供')}</small></li>`).join('')}</ol>
    ${sourceMarkup(attractions.source)}</section>
    <section class="panel prediction-limitations"><h2>出行提醒与限制</h2><ul>${(output.travelTips ?? []).map(tip => `<li>${escape(tip)}</li>`).join('')}</ul><p class="field-hint">兴趣以本次选择和输入为准。建议由模型生成，出发前请复查天气、景区开放和预约信息。</p></section>`;
}
