import type { GradeRecord, GradeWeightItem, GradeWeights } from '@/types/domain'

export const MAX_WEIGHT_ITEMS = 20

export const defaultWeights: GradeWeights = {
  name: '综合成绩评分方案', totalWeight: 100, version: 0, status: 'ACTIVE',
  items: [
    { itemCode: 'USUAL', itemName: '平时成绩', weight: 10, maxScore: 100, sortOrder: 1 },
    { itemCode: 'ATTENDANCE', itemName: '考勤', weight: 10, maxScore: 100, sortOrder: 2 },
    { itemCode: 'HOMEWORK', itemName: '作业', weight: 10, maxScore: 100, sortOrder: 3 },
    { itemCode: 'LAB', itemName: '实验', weight: 10, maxScore: 100, sortOrder: 4 },
    { itemCode: 'MIDTERM', itemName: '期中', weight: 10, maxScore: 100, sortOrder: 5 },
    { itemCode: 'FINAL', itemName: '期末', weight: 50, maxScore: 100, sortOrder: 6 },
  ],
}

export function cloneWeights(source: GradeWeights = defaultWeights): GradeWeights {
  return { ...source, items: source.items.map((item) => ({ ...item })) }
}

export function parseScoreValue(value: unknown): number | null {
  if (value === '' || value === null || value === undefined) return null
  const score = Number(value)
  if (!Number.isFinite(score) || score < 0 || score > 100) return null
  return Math.round(score * 10) / 10
}

export function validWeights(weights: GradeWeights): boolean {
  if (!weights.name.trim() || !weights.items.length || weights.items.length > MAX_WEIGHT_ITEMS) return false
  const codes = new Set<string>()
  const sortOrders = new Set<number>()
  let previousSortOrder = 0
  const itemsValid = weights.items.every((item) => {
    const code = item.itemCode.trim()
    if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(code) || !item.itemName.trim() || codes.has(code)) return false
    codes.add(code)
    if (!Number.isInteger(item.sortOrder) || item.sortOrder < 1 || item.sortOrder > 100
      || sortOrders.has(item.sortOrder) || item.sortOrder <= previousSortOrder) return false
    sortOrders.add(item.sortOrder)
    previousSortOrder = item.sortOrder
    return Number.isFinite(Number(item.weight)) && Number(item.weight) > 0 && Number(item.weight) <= 100
      && Number(item.maxScore) === 100
  })
  return itemsValid && Math.abs(weightTotal(weights) - 100) < 1e-6
}

export function appendWeightItem(weights: GradeWeights): GradeWeightItem | null {
  if (weights.items.length >= MAX_WEIGHT_ITEMS) return null
  const usedCodes = new Set(weights.items.map((item) => item.itemCode.trim()))
  let suffix = 1
  while (usedCodes.has(`ITEM_${suffix}`)) suffix += 1
  const item: GradeWeightItem = {
    itemCode: `ITEM_${suffix}`,
    itemName: '新评分项',
    weight: 10,
    maxScore: 100,
    sortOrder: weights.items.length + 1,
  }
  weights.items.push(item)
  return item
}

export function removeWeightItem(weights: GradeWeights, index: number): GradeWeightItem | null {
  if (weights.items.length <= 1 || index < 0 || index >= weights.items.length) return null
  const [removed] = weights.items.splice(index, 1)
  weights.items.forEach((item, itemIndex) => { item.sortOrder = itemIndex + 1 })
  return removed || null
}

export function migrateComponentScoreCode(records: GradeRecord[], oldCode: string, newCode: string): boolean {
  if (oldCode === newCode) return true
  if (records.some((record) => Object.hasOwn(record.componentScores, newCode))) return false
  records.forEach((record) => {
    if (!Object.hasOwn(record.componentScores, oldCode)) return
    const score = record.componentScores[oldCode]
    delete record.componentScores[oldCode]
    record.componentScores[newCode] = score ?? null
  })
  return true
}

export type WeightItemCodeChangeResult =
  | { ok: true; oldCode: string; newCode: string }
  | { ok: false; oldCode: string; newCode: string; reason: 'INVALID' | 'DUPLICATE' | 'SCORE_CONFLICT' }

export function renameWeightItemCode(weights: GradeWeights, item: GradeWeightItem,
                                     records: GradeRecord[], value: string): WeightItemCodeChangeResult {
  const oldCode = item.itemCode
  const newCode = value.trim().toUpperCase()
  if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(newCode)) {
    return { ok: false, oldCode, newCode, reason: 'INVALID' }
  }
  if (weights.items.some((candidate) => candidate !== item && candidate.itemCode.trim() === newCode)) {
    return { ok: false, oldCode, newCode, reason: 'DUPLICATE' }
  }
  if (!migrateComponentScoreCode(records, oldCode, newCode)) {
    return { ok: false, oldCode, newCode, reason: 'SCORE_CONFLICT' }
  }
  item.itemCode = newCode
  return { ok: true, oldCode, newCode }
}

export function weightTotal(weights: GradeWeights): number {
  return weights.items.reduce((sum, item) => sum + Number(item.weight || 0), 0)
}

export function calculateTotal(record: GradeRecord, weights: GradeWeights): number | null {
  let total = 0
  for (const item of weights.items) {
    const value = record.componentScores[item.itemCode]
    if (value === null || value === undefined) return null
    total += Number(value) / Number(item.maxScore) * Number(item.weight)
  }
  return Math.round(total * 100) / 100
}

