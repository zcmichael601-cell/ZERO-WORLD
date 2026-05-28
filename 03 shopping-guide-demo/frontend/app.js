const BACKEND_URL = "http://localhost:8000/chat";

const welcome    = document.getElementById("welcome");
const chatWindow = document.getElementById("chatWindow");
const userInput  = document.getElementById("userInput");
const sendBtn    = document.getElementById("sendBtn");

let history     = [];
let chatStarted = false;
let isLoading   = false;
let sessionId   = _uuid();
let lastQuery   = "";  // for retry on error

function _uuid() {
  return "s-" + Math.random().toString(36).slice(2, 10);
}

// ── Toast 提示 ────────────────────────────────────────
function showToast(msg, duration = 2000) {
  let toast = document.getElementById("_toast");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "_toast";
    toast.className = "app-toast";
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.classList.add("visible");
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => toast.classList.remove("visible"), duration);
}

// ── 状态栏时钟 ────────────────────────────────────────
function updateTime() {
  const now = new Date();
  document.getElementById("statusTime").textContent =
    `${String(now.getHours()).padStart(2,"0")}:${String(now.getMinutes()).padStart(2,"0")}`;
}
updateTime();
setInterval(updateTime, 10000);

// ── Tab 切换 ──────────────────────────────────────────
function switchTab(tab) {
  document.querySelectorAll(".page").forEach(p => p.classList.remove("active"));
  document.querySelectorAll(".tab").forEach(t => t.classList.remove("active"));
  document.getElementById(`page-${tab}`).classList.add("active");
  document.querySelector(`.tab[data-tab="${tab}"]`).classList.add("active");
}

function goChat(text) {
  switchTab("chat");
  userInput.value = text;
  sendBtn.disabled = false;
  sendMessage();
}

// ── 新对话 ────────────────────────────────────────────
function startNewChat() {
  history     = [];
  sessionId   = _uuid();
  chatStarted = false;
  lastQuery   = "";
  chatWindow.innerHTML = "";
  chatWindow.classList.remove("active");
  welcome.style.display = "";
  userInput.value = "";
  userInput.style.height = "auto";
  sendBtn.disabled = true;
  userInput.focus();
}

document.getElementById("newChatBtn").addEventListener("click", startNewChat);

// ── 键盘快捷键 ─────────────────────────────────────────
document.addEventListener("keydown", e => {
  // Cmd+K（Mac）或 Ctrl+K（Win）：新对话
  if ((e.metaKey || e.ctrlKey) && e.key === "k") {
    e.preventDefault();
    startNewChat();
    showToast("已开始新对话 ⌘K");
  }
  // Cmd+/ : 聚焦输入框
  if ((e.metaKey || e.ctrlKey) && e.key === "/") {
    e.preventDefault();
    userInput.focus();
  }
});

// ── 输入框自动伸高 ────────────────────────────────────
userInput.addEventListener("input", () => {
  sendBtn.disabled = !userInput.value.trim() || isLoading;
  userInput.style.height = "auto";
  userInput.style.height = Math.min(userInput.scrollHeight, 100) + "px";
});

sendBtn.addEventListener("click", sendMessage);
userInput.addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

function sendChip(text) {
  if (isLoading) return;
  userInput.value = text;
  sendBtn.disabled = false;
  sendMessage();
}

