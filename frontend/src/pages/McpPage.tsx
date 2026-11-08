/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
import { useEffect, useMemo, useState } from 'react'
import {
  DEFAULT_MCP_KEY,
  errorMessage,
  getMcpApiKey,
  mcpInfo,
  mcpRequest,
  setMcpApiKey,
  api,
  type McpRawResult,
} from '../api/client'
import type {
  McpApiKey,
  McpCallAudit,
  McpConfig,
  McpExternalServer,
  McpInfo,
  McpKeyCreateResult,
  McpTool,
  PageResult,
} from '../api/types'
import { pageItems, pageTotal } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock, ErrorBlock, LoadingBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Drawer } from '../components/Drawer'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { JsonView } from '../components/JsonView'
import { Modal } from '../components/Modal'
import { Pager } from '../components/Pager'
import { useToast } from '../components/Toast'
import { formatDateTime, safeStringify, truncate } from '../utils/format'
import { mcpKeyStatusLabel, mcpKeyStatusTone } from '../utils/labels'

const METHODS = [
  { value: 'initialize', label: 'initialize · 初始化握手' },
  { value: 'ping', label: 'ping · 连通性探测' },
  { value: 'tools/list', label: 'tools/list · 工具清单' },
  { value: 'tools/call', label: 'tools/call · 调用工具' },
  { value: 'resources/list', label: 'resources/list · 资源清单' },
  { value: 'resources/read', label: 'resources/read · 读取资源' },
  { value: 'prompts/list', label: 'prompts/list · 提示词清单' },
  { value: 'prompts/get', label: 'prompts/get · 获取提示词' },
  { value: 'notifications/initialized', label: 'notifications/initialized · 通知（202）' },
]

const TEMPLATES: Record<string, string> = {
  initialize: safeStringify({
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'tenancy-console', version: '1.0.0' },
  }),
  ping: '{}',
  'tools/list': '{}',
  'tools/call': safeStringify({ name: 'tenant.list', arguments: { page: 0, size: 10 } }),
  'resources/list': '{}',
  'resources/read': safeStringify({ uri: 'tenancy://tenants' }),
  'prompts/list': '{}',
  'prompts/get': safeStringify({ name: 'tenant_review', arguments: { tenantId: '' } }),
  'notifications/initialized': '{}',
}

function extractTools(payload: unknown): McpTool[] {
  if (!payload || typeof payload !== 'object') return []
  const record = payload as Record<string, unknown>
  const candidates = [record.result, record]
  for (const candidate of candidates) {
    if (candidate && typeof candidate === 'object') {
      const tools = (candidate as Record<string, unknown>).tools
      if (Array.isArray(tools)) {
        return tools.filter(
          (tool): tool is McpTool =>
            Boolean(tool) && typeof tool === 'object' && typeof (tool as McpTool).name === 'string',
        )
      }
    }
  }
  return []
}

