import { ApiError, errorMessage } from '../api/client'

/** 后端配额超限：HTTP 409 / ErrorCode QUOTA_EXCEEDED（常见 40901），message 含 quota */
export function isQuotaExceeded(err: unknown): boolean {
  if (!(err instanceof ApiError)) return false
  if (err.code === 40901) return true
  return /QUOTA_EXCEEDED|quota\s*(exceeded|exceed|limit)|配额/i.test(err.message)
}

/** 对配额超限给出业务友好提示，其他错误保留后端 message */
export function friendlyErrorMessage(err: unknown): string {
  const message = errorMessage(err)
  if (isQuotaExceeded(err)) {
    return `配额不足：${message}。已达到租户配额上限，请联系平台管理员调整配额后再试。`
  }
  return message
}

/** 导入结果 errors[].message 可能包含 QUOTA_EXCEEDED，统一转为友好文案 */
export function friendlyQuotaText(message?: string | null): string {
  if (!message) return '—'
  if (/QUOTA_EXCEEDED|quota\s*(exceeded|exceed|limit)|配额/i.test(message)) {
    return `配额不足：${message}`
  }
  return message
}