// ── 主发送逻辑 ────────────────────────────────────────
async function sendMessage() {
  const text = userInput.value.trim();
  if (!text || isLoading) return;

  if (!chatStarted) {
    welcome.style.display = "none";
    chatWindow.classList.add("active");
    chatStarted = true;
  }

  userInput.value = "";
  userInput.style.height = "auto";
  sendBtn.disabled = true;
  isLoading = true;

  lastQuery = text;
  appendUser(text);
  history.push({ role: "user", content: text });

  const botRow = createBotRow();
  chatWindow.appendChild(botRow);
  scrollBottom();

  const thinkingEl   = addThinking(botRow, "正在理解你的需求…");
  let assistantText  = "";
  let collectedTitles = [];
  let lastPipeline   = "unknown";
  let lastIntentType = "unknown";

  try {
    const res = await fetch(BACKEND_URL, {
      method:  "POST",
      headers: { "Content-Type": "application/json" },
      body:    JSON.stringify({
        message:    text,
        history:    history.slice(0, -1),
        session_id: sessionId,
      }),
    });

    if (!res.ok || !res.body) throw new Error("network error");

    const reader  = res.body.getReader();
    const decoder = new TextDecoder();
    let   buffer  = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const parts = buffer.split("\n\n");
      buffer = parts.pop();

      for (const part of parts) {
        const eventLine = part.match(/^event: (\w+)/m);
        const dataLine  = part.match(/^data: (.+)/m);
        if (!eventLine || !dataLine) continue;

        const event = eventLine[1];
        let   data;
        try { data = JSON.parse(dataLine[1]); } catch { continue; }

        switch (event) {
          case "intent":
            thinkingEl.remove();
            showIntentBadge(botRow, data);
            lastIntentType = data.intent_type;
            lastPipeline   = data.pipeline;
            break;

          case "thinking":
            updateThinking(botRow, data.message);
            break;

          case "clarify":
            removeThinking(botRow);
            addClarifyBubble(botRow, data);
            assistantText = data.question;
            break;

          case "product":
            removeThinking(botRow);
            addProductCard(botRow, data);
            collectedTitles.push(data.title);
            break;

          case "rank":
            if (data.summary) addRankSummary(botRow, data.summary);
            break;

          case "done":
            removeThinking(botRow);
            lastPipeline = data.pipeline;
            if (collectedTitles.length > 0) {
              addPipelineBadge(botRow, data);
              addFeedbackRow(botRow);
              // 为多轮对话记录：本轮推荐了哪些手机
              assistantText = `为你推荐了 ${collectedTitles.slice(0, 3).join("、")} 等 ${collectedTitles.length} 款手机`;
            }
            break;

          case "error":
            removeThinking(botRow);
            addErrorBubble(botRow, data.message);
            break;
        }
        scrollBottom();
      }
    }
  } catch {
    removeThinking(botRow);
    addErrorBubble(botRow, "连接服务失败，请确认后端已启动 🔌", true);
  }

  if (assistantText) {
    history.push({ role: "assistant", content: assistantText });
  }

  isLoading = false;
  sendBtn.disabled = !userInput.value.trim();
  userInput.focus();
}

// ── DOM 构建助手 ──────────────────────────────────────

function createBotRow() {
  const row = document.createElement("div");
  row.className = "msg-row bot";
  row.innerHTML = `
    <div class="msg-avatar bot-av">✦</div>
    <div class="msg-body" data-body></div>`;
  return row;
}

function getBody(row) { return row.querySelector("[data-body]"); }

function addThinking(row, text) {
  const body = getBody(row);
  const el = document.createElement("div");
  el.className = "thinking-row";
  el.setAttribute("data-thinking", "");
  el.innerHTML = `<span class="thinking-dots"><span></span><span></span><span></span></span>
    <span class="thinking-text">${esc(text)}</span>`;
  body.appendChild(el);
  return el;
}

function updateThinking(row, text) {
  const el = row.querySelector("[data-thinking]");
  if (el) el.querySelector(".thinking-text").textContent = text;
  else    addThinking(row, text);
}

function removeThinking(row) {
  row.querySelectorAll("[data-thinking]").forEach(el => el.remove());
}

function showIntentBadge(row, data) {
  const body = getBody(row);
  const map  = {
    direct_search:   "🔍 直接搜索",
    time_sensitive:  "⚡ 最新热款",
    model_selection: "🤖 选型推荐",
    spec_comparison: "⚖️ 规格对比",
    guided_shopping: "🛒 引导购物",
    cross_platform:  "🌐 比价查询",
    out_of_scope:    "💬 超出范围",
  };
  const label    = map[data.intent_type] || data.intent_type;
  const pipeline = { traditional: "传统搜索", llm: "AI 链路", fallback: "兜底" }[data.pipeline] || data.pipeline;
  const badge = document.createElement("div");
  badge.className = "intent-badge";
  badge.textContent = `${label} · ${pipeline} · ${Math.round(data.confidence * 100)}%`;
  body.appendChild(badge);
  updateThinking(row, _thinkingText(data));
}

