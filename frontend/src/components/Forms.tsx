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

export function Field({
  label,
  required = false,
  hint,
  error,
  children,
  className = '',
}: {
  label: string
  required?: boolean
  hint?: string
  error?: string | null
  children: ReactNode
  className?: string
}) {
  return (
    <div className={`field ${className}`.trim()}>
      <label className="field-label">
        {label}
        {required ? <em className="required">*</em> : null}
      </label>
      {children}
      {error ? <p className="field-error">{error}</p> : null}
      {!error && hint ? <p className="field-hint">{hint}</p> : null}
    </div>
  )
}

export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: string
  description?: string
  actions?: ReactNode
  back?: ReactNode
}) {
  return (
    <header className="page-head">
      <div className="page-head-main">
        {back}
        <div>
          <h2>{title}</h2>
          {description ? <p>{description}</p> : null}
        </div>
      </div>
      {actions ? <div className="page-head-actions">{actions}</div> : null}
    </header>
  )
}

export function SectionCard({
  title,
  description,
  actions,
  children,
  className = '',
  bodyClassName = '',
}: {
  title?: string
  description?: string
  actions?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <section className={`card ${className}`.trim()}>
      {title || actions ? (
        <header className="card-head">
          <div>
            {title ? <h3>{title}</h3> : null}
            {description ? <p>{description}</p> : null}
          </div>
          {actions ? <div className="card-head-actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className={`card-body ${bodyClassName}`.trim()}>{children}</div>
    </section>
  )
}
