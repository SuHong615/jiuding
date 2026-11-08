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
import type { BadgeTone } from './Badge'
import { EmptyBlock } from './PageState'
import { formatDateTime } from '../utils/format'

export interface TimelineEntry {
  title: string
  time?: string | null
  actor?: string | null
  detail?: string | null
  tone?: BadgeTone
}

export function Timeline({ items, emptyText = '暂无记录' }: { items: TimelineEntry[]; emptyText?: string }) {
  if (!items.length) return <EmptyBlock text={emptyText} />
  return (
    <ol className="timeline">
      {items.map((item, index) => (
        <li key={`${item.title}-${index}`} className="timeline-item">
          <span className={`timeline-dot tone-${item.tone ?? 'blue'}`} aria-hidden="true" />
          <div className="timeline-body">
            <div className="timeline-head">
              <strong>{item.title}</strong>
              <span className="muted">{formatDateTime(item.time)}</span>
            </div>
            {item.actor || item.detail ? (
              <div className="timeline-meta">
                {item.actor ? <span className="timeline-actor">{item.actor}</span> : null}
                {item.detail ? <span className="timeline-detail">{item.detail}</span> : null}
              </div>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  )
}
