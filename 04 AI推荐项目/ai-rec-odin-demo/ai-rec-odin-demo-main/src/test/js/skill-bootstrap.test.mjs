import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/skill-bootstrap.js', import.meta.url), 'utf8');

function bootstrap() {
  const nodes = new Map();
  const listeners = new Map();
  let timeout;
  let timerCleared = false;
  let destination;
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, { hidden: true, textContent: '', innerHTML: '', disabled: true,
      addEventListener(type, callback) { this[type] = callback; } });
    return nodes.get(id);
  };
  const window = {
    location: { href: 'http://localhost:8080/demo/skills.html?existing=1', replace(url) { destination = url; } },
    addEventListener(type, callback) { listeners.set(type, callback); },
    removeEventListener(type) { listeners.delete(type); }
  };
  vm.runInNewContext(source, { document: { getElementById: element }, window, URL, Date,
    setTimeout(callback, milliseconds) { assert.equal(milliseconds, 15000); timeout = callback; return 1; },
    clearTimeout() { timerCleared = true; } });
  return { element, listeners, timeout: () => timeout(), cleared: () => timerCleared, destination: () => destination };
}

test('missing module resources show recovery UI even when skills.js never executes', () => {
  const view = bootstrap();
  view.listeners.get('error')({ target: { tagName: 'SCRIPT' } });
  assert.equal(view.element('startup-error').hidden, false);
  assert.match(view.element('startup-error-details').textContent, /脚本加载失败/);
  assert.match(view.element('skill-select').innerHTML, /页面加载异常/);
  assert.equal(view.element('run-button').disabled, true);
  assert.equal(view.cleared(), true);
});

test('syntax and evaluation errors report location without injecting upstream error text', () => {
  const view = bootstrap();
  view.listeners.get('error')({ filename: 'http://localhost/skill-timing.mjs?v=1', lineno: 18, message: '<script>bad</script>' });
  assert.match(view.element('startup-error-details').textContent, /skill-timing.mjs，行 18/);
  assert.ok(!view.element('startup-error-details').textContent.includes('<script>'));
});

test('watchdog bounds otherwise silent initialization and late success recovers', () => {
  const view = bootstrap();
  view.timeout();
  assert.equal(view.element('startup-error').hidden, false);
  assert.match(view.element('startup-error-details').textContent, /15 秒/);
  view.listeners.get('skill-page-initialized')();
  assert.equal(view.element('startup-error').hidden, true);
  assert.equal(view.listeners.has('error'), false);
  view.timeout();
  assert.equal(view.element('startup-error').hidden, true);
});

test('successful initialization cancels watchdog and reload preserves context path', () => {
  const view = bootstrap();
  view.listeners.get('skill-page-initialized')();
  assert.equal(view.cleared(), true);
  view.element('startup-reload').click();
  const url = new URL(view.destination());
  assert.equal(url.pathname, '/demo/skills.html');
  assert.equal(url.searchParams.get('existing'), '1');
  assert.ok(url.searchParams.has('reload'));
});

test('bootstrap loads before the module and the entire import graph shares one release version', async () => {
  const root = new URL('../../main/resources/static/', import.meta.url);
  const html = await readFile(new URL('skills.html', root), 'utf8');
  assert.ok(html.indexOf('src="skill-bootstrap.js?') < html.indexOf('src="skills.js?'));
  const version = html.match(/src="skills.js\?v=([^"&]+)"/)[1];
  const seen = new Set();
  async function verify(file) {
    if (seen.has(file)) return;
    seen.add(file);
    const contents = await readFile(new URL(file, root), 'utf8');
    for (const match of contents.matchAll(/from ['"]\.\/([^'"]+)['"]/g)) {
      const [name, query] = match[1].split('?');
      assert.equal(query, `v=${version}`, `${file} imports stale ${name}`);
      await verify(name);
    }
  }
  await verify('skills.js');
  assert.ok(seen.has('skill-timing.mjs'));
  assert.ok(seen.has('chat-stream.js'));
});
