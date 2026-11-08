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
// 统一 API client：
// - fetch 封装，解析 {code,message,data}，code !== 0 抛 ApiError
// - 自动携带 Authorization，存在租户上下文时携带 X-Tenant-Id
// - 401 清空本地登录态并广播 tenancy:unauthorized（由路由守卫跳 /login）
// - 独立导出 MCP 原始 JSON-RPC 调用（/mcp 为非 ApiResponse 封装的 JSON-RPC）

import type { ApiEnvelope, LoginResult, Principal } from './types'

export const TOKEN_KEY = 'tenancy.token'
export const PRINCIPAL_KEY = 'tenancy.principal'
export const MCP_KEY = 'tenancy.mcpApiKey'
export const DEFAULT_MCP_KEY = 'dev-mcp-key'

export class ApiError extends Error {
  readonly code: number
  readonly status: number

  constructor(message: string, code: number, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function getStoredPrincipal(): Principal | null {
  try {
    const raw = localStorage.getItem(PRINCIPAL_KEY)
    if (!raw) return null
    return JSON.parse(raw) as Principal
  } catch {
    return null
  }
}

export function saveAuth(result: LoginResult): void {
  localStorage.setItem(TOKEN_KEY, result.token)
  localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(result.principal))
}

export function clearAuthStorage(): void {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(PRINCIPAL_KEY)
  } catch {
    /* localStorage 不可用时忽略 */
  }
}

/** 平台管理员默认不带 X-Tenant-Id；租户内用户自动带自己的 tenantId */
function defaultTenantHeader(): string | null {
  const principal = getStoredPrincipal()
  if (!principal) return null
  if (principal.roles?.includes('PLATFORM_ADMIN')) return null
  return principal.tenantId ?? null
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  tenantId?: string | null
  signal?: AbortSignal
}

function buildHeaders(options: RequestOptions): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
  }
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData
  if (options.body !== undefined && !isForm) {
    headers['Content-Type'] = 'application/json;charset=UTF-8'
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const tenantId = options.tenantId !== undefined ? options.tenantId : defaultTenantHeader()
  if (tenantId) headers['X-Tenant-Id'] = tenantId
  return headers
}

function unauthorized(): void {
  clearAuthStorage()
  window.dispatchEvent(new CustomEvent('tenancy:unauthorized'))
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData
  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers: buildHeaders(options),
      body:
        options.body === undefined
          ? undefined
          : isForm
            ? (options.body as FormData)
            : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError('网络请求失败：无法连接网关，请确认服务已启动（默认 http://localhost:8080）', -1, 0)
  }

  let text = ''
  try {
    text = await res.text()
  } catch {
    text = ''
  }

  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = null
    }
  }

  const envelope = payload as ApiEnvelope<T> | null

  // 登录接口的 401 是"凭据错误"，不能清空登录态；其它接口 401 视为会话过期
  const isLoginRequest = path.startsWith('/api/auth/login')
  if (res.status === 401 && !isLoginRequest) {
    unauthorized()
    throw new ApiError('登录已过期，请重新登录', 401, 401)
  }
  if (!res.ok) {
    if (envelope && typeof envelope.code === 'number' && envelope.code !== 0) {
      throw new ApiError(envelope.message || `请求失败（HTTP ${res.status}）`, envelope.code, res.status)
    }
    throw new ApiError(`请求失败（HTTP ${res.status}）`, res.status, res.status)
  }

  if (envelope && typeof envelope.code === 'number') {
    if (envelope.code !== 0) {
      throw new ApiError(envelope.message || '业务处理失败', envelope.code, res.status)
    }
    return envelope.data as T
  }

  if (!text) return undefined as T
  return payload as T
}

export const api = {
  get: <T>(path: string, tenantId?: string | null) => request<T>(path, { tenantId }),
  post: <T>(path: string, body?: unknown, tenantId?: string | null) =>
    request<T>(path, { method: 'POST', body, tenantId }),
  put: <T>(path: string, body?: unknown, tenantId?: string | null) =>
    request<T>(path, { method: 'PUT', body, tenantId }),
  patch: <T>(path: string, body?: unknown, tenantId?: string | null) =>
    request<T>(path, { method: 'PATCH', body, tenantId }),
  delete: <T>(path: string, tenantId?: string | null) =>
    request<T>(path, { method: 'DELETE', tenantId }),
  upload: <T>(path: string, form: FormData, tenantId?: string | null) =>
    request<T>(path, { method: 'POST', body: form, tenantId }),
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error) return err.message
  return '未知错误，请稍后重试'
}

