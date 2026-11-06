import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { showView } from '../../main/resources/static/workspace.js';

const resources = new URL('../../main/resources/static/', import.meta.url);

function workspace() {
  const panels = new Map([['chat-view', { hidden: true }], ['skill-view', { hidden: false }]]);
  const links = ['chat', 'skills'].map(view => ({ dataset: { view }, attributes: {},
    classList: { toggle(name, active) { this[name] = active; } },
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; } }));
  return { body: { dataset: {} }, getElementById: id => panels.get(id), querySelectorAll: () => links, links };
}

test('switching views preserves existing panels and uses accessible navigation', () => {
  const document = workspace();
  const originalChat = document.getElementById('chat-view');
  assert.equal(showView(document, '#chat'), 'chat');
  assert.equal(document.getElementById('skill-view').hidden, true);
  assert.equal(originalChat.hidden, false);
  assert.equal(document.links[0].attributes['aria-current'], 'page');
  assert.equal(document.body.dataset.view, 'chat');
  showView(document, '#skills');
  assert.equal(document.getElementById('chat-view'), originalChat);
  assert.equal(originalChat.hidden, true);
  assert.equal(document.getElementById('skill-view').hidden, false);
  assert.equal(document.links[0].attributes['aria-current'], undefined);
  assert.equal(document.links[1].attributes['aria-current'], 'page');
});

test('empty and unknown fragments open the Skill laboratory', () => {
  for (const hash of ['', '#missing']) assert.equal(showView(workspace(), hash), 'skills');
});

test('a single workspace contains both features with no duplicate ids or retired navigation', async () => {
  const html = await readFile(new URL('skills.html', resources), 'utf8');
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const id of ['skill-view', 'chat-view', 'skill-form', 'chat-form', 'chat-status', 'status']) assert.ok(ids.includes(id), id);
  assert.doesNotMatch(html, /(?:href|src)="(?:index|rag)\./);
  assert.deepEqual((await readdir(resources)).filter(name => name.endsWith('.html')), ['skills.html']);
  for (const match of html.matchAll(/(?:href|src)="([^"#?]+)(?:\?[^"]*)?"/g)) await readFile(new URL(match[1], resources));
});

test('chat styles are scoped and cannot reset the Skill page', async () => {
  const css = await readFile(new URL('chat.css', resources), 'utf8');
  assert.doesNotMatch(css, /(?:^|\})\s*(?:body|:root|button|\.sidebar)\s*\{/);
  assert.match(css, /#chat-view \.chat-sidebar/);
  assert.match(css, /#chat-view textarea/);
});
