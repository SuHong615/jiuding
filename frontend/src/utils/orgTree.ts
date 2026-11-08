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
import type { OrgUnitView } from '../api/types'

function sortUnits(list: OrgUnitView[]): OrgUnitView[] {
  return [...list].sort((a, b) => {
    const orderA = a.sortOrder ?? 0
    const orderB = b.sortOrder ?? 0
    if (orderA !== orderB) return orderA - orderB
    return (a.name || '').localeCompare(b.name || '', 'zh-CN')
  })
}

/** 后端可能返回 tree 或 flat；统一成带 children 的树结构 */
export function normalizeOrgTree(list: OrgUnitView[]): OrgUnitView[] {
  if (!Array.isArray(list) || !list.length) return []
  const hasNestedChildren = list.some((unit) => Array.isArray(unit.children) && unit.children.length > 0)
  if (hasNestedChildren) {
    const clone = (units: OrgUnitView[]): OrgUnitView[] =>
      sortUnits(units).map((unit) => ({
        ...unit,
        children: clone(unit.children ?? []),
      }))
    return clone(list)
  }

  const byId = new Map<string, OrgUnitView>()
  list.forEach((unit) => byId.set(unit.id, { ...unit, children: [] }))
  const roots: OrgUnitView[] = []
  byId.forEach((unit) => {
    const parent = unit.parentId ? byId.get(unit.parentId) : undefined
    if (parent) parent.children = [...(parent.children ?? []), unit]
    else roots.push(unit)
  })
  const finalize = (units: OrgUnitView[]): OrgUnitView[] =>
    sortUnits(units).map((unit) => ({ ...unit, children: finalize(unit.children ?? []) }))
  return finalize(roots)
}

/** 深度优先展开为扁平数组（父节点在子节点之前） */
export function flattenOrgUnits(tree: OrgUnitView[]): OrgUnitView[] {
  const result: OrgUnitView[] = []
  const walk = (units: OrgUnitView[]) => {
    units.forEach((unit) => {
      result.push(unit)
      if (unit.children?.length) walk(unit.children)
    })
  }
  walk(tree)
  return result
}

/** 返回节点自身与全部后代的 id（用于移动时排除非法目标） */
export function collectDescendantIds(unit: OrgUnitView): string[] {
  const ids: string[] = [unit.id]
  const walk = (node: OrgUnitView) => {
    ;(node.children ?? []).forEach((child) => {
      ids.push(child.id)
      walk(child)
    })
  }
  walk(unit)
  return ids
}

export function orgChildCount(unit: OrgUnitView): number {
  return unit.childCount ?? unit.children?.length ?? 0
}

export function orgAccountCount(unit: OrgUnitView): number {
  return unit.accountCount ?? 0
}