function _thinkingText(data) {
  return { traditional: "正在快速搜索…", llm: "AI 正在理解需求…", fallback: "准备推荐…" }[data.pipeline] || "处理中…";
}

function addClarifyBubble(row, data) {
  const body = getBody(row);
  const el   = document.createElement("div");
  el.className = "clarify-wrap";

  const bubble = document.createElement("div");
  bubble.className = "bubble bot";
  bubble.textContent = data.question;
  el.appendChild(bubble);

  if (data.options && data.options.length) {
    const opts = document.createElement("div");
    opts.className = "clarify-options";
    data.options.forEach(opt => {
      const btn = document.createElement("button");
      btn.className = "clarify-opt";
      btn.textContent = opt;
      btn.addEventListener("click", () => {
        // 禁用所有选项，防止重复点击
        opts.querySelectorAll(".clarify-opt").forEach(b => {
          b.disabled = true;
          b.classList.add("selected");
        });
        btn.classList.add("active");
        sendChip(opt);
      });
      opts.appendChild(btn);
    });
    el.appendChild(opts);
  }

  body.appendChild(el);
}

function addProductCard(row, p) {
  const body = getBody(row);
  let list = body.querySelector(".product-list");
  if (!list) {
    list = document.createElement("div");
    list.className = "product-list";
    body.appendChild(list);
  }

  const stars = Math.min(5, Math.round(p.rating || 4));
  const salesStr = p.sales >= 10000
    ? `${(p.sales / 10000).toFixed(1)}万+人购买`
    : `${p.sales}+人购买`;

  const card = document.createElement("div");
  card.className = "product-card";

  const reasonHtml = p.reason
    ? `<div class="p-reason">💡 ${esc(p.reason)}</div>` : "";
  const highlightHtml = p.highlights && p.highlights.length
    ? `<div class="p-highlights">${
        p.highlights.slice(0, 2).map(h => `<span class="p-tag">${esc(h)}</span>`).join("")
      }</div>` : "";

  card.innerHTML = `
    <div class="p-thumb">📱</div>
    <div class="p-info">
      <div class="p-name">${esc(p.title)}</div>
      <div class="p-meta">
        <span class="p-stars">${"★".repeat(stars)}${"☆".repeat(5 - stars)}</span>
        <span class="p-sales">${salesStr}</span>
      </div>
      ${highlightHtml}
      ${reasonHtml}
    </div>
    <div class="p-price">¥${Number(p.price).toLocaleString()}</div>`;

  card.addEventListener("click", () => {
    card.classList.add("card-tapped");
    setTimeout(() => card.classList.remove("card-tapped"), 300);
    showProductDetail(p);
  });

  list.appendChild(card);
}

function addRankSummary(row, summary) {
  const body = getBody(row);
  const el   = document.createElement("div");
  el.className = "rank-summary";
  el.innerHTML = `<span class="rank-icon">🤔 怎么选？</span><span>${esc(summary)}</span>`;
  body.appendChild(el);
}

function addPipelineBadge(row, data) {
  const body = getBody(row);
  const el   = document.createElement("div");
  el.className = "done-badge";
  el.textContent = `⏱ ${data.latency_ms}ms · ${data.pipeline}`;
  body.appendChild(el);
}

function addFeedbackRow(row) {
  const body = getBody(row);
  const el   = document.createElement("div");
  el.className = "feedback-row";
  el.innerHTML = `
    <span class="fb-label">推荐有帮助吗？</span>
    <button class="fb-btn" data-v="up" title="有帮助">👍</button>
    <button class="fb-btn" data-v="down" title="不满意">👎</button>`;

  el.querySelector('[data-v="up"]').addEventListener("click", () => {
    el.innerHTML = `<span class="fb-thanks">👍 感谢反馈！</span>`;
  });

  el.querySelector('[data-v="down"]').addEventListener("click", () => {
    el.innerHTML = `
      <div class="fb-down-wrap">
        <span class="fb-label">哪里不满意？</span>
        <div class="fb-reasons">
          <button class="fb-reason-btn" data-r="inaccurate">推荐不准</button>
          <button class="fb-reason-btn" data-r="pricey">太贵了</button>
          <button class="fb-reason-btn" data-r="weak_reason">理由不够</button>
          <button class="fb-reason-btn" data-r="owned">已经有了</button>
        </div>
      </div>`;
    el.querySelectorAll(".fb-reason-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        const reason = btn.dataset.r;
        el.innerHTML = `<span class="fb-thanks">👎 已记录，优化中～</span>`;
        // 根据反馈类型触发新一轮推荐
        if (reason === "pricey" && lastQuery) {
          setTimeout(() => {
            userInput.value = lastQuery + " 便宜一点";
            sendBtn.disabled = false;
            sendMessage();
          }, 800);
        } else if (reason === "inaccurate" && lastQuery) {
          setTimeout(() => {
            userInput.value = lastQuery;
            sendBtn.disabled = false;
            sendMessage();
          }, 800);
        }
      });
    });
  });

  body.appendChild(el);
}

