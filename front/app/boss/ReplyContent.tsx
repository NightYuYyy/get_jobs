'use client'

import { useCallback, useEffect, useState } from 'react'
import { API_BASE } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

type Item = {
  id: string; conversation: string; title: string; question: string; answer: string
  state: string; error: string; created_at: string
}
type Status = {
  enabled: boolean; autoSend: boolean; deliveryRunning: boolean; lastError: string
  items: Item[]; paused: { conversation: string }[]
}
const labels: Record<string, string> = {
  GENERATING: '生成中', DRAFT: '待确认', MANUAL: '需本人确认', SENDING: '发送中',
  SENT: '已确认发送', UNKNOWN: '结果未知，请到 BOSS 核对', FAILED: '失败',
  SKIPPED: '已忽略', CANCELLED: '已取消',
}

export default function ReplyContent() {
  const [status, setStatus] = useState<Status | null>(null)
  const [automatic, setAutomatic] = useState(false)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const response = await fetch(API_BASE + '/api/boss/replies', { cache: 'no-store' })
      if (!response.ok) throw new Error(response.status === 404 ? '当前运行版本尚未安装回复模块，请升级后使用。' : '读取回复状态失败')
      setStatus(await response.json())
      setError('')
    } catch (e) { setError(e instanceof Error ? e.message : '连接失败') }
  }, [])
  useEffect(() => {
    void refresh()
    const timer = setInterval(() => { void refresh() }, 5000)
    return () => clearInterval(timer)
  }, [refresh])

  const post = async (path: string, body: object = {}) => {
    setBusy(true)
    setError('')
    try {
      const response = await fetch(API_BASE + '/api/boss/replies' + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
      const result = await response.json()
      if (!response.ok) throw new Error(result.message || '操作失败')
      setStatus(result)
    } catch (e) { setError(e instanceof Error ? e.message : '连接失败') }
    finally { setBusy(false) }
  }

  const start = () => {
    const message = automatic
      ? '开启监听会把新 HR 消息和会话上下文发送到你配置的模型并产生费用。常见问候将自动发送，其他消息生成草稿等待确认。确定开启？'
      : '开启监听会把新 HR 消息和会话上下文发送到你配置的模型并产生费用，只生成草稿、不自动发送。确定开启？'
    if (window.confirm(message)) void post('/start', { autoSend: automatic })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>BOSS 回复助手</CardTitle>
        <CardDescription>
          默认关闭。复用现有 AI 与个人介绍；投递运行时暂停，停止或重启不会补发旧消息。
          第一版仅监控当前加载的普通 HR 会话，默认只生成草稿；不交换联系方式、不发送附件。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={automatic} disabled={busy || !!status?.enabled}
              onChange={e => setAutomatic(e.target.checked)} />
            常见问候自动发送（薪资、面试等仍需本人确认；每天最多 20 条）
          </label>
          <Button onClick={start} disabled={busy || !status || status.enabled || status.deliveryRunning}>开启监听</Button>
          <Button variant="outline" onClick={() => void post('/stop')} disabled={busy || !status?.enabled}>停止监听</Button>
          <span className="text-sm">{status?.deliveryRunning ? '投递中：回复暂停' : status?.enabled ? '监听中' : '未开启'}</span>
        </div>
        {(error || status?.lastError) && <p role="alert" className="text-sm text-red-600">{error || status?.lastError}</p>}
        {status?.items.length === 0 && <p className="text-sm text-muted-foreground">开启后只处理新消息；已有历史消息不会自动回复。</p>}
        {status?.items.map(item => {
          const paused = status.paused.some(p => p.conversation === item.conversation)
          const pending = item.state === 'DRAFT' || item.state === 'MANUAL'
          const answer = answers[item.id] ?? item.answer
          return (
            <section key={item.id} className="space-y-2 rounded-lg border p-4">
              <div className="flex flex-wrap justify-between gap-2">
                <h3 className="font-medium">{item.title}</h3>
                <span className="text-sm">{labels[item.state] || item.state}{paused ? ' · 人工接管' : ''}</span>
              </div>
              <p className="whitespace-pre-wrap text-sm">HR：{item.question}</p>
              <label className="block text-sm">
                回复内容
                <textarea className="mt-1 min-h-24 w-full rounded-md border bg-background p-2"
                  maxLength={300} value={answer} disabled={!pending || busy}
                  onChange={e => setAnswers(previous => ({ ...previous, [item.id]: e.target.value }))} />
              </label>
              {item.error && <p role="alert" className="text-sm text-red-600">{item.error}</p>}
              <div className="flex flex-wrap gap-2">
                <Button disabled={busy || !pending || !status.enabled || status.deliveryRunning || paused || !answer.trim()}
                  onClick={() => {
                    if (window.confirm('将向 ' + item.title + ' 发送以下内容：\n\n' + answer)) void post('/' + item.id + '/send', { answer })
                  }}>确认发送</Button>
                <Button variant="outline" disabled={busy || !pending} onClick={() => void post('/' + item.id + '/dismiss')}>忽略</Button>
                <Button variant="outline" disabled={busy}
                  onClick={() => void post('/sessions/' + encodeURIComponent(item.conversation) + (paused ? '/resume' : '/pause'))}>
                  {paused ? '恢复此会话' : '人工接管'}
                </Button>
              </div>
            </section>
          )
        })}
      </CardContent>
    </Card>
  )
}