export function McpPage({ focus }: { focus?: 'keys' | 'audits' }) {
  const toast = useToast()
  const [apiKey, setApiKeyState] = useState(() => getMcpApiKey())
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [externalDrawerOpen, setExternalDrawerOpen] = useState(false)

  const scrollToSection = (title: string) => {
    const heading = [...document.querySelectorAll('.card-head h3')].find(
      (element) => element.textContent?.trim() === title,
    )
    const card = heading?.closest('.card')
    card?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  useEffect(() => {
    if (!focus) return
    const timer = window.setTimeout(
      () => scrollToSection(focus === 'keys' ? '我的 API Key' : '调用审计'),
      500,
    )
    return () => window.clearTimeout(timer)
  }, [focus])

  const info = useAsync<McpInfo>(async () => {
    const raw = await mcpInfo({ apiKey: apiKey.trim() || DEFAULT_MCP_KEY })
    return (raw ?? {}) as McpInfo
  }, [])

  const tools = useAsync<McpTool[]>(async () => {
    const res = await mcpRequest(
      { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} },
      { apiKey: apiKey.trim() || DEFAULT_MCP_KEY },
    )
    if (!res.ok) {
      throw new Error(`tools/list 失败：HTTP ${res.status}${res.text ? ` · ${truncate(res.text, 160)}` : ''}`)
    }
    const list = extractTools(res.json)
    const error = (res.json as { error?: { message?: string } } | null)?.error
    if (!list.length && error) throw new Error(`tools/list 失败：${error.message || '未知错误'}`)
    return list
  }, [])

  const [method, setMethod] = useState('initialize')
  const [paramsText, setParamsText] = useState(TEMPLATES.initialize)
  const [requestId, setRequestId] = useState(1)
  const [sending, setSending] = useState(false)
  const [response, setResponse] = useState<McpRawResult | null>(null)
  const [sendError, setSendError] = useState<string | null>(null)

  const handleApiKeyChange = (value: string) => {
    setApiKeyState(value)
    setMcpApiKey(value)
  }

  const handleMethodChange = (value: string) => {
    setMethod(value)
    setParamsText(TEMPLATES[value] ?? '{}')
    setResponse(null)
    setSendError(null)
  }

  const requestBody = useMemo(() => {
    const isNotification = method.startsWith('notifications/')
    const body: Record<string, unknown> = { jsonrpc: '2.0', method }
    if (!isNotification) body.id = requestId
    let params: unknown
    let paramsError: string | null = null
    if (paramsText.trim()) {
      try {
        params = JSON.parse(paramsText)
      } catch {
        paramsError = 'params 不是合法 JSON'
      }
    }
    if (params !== undefined) body.params = params
    return { body, paramsError, isNotification }
  }, [method, paramsText, requestId])

  const send = async () => {
    setSendError(null)
    if (requestBody.paramsError) {
      setSendError(requestBody.paramsError)
      return
    }
    setSending(true)
    try {
      const res = await mcpRequest(requestBody.body, {
        apiKey: apiKey.trim() || DEFAULT_MCP_KEY,
        sessionId,
      })
      setResponse(res)
      if (res.sessionId) setSessionId(res.sessionId)
      if (!requestBody.isNotification) setRequestId((current) => current + 1)
      if (res.ok) toast.success(`MCP ${method} 调用完成（HTTP ${res.status}）`)
      else toast.error(`MCP 返回 HTTP ${res.status}`)
    } catch (err) {
      const message = errorMessage(err)
      setSendError(message)
      toast.error(message)
    } finally {
      setSending(false)
    }
  }

  const infoData = info.data
  const resourceCount = Array.isArray(infoData?.resources) ? infoData.resources.length : undefined
  const promptCount = Array.isArray(infoData?.prompts) ? infoData.prompts.length : undefined

  const rawInfoText = infoData ? safeStringify(infoData) : ''

  const { isPlatformAdmin } = useAuth()

  const copyText = (value: string) => {
    if (navigator.clipboard) {
      navigator.clipboard
        .writeText(value)
        .then(() => toast.success('已复制到剪贴板'))
        .catch(() => toast.error('复制失败，请手动选择'))
    } else {
      toast.error('当前环境不支持复制')
    }
  }

  /* ---------- v5：MCP 配置 ---------- */
  const [configOpen, setConfigOpen] = useState(false)
  const config = useAsync(() => api.get<McpConfig>('/api/mcp/config'), [])

  /* ---------- v5：我的 API Key ---------- */
  const [allKeys, setAllKeys] = useState(false)
  const keys = useAsync(
    () => api.get<McpApiKey[]>(`/api/mcp/keys${allKeys ? '?all=true' : ''}`),
    [allKeys],
  )
  const keyItems = keys.data ?? []

  const [createKeyOpen, setCreateKeyOpen] = useState(false)
  const [keyName, setKeyName] = useState('')
  const [keyExpires, setKeyExpires] = useState('')
  const [keyError, setKeyError] = useState<string | null>(null)
  const [creatingKey, setCreatingKey] = useState(false)
  const [createdKey, setCreatedKey] = useState<McpKeyCreateResult | null>(null)
  const [revokeTarget, setRevokeTarget] = useState<McpApiKey | null>(null)
  const [revoking, setRevoking] = useState(false)

  const openCreateKey = () => {
    setKeyName('')
    setKeyExpires('')
    setKeyError(null)
    setCreateKeyOpen(true)
  }

  const createKey = async () => {
    setKeyError(null)
    if (!keyName.trim()) {
      setKeyError('请输入 Key 名称')
      return
    }
    const expires = keyExpires.trim() ? Number(keyExpires) : undefined
    if (expires !== undefined && (!Number.isInteger(expires) || expires < 1 || expires > 3650)) {
      setKeyError('有效期必须为 1-3650 天的整数')
      return
    }
    setCreatingKey(true)
    try {
      const result = await api.post<McpKeyCreateResult>('/api/mcp/keys', {
        name: keyName.trim(),
        expiresInDays: expires,
      })
      setCreateKeyOpen(false)
      setCreatedKey(result)
      keys.reload()
    } catch (err) {
      setKeyError(errorMessage(err))
    } finally {
      setCreatingKey(false)
    }
  }

  const revokeKey = async () => {
    if (!revokeTarget) return
    setRevoking(true)
    try {
      await api.delete<unknown>(`/api/mcp/keys/${revokeTarget.id}`)
      toast.success('API Key 已撤销')
      setRevokeTarget(null)
      keys.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setRevoking(false)
    }
  }

  /* ---------- v5：调用审计 ---------- */
  const [auditPage, setAuditPage] = useState(0)
  const [auditTool, setAuditTool] = useState('')
  const [auditToolInput, setAuditToolInput] = useState('')
  const [auditSuccess, setAuditSuccess] = useState('')
  const auditQuery = useMemo(() => {
    const params = new URLSearchParams()
    if (auditTool) params.set('tool', auditTool)
    if (auditSuccess) params.set('success', auditSuccess)
    params.set('page', String(auditPage))
    params.set('size', '10')
    return params.toString()
  }, [auditTool, auditSuccess, auditPage])
  const audits = useAsync(
    () => api.get<PageResult<McpCallAudit>>(`/api/mcp/audits?${auditQuery}`),
    [auditQuery],
  )
  const auditItems = pageItems(audits.data)

  /* ---------- v5：外部 MCP 服务器 ---------- */
  const external = useAsync(
    () => (isPlatformAdmin ? api.get<McpExternalServer[]>('/api/mcp/external-servers') : Promise.resolve([])),
    [isPlatformAdmin],
  )
  const serverItems = external.data ?? []

  const [serverOpen, setServerOpen] = useState(false)
  const [serverName, setServerName] = useState('')
  const [serverTransport, setServerTransport] = useState('STREAMABLE_HTTP')
  const [serverUrl, setServerUrl] = useState('')
  const [serverHeaders, setServerHeaders] = useState('')
  const [serverError, setServerError] = useState<string | null>(null)
  const [addingServer, setAddingServer] = useState(false)
  const [checkingServerId, setCheckingServerId] = useState<string | null>(null)
  const [deleteServerTarget, setDeleteServerTarget] = useState<McpExternalServer | null>(null)
  const [deletingServer, setDeletingServer] = useState(false)

  const openAddServer = () => {
    setServerName('')
    setServerTransport('STREAMABLE_HTTP')
    setServerUrl('')
    setServerHeaders('')
    setServerError(null)
    setServerOpen(true)
  }

  const addServer = async () => {
    setServerError(null)
    if (!serverName.trim() || !serverUrl.trim()) {
      setServerError('请输入服务器名称与 URL')
      return
    }
    let headers: Record<string, string> | undefined
    if (serverHeaders.trim()) {
      try {
        const parsed = JSON.parse(serverHeaders)
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          setServerError('headers 必须是 JSON 对象')
          return
        }
        headers = parsed as Record<string, string>
      } catch {
        setServerError('headers 不是合法 JSON')
        return
      }
    }
    setAddingServer(true)
    try {
      await api.post<McpExternalServer>('/api/mcp/external-servers', {
        name: serverName.trim(),
        transport: serverTransport,
        url: serverUrl.trim(),
        headers,
      })
      toast.success('外部 MCP 服务器已添加')
      setServerOpen(false)
      external.reload()
    } catch (err) {
      setServerError(errorMessage(err))
    } finally {
      setAddingServer(false)
    }
  }

  const checkServer = async (server: McpExternalServer) => {
    setCheckingServerId(server.id)
    try {
      const result = await api.post<{ ok?: boolean; status?: string; message?: string }>(
        `/api/mcp/external-servers/${server.id}/check`,
      )
      if (result?.ok === false) toast.error(result.message || '连通性检查失败')
      else toast.success(`连通性检查完成：${result?.status || 'OK'}`)
      external.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setCheckingServerId(null)
    }
  }

  const deleteServer = async () => {
    if (!deleteServerTarget) return
    setDeletingServer(true)
    try {
      await api.delete<unknown>(`/api/mcp/external-servers/${deleteServerTarget.id}`)
      toast.success('外部 MCP 服务器已删除')
      setDeleteServerTarget(null)
      external.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeletingServer(false)
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>MCP 控制台</h2>
            <p>
              JSON-RPC 2.0 调试：工具目录来自 <code className="inline-json">tools/list</code>，支持 initialize /
              tools/call / resources / prompts 与 SSE 能力快照
            </p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setConfigOpen(true)}>
            <Icon name="key" size={15} />
            获取 MCP
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => scrollToSection('我的 API Key')}>
            我的 API Keys
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => scrollToSection('调用审计')}>
            调用审计
          </button>
          {isPlatformAdmin ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setExternalDrawerOpen(true)}>
              <Icon name="server" size={15} />
              外部 MCP 服务器
            </button>
          ) : null}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => {
              info.reload()
              tools.reload()
              keys.reload()
              audits.reload()
              if (isPlatformAdmin) external.reload()
            }}
          >
            <Icon name="refresh" size={15} />
            刷新连接
          </button>
        </div>
      </header>

      <div className="card mcp-info-strip">
        <div className="mcp-info-main">
          <span className="muted">协议版本</span>
          <Badge tone="blue">{infoData?.protocolVersion || '2025-06-18'}</Badge>
          <span className="muted">API Key</span>
          <Badge tone={infoData?.apiKeyRequired === false ? 'gray' : 'amber'}>
            {infoData?.apiKeyRequired === false ? '无需校验' : '需要 X-API-Key'}
          </Badge>
          <span className="muted">工具</span>
          <Badge tone="green">{tools.data?.length ?? '—'} 个</Badge>
          {typeof resourceCount === 'number' ? (
            <>
              <span className="muted">资源</span>
              <Badge tone="cyan">{resourceCount} 个</Badge>
            </>
          ) : null}
          {typeof promptCount === 'number' ? (
            <>
              <span className="muted">提示词</span>
              <Badge tone="purple">{promptCount} 个</Badge>
            </>
          ) : null}
          {sessionId ? (
            <>
              <span className="muted">会话</span>
              <span className="mono session-id">{sessionId}</span>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSessionId(null)}>
                清除会话
              </button>
            </>
          ) : null}
        </div>
        {info.loading ? (
          <span className="muted">正在读取 /mcp/info…</span>
        ) : info.error ? (
          <span className="text-red inline-error">
            <Icon name="alert" size={14} /> {info.error}
          </span>
        ) : (
          <details className="raw-details mcp-info-details">
            <summary>/mcp/info 原始响应</summary>
            <JsonView value={rawInfoText ? infoData : null} maxHeight={220} />
          </details>
        )}
      </div>

      <SectionCard
        title="我的 API Key"
        description="用于外部 MCP 客户端认证；明文仅在创建时展示一次，撤销后立即失效"
        actions={
          <div className="card-head-actions">
            {isPlatformAdmin ? (
              <label className="checkbox-row mask-toggle">
                <input type="checkbox" checked={allKeys} onChange={(event) => setAllKeys(event.target.checked)} />
                <span>全部用户</span>
              </label>
            ) : null}
            <button type="button" className="btn btn-primary btn-sm" onClick={openCreateKey}>
              <Icon name="plus" size={15} />
              新建 Key
            </button>
          </div>
        }
      >
        <AsyncBlock
          loading={keys.loading}
          error={keys.error}
          onRetry={keys.reload}
          empty={!keyItems.length}
          emptyText="暂无 API Key"
          emptyHint="点击「新建 Key」生成个人 MCP 凭据，明文只显示一次"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>Key 前缀</th>
                  <th>状态</th>
                  {allKeys ? <th>所属用户</th> : null}
                  <th>创建时间</th>
                  <th>过期时间</th>
                  <th>最近使用</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {keyItems.map((key) => (
                  <tr key={key.id}>
                    <td>{key.name}</td>
                    <td className="mono">{key.keyPrefix}…</td>
                    <td>
                      <Badge tone={mcpKeyStatusTone(key.status)} dot>
                        {mcpKeyStatusLabel(key.status)}
                      </Badge>
                    </td>
                    {allKeys ? <td className="muted">{key.ownerUsername || '—'}</td> : null}
                    <td className="muted nowrap">{formatDateTime(key.createdAt)}</td>
                    <td className="muted nowrap">{formatDateTime(key.expiresAt)}</td>
                    <td className="muted nowrap">{formatDateTime(key.lastUsedAt)}</td>
                    <td className="align-right">
                      {key.status === 'ACTIVE' ? (
                        <button
                          type="button"
                          className="link-btn danger"
                          onClick={() => setRevokeTarget(key)}
                          aria-label="撤销 Key"
                        >
                          撤销
                        </button>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AsyncBlock>
      </SectionCard>

      <SectionCard
        title="调用审计"
        description="tools/call 调用记录（本人；平台管理员全量），支持按工具与结果筛选"
        actions={
          <div className="audit-toolbar">
            <input
              className="input input-tool"
              value={auditToolInput}
              placeholder="按工具名筛选，如 tenant.list"
              onChange={(event) => setAuditToolInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  setAuditTool(auditToolInput.trim())
                  setAuditPage(0)
                }
              }}
            />
            <select
              className="select select-compact"
              value={auditSuccess}
              onChange={(event) => {
                setAuditSuccess(event.target.value)
                setAuditPage(0)
              }}
            >
              <option value="">全部结果</option>
              <option value="true">成功</option>
              <option value="false">失败</option>
            </select>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setAuditTool(auditToolInput.trim())
                setAuditPage(0)
              }}
            >
              查询
            </button>
            <button type="button" className="icon-btn" onClick={audits.reload} title="刷新调用审计">
              <Icon name="refresh" size={15} />
            </button>
          </div>
        }
      >
        <AsyncBlock
          loading={audits.loading}
          error={audits.error}
          onRetry={audits.reload}
          empty={!auditItems.length}
          emptyText="暂无调用审计"
          emptyHint="使用 X-API-Key 调用 tools/call 后这里会出现记录"
        >
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>Key 前缀</th>
                  <th>用户</th>
                  <th>工具</th>
                  <th>结果</th>
                  <th>错误码</th>
                  <th>耗时</th>
                  <th>IP</th>
                  <th>User-Agent</th>
                </tr>
              </thead>
              <tbody>
                {auditItems.map((audit) => (
                  <tr key={audit.id}>
                    <td className="muted nowrap">{formatDateTime(audit.at)}</td>
                    <td className="mono">{audit.keyPrefix ? `${audit.keyPrefix}…` : '—'}</td>
                    <td className="muted">{audit.ownerUsername || '—'}</td>
                    <td className="mono">{audit.tool}</td>
                    <td>
                      <Badge tone={audit.success ? 'green' : 'red'} dot>
                        {audit.success ? '成功' : '失败'}
                      </Badge>
                    </td>
                    <td className="muted">{audit.errorCode ?? '—'}</td>
                    <td className="muted">{audit.durationMs != null ? `${audit.durationMs} ms` : '—'}</td>
                    <td className="mono muted">{audit.ip || '—'}</td>
                    <td className="muted ua-cell" title={audit.userAgent ?? undefined}>
                      {audit.userAgent ? truncate(audit.userAgent, 40) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={auditPage}
            totalPages={audits.data?.totalPages}
            total={pageTotal(audits.data)}
            onPage={setAuditPage}
            disabled={audits.loading}
          />
        </AsyncBlock>
      </SectionCard>

      {isPlatformAdmin ? (
        <Drawer
          open={externalDrawerOpen}
          title="外部 MCP 服务器"
          subtitle="右侧抽屉配置：注册外部 MCP 服务并做连通性检查（Streamable HTTP / SSE）"
          width={860}
          onClose={() => setExternalDrawerOpen(false)}
          footer={
            <button type="button" className="btn btn-ghost" onClick={() => setExternalDrawerOpen(false)}>
              关闭
            </button>
          }
        >
        <SectionCard
          title="外部 MCP 服务器"
          description="平台管理员可注册外部 MCP 服务器并做连通性检查（Streamable HTTP / SSE）"
          actions={
            <div className="card-head-actions">
              <button type="button" className="btn btn-primary btn-sm" onClick={openAddServer}>
                <Icon name="plus" size={15} />
                新增服务器
              </button>
              <button type="button" className="icon-btn" onClick={external.reload} title="刷新外部服务器">
                <Icon name="refresh" size={15} />
              </button>
            </div>
          }
        >
          <AsyncBlock
            loading={external.loading}
            error={external.error}
            onRetry={external.reload}
            empty={!serverItems.length}
            emptyText="暂无外部 MCP 服务器"
            emptyHint="注册外部 MCP 服务器后可做连通性检查"
          >
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>名称</th>
                    <th>传输</th>
                    <th>URL</th>
                    <th>状态</th>
                    <th>最近检查</th>
                    <th>结果</th>
                    <th className="align-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {serverItems.map((server) => (
                    <tr key={server.id}>
                      <td>{server.name}</td>
                      <td>
                        <Badge tone="gray">{server.transport}</Badge>
                      </td>
                      <td className="mono muted server-url" title={server.url}>
                        {server.url}
                      </td>
                      <td className="muted">{server.status || '—'}</td>
                      <td className="muted nowrap">{formatDateTime(server.lastCheckAt)}</td>
                      <td>
                        {server.lastCheckOk == null ? (
                          <span className="muted">未检查</span>
                        ) : (
                          <Badge tone={server.lastCheckOk ? 'green' : 'red'} dot>
                            {server.lastCheckOk ? '连通' : '不可用'}
                          </Badge>
                        )}
                      </td>
                      <td className="align-right nowrap">
                        <button
                          type="button"
                          className="link-btn"
                          disabled={checkingServerId === server.id}
                          onClick={() => checkServer(server)}
                          aria-label="检查连通性"
                        >
                          {checkingServerId === server.id ? '检查中…' : '检查'}
                        </button>
                        <button
                          type="button"
                          className="link-btn danger"
                          onClick={() => setDeleteServerTarget(server)}
                          aria-label="删除外部服务器"
                        >
                          删除
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </AsyncBlock>
        </SectionCard>
        </Drawer>
      ) : null}

      <SectionCard
        title="工具目录"
        description="来自 MCP tools/list，点击展开 inputSchema"
        actions={<Badge tone="blue">{tools.data?.length ?? 0} / 14</Badge>}
      >
        {tools.loading ? (
          <LoadingBlock text="正在读取 MCP 工具清单…" />
        ) : tools.error ? (
          <ErrorBlock
            message={tools.error}
            onRetry={tools.reload}
            title="无法读取工具目录（请检查 mcp-service 与 X-API-Key）"
          />
        ) : !tools.data?.length ? (
          <p className="muted">MCP 服务未返回任何工具。</p>
        ) : (
          <div className="tool-grid">
            {tools.data.map((tool) => (
              <article key={tool.name} className="tool-card">
                <header className="tool-card-head">
                  <span className="tool-icon">
                    <Icon name="mcp" size={16} />
                  </span>
                  <strong className="mono">{tool.name}</strong>
                </header>
                <p>{tool.description || '暂无描述'}</p>
                <details className="schema-details">
                  <summary>inputSchema</summary>
                  <JsonView value={tool.inputSchema} empty="无参数" maxHeight={220} />
                </details>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm tool-try"
                  onClick={() => {
                    setMethod('tools/call')
                    setParamsText(safeStringify({ name: tool.name, arguments: {} }))
                    setResponse(null)
                    setSendError(null)
                    document.getElementById('mcp-console')?.scrollIntoView({ behavior: 'smooth' })
                  }}
                >
                  在控制台调试
                  <Icon name="arrowRight" size={14} />
                </button>
              </article>
            ))}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="JSON-RPC 控制台"
        description="手动构造请求并查看原始响应；initialize 返回的 Mcp-Session-Id 会自动透传"
        className="mcp-console"
      >
        <div id="mcp-console" className="mcp-console-grid">
          <div className="mcp-console-form">
            <div className="field">
              <label className="field-label">X-API-Key</label>
              <div className="input-with-button">
                <input
                  className="input mono"
                  value={apiKey}
                  placeholder={DEFAULT_MCP_KEY}
                  onChange={(event) => handleApiKeyChange(event.target.value)}
                />
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => handleApiKeyChange(DEFAULT_MCP_KEY)}>
                  默认值
                </button>
              </div>
              <p className="field-hint">默认 dev-mcp-key，持久化在浏览器 localStorage</p>
            </div>
            <div className="form-grid form-grid-2">
              <div className="field">
                <label className="field-label">方法</label>
                <select className="select" value={method} onChange={(event) => handleMethodChange(event.target.value)}>
                  {METHODS.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label className="field-label">id</label>
                <input
                  className="input mono"
                  value={requestBody.isNotification ? '（通知无 id）' : requestId}
                  disabled={requestBody.isNotification}
                  onChange={(event) => {
                    const value = Number(event.target.value)
                    if (Number.isInteger(value) && value > 0) setRequestId(value)
                  }}
                />
              </div>
            </div>
            <div className="field">
              <label className="field-label">params（JSON，可留空）</label>
              <textarea
                className={`input json-editor ${requestBody.paramsError ? 'has-error' : ''}`}
                rows={9}
                value={paramsText}
                spellCheck={false}
                onChange={(event) => {
                  setParamsText(event.target.value)
                  setSendError(null)
                }}
              />
              {requestBody.paramsError ? (
                <p className="field-error">{requestBody.paramsError}</p>
              ) : null}
            </div>
            <button type="button" className="btn btn-primary btn-block" onClick={send} disabled={sending}>
              {sending ? <span className="spinner spinner-light" /> : <Icon name="play" size={15} />}
              {sending ? '发送中…' : '发送 JSON-RPC 请求'}
            </button>
            {sendError ? (
              <div className="alert alert-red mcp-send-error" role="alert">
                <Icon name="alert" size={16} />
                <span>{sendError}</span>
              </div>
            ) : null}
            <details className="raw-details">
              <summary>本次请求 JSON</summary>
              <JsonView value={requestBody.body} maxHeight={220} />
            </details>
          </div>

          <div className="mcp-console-response">
            <div className="response-head">
              <span className="field-label">响应</span>
              {response ? (
                <>
                  <Badge tone={response.ok ? 'green' : 'red'}>
                    HTTP {response.status} {response.ok ? 'OK' : 'ERROR'}
                  </Badge>
                  <span className="muted">{response.latencyMs} ms</span>
                  {response.sessionId ? (
                    <span className="muted mono">session: {truncate(response.sessionId, 18)}</span>
                  ) : null}
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    onClick={() => {
                      navigator.clipboard
                        ?.writeText(response.text)
                        .then(() => toast.success('响应已复制'))
                        .catch(() => toast.error('复制失败'))
                    }}
                  >
                    <Icon name="copy" size={14} />
                    复制
                  </button>
                </>
              ) : (
                <span className="muted">尚未发送请求</span>
              )}
            </div>
            {response ? (
              response.text ? (
                <JsonView value={response.json ?? response.text} maxHeight={420} />
              ) : (
                <p className="muted response-empty">
                  HTTP {response.status} 无响应体
                  {response.status === 202 ? '（通知类请求按约定返回 202 Accepted）' : ''}
                </p>
              )
            ) : (
              <div className="response-placeholder">
                <Icon name="mcp" size={28} />
                <p>选择方法并点击「发送 JSON-RPC 请求」查看响应</p>
                <span className="muted">
                  例如 tools/call tenant.list 查询租户，或 resources/read 读取 tenancy://tenants
                </span>
              </div>
            )}
          </div>
        </div>
      </SectionCard>

      <Modal
        open={configOpen}
        title="获取 MCP 配置"
        description="把配置复制到 MCP 客户端；认证使用 X-API-Key（通过「新建 Key」生成，明文仅显示一次）。"
        onClose={() => setConfigOpen(false)}
        width={680}
        footer={
          <>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => {
                const sample = config.data?.sampleConfig
                if (sample === undefined || sample === null) {
                  toast.error('暂无 sampleConfig')
                  return
                }
                copyText(typeof sample === 'string' ? sample : safeStringify(sample))
              }}
            >
              <Icon name="copy" size={14} />
              复制 sampleConfig
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                setConfigOpen(false)
                openCreateKey()
              }}
            >
              生成 API Key
            </button>
          </>
        }
      >
        {config.loading ? (
          <LoadingBlock text="正在获取 MCP 配置…" />
        ) : config.error ? (
          <ErrorBlock message={config.error} onRetry={config.reload} />
        ) : config.data ? (
          <div className="mcp-config">
            <div className="info-grid">
              <div className="info-row">
                <span className="info-label">Endpoint</span>
                <span className="info-value mono">{config.data.endpoint}</span>
              </div>
              <div className="info-row">
                <span className="info-label">Transport</span>
                <span className="info-value">{config.data.transport || '—'}</span>
              </div>
              <div className="info-row">
                <span className="info-label">协议版本</span>
                <span className="info-value">{config.data.protocolVersion || '—'}</span>
              </div>
              <div className="info-row">
                <span className="info-label">Key 提示</span>
                <span className="info-value">{config.data.keyHint || '使用 X-API-Key 请求头'}</span>
              </div>
            </div>
            <h4 className="block-title">sampleConfig（可复制）</h4>
            <JsonView value={config.data.sampleConfig} empty="暂无 sampleConfig" maxHeight={260} />
            {config.data.docs ? <p className="field-hint">文档：{config.data.docs}</p> : null}
          </div>
        ) : null}
      </Modal>

      <Modal
        open={createKeyOpen}
        title="新建 MCP API Key"
        description="Key 明文仅在创建成功后显示一次，请立即保存。"
        onClose={() => {
          if (!creatingKey) setCreateKeyOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setCreateKeyOpen(false)} disabled={creatingKey}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={createKey} disabled={creatingKey}>
              {creatingKey ? <span className="spinner spinner-light" /> : null}
              生成 Key
            </button>
          </>
        }
      >
        <div className="form-grid form-grid-2">
          <Field label="Key 名称" required>
            <input className="input" value={keyName} placeholder="如 本地 CLI" onChange={(event) => setKeyName(event.target.value)} />
          </Field>
          <Field label="有效期（天）" hint="可选，留空表示长期有效">
            <input
              className="input"
              value={keyExpires}
              inputMode="numeric"
              placeholder="如 90"
              onChange={(event) => setKeyExpires(event.target.value)}
            />
          </Field>
        </div>
        {keyError ? <p className="field-error modal-error">{keyError}</p> : null}
      </Modal>

      <Modal
        open={Boolean(createdKey)}
        title="API Key 已生成"
        description="以下明文只会显示这一次，关闭后无法再次查看。"
        onClose={() => setCreatedKey(null)}
        footer={
          <button type="button" className="btn btn-primary" onClick={() => setCreatedKey(null)}>
            我已保存
          </button>
        }
      >
        {createdKey ? (
          <>
            <div className="temp-password-hint">
              Key 名称：{createdKey.name} · 前缀：<span className="mono">{createdKey.keyPrefix}</span>
            </div>
            <div className="temp-password">
              <code>{createdKey.apiKey}</code>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => copyText(createdKey.apiKey)}>
                <Icon name="copy" size={14} />
                复制
              </button>
            </div>
            <p className="field-hint">在 MCP 客户端中设置请求头：<code className="inline-json">X-API-Key: {createdKey.apiKey.slice(0, 8)}…</code></p>
          </>
        ) : null}
      </Modal>

      <ConfirmModal
        open={Boolean(revokeTarget)}
        title="撤销 API Key"
        description={revokeTarget ? `撤销后 Key「${revokeTarget.name}」（${revokeTarget.keyPrefix}…）立即失效，无法恢复。` : undefined}
        confirmText="确认撤销"
        danger
        loading={revoking}
        onCancel={() => {
          if (!revoking) setRevokeTarget(null)
        }}
        onConfirm={revokeKey}
      />

      <Modal
        open={serverOpen}
        title="新增外部 MCP 服务器"
        description="注册外部 MCP 服务地址，支持 Streamable HTTP 或 SSE 传输。"
        onClose={() => {
          if (!addingServer) setServerOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setServerOpen(false)} disabled={addingServer}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={addServer} disabled={addingServer}>
              {addingServer ? <span className="spinner spinner-light" /> : null}
              保存
            </button>
          </>
        }
      >
        <div className="form-grid">
          <Field label="名称" required>
            <input className="input" value={serverName} onChange={(event) => setServerName(event.target.value)} />
          </Field>
          <Field label="传输方式">
            <select className="select" value={serverTransport} onChange={(event) => setServerTransport(event.target.value)}>
              <option value="STREAMABLE_HTTP">Streamable HTTP</option>
              <option value="SSE">SSE</option>
            </select>
          </Field>
          <Field label="URL" required>
            <input className="input mono" value={serverUrl} placeholder="https://example.com/mcp" onChange={(event) => setServerUrl(event.target.value)} />
          </Field>
          <Field label="Headers（JSON 对象，可选）">
            <textarea
              className="input json-editor"
              rows={3}
              value={serverHeaders}
              placeholder='{"Authorization":"Bearer ..."}'
              onChange={(event) => setServerHeaders(event.target.value)}
            />
          </Field>
        </div>
        {serverError ? <p className="field-error modal-error">{serverError}</p> : null}
      </Modal>

      <ConfirmModal
        open={Boolean(deleteServerTarget)}
        title="删除外部 MCP 服务器"
        description={deleteServerTarget ? `将删除外部服务器「${deleteServerTarget.name}」，删除后可重新注册。` : undefined}
        confirmText="确认删除"
        danger
        loading={deletingServer}
        onCancel={() => {
          if (!deletingServer) setDeleteServerTarget(null)
        }}
        onConfirm={deleteServer}
      />
    </div>
  )
}
