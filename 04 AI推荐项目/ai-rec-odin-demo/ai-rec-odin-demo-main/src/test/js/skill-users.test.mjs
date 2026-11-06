import test from 'node:test';
import assert from 'node:assert/strict';
import { skillInputsMarkup, userMarkup, applyUser, supportsUserDataMode } from '../../main/resources/static/skill-users.mjs';

test('both recommendation skills support real UUID and demo user modes', () => {
  assert.equal(supportsUserDataMode('shopping-recommendation'), true);
  assert.equal(supportsUserDataMode('shopping-recommendation-fast'), true);
  assert.equal(supportsUserDataMode('shopping-recommendation-deep'), true);
  assert.equal(supportsUserDataMode('user-insight'), false);
  assert.equal(supportsUserDataMode(undefined), false);
});

const users = [
  { username: 'xiaoming', displayName: '小明', segment: '运动生活派', interests: ['跑步'], travelInterests: ['户外', '摄影'], behaviors: ['浏览跑鞋', '搜索长城'] },
  { username: 'xiaoyu', displayName: '小雨', segment: '数码效率派', interests: ['数码'], travelInterests: ['主题乐园'], behaviors: ['浏览耳机', '收藏乐园攻略'] }
];

test('shopping real mode accepts manual UUID and escapes the saved value', () => {
  const html = skillInputsMarkup(['username'], [], 'CaseSensitive"><img>', 'REAL');
  assert.match(html, /name="userDataMode"/);
  assert.match(html, /value="REAL" selected/);
  assert.match(html, /<input[^>]+name="username"/);
  assert.match(html, /maxlength="64"/);
  assert.match(html, /真实用户 UUID/);
  assert.match(html, /placeholder="请输入用户 UUID，例如 315c70c8f4a25681"/);
  assert.match(html, /不是登录 PIN/);
  assert.doesNotMatch(html, /真实用户 PIN|用户 PIN<\/label>/);
  assert.match(html, /CaseSensitive&quot;&gt;&lt;img&gt;/);
  assert.doesNotMatch(html, /disabled|<img>|mock/i);
});

test('shopping demo mode keeps the original user list instead of a manual input', () => {
  const html = skillInputsMarkup(['username'], users, 'xiaoyu', 'DEMO');
  assert.match(html, /value="DEMO" selected/);
  assert.match(html, /value="xiaoyu" selected/);
  assert.match(html, /演示用户/);
  assert.doesNotMatch(html, /<input|mock/i);
});

test('renders username as select with only users from server and preserves selection', () => {
  const html = skillInputsMarkup(['username', 'interests'], users, 'xiaoyu');
  assert.match(html, /<select[^>]+name="username"/);
  assert.doesNotMatch(html, /<input[^>]+name="username"/);
  assert.match(html, /value="xiaoyu" selected/);
  assert.match(html, /小明 \(xiaoming\) · 运动生活派/);
  assert.equal((html.match(/<option /g) ?? []).length, 2);
  assert.match(html, /<input[^>]+name="interests"/);
});

test('switching users replaces preview and previous interest override', () => {
  const interests = { value: '' };
  const form = { elements: { namedItem: name => name === 'interests' ? interests : null } };
  const preview = { innerHTML: '', hidden: true };
  applyUser(form, preview, users[0]);
  assert.equal(interests.value, '户外、摄影');
  assert.ok(preview.innerHTML.includes('搜索长城'));
  interests.value = '用户自定义兴趣';
  applyUser(form, preview, users[1]);
  assert.equal(interests.value, '主题乐园');
  assert.ok(preview.innerHTML.includes('收藏乐园攻略'));
  assert.ok(!preview.innerHTML.includes('搜索长城'));
  assert.equal(preview.hidden, false);
  applyUser(form, preview, undefined);
  assert.equal(interests.value, '');
  assert.equal(preview.hidden, true);
  assert.equal(preview.innerHTML, '');
});

test('shows business context without implementation details', () => {
  const preview = {};
  applyUser({ elements: { namedItem: () => null } }, preview, users[0]);
  assert.ok(preview.innerHTML.includes('兴趣与行为'));
  assert.doesNotMatch(preview.innerHTML, /mock|模拟|预置|样本/i);
  assert.ok(preview.innerHTML.includes('历史行为 · 2 条'));
  assert.ok(preview.innerHTML.includes('日常兴趣'));
});

test('empty user list is disabled and never creates arbitrary username input', () => {
  const html = skillInputsMarkup(['username'], [], 'unknown');
  assert.match(html, /required disabled/);
  assert.ok(html.includes('暂无可用用户'));
  assert.ok(!html.includes('<input'));
});

test('escapes user labels, input names, interests and history', () => {
  const user = { ...users[0], username: '\"><script>', displayName: '<img src=x>',
    interests: ['<svg>'], travelInterests: ['<iframe>'], behaviors: ['<script>alert(1)</script>'] };
  const html = skillInputsMarkup(['username', '\"><svg>'], [user], user.username) + userMarkup(user);
  for (const tag of ['<img', '<script', '<iframe', '<svg']) assert.ok(!html.includes(tag));
  assert.ok(html.includes('&lt;script&gt;'));
});

test('real memory account choices contain no fabricated interests, budget or behavior preview', () => {
  const user = { username: 'ctlihua', displayName: 'ctlihua', source: 'AI_MEMORY' };
  const html = skillInputsMarkup(['username', 'interests'], [user], 'ctlihua');
  assert.match(html, /ctlihua/);
  assert.match(html, /选择用户 UUID/);
  assert.doesNotMatch(html, /undefined|运动生活派|800/);
  const preview = {};
  const interests = { value: '上个用户的兴趣' };
  applyUser({ elements: { namedItem: () => interests } }, preview, user);
  assert.equal(interests.value, '');
  assert.match(preview.innerHTML, /执行时从 AI Memory/);
  assert.match(preview.innerHTML, /用户 UUID：ctlihua/);
  assert.doesNotMatch(preview.innerHTML, /用户 PIN/);
  assert.match(preview.innerHTML, /不代表已有画像/);
  assert.doesNotMatch(preview.innerHTML, /浏览跑鞋|收藏乐园|mock/i);
});
