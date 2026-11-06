import { readEvents } from "./chat-stream.js";
import { responseError } from "./chat-errors.js";

(() => {
  "use strict";

  const storageKey = "odin.chat.history.v1";
  const elements = Object.fromEntries(["new-chat", "clear-history", "conversations", "welcome", "messages", "conversation-scroll", "status", "chat-form", "message-input", "send-button", "character-count"].map(id => [id, document.getElementById(id === "status" ? "chat-status" : id)]));
  let conversations = loadHistory();
  let activeId = conversations[0]?.id ?? null;
  let requestController = null;

  function loadHistory() {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "[]");
      if (!Array.isArray(saved)) return [];
      return saved.filter(conversation => Number.isSafeInteger(conversation.id) && conversation.id > 0 && typeof conversation.title === "string" && Array.isArray(conversation.messages) && conversation.messages.every(message => ["user", "assistant"].includes(message.role) && typeof message.content === "string"))
        .map(conversation => ({ ...conversation, messages: conversation.messages.map(message => ({ ...message, state: message.state === "generating" ? "stopped" : message.state })) }));
    } catch {
      return [];
    }
  }

  function saveHistory() {
    try {
      localStorage.setItem(storageKey, JSON.stringify(conversations));
    } catch {
      elements.status.textContent = "浏览器无法保存记录，本次对话仍可继续。";
    }
  }

  function activeConversation() {
    return conversations.find(conversation => conversation.id === activeId);
  }

  function renderHistory() {
    elements.conversations.replaceChildren();
    if (!conversations.length) {
      const empty = document.createElement("div");
      empty.className = "history-empty";
      empty.textContent = "还没有对话，开始聊聊吧";
      elements.conversations.append(empty);
    }
    for (const conversation of conversations) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `history-item${conversation.id === activeId ? " active" : ""}`;
      button.textContent = conversation.title;
      button.title = conversation.title;
      button.disabled = Boolean(requestController);
      button.setAttribute("aria-current", String(conversation.id === activeId));
      button.addEventListener("click", () => {
        activeId = conversation.id;
        elements.status.textContent = "";
        renderConversation();
      });
      elements.conversations.append(button);
    }
  }

  function appendMessage(message) {
    const article = document.createElement("article");
    article.className = `message ${message.role}`;
    const avatar = document.createElement("span");
    avatar.className = "avatar";
    avatar.textContent = message.role === "user" ? "你" : "✳";
    avatar.setAttribute("aria-hidden", "true");
    const body = document.createElement("div");
    body.className = "message-body";
    const name = document.createElement("div");
    name.className = "message-name";
    name.textContent = message.role === "user" ? "你" : "Odin";
    const content = document.createElement("div");
    content.className = "message-content";
    content.textContent = message.content;
    const footer = document.createElement("div");
    footer.className = "message-footer";
    const state = document.createElement("span");
    state.className = "message-state";
    const errorPanel = document.createElement("div");
    errorPanel.className = "message-error";
    const errorTitle = document.createElement("strong");
    errorTitle.textContent = "错误详情";
    const errorText = document.createElement("pre");
    const copyError = document.createElement("button");
    copyError.type = "button";
    copyError.className = "copy-button";
    copyError.textContent = "复制错误详情";
    copyError.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(message.error);
        elements.status.textContent = "错误详情已复制。";
      } catch {
        elements.status.textContent = "复制不可用，请选中错误详情后手动复制。";
      }
    });
    errorPanel.append(errorTitle, errorText, copyError);
    if (message.role === "assistant") {
      const copy = document.createElement("button");
      copy.type = "button";
      copy.className = "copy-button";
      copy.textContent = "复制回复";
      copy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(message.content);
          elements.status.textContent = "回复已复制。";
        } catch {
          elements.status.textContent = "复制不可用，请选中文字后手动复制。";
        }
      });
      footer.append(copy, state);
    }
    body.append(name, content, errorPanel, footer);
    article.append(avatar, body);
    elements.messages.append(article);
    const update = () => {
      content.textContent = message.content;
      state.textContent = { generating: "正在思考与生成…", stopped: "已停止生成", error: "回复未完成" }[message.state] || "";
      state.classList.toggle("error", message.state === "error");
      errorPanel.hidden = message.state !== "error" || typeof message.error !== "string" || !message.error;
      errorText.textContent = typeof message.error === "string" ? message.error : "";
    };
    update();
    return update;
  }

  function scrollToLatest(force = false) {
    const container = elements["conversation-scroll"];
    if (force || container.scrollHeight - container.scrollTop - container.clientHeight < 160) {
      container.scrollTop = container.scrollHeight;
    }
  }

  function renderConversation() {
    renderHistory();
    elements.messages.replaceChildren();
    const conversation = activeConversation();
    elements.welcome.hidden = Boolean(conversation?.messages.length);
    let updateLastMessage = () => {};
    for (const message of conversation?.messages || []) updateLastMessage = appendMessage(message);
    scrollToLatest(true);
    return updateLastMessage;
  }

  function updateControls() {
    const busy = Boolean(requestController);
    elements["send-button"].disabled = !busy && !elements["message-input"].value.trim();
    elements["send-button"].textContent = busy ? "■" : "↑";
    elements["send-button"].setAttribute("aria-label", busy ? "停止生成" : "发送消息");
    elements["message-input"].disabled = busy;
    elements["new-chat"].disabled = busy;
    elements["clear-history"].disabled = busy;
    elements.messages.setAttribute("aria-busy", String(busy));
    renderHistory();
  }

  function resizeInput() {
    const input = elements["message-input"];
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 155)}px`;
    elements["character-count"].textContent = `${input.value.length} / 12000`;
    updateControls();
  }

  async function sendMessage() {
    if (requestController) {
      requestController.abort();
      return;
    }
    const input = elements["message-input"];
    const text = input.value.trim();
    if (!text || text.length > 12000) return;
    let conversation = activeConversation();
    if (!conversation) {
      const random = new Uint32Array(1);
      crypto.getRandomValues(random);
      activeId = Date.now() * 1000 + random[0] % 1000;
      conversation = { id: activeId, title: text.slice(0, 32), messages: [] };
      conversations.unshift(conversation);
    }
    const reply = { role: "assistant", content: "", state: "generating" };
    conversation.messages.push({ role: "user", content: text }, reply);
    input.value = "";
    elements.status.textContent = "";
    const updateReply = renderConversation();
    requestController = new AbortController();
    resizeInput();
    saveHistory();
    scrollToLatest(true);
    let timedOut = false;
    let timeout;
    const resetTimeout = () => {
      clearTimeout(timeout);
      timeout = setTimeout(() => { timedOut = true; requestController?.abort(); }, 90000);
    };
    resetTimeout();
    try {
      const response = await fetch(new URL("chat/stream", document.baseURI), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8", Accept: "text/event-stream" },
        body: new URLSearchParams({ msg: text, chatId: String(conversation.id) }),
        signal: requestController.signal
      });
      if (!response.ok) throw await responseError(response);
      if (!response.headers.get("content-type")?.includes("text/event-stream")) throw new Error("服务未返回聊天数据，请检查登录状态或后端配置。");
      await readEvents(response, (event, data) => {
        resetTimeout();
        const payload = JSON.parse(data);
        if (typeof payload.content !== "string") throw new Error("服务返回了无法识别的消息格式。");
        if (event === "error") throw new Error([payload.content, typeof payload.details === "string" ? payload.details : ""].filter(Boolean).join("\n"));
        if (event === "done") return true;
        if (event === "delta") {
          reply.content += payload.content;
          updateReply();
          scrollToLatest();
        }
        return false;
      });
      if (!reply.content) throw new Error("模型未返回内容，请换个问题重试。");
      reply.state = "complete";
      elements.status.textContent = "回复已完成。";
    } catch (error) {
      reply.state = error.name === "AbortError" && !timedOut ? "stopped" : "error";
      if (reply.state === "error") {
        reply.error = timedOut ? "等待回复超时：连续 90 秒未收到服务端数据，请检查模型网关和后端日志。" : error.message || "连接失败，请检查网络及服务状态。";
        elements.status.textContent = "对话失败，详细原因见回复中的“错误详情”。";
      } else {
        elements.status.textContent = "已停止生成，可以继续提问。";
      }
    } finally {
      clearTimeout(timeout);
      requestController = null;
      updateReply();
      saveHistory();
      updateControls();
      input.focus();
    }
  }

  elements["chat-form"].addEventListener("submit", event => { event.preventDefault(); sendMessage(); });
  elements["message-input"].addEventListener("input", resizeInput);
  window.addEventListener("resize", resizeInput);
  elements["message-input"].addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      sendMessage();
    }
  });
  elements["new-chat"].addEventListener("click", () => {
    activeId = null;
    elements.status.textContent = "";
    elements["message-input"].value = "";
    renderConversation();
    resizeInput();
    elements["message-input"].focus();
  });
  elements["clear-history"].addEventListener("click", () => {
    if (!conversations.length) return;
    const dialog = document.getElementById("clear-dialog");
    dialog.returnValue = "cancel";
    dialog.showModal();
  });
  document.getElementById("clear-dialog").addEventListener("close", event => {
    if (event.target.returnValue !== "confirm") return;
    conversations = [];
    activeId = null;
    saveHistory();
    renderConversation();
    elements.status.textContent = "浏览器中的对话记录已清空。";
  });
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => {
    elements["message-input"].value = button.dataset.prompt;
    resizeInput();
    elements["message-input"].focus();
  }));
  renderConversation();
  resizeInput();
})();
