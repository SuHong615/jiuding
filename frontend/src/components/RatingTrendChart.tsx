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
import { formatDateTime } from '../utils/format'

export interface RatingTrendPoint {
  score: number
  grade?: string | null
  at?: string | null
}

const WIDTH = 640
const HEIGHT = 220
const PAD_L = 38
const PAD_R = 18
const PAD_T = 18
const PAD_B = 32
const INNER_W = WIDTH - PAD_L - PAD_R
const INNER_H = HEIGHT - PAD_T - PAD_B

const clampScore = (score: number) => Math.max(0, Math.min(100, score))

function axisDate(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 纯 SVG 评级趋势折线图（最近 10 次综合分，黑色线条 + 灰色网格，无图表库） */
export function RatingTrendChart({ points }: { points: RatingTrendPoint[] }) {
  if (!points.length) return null

  const coords = points.map((point, index) => ({
    ...point,
    x: points.length === 1 ? PAD_L + INNER_W / 2 : PAD_L + (index * INNER_W) / (points.length - 1),
    y: PAD_T + INNER_H * (1 - clampScore(point.score) / 100),
  }))
  const line = coords.map((point) => `${point.x},${point.y}`).join(' ')
  const gridValues = [0, 25, 50, 75, 100]
  const labelEvery = coords.length > 8 ? 2 : 1

  return (
    <div className="trend-chart-wrap">
      <svg
        className="trend-chart"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label="最近评级综合分趋势"
        preserveAspectRatio="xMidYMid meet"
      >
        {gridValues.map((value) => {
          const y = PAD_T + INNER_H * (1 - value / 100)
          return (
            <g key={value}>
              <line x1={PAD_L} y1={y} x2={WIDTH - PAD_R} y2={y} className="chart-grid-line" />
              <text x={PAD_L - 8} y={y + 3} textAnchor="end" className="chart-axis-label">
                {value}
              </text>
            </g>
          )
        })}

        {coords.length > 1 ? <polyline points={line} className="chart-line" /> : null}

        {coords.map((point, index) => {
          const isCurrent = index === coords.length - 1
          const showDate = index % labelEvery === 0 || isCurrent
          return (
            <g key={`${point.at ?? index}-${index}`}>
              <circle cx={point.x} cy={point.y} r={isCurrent ? 4.5 : 3.5} className={`chart-dot ${isCurrent ? 'current' : ''}`}>
                <title>{`${point.grade ?? '—'} · ${point.score} 分 ${point.at ? `· ${formatDateTime(point.at)}` : ''}`}</title>
              </circle>
              {isCurrent ? (
                <text x={point.x} y={point.y - 11} textAnchor="middle" className="chart-current-label">
                  {point.score}
                </text>
              ) : null}
              {showDate ? (
                <text x={point.x} y={HEIGHT - 10} textAnchor="middle" className="chart-axis-label">
                  {axisDate(point.at)}
                </text>
              ) : null}
            </g>
          )
        })}
      </svg>
      <p className="chart-caption">
        最近 {points.length} 次综合评分（0–100）· 悬停节点查看等级与评估时间
      </p>
    </div>
  )
}
