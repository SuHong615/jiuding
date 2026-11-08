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
import { Icon, type IconName } from './Icon'

export type Accent = 'blue' | 'green' | 'amber' | 'purple' | 'red' | 'cyan'

export interface KpiCardProps {
  label: string
  value: ReactNode
  sub?: ReactNode
  icon: IconName
  accent?: Accent
}

export function KpiCard({ label, value, sub, icon, accent = 'blue' }: KpiCardProps) {
  return (
    <div className="card kpi-card">
      <span className={`kpi-icon kpi-${accent}`}>
        <Icon name={icon} size={20} />
      </span>
      <div className="kpi-main">
        <span className="kpi-label">{label}</span>
        <span className="kpi-value">{value}</span>
        {sub ? <span className="kpi-sub">{sub}</span> : null}
      </div>
    </div>
  )
}
