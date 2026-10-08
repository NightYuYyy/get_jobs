import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const reader = readFileSync(new URL('../src/main/resources/boss-reply-reader.js', import.meta.url), 'utf8')
const boss = { uniqueId: 'conversation-fixture', friendId: 2001, lastIsSelf: false,
  lastText: '你好', lastTS: Date.now(), name: '离线测试' }
const friend = { __vue__: { $props: { boss } }, parentElement: null }
const messages = [{ mid: '1', uniqueId: boss.uniqueId, time: Date.now(), isSelf: false, messageType: 'text', text: '你好', status: 2 }]
const list = { __vue__: { messages } }
const input = { innerText: '' }
const document = {
  querySelectorAll: selector => selector === '.friend-content' ? [friend] : [],
  querySelector: selector => selector === '.friend-content.selected' ? friend
    : selector === '.chat-conversation ul.im-list' ? list
    : selector === '#chat-input[contenteditable="true"]' ? input : null,
}
const read = vm.runInNewContext('(' + reader + ')', { document })
assert.equal(read({ mode: 'friends' })[0].key, boss.uniqueId)
assert.equal(read({ mode: 'conversation' }).messages[0].id, '1')
input.innerText = '本人正在编辑'
assert.equal(read({ mode: 'conversation' }).draft, '本人正在编辑')
messages[0].mid = Number.MAX_SAFE_INTEGER + 1
assert.equal(read({ mode: 'conversation' }).supported, false)
messages[0].mid = {}
assert.equal(read({ mode: 'conversation' }).supported, false)
messages[0].mid = '2'
messages[0].uniqueId = 'other-conversation'
assert.equal(read({ mode: 'conversation' }).supported, false)
messages[0].uniqueId = boss.uniqueId
delete messages[0].isSelf
assert.equal(read({ mode: 'conversation' }).supported, false)
messages[0].isSelf = false
boss.groupId = 'group'
assert.equal(read({ mode: 'friends' }).length, 0)
delete boss.groupId
boss.internal = 1
assert.equal(read({ mode: 'friends' }).length, 0)
delete boss.internal
boss.lastIsSelf = undefined
assert.equal(read({ mode: 'friends' }).length, 0)
console.log('PASS: DOM fixture, stable IDs, manual draft, unsafe numeric IDs, missing sender, group/system filtering')
