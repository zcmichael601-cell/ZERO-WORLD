export function showView(document, hash) {
  const view = hash === "#chat" ? "chat" : "skills";
  document.getElementById("chat-view").hidden = view !== "chat";
  document.getElementById("skill-view").hidden = view !== "skills";
  document.body.dataset.view = view;
  document.title = view === "chat" ? "Odin · AI 对话" : "Odin · Skill 实验室";
  document.querySelectorAll("nav a[data-view]").forEach(link => {
    const active = link.dataset.view === view;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  return view;
}

if (typeof window !== "undefined") {
  const update = () => showView(document, window.location.hash);
  window.addEventListener("hashchange", update);
  update();
}
