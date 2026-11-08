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

export type BadgeTone = 'gray' | 'blue' | 'cyan' | 'green' | 'amber' | 'red' | 'purple'

export interface BadgeProps {
  tone?: BadgeTone
  children: ReactNode
  dot?: boolean
  className?: string
  title?: string
}

export function Badge({ tone = 'gray', children, dot = false, className = '', title }: BadgeProps) {
  return (
    <span className={`badge badge-${tone} ${className}`.trim()} title={title}>
      {dot ? <i className="badge-dot" aria-hidden="true" /> : null}
      {children}
    </span>
  )
}
