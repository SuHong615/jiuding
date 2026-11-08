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
import { compactJson, safeStringify } from '../utils/format'

export function JsonView({
  value,
  empty = '无数据',
  maxHeight,
}: {
  value: unknown
  empty?: string
  maxHeight?: number
}) {
  if (value === undefined || value === null) {
    return <div className="json-empty">{empty}</div>
  }
  const text = typeof value === 'string' ? value : safeStringify(value)
  return (
    <pre className="json-view" style={maxHeight ? { maxHeight, overflow: 'auto' } : undefined}>
      {text}
    </pre>
  )
}

export function JsonEditor({
  value,
  onChange,
  rows = 8,
  placeholder,
  error,
  label,
  hint,
}: {
  value: string
  onChange: (value: string) => void
  rows?: number
  placeholder?: string
  error?: string | null
  label?: string
  hint?: string
}) {
  return (
    <div className="field">
      {label ? <label className="field-label">{label}</label> : null}
      <textarea
        className={`input json-editor ${error ? 'has-error' : ''}`}
        rows={rows}
        value={value}
        spellCheck={false}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
      {error ? <p className="field-error">{error}</p> : null}
      {!error && hint ? <p className="field-hint">{hint}</p> : null}
    </div>
  )
}

export function InlineJson({ value }: { value: unknown }) {
  return <code className="inline-json">{compactJson(value)}</code>
}
