import { escapeHtml as escape } from './skill-utils.mjs?v=20261103-parallel-recall';

export function supportsUserDataMode(skillId) {
  return ['shopping-recommendation', 'shopping-recommendation-fast', 'shopping-recommendation-deep'].includes(skillId);
}

export function skillInputsMarkup(requiredInputs, users, selectedUsername, mode = null) {
  const selector = mode === null ? '' : `<label for="user-data-mode">用户来源</label><select id="user-data-mode" name="userDataMode"><option value="REAL"${mode === 'REAL' ? ' selected' : ''}>真实用户 UUID</option><option value="DEMO"${mode === 'DEMO' ? ' selected' : ''}>演示用户</option></select>`;
  return selector + requiredInputs.map((name, index) => {
    const id = `skill-input-${index}`;
    if (name === 'username') {
      if (mode === 'REAL') return `<label for="${id}">用户 UUID</label><input id="${id}" name="username" value="${escape(selectedUsername)}" placeholder="请输入用户 UUID，例如 315c70c8f4a25681" maxlength="64" required autocomplete="off"><p class="field-hint">请填写 AI Memory 对应的用户 UUID，不是登录 PIN；将原样用于 user_id 查询，不做账号转换。</p>`;
      const options = users.map(user => `<option value="${escape(user.username)}"${user.username === selectedUsername ? ' selected' : ''}>${escape(user.displayName)} (${escape(user.username)})${user.segment ? ` · ${escape(user.segment)}` : ''}</option>`).join('');
      const label = mode === 'DEMO' ? '选择演示用户' : users.length && users.every(user => user.source === 'AI_MEMORY') ? '选择用户 UUID' : '选择用户';
      return `<label for="${id}">${label}</label><select id="${id}" name="username" required${users.length ? '' : ' disabled'}>${options || '<option value="">暂无可用用户</option>'}</select>`;
    }
    const label = name === 'interests' ? '本次出游兴趣（请填写本次需求）' : name;
    const placeholder = name === 'interests' ? '例如：历史文化、摄影、园林，喜欢轻松步行' : `请输入 ${name}`;
    return `<label for="${id}">${escape(label)}</label><input id="${id}" name="${escape(name)}" placeholder="${escape(placeholder)}" maxlength="512" required autocomplete="off">`;
  }).join('');
}

export function userMarkup(user) {
  if (!user) return '';
  if (user.source === 'AI_MEMORY') return `<h3>用户 UUID：${escape(user.username)}</h3><p class="field-hint">执行时从 AI Memory 查询画像与历史行为。UUID 原样作为 user_id 发送，不做登录账号转换。输入或选择 UUID 不代表已有画像；若无记忆，结果会明确说明资料不足。</p>`;
  return `<h3>${escape(user.displayName)}的兴趣与行为</h3><p class="field-hint">结合兴趣偏好和近期行为，为你发现更适合的推荐。</p>
    <p><strong>日常兴趣</strong> ${user.interests.map(escape).join('、')}</p>
    <p><strong>出游兴趣</strong> ${user.travelInterests.map(escape).join('、')}</p>
    <details><summary>历史行为 · ${user.behaviors.length} 条</summary><ul>${user.behaviors.map(behavior => `<li>${escape(behavior)}</li>`).join('')}</ul></details>`;
}

export function applyUser(form, preview, user) {
  const interests = form.elements.namedItem('interests');
  if (interests) interests.value = user?.travelInterests?.join('、') ?? '';
  preview.innerHTML = userMarkup(user);
  preview.hidden = !user;
}
