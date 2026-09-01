import { describe, expect, it } from 'vitest'
import {
  appendWeightItem,
  applyParsedScores,
  calculateTotal,
  cloneWeights,
  defaultWeights,
  displayedCourseScore,
  finalExamItem,
  mergeGradeRecord,
  migrateComponentScoreCode,
  missingComponentCodes,
  nextDirtyState,
  parseScoreTextDetailed,
  parseScoreText,
  renameWeightItemCode,
  removeWeightItem,
  validWeights,
} from './grade'
import type { GradeRecord, GradeWeights } from '@/types/domain'

const weights: GradeWeights = {
  name: '动态方案', totalWeight: 100, version: 1,
  items: [
    { id: 'daily', itemCode: 'DAILY', itemName: '平时', weight: 20, maxScore: 100, sortOrder: 1 },
    { id: 'lab', itemCode: 'LAB', itemName: '实验', weight: 30, maxScore: 100, sortOrder: 2 },
    { id: 'project', itemCode: 'PROJECT', itemName: '项目', weight: 10, maxScore: 100, sortOrder: 3 },
    { id: 'final', itemCode: 'FINAL', itemName: '期末', weight: 40, maxScore: 100, sortOrder: 4 },
  ],
}

function record(overrides: Partial<GradeRecord> = {}): GradeRecord {
  return {
    id: 'grade-1', studentId: 'student-1', studentNo: '20260001', studentName: '张三', status: 'DRAFT',
    componentScores: {},
    ...overrides,
  }
}

