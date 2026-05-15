const BACKEND_URL = "http://localhost:8000/chat";

const welcome    = document.getElementById("welcome");
const chatWindow = document.getElementById("chatWindow");
const userInput  = document.getElementById("userInput");
const sendBtn    = document.getElementById("sendBtn");

let history = [];
let chatStarted = false;

// ── 状态栏时间 ────────────────────────────────────────
function updateTime() {
  const now = new Date();
  const h = now.getHours().toString().padStart(2, "0");
  const m = now.getMinutes().toString().padStart(2, "0");
  document.getElementById("statusTime").textContent = `${h}:${m}`;
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

// ── 发现页跳到聊天 ────────────────────────────────────
function goChat(text) {
  switchTab("chat");
  userInput.value = text;
  sendBtn.disabled = false;
  sendMessage();
}

// ── 新对话 ────────────────────────────────────────────
document.getElementById("newChatBtn").addEventListener("click", () => {
  history = [];
  chatStarted = false;
  chatWindow.innerHTML = "";
  chatWindow.classList.remove("active");
  welcome.style.display = "";
  userInput.value = "";
  userInput.style.height = "auto";
  sendBtn.disabled = true;
});

// ── textarea 自动伸高 ─────────────────────────────────
userInput.addEventListener("input", () => {
  sendBtn.disabled = !userInput.value.trim();
  userInput.style.height = "auto";
  userInput.style.height = Math.min(userInput.scrollHeight, 100) + "px";
});

// ── 发送 ─────────────────────────────────────────────
sendBtn.addEventListener("click", sendMessage);
userInput.addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});

function sendChip(text) {
  userInput.value = text;
  sendBtn.disabled = false;
  sendMessage();
}

async function sendMessage() {
  const text = userInput.value.trim();
  if (!text) return;

  if (!chatStarted) {
    welcome.style.display = "none";
    chatWindow.classList.add("active");
    chatStarted = true;
  }

  userInput.value = "";
  userInput.style.height = "auto";
  sendBtn.disabled = true;

  appendUser(text);
  history.push({ role: "user", content: text });

  const typingEl = showTyping();

  try {
    const res = await fetch(BACKEND_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ history }),
    });
    const data = await res.json();
    typingEl.remove();
    appendBot(data.reply, data.products);
    history.push({ role: "assistant", content: data.reply });
  } catch {
    typingEl.remove();
    appendBot("连接服务失败，请确认后端已启动 🔌");
  }

  userInput.focus();
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

// ── Bot 消息 ──────────────────────────────────────────
function appendBot(text, products = []) {
  const row = document.createElement("div");
  row.className = "msg-row bot";

  let html = `
    <div class="msg-avatar bot-av">✦</div>
    <div class="msg-body">
      <div class="bubble bot">${esc(text)}</div>`;

  if (products && products.length) {
    html += `<div class="product-list">`;
    products.forEach(p => {
      html += `
        <div class="product-card">
          <div class="p-thumb">${p.emoji || "📦"}</div>
          <div class="p-info">
            <div class="p-name">${esc(p.name)}</div>
            <div class="p-desc">${esc(p.desc)}</div>
          </div>
          <div class="p-price">¥${esc(p.price)}</div>
        </div>`;
    });
    html += `</div>`;
  }

  html += `</div>`;
  row.innerHTML = html;
  chatWindow.appendChild(row);
  scrollBottom();
}

// ── 打字动画 ─────────────────────────────────────────
function showTyping() {
  const row = document.createElement("div");
  row.className = "msg-row bot";
  row.innerHTML = `
    <div class="msg-avatar bot-av">✦</div>
    <div class="msg-body">
      <div class="typing-bubble">
        <span class="dot"></span><span class="dot"></span><span class="dot"></span>
      </div>
    </div>`;
  chatWindow.appendChild(row);
  scrollBottom();
  return row;
}

function scrollBottom() { chatWindow.scrollTop = chatWindow.scrollHeight; }

function esc(s) {
  return String(s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;")
    .replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}
