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
    showToast(`「${p.title.slice(0, 12)}…」已加入心愿单 ❤️`);
    card.classList.add("card-tapped");
    setTimeout(() => card.classList.remove("card-tapped"), 300);
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
  el.querySelectorAll(".fb-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      const vote = btn.dataset.v;
      el.innerHTML = vote === "up"
        ? `<span class="fb-thanks">👍 感谢反馈！</span>`
        : `<span class="fb-thanks">👎 已记录，我们会改进</span>`;
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
