arg => {
  const bossOf = el => {
    for (let node = el, depth = 0; node && depth < 6; node = node.parentElement, depth++) {
      const vm = node.__vue__;
      const boss = vm && (vm.$props?.boss || vm.boss || vm.$props?.source || vm.source);
      if (boss?.uniqueId) return boss;
    }
    return null;
  };
  const textOf = html => {
    return String(html || '').replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]*>/g, '').trim().slice(0, 1000);
  };
  const idOf = id => {
    if (typeof id === 'string') return id;
    return typeof id === 'number' && Number.isSafeInteger(id) ? String(id) : '';
  };
  // ponytail: 仅扫描已加载的 100 个会话；需要全量监听时再增加分页适配。
  const friends = Array.from(document.querySelectorAll('.friend-content')).slice(0, 100)
    .map((el, index) => {
      const boss = bossOf(el);
      if (!boss || boss.groupId || boss.gid || boss.internal || Number(boss.friendId) <= 1000) return null;
      const key = idOf(boss.uniqueId);
      if (!key || typeof boss.lastIsSelf !== 'boolean') return null;
      const time = Number(boss.lastTS || boss.updateTime || 0);
      return {key, index, title: [boss.brandName, boss.title, boss.name].filter(Boolean).join(' / ').slice(0, 120),
        fingerprint: JSON.stringify([boss.lastText || '', boss.lastTS || '', boss.lastMid || '', boss.lastIsSelf]),
        self: boss.lastIsSelf, time: time > 0 && time < 100000000000 ? time * 1000 : time};
    }).filter(Boolean);
  if (arg.mode === 'friends') return friends;
  const selected = document.querySelector('.friend-content.selected');
  const boss = bossOf(selected);
  const list = document.querySelector('.chat-conversation ul.im-list');
  const vm = list?.__vue__;
  const input = document.querySelector('#chat-input[contenteditable="true"]');
  if (!boss?.uniqueId || !Array.isArray(vm?.messages) || !input) return {supported: false};
  const messages = vm.messages.slice(-20).map(m => ({
    id: idOf(m.mid), key: idOf(m.uniqueId), time: Number(m.time || 0), self: m.isSelf, type: m.messageType,
    text: textOf(m.text), status: m.status
  }));
  if (!messages.length || messages.some(m => !m.id || m.key !== idOf(boss.uniqueId) || !Number.isFinite(m.time) || m.time <= 0 || typeof m.self !== 'boolean')) return {supported: false};
  return {supported: true, key: idOf(boss.uniqueId), messages, draft: (input.innerText || '').trim()};
}
