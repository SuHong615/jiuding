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
import type { ReactNode } from 'react'
import { Icon } from './Icon'

export function Spinner({ size = 20 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} aria-hidden="true" />
}

export function LoadingBlock({ text = '正在加载数据…' }: { text?: string }) {
  return (
    <div className="state-block state-loading" role="status" aria-live="polite">
      <Spinner size={26} />
      <p>{text}</p>
    </div>
  )
}

export function ErrorBlock({
  message,
  onRetry,
  title = '加载失败',
}: {
  message: string
  onRetry?: () => void
  title?: string
}) {
  return (
    <div className="state-block state-error" role="alert">
      <div className="state-icon state-icon-error">
        <Icon name="alert" size={22} />
      </div>
      <h4>{title}</h4>
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="btn btn-ghost btn-sm" onClick={onRetry}>
          <Icon name="refresh" size={15} />
          重试
        </button>
      ) : null}
    </div>
  )
}

export function EmptyBlock({ text = '暂无数据', hint }: { text?: string; hint?: string }) {
  return (
    <div className="state-block state-empty">
      <div className="state-icon state-icon-empty">
        <Icon name="info" size={22} />
      </div>
      <h4>{text}</h4>
      {hint ? <p>{hint}</p> : null}
    </div>
  )
}

export interface AsyncBlockProps {
  loading: boolean
  error?: string | null
  empty?: boolean
  emptyText?: string
  emptyHint?: string
  onRetry?: () => void
  children: ReactNode
}

/** 页面通用三态：loading → error → empty → content */
export function AsyncBlock({
  loading,
  error,
  empty = false,
  emptyText,
  emptyHint,
  onRetry,
  children,
}: AsyncBlockProps) {
  if (loading) return <LoadingBlock />
  if (error) return <ErrorBlock message={error} onRetry={onRetry} />
  if (empty) return <EmptyBlock text={emptyText} hint={emptyHint} />
  return <>{children}</>
}
