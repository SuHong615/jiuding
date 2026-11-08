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
import { Modal } from './Modal'

export function ConfirmModal({
  open,
  title,
  description,
  confirmText = '确认',
  danger = false,
  loading = false,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean
  title: string
  description?: string
  confirmText?: string
  danger?: boolean
  loading?: boolean
  onConfirm: () => void
  onCancel: () => void
  children?: ReactNode
}) {
  return (
    <Modal
      open={open}
      title={title}
      description={description}
      onClose={onCancel}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={loading}>
            取消
          </button>
          <button
            type="button"
            className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`}
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? <span className="spinner spinner-light" /> : null}
            {confirmText}
          </button>
        </>
      }
    >
      {children}
    </Modal>
  )
}
