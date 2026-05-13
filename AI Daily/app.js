// ── State ──
let currentPlatform = 'all';
let currentEditId = null;

const PLATFORM_LABELS = {
  youtube:     { label: 'YouTube', icon: '▶' },
  x:           { label: 'X',       icon: '𝕏' },
  podcast:     { label: 'Podcast', icon: '🎙' },
  douyin:      { label: '抖音',    icon: '♪' },
  xiaohongshu: { label: '小红书',  icon: '✿' },
};

// ── Init ──
document.addEventListener('DOMContentLoaded', () => {
  setHeaderDate();
  renderCounts();
  renderCards('all');
  bindTabs();
});

function setHeaderDate() {
  const el = document.getElementById('header-date');
  const now = new Date();
  el.textContent = now.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
}

// ── Counts ──
function renderCounts() {
  document.getElementById('count-all').textContent = AI_DIGEST_DATA.length;
  ['youtube', 'x', 'podcast', 'douyin', 'xiaohongshu'].forEach(p => {
    const n = AI_DIGEST_DATA.filter(d => d.platform === p).length;
    document.getElementById(`count-${p}`).textContent = n;
  });
}

// ── Tabs ──
function bindTabs() {
  document.querySelectorAll('.tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentPlatform = btn.dataset.platform;
      renderCards(currentPlatform);
    });
  });
}

// ── Render Cards ──
function renderCards(platform) {
  const container = document.getElementById('cards-container');
  const emptyState = document.getElementById('empty-state');
  const items = platform === 'all'
    ? AI_DIGEST_DATA
    : AI_DIGEST_DATA.filter(d => d.platform === platform);

  if (items.length === 0) {
    container.innerHTML = '';
    emptyState.style.display = 'block';
    return;
  }
  emptyState.style.display = 'none';
  container.innerHTML = items.map((item, i) => buildCard(item, i)).join('');
}

// ── Build Card HTML ──
function buildCard(item, index) {
  const p = PLATFORM_LABELS[item.platform];
  const dt = formatDatetime(item.datetime);
  const thought = getThought(item.id);
  const hasThought = thought.trim().length > 0;

  const tagsHtml = item.tags.map(t => `<span class="tag">${t}</span>`).join('');
  const coreHtml = item.core_content.map(c => `<li>${c}</li>`).join('');
  const thoughtPreview = hasThought
    ? `<div class="thought-preview">${escHtml(thought)}</div>` : '';

  return `
  <article class="card" style="animation-delay:${index * 0.05}s" id="card-${item.id}">
    <div class="card-header">
      <div class="card-meta">
        <span class="platform-badge ${item.platform}">${p.icon} ${p.label}</span>
        <span class="card-datetime">${dt}</span>
      </div>
    </div>

    <h2 class="card-title">${escHtml(item.title)}</h2>

    <div class="card-tags">${tagsHtml}</div>

    <p class="section-label">摘要</p>
    <p class="card-summary">${escHtml(item.summary)}</p>

    <hr class="card-divider">

    <p class="section-label">核心内容</p>
    <ul class="core-list">${coreHtml}</ul>

    <div class="card-footer">
      <div class="card-links">
        <a class="link-btn" href="${item.url}" target="_blank" rel="noopener">
          查看内容 <span class="arrow">↗</span>
        </a>
        <a class="link-btn" href="${item.original_url}" target="_blank" rel="noopener">
          原文链接 <span class="arrow">↗</span>
        </a>
      </div>
      <button
        class="thought-btn ${hasThought ? 'has-thought' : ''}"
        onclick="openThoughtModal('${item.id}')"
      >
        ✏ ${hasThought ? '查看我的思考' : '写下我的思考'}
      </button>
    </div>

    ${thoughtPreview}
  </article>`;
}

// ── Datetime Format ──
function formatDatetime(iso) {
  const d = new Date(iso);
  const date = d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
  const time = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  return `${date} ${time}`;
}

// ── Escape HTML ──
function escHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ── Thoughts (localStorage) ──
function getThought(id) {
  return localStorage.getItem(`ai-digest-thought-${id}`) || '';
}
function saveThoughtData(id, text) {
  localStorage.setItem(`ai-digest-thought-${id}`, text);
}

function openThoughtModal(id) {
  currentEditId = id;
  const item = AI_DIGEST_DATA.find(d => d.id === id);
  document.getElementById('modal-item-title').textContent = item.title;
  document.getElementById('thought-input').value = getThought(id);
  document.getElementById('thought-modal').classList.add('open');
  setTimeout(() => document.getElementById('thought-input').focus(), 220);
}

function closeThoughtModal() {
  document.getElementById('thought-modal').classList.remove('open');
  currentEditId = null;
}

function closeModal(e) {
  if (e.target === document.getElementById('thought-modal')) closeThoughtModal();
}

function saveThought() {
  if (!currentEditId) return;
  const text = document.getElementById('thought-input').value.trim();
  saveThoughtData(currentEditId, text);
  closeThoughtModal();
  renderCards(currentPlatform);
  showToast('思考已保存 ✓');
}

// ── Toast ──
function showToast(msg) {
  let el = document.getElementById('toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast';
    el.className = 'toast';
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 2200);
}

// ── Export Links ──
function exportLinks() {
  const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\//g, '-');
  let md = `# AI 资讯原文链接 — ${today}\n\n`;

  const platforms = ['youtube', 'x', 'podcast', 'douyin', 'xiaohongshu'];
  platforms.forEach(p => {
    const items = AI_DIGEST_DATA.filter(d => d.platform === p);
    if (!items.length) return;
    md += `## ${PLATFORM_LABELS[p].label}\n\n`;
    items.forEach(item => {
      md += `- [${item.title}](${item.original_url})\n`;
    });
    md += '\n';
  });

  const blob = new Blob([md], { type: 'text/markdown' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `ai-links-${today}.md`;
  a.click();
  showToast('链接文档已导出 ↓');
}

// Keyboard shortcut: Escape to close modal
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeThoughtModal();
});
