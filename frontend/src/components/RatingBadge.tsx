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
const KNOWN_GRADES = ['AAA', 'AA', 'A', 'B', 'C', 'D']

/** 租户评级徽章：AAA 深绿 / AA 绿 / A 蓝 / B 灰 / C 橙 / D 红（低饱和浅底） */
export function RatingBadge({
  grade,
  size = 'md',
  title,
}: {
  grade?: string | null
  size?: 'sm' | 'md' | 'lg'
  title?: string
}) {
  const normalized = (grade ?? '').toUpperCase()
  const known = KNOWN_GRADES.includes(normalized)
  const sizeClass = size === 'sm' ? 'rating-sm' : size === 'lg' ? 'rating-lg' : ''
  return (
    <span
      className={`rating-badge rating-${known ? normalized.toLowerCase() : 'none'} ${sizeClass}`.trim()}
      title={title ?? (known ? `租户评级 ${normalized}` : '未评级')}
    >
      {known ? normalized : '—'}
    </span>
  )
}