export function missingComponentCodes(record: GradeRecord, weights: GradeWeights): string[] {
  return weights.items
    .filter((item) => record.componentScores[item.itemCode] === null
      || record.componentScores[item.itemCode] === undefined)
    .map((item) => item.itemCode)
}

export function componentScoresForScheme(record: GradeRecord, weights: GradeWeights): Record<string, number | null> {
  const result: Record<string, number | null> = {}
  for (const item of weights.items) {
    const value = record.componentScores[item.itemCode]
    result[item.itemCode] = value === null || value === undefined ? null : Number(value)
  }
  return result
}

function normalizedCode(item: GradeWeightItem): string {
  return item.itemCode.toUpperCase().replace(/[^A-Z0-9]/g, '')
}

export function finalExamItem(weights: GradeWeights): GradeWeightItem | undefined {
  const sorted = [...weights.items].sort((left, right) => left.sortOrder - right.sortOrder)
  return sorted.find((item) => ['FINAL', 'FINALEXAM'].includes(normalizedCode(item)))
    || sorted.find((item) => normalizedCode(item).includes('FINAL'))
    || sorted.find((item) => /期末|终考|最终考试/.test(item.itemName))
    || sorted.at(-1)
}

export function mergeGradeRecord(current: GradeRecord, updated: GradeRecord): GradeRecord {
  return {
    ...current,
    ...updated,
    componentScores: { ...current.componentScores, ...updated.componentScores },
  }
}

export function displayedCourseScore(regular?: number | null, makeup?: number | null): string {
  if (regular === null || regular === undefined) return '--'
  if (makeup === null || makeup === undefined) return String(regular)
  return `${regular}/${Math.min(60, makeup)}`
}

export interface ParsedScore {
  studentNo?: string
  studentName?: string
  score: number
}

export interface RejectedScore {
  source: string
  reason: '分数必须是 0 至 100 的数字' | '缺少学号或姓名'
}

export interface ParsedScoreText {
  parsed: ParsedScore[]
  rejected: RejectedScore[]
}

function parseIdentityAndScore(identity: string[], rawScore: string, source: string,
                               result: ParsedScoreText) {
  const score = parseScoreValue(rawScore)
  if (score === null) {
    result.rejected.push({ source, reason: '分数必须是 0 至 100 的数字' })
    return
  }
  const studentNo = identity.find((cell) => /^\d{5,}$/.test(cell))
  const studentName = identity.find((cell) => cell !== studentNo && cell.trim().length > 0)
  if (!studentNo && !studentName) {
    result.rejected.push({ source, reason: '缺少学号或姓名' })
    return
  }
  result.parsed.push({ studentNo, studentName, score })
}

function parseNaturalLine(line: string, result: ParsedScoreText) {
  const tokens = line.trim().split(/\s+/).filter(Boolean)
  if (tokens.length === 3 && parseScoreValue(tokens[2]) !== null) {
    parseIdentityAndScore(tokens.slice(0, 2), tokens[2]!, line, result)
    return
  }
  for (let index = 0; index < tokens.length; index += 2) {
    const identity = tokens[index]
    const rawScore = tokens[index + 1]
    const source = [identity, rawScore].filter(Boolean).join(' ')
    if (!identity || !rawScore) {
      result.rejected.push({ source: source || line, reason: '缺少学号或姓名' })
      continue
    }
    parseIdentityAndScore([identity], rawScore, source, result)
  }
}

export function parseScoreTextDetailed(input: string): ParsedScoreText {
  const result: ParsedScoreText = { parsed: [], rejected: [] }
  const lines = input.replace(/^\uFEFF/, '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  lines.forEach((line) => {
    if (/[\t,，;；]/.test(line)) {
      const cells = line.split(/[\t,，;；]+/).map((cell) => cell.trim()).filter(Boolean)
      if (cells.length < 2) {
        result.rejected.push({ source: line, reason: '缺少学号或姓名' })
        return
      }
      parseIdentityAndScore(cells.slice(0, -1), cells[cells.length - 1]!, line, result)
      return
    }
    parseNaturalLine(line, result)
  })
  return result
}

export function parseScoreText(input: string): ParsedScore[] {
  return parseScoreTextDetailed(input).parsed
}

export type ParsedScoreTarget = { type: 'MAKEUP' } | { type: 'COMPONENT'; itemCode: string }

export function applyParsedScores(records: GradeRecord[], parsed: ParsedScore[], targetField: ParsedScoreTarget) {
  let matched = 0
  const unmatched: ParsedScore[] = []
  const ambiguous: ParsedScore[] = []
  const matchedRecords: GradeRecord[] = []
  for (const item of parsed) {
    const candidates = item.studentNo
      ? records.filter((record) => record.studentNo === item.studentNo)
      : records.filter((record) => item.studentName && record.studentName === item.studentName)
    if (!candidates.length) {
      unmatched.push(item)
      continue
    }
    if (candidates.length > 1) {
      ambiguous.push(item)
      continue
    }
    const target = candidates[0]!
    if (targetField.type === 'MAKEUP') target.makeupScore = item.score
    else target.componentScores[targetField.itemCode] = item.score
    matchedRecords.push(target)
    matched += 1
  }
  return { matched, unmatched, ambiguous, matchedRecords }
}

export function nextDirtyState(current: boolean, matchedImports: number): boolean {
  return current || matchedImports > 0
}