describe('grade utilities', () => {
  it('parses CSV rows and natural voice pairs', () => {
    expect(parseScoreText('20260001,张三,88\n20260002,李四,76')).toEqual([
      { studentNo: '20260001', studentName: '张三', score: 88 },
      { studentNo: '20260002', studentName: '李四', score: 76 },
    ])
    expect(parseScoreText('张三 88 李四 76')).toEqual([
      { studentName: '张三', score: 88 },
      { studentName: '李四', score: 76 },
    ])
  })

  it('matches by student number or exact name without creating rows', () => {
    const records = [record(), record({ id: 'grade-2', studentId: 'student-2', studentNo: '20260002', studentName: '李四' })]
    const result = applyParsedScores(records, parseScoreText('20260001,90\n王五,72'),
      { type: 'COMPONENT', itemCode: 'FINAL' })
    expect(result.matched).toBe(1)
    expect(result.unmatched).toHaveLength(1)
    expect(records[0]?.componentScores.FINAL).toBe(90)
    expect(records).toHaveLength(2)
  })

  it('rejects out-of-range imported scores instead of silently clamping them', () => {
    const result = parseScoreTextDetailed('20260001,120\n20260002,-5\n20260003,99.94')

    expect(result.parsed).toEqual([{ studentNo: '20260003', score: 99.9 }])
    expect(result.rejected).toEqual([
      { source: '20260001,120', reason: '分数必须是 0 至 100 的数字' },
      { source: '20260002,-5', reason: '分数必须是 0 至 100 的数字' },
    ])
  })

  it('rejects a name-only match when more than one student has that name', () => {
    const records = [
      record({ id: 'grade-1', studentNo: '20260001', studentName: '张三' }),
      record({ id: 'grade-2', studentId: 'student-2', studentNo: '20260002', studentName: '张三' }),
    ]

    const result = applyParsedScores(records, parseScoreText('张三,88'),
      { type: 'COMPONENT', itemCode: 'FINAL' })

    expect(result).toMatchObject({ matched: 0, unmatched: [], ambiguous: [{ studentName: '张三', score: 88 }] })
    expect(records.every((item) => item.componentScores.FINAL === undefined)).toBe(true)
  })

  it('does not fall back to a matching name when a supplied student number is unknown', () => {
    const records = [record({ studentName: '张三', studentNo: '20260001' })]
    const result = applyParsedScores(records, parseScoreText('20999999,张三,88'),
      { type: 'COMPONENT', itemCode: 'FINAL' })

    expect(result.matched).toBe(0)
    expect(result.unmatched).toHaveLength(1)
    expect(records[0]?.componentScores.FINAL).toBeUndefined()
  })

  it('keeps earlier manual edits dirty when an import matches no rows', () => {
    expect(nextDirtyState(true, 0)).toBe(true)
    expect(nextDirtyState(false, 0)).toBe(false)
    expect(nextDirtyState(false, 1)).toBe(true)
  })

  it('calculates weighted totals and validates a 100 percent scheme', () => {
    expect(validWeights(weights)).toBe(true)
    const invalid = cloneWeights(weights)
    invalid.items[3]!.weight = 35
    expect(validWeights(invalid)).toBe(false)
    const scores = record({ componentScores: { DAILY: 80, LAB: 90, PROJECT: 70, FINAL: 84 } })
    expect(calculateTotal(scores, weights)).toBe(83.6)
    expect(missingComponentCodes(scores, weights)).toEqual([])
  })

  it('rejects unsafe component codes and non-unique sort order', () => {
    const invalidCode = cloneWeights(weights)
    invalidCode.items[0]!.itemCode = 'daily-score'
    expect(validWeights(invalidCode)).toBe(false)

    const duplicateOrder = cloneWeights(weights)
    duplicateOrder.items[1]!.sortOrder = duplicateOrder.items[0]!.sortOrder
    expect(validWeights(duplicateOrder)).toBe(false)
  })

  it('keeps semantically similar component codes independent', () => {
    const current = record({ componentScores: { DAILY: 80, LAB: 91, PROJECT: 72 } })
    const partial = record({ componentScores: { PROJECT: 88, FINAL: 76 }, version: 2 })
    const merged = mergeGradeRecord(current, partial)
    expect(merged.componentScores).toEqual({ DAILY: 80, LAB: 91, PROJECT: 88, FINAL: 76 })
    expect(missingComponentCodes(merged, weights)).toEqual([])
  })

  it('uses six distinct defaults and selects the explicit final exam item', () => {
    expect(defaultWeights.items.map((item) => item.itemCode)).toEqual([
      'USUAL', 'ATTENDANCE', 'HOMEWORK', 'LAB', 'MIDTERM', 'FINAL',
    ])
    expect(finalExamItem(weights)?.itemCode).toBe('FINAL')
  })

  it('adds uniquely coded items, caps the scheme at 20, and resequences removals', () => {
    const editable = cloneWeights(weights)
    editable.items.push({ itemCode: 'ITEM_1', itemName: '已有项', weight: 1, maxScore: 100, sortOrder: 5 })

    expect(appendWeightItem(editable)?.itemCode).toBe('ITEM_2')
    expect(removeWeightItem(editable, 1)?.itemCode).toBe('LAB')
    expect(editable.items.map((item) => item.sortOrder)).toEqual([1, 2, 3, 4, 5])

    while (editable.items.length < 20) expect(appendWeightItem(editable)).not.toBeNull()
    expect(appendWeightItem(editable)).toBeNull()
    const single = { ...cloneWeights(weights), items: [cloneWeights(weights).items[0]!] }
    expect(removeWeightItem(single, 0)).toBeNull()
  })

  it('migrates local component scores without overwriting an existing key', () => {
    const localRecords = [record({ componentScores: { PROJECT: 82 } })]
    expect(migrateComponentScoreCode(localRecords, 'PROJECT', 'CAPSTONE')).toBe(true)
    expect(localRecords[0]?.componentScores).toEqual({ CAPSTONE: 82 })

    localRecords[0]!.componentScores.PROJECT = 91
    expect(migrateComponentScoreCode(localRecords, 'PROJECT', 'CAPSTONE')).toBe(false)
    expect(localRecords[0]?.componentScores).toEqual({ CAPSTONE: 82, PROJECT: 91 })
  })

  it('renames a weight code atomically and preserves all component scores', () => {
    const editable = cloneWeights(weights)
    const item = editable.items[2]!
    const localRecords = [record({ componentScores: { PROJECT: 82 } })]

    expect(renameWeightItemCode(editable, item, localRecords, ' capstone ')).toEqual({
      ok: true, oldCode: 'PROJECT', newCode: 'CAPSTONE',
    })
    expect(item.itemCode).toBe('CAPSTONE')
    expect(localRecords[0]?.componentScores).toEqual({ CAPSTONE: 82 })
  })

  it('restores the old code and scores when a rename is invalid, duplicate, or conflicts', () => {
    const editable = cloneWeights(weights)
    const item = editable.items[2]!
    const localRecords = [record({ componentScores: { PROJECT: 82, CAPSTONE: 90 } })]

    expect(renameWeightItemCode(editable, item, localRecords, 'bad-code').ok).toBe(false)
    expect(renameWeightItemCode(editable, item, localRecords, 'LAB').ok).toBe(false)
    expect(renameWeightItemCode(editable, item, localRecords, 'CAPSTONE').ok).toBe(false)
    expect(item.itemCode).toBe('PROJECT')
    expect(localRecords[0]?.componentScores).toEqual({ PROJECT: 82, CAPSTONE: 90 })
  })

  it('caps the displayed makeup score at 60', () => {
    expect(displayedCourseScore(58, 87)).toBe('58/60')
    expect(displayedCourseScore(58, 57)).toBe('58/57')
  })
})