function addErrorBubble(row, msg, showRetry = false) {
  const body = getBody(row);
  const el   = document.createElement("div");
  el.className = "error-wrap";

  const bubble = document.createElement("div");
  bubble.className = "bubble bot error-bubble";
  bubble.textContent = msg;
  el.appendChild(bubble);

  if (showRetry && lastQuery) {
    const btn = document.createElement("button");
    btn.className = "retry-btn";
    btn.textContent = "重试";
    btn.addEventListener("click", () => {
      if (isLoading) return;
      userInput.value = lastQuery;
      sendBtn.disabled = false;
      sendMessage();
    });
    el.appendChild(btn);
  }

  body.appendChild(el);
}

// ── 用户消息 ─────────────────────────────────────────
function appendUser(text) {
  const row = document.createElement("div");
  row.className = "msg-row user";
  row.innerHTML = `
    <div class="msg-body">
      <div class="bubble user">${esc(text)}</div>
    </div>`;
  chatWindow.appendChild(row);
  scrollBottom();
}

function scrollBottom() {
  requestAnimationFrame(() => { chatWindow.scrollTop = chatWindow.scrollHeight; });
}

function esc(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

// ══════════════════════════════════════════════════════
// 心愿单（localStorage）
// ══════════════════════════════════════════════════════

const Wishlist = {
  _key: "jd_guide_wishlist",

  getAll() {
    try { return JSON.parse(localStorage.getItem(this._key) || "[]"); }
    catch { return []; }
  },

  save(product) {
    const list = this.getAll();
    if (!list.find(p => p.id === product.id)) {
      list.unshift({ ...product, savedAt: Date.now() });
      localStorage.setItem(this._key, JSON.stringify(list));
    }
    this._updateBadge();
  },

  remove(id) {
    const list = this.getAll().filter(p => p.id !== id);
    localStorage.setItem(this._key, JSON.stringify(list));
    this._updateBadge();
  },

  has(id) { return this.getAll().some(p => p.id === id); },

  count() { return this.getAll().length; },

  _updateBadge() {
    const badge = document.getElementById("wishlistBadge");
    if (!badge) return;
    const n = this.count();
    badge.textContent = n;
    badge.style.display = n > 0 ? "flex" : "none";
  },
};

// ══════════════════════════════════════════════════════
// 商品详情底部抽屉
// ══════════════════════════════════════════════════════

function showProductDetail(product) {
  // 如果有 id，尝试从后端获取完整数据
  const fetchDetail = product.id
    ? fetch(`http://localhost:8000/products/${encodeURIComponent(product.id)}`)
        .then(r => r.ok ? r.json() : null)
        .then(d => d ? d.product : null)
        .catch(() => null)
    : Promise.resolve(null);

  fetchDetail.then(full => {
    const p = full || product;
    _renderDetailSheet(p);
  });
}

function _renderDetailSheet(p) {
  // 移除旧抽屉
  document.getElementById("_detailSheet")?.remove();
  document.getElementById("_detailOverlay")?.remove();

  const overlay = document.createElement("div");
  overlay.id = "_detailOverlay";
  overlay.className = "sheet-overlay";
  overlay.addEventListener("click", _closeDetailSheet);

  const sheet = document.createElement("div");
  sheet.id = "_detailSheet";
  sheet.className = "product-sheet";

  const inWishlist = Wishlist.has(p.id);
  const stars      = Math.min(5, Math.round(p.rating || 4));
  const salesStr   = (p.sales || 0) >= 10000
    ? `${((p.sales || 0) / 10000).toFixed(1)}万+` : `${p.sales || 0}+`;

  // 规格参数行
  const specs = [
    p.chip        && { label: "芯片", value: p.chip },
    p.storage     && { label: "存储", value: `${p.storage}G` },
    p.camera_mp   && { label: "相机", value: `${p.camera_mp}MP` },
    p.battery_mah && { label: "电池", value: `${p.battery_mah}mAh` },
    p.screen_size && { label: "屏幕", value: `${p.screen_size}英寸` },
    p.color       && { label: "颜色", value: p.color },
  ].filter(Boolean);

  const specsHtml = specs.length
    ? `<div class="spec-grid">${specs.map(s =>
        `<div class="spec-item"><span class="spec-label">${esc(s.label)}</span><span class="spec-value">${esc(s.value)}</span></div>`
      ).join("")}</div>` : "";

  const highlightsHtml = (p.highlights || []).length
    ? `<ul class="detail-highlights">${(p.highlights).map(h =>
        `<li>${esc(h)}</li>`).join("")}</ul>` : "";

  const tagsHtml = (p.tags || []).length
    ? `<div class="detail-tags">${p.tags.map(t =>
        `<span class="p-tag">${esc(t)}</span>`).join("")}</div>` : "";

  sheet.innerHTML = `
    <div class="sheet-handle"></div>
    <button class="sheet-close" id="_sheetClose">✕</button>

    <div class="sheet-body">
      <div class="sheet-thumb">📱</div>
      ${p.brand ? `<div class="sheet-brand">${esc(p.brand)}</div>` : ""}
      <div class="sheet-title">${esc(p.title)}</div>
      <div class="sheet-price">¥${Number(p.price || 0).toLocaleString()}</div>
      <div class="sheet-meta">
        <span class="p-stars">${"★".repeat(stars)}${"☆".repeat(5 - stars)}</span>
        <span class="p-sales">${salesStr} 人购买</span>
      </div>

      ${tagsHtml}
      ${specsHtml ? `<div class="sheet-section-title">规格参数</div>${specsHtml}` : ""}
      ${highlightsHtml ? `<div class="sheet-section-title">产品亮点</div>${highlightsHtml}` : ""}
    </div>

    <div class="sheet-footer">
      <button class="wishlist-btn ${inWishlist ? "in-wishlist" : ""}" id="_wishlistBtn">
        ${inWishlist ? "❤️ 已收藏" : "🤍 加入心愿单"}
      </button>
      <button class="buy-btn" onclick="showToast('跳转购买功能即将上线 ✨')">去购买</button>
    </div>`;

  document.body.appendChild(overlay);
  document.body.appendChild(sheet);

  // 动画入场
  requestAnimationFrame(() => {
    overlay.classList.add("visible");
    sheet.classList.add("visible");
  });

  document.getElementById("_sheetClose").addEventListener("click", _closeDetailSheet);

  document.getElementById("_wishlistBtn").addEventListener("click", () => {
    const btn = document.getElementById("_wishlistBtn");
    if (Wishlist.has(p.id)) {
      Wishlist.remove(p.id);
      btn.textContent = "🤍 加入心愿单";
      btn.classList.remove("in-wishlist");
      showToast("已移出心愿单");
    } else {
      Wishlist.save(p);
      btn.textContent = "❤️ 已收藏";
      btn.classList.add("in-wishlist");
      showToast(`「${p.title.slice(0, 10)}」已加入心愿单 ❤️`);
    }
    // 刷新发现页 / 我的页心愿单
    _refreshWishlistViews();
  });
}

function _closeDetailSheet() {
  const sheet   = document.getElementById("_detailSheet");
  const overlay = document.getElementById("_detailOverlay");
  if (sheet)   { sheet.classList.remove("visible"); }
  if (overlay) { overlay.classList.remove("visible"); }
  setTimeout(() => { sheet?.remove(); overlay?.remove(); }, 280);
}

function _refreshWishlistViews() {
  Wishlist._updateBadge();
  // 如果"我的"页面当前可见，刷新心愿单列表
  if (document.getElementById("page-profile").classList.contains("active")) {
    _renderWishlistSection();
  }
}

// ══════════════════════════════════════════════════════
// 我的页面 · 心愿单
// ══════════════════════════════════════════════════════

function _renderWishlistSection() {
  const container = document.getElementById("wishlistSection");
  if (!container) return;
  const items = Wishlist.getAll();
  if (!items.length) {
    container.innerHTML = `<div class="wishlist-empty">暂无收藏 · 去聊天找找感兴趣的手机吧</div>`;
    return;
  }
  container.innerHTML = items.map(p => `
    <div class="wishlist-item" data-id="${esc(p.id)}">
      <span class="wishlist-thumb">📱</span>
      <div class="wishlist-info">
        <div class="wishlist-name">${esc(p.title)}</div>
        <div class="wishlist-price">¥${Number(p.price || 0).toLocaleString()}</div>
      </div>
      <button class="wishlist-remove" data-id="${esc(p.id)}">✕</button>
    </div>`).join("");

  container.querySelectorAll(".wishlist-item").forEach(row => {
    row.addEventListener("click", e => {
      if (e.target.classList.contains("wishlist-remove")) return;
      const item = Wishlist.getAll().find(p => p.id === row.dataset.id);
      if (item) showProductDetail(item);
    });
  });
  container.querySelectorAll(".wishlist-remove").forEach(btn => {
    btn.addEventListener("click", e => {
      e.stopPropagation();
      Wishlist.remove(btn.dataset.id);
      _renderWishlistSection();
    });
  });
}

// ══════════════════════════════════════════════════════
// 发现页 · 接入后端热门数据
// ══════════════════════════════════════════════════════

let _discoverScenario = "";

async function loadDiscover(scenario = "") {
  _discoverScenario = scenario;
  const grid = document.getElementById("discoverGrid");
  if (!grid) return;

  // 更新筛选器状态
  document.querySelectorAll(".dfilter-btn").forEach(b => {
    b.classList.toggle("active", b.dataset.s === scenario);
  });

  grid.innerHTML = `<div class="discover-loading">加载中…</div>`;

  try {
    const params = new URLSearchParams({ limit: 12 });
    if (scenario) params.set("scenario", scenario);
    const res  = await fetch(`http://localhost:8000/trending?${params}`);
    const data = await res.json();

    if (!data.products?.length) {
      grid.innerHTML = `<div class="discover-loading">暂无数据</div>`;
      return;
    }

    grid.innerHTML = data.products.map(p => {
      const stars = Math.min(5, Math.round(p.rating || 4));
      const salesStr = (p.sales || 0) >= 10000
        ? `${((p.sales || 0) / 10000).toFixed(1)}万+` : `${p.sales || 0}+`;
      return `
        <div class="discover-card" data-id="${esc(p.id)}"
             data-p='${esc(JSON.stringify(p))}'>
          <div class="dc-thumb">📱</div>
          <div class="dc-info">
            <div class="dc-name">${esc(p.title)}</div>
            <div class="dc-meta">
              <span class="p-stars">${"★".repeat(stars)}</span>
              <span class="p-sales">${salesStr}人</span>
            </div>
            <div class="dc-price">¥${Number(p.price || 0).toLocaleString()}</div>
          </div>
        </div>`;
    }).join("");

    grid.querySelectorAll(".discover-card").forEach(card => {
      card.addEventListener("click", () => {
        try { showProductDetail(JSON.parse(card.dataset.p)); }
        catch { showProductDetail({ id: card.dataset.id }); }
      });
    });
  } catch {
    grid.innerHTML = `<div class="discover-loading">后端未启动，请启动服务</div>`;
  }
}

// ══════════════════════════════════════════════════════
// Tab 切换钩子 · 按需加载各页数据
// ══════════════════════════════════════════════════════

const _origSwitchTab = switchTab;
// 重写 switchTab 以注入页面加载逻辑
window.switchTab = function(tab) {
  _origSwitchTab(tab);
  if (tab === "discover") loadDiscover(_discoverScenario);
  if (tab === "profile")  { Wishlist._updateBadge(); _renderWishlistSection(); }
};

// 初始化
Wishlist._updateBadge();
