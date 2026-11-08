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
import { Icon } from './Icon'

export interface PagerProps {
  page: number
  totalPages?: number | null
  total?: number | null
  onPage: (page: number) => void
  disabled?: boolean
}

export function Pager({ page, totalPages, total, onPage, disabled = false }: PagerProps) {
  const pages = Math.max(1, totalPages ?? 1)
  const current = Math.min(Math.max(page, 0), pages - 1)

  const windowPages: number[] = []
  const start = Math.max(0, Math.min(current - 2, pages - 5))
  const end = Math.min(pages, start + 5)
  for (let i = start; i < end; i += 1) windowPages.push(i)

  return (
    <div className="pager">
      <span className="pager-info">
        共 <strong>{total ?? 0}</strong> 条 · 第 {current + 1}/{pages} 页
      </span>
      <div className="pager-actions">
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={disabled || current <= 0}
          onClick={() => onPage(current - 1)}
        >
          <Icon name="chevronLeft" size={15} />
          上一页
        </button>
        {windowPages.map((index) => (
          <button
            key={index}
            type="button"
            className={`page-num ${index === current ? 'active' : ''}`}
            disabled={disabled}
            onClick={() => onPage(index)}
            aria-current={index === current ? 'page' : undefined}
          >
            {index + 1}
          </button>
        ))}
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={disabled || current >= pages - 1}
          onClick={() => onPage(current + 1)}
        >
          下一页
          <Icon name="chevronRight" size={15} />
        </button>
      </div>
    </div>
  )
}