/**
 * 下载二进制/CSV 响应（非 {code,message,data} 统一包装）。
 * 复用统一鉴权头；401 清 token；文件名优先取 Content-Disposition。
 */
export async function downloadFile(path: string, fallbackFilename = 'download'): Promise<void> {
  const headers = buildHeaders({})
  headers.Accept = 'text/csv, application/octet-stream, */*'
  let res: Response
  try {
    res = await fetch(path, { headers })
  } catch {
    throw new ApiError('下载失败：无法连接服务', -1, 0)
  }

  if (res.status === 401) {
    unauthorized()
    throw new ApiError('登录已过期，请重新登录', 401, 401)
  }

  if (!res.ok) {
    let message = `下载失败（HTTP ${res.status}）`
    try {
      const text = await res.text()
      if (text) {
        const payload = JSON.parse(text) as ApiEnvelope<unknown>
        if (payload?.message) message = payload.message
      }
    } catch {
      /* 非 JSON 错误响应，保留默认提示 */
    }
    throw new ApiError(message, res.status, res.status)
  }

  const blob = await res.blob()
  const disposition = res.headers.get('Content-Disposition') ?? ''
  let filename = fallbackFilename
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition)
  if (utf8Match) {
    try {
      filename = decodeURIComponent(utf8Match[1])
    } catch {
      /* 保留 fallback */
    }
  } else if (plainMatch) {
    filename = plainMatch[1]
  }

  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 下载可能异步读取 blob；过早 revoke 会导致部分浏览器取消下载
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export function getMcpApiKey(): string {
  try {
    return localStorage.getItem(MCP_KEY) || DEFAULT_MCP_KEY
  } catch {
    return DEFAULT_MCP_KEY
  }
}

export function setMcpApiKey(key: string): void {
  try {
    localStorage.setItem(MCP_KEY, key)
  } catch {
    /* ignore */
  }
}

/* ------------------------------ MCP 原始调用 ------------------------------ */

export interface McpRawResult {
  ok: boolean
  status: number
  latencyMs: number
  sessionId: string | null
  text: string
  json: unknown
}

export interface McpRequestOptions {
  apiKey?: string
  sessionId?: string | null
  signal?: AbortSignal
}

/** POST /mcp：JSON-RPC 2.0，非统一响应包，返回原始文本 + 解析结果供控制台展示 */
export async function mcpRequest(body: unknown, options: McpRequestOptions = {}): Promise<McpRawResult> {
  const started = performance.now()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'X-API-Key': options.apiKey || getMcpApiKey(),
    'MCP-Protocol-Version': '2025-06-18',
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.sessionId) headers['Mcp-Session-Id'] = options.sessionId

  let res: Response
  try {
    res = await fetch('/mcp', {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: options.signal,
    })
  } catch {
    throw new ApiError('MCP 请求失败：无法连接 /mcp，请确认网关与 mcp-service 已启动', -1, 0)
  }

  const text = await res.text()
  let json: unknown = null
  if (text) {
    try {
      json = JSON.parse(text)
    } catch {
      json = null
    }
  }
  return {
    ok: res.ok,
    status: res.status,
    latencyMs: Math.round(performance.now() - started),
    sessionId: res.headers.get('Mcp-Session-Id'),
    text,
    json,
  }
}

/** GET /mcp/info：能力快照（非协议） */
export async function mcpInfo(options: { apiKey?: string; signal?: AbortSignal } = {}): Promise<unknown> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-API-Key': options.apiKey || getMcpApiKey(),
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let res: Response
  try {
    res = await fetch('/mcp/info', { headers, signal: options.signal })
  } catch {
    throw new ApiError('无法连接 MCP 服务（/mcp/info）', -1, 0)
  }
  const text = await res.text()
  if (!res.ok) throw new ApiError(`MCP info 请求失败（HTTP ${res.status}）`, res.status, res.status)
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return text
  }
}
