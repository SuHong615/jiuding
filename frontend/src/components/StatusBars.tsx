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
export interface BarItem {
  key: string
  label: string
  value: number
  color: string
}

/** 纯 SVG 横向条形图（状态分布），不依赖任何图表库 */
export function StatusBars({ items }: { items: BarItem[] }) {
  const max = Math.max(...items.map((item) => item.value), 1)
  const rowHeight = 36
  const height = items.length * rowHeight + 12

  return (
    <svg
      className="status-bars"
      viewBox={`0 0 640 ${height}`}
      role="img"
      aria-label="状态分布条形图"
      preserveAspectRatio="xMinYMin meet"
    >
      {items.map((item, index) => {
        const y = 6 + index * rowHeight
        const barWidth = (item.value / max) * 388
        return (
          <g key={item.key}>
            <text x="0" y={y + 18} className="bar-label">
              {item.label}
            </text>
            <rect x="132" y={y + 4} width="400" height="18" rx="9" className="bar-track" />
            <rect
              x="132"
              y={y + 4}
              width={Math.max(barWidth, item.value > 0 ? 8 : 0)}
              height="18"
              rx="9"
              fill={item.color}
            />
            <text x="556" y={y + 18} className="bar-value">
              {item.value}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
