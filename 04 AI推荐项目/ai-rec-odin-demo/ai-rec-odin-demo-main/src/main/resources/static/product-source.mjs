import { escapeHtml as escape } from './skill-utils.mjs?v=20261103-parallel-recall';

export function jdProductUrl(value, productId) {
  if (typeof value !== 'string' || !/^https:\/\/item\.jd\.com\/[1-9][0-9]{5,19}\.html$/.test(value)) return '';
  if (productId != null && value !== `https://item.jd.com/${productId}.html`) return '';
  return value;
}

export function productSourceMarkup(product) {
  const url = jdProductUrl(product.sourceUrl, product.id);
  if (!url) return '';
  const stock = product.availability === 'UNAVAILABLE' ? '采集时页面提示缺货' : product.availability === 'AVAILABLE'
    ? '采集时页面提示有货，下单前需核实' : '库存待核实';
  return `<div class="product-source"><a href="${escape(url)}" target="_blank" rel="noopener noreferrer">查看京东商品 ↗</a>
    <span>SKU ${escape(product.id)}</span><small>采集时间：${escape(product.capturedAt)}</small>
    <small>采集参考价，非实时报价；${stock}。成交价、优惠及配送以京东为准。</small></div>`;
}
