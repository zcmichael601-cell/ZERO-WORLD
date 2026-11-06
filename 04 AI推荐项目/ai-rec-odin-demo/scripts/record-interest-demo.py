#!/usr/bin/env python3
"""Record: 展示更多 → i2i → 三次展示更多 → i2i."""

import time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "demo" / "recordings"
FINAL = ROOT / "docs" / "demo" / "app-action-rounds-real.webm"
STATIC = ROOT / "ai-rec-odin-demo-main" / "src" / "main" / "resources" / "static" / "demo" / "app-action-rounds-real.webm"
TARGET = ROOT / "ai-rec-odin-demo-main" / "target" / "classes" / "static" / "demo" / "app-action-rounds-real.webm"
URL = "http://127.0.0.1:8080/skills.html"
SKILL = "shopping-recommendation-jev-cid"
USER = "a59af099cf5449056571b7c6f97ff53ee68d29e5"
TARGET_SECONDS = 90
BANNER_DWELL_MS = 3500
CLICK_DWELL_MS = 900
SCRIPT = ("more", "i2i", "more", "more", "more", "i2i")
CURSOR = """
(() => {
  if (document.getElementById("rec-cursor")) return;
  const c = document.createElement("div");
  c.id = "rec-cursor";
  Object.assign(c.style, {
    position: "fixed", zIndex: "2147483647", width: "18px", height: "18px",
    borderRadius: "50%", border: "2px solid #111", background: "#fff",
    pointerEvents: "none", transform: "translate(-30%, -30%)",
    boxShadow: "0 2px 8px rgba(0,0,0,.35)", left: "40px", top: "40px"
  });
  document.body.appendChild(c);
  document.addEventListener("mousemove", e => {
    c.style.left = e.clientX + "px";
    c.style.top = e.clientY + "px";
  });
  document.addEventListener("mousedown", () => { c.style.background = "#ffe566"; });
  document.addEventListener("mouseup", () => { c.style.background = "#fff"; });
})();
"""


def move_click(page, locator, steps=8):
    locator.scroll_into_view_if_needed()
    box = locator.bounding_box()
    if not box:
        locator.click()
        return
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2, steps=steps)
    locator.click()


def card_count(page):
    return page.locator("#products-content .product-card").count()


def snapshot(page, label):
    data = page.evaluate(
        """() => ({
          rounds: [...document.querySelectorAll('.phone-round')].map(n => n.innerText.replace(/\\s+/g, ' ').trim()),
          products: document.querySelectorAll('#products-content .product-card').length,
        })"""
    )
    print(label, data)
    return data


def scroll_phone(page, top=9999):
    page.evaluate(
        f"document.querySelector('.iphone-screen')?.scrollTo({{ top: {int(top)}, behavior: 'smooth' }})"
    )


def wait_step(page):
    page.wait_for_timeout(1000)


def show_latest_decision(page):
    page.locator(".phone-round-current").scroll_into_view_if_needed()
    page.locator("#products-panel").scroll_into_view_if_needed()
    page.wait_for_timeout(BANNER_DWELL_MS)


def show_more(page, before):
    more = page.locator("#show-more-button")
    more.wait_for(state="visible", timeout=10000)
    scroll_phone(page)
    page.wait_for_timeout(200)
    move_click(page, more)
    page.wait_for_function(
        f"() => document.querySelectorAll('#products-content .product-card').length > {before}",
        timeout=180000,
    )
    page.wait_for_function(
        """() => {
          const button = document.getElementById('show-more-button');
          return button && !button.disabled && (button.textContent || '').includes('展示更多');
        }""",
        timeout=180000,
    )
    page.locator("#products-panel").scroll_into_view_if_needed()
    show_latest_decision(page)


def click_latest(page, start_index):
    cards = page.locator("#products-content .product-card")
    total = cards.count()
    if total == 0:
        return False
    index = min(max(start_index, 0), total - 1)
    card = cards.nth(index)
    page.evaluate(
        """(el) => {
          const screen = document.querySelector('.iphone-screen');
          if (screen && screen.contains(el)) {
            const top = el.offsetTop - 48;
            screen.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
          }
        }""",
        card.element_handle(),
    )
    page.wait_for_timeout(200)
    card.scroll_into_view_if_needed()
    move_click(page, card)
    page.wait_for_timeout(CLICK_DWELL_MS)
    return True


def choose_user(page):
    if page.locator('select[name="username"]').count():
        page.select_option('select[name="username"]', USER)
        return
    if page.locator('input[name="username"]').count():
        page.fill('input[name="username"]', USER)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1760, "height": 1020},
            device_scale_factor=1,
            record_video_dir=str(OUT_DIR),
            record_video_size={"width": 1760, "height": 1020},
        )
        page = context.new_page()
        started = time.monotonic()
        page.add_init_script(CURSOR)
        page.goto(URL, wait_until="domcontentloaded")
        page.wait_for_selector("#skill-select:not([disabled])", timeout=20000)
        page.evaluate(CURSOR)
        page.select_option("#skill-select", SKILL)
        page.wait_for_function(
            "() => { const b = document.getElementById('run-button'); return b && !b.disabled; }"
        )
        choose_user(page)
        started_first = False
        for attempt in range(3):
            move_click(page, page.locator("#run-button"))
            try:
                page.wait_for_selector("#products-content .product-card", timeout=45000)
                started_first = True
                break
            except Exception as error:
                print("SCREEN1_RETRY", attempt + 1, error)
                page.wait_for_timeout(1500)
        if not started_first:
            raise TimeoutError("首屏未返回商品卡片")
        page.locator("#products-panel").scroll_into_view_if_needed()
        batch_start = 0
        snapshot(page, "SCREEN1")
        show_latest_decision(page)

        explores = 0
        clicks = 0
        try:
            for step, action in enumerate(SCRIPT, start=1):
                if action == "i2i":
                    if click_latest(page, batch_start):
                        clicks += 1
                        snapshot(page, f"CLICK{clicks}")
                before = card_count(page)
                show_more(page, before)
                batch_start = before
                explores += 1
                snapshot(page, f"{action.upper()}{step}")
        except Exception as error:
            print("SCRIPT_STOP", error)

        remaining = TARGET_SECONDS - (time.monotonic() - started)
        if remaining > 0:
            page.wait_for_timeout(int(remaining * 1000))
        print("SHOW_MORE", explores, "I2I", clicks, "elapsed", round(time.monotonic() - started, 1), flush=True)
        video_path = Path(page.video.path())
        context.close()
        browser.close()
        FINAL.parent.mkdir(parents=True, exist_ok=True)
        STATIC.parent.mkdir(parents=True, exist_ok=True)
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        payload = video_path.read_bytes()
        for dest in (FINAL, STATIC, TARGET):
            dest.write_bytes(payload)
            print(dest)
        if video_path.exists() and video_path != FINAL:
            video_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
