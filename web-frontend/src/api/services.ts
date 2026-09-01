import { http } from './http'
import type {
  AdminGradeRecord,
  AuditLog,
  Course,
  CurrentUser,
  DecisionNode,
  GradeRecord,
  GradeHistoryEntry,
  GradeStatistics,
  GradeWeightItem,
  GradeWeights,
  HistoryCourse,
  ManagedUser,
  Organization,
  PageResult,
  PermissionOption,
  Prediction,
  ReversionRequest,
  RecoveryEvidence,
  RiskAssessment,
  SecurityAlert,
  StudentCourse,
  StudentGrade,
  WarningItem,
  Role,
  RolePermissions,
} from '@/types/domain'
import { cloneWeights, componentScoresForScheme, defaultWeights, mergeGradeRecord } from '@/utils/grade'

interface RawPage<T> { items: T[]; page: number; size: number; total: number; totalPages: number }
interface RawCourse {
  offeringId: string; courseId: string; courseCode: string; courseName: string; credit: number
  academicYear: string; semester: number; className?: string; status?: string; enrolledStudents: number
}
interface RawWeightItem {
  id?: string; itemCode: string; itemName: string; weight: number; maxScore: number; sortOrder: number
}
interface RawWeightScheme {
  id: string; offeringId: string; name: string; totalWeight: number; version: number; status: string; items: RawWeightItem[]
}
interface RawGrade {
  id?: string | null; enrollmentId: string; studentId: string; studentNo: string; studentName: string
  schemeId?: string; componentScores?: Record<string, number>; regularScore?: number | null
  makeupRawScore?: number | null; makeupEffectiveScore?: number | null; finalScore?: number | null
  score?: number | null; examType?: 'REGULAR' | 'RETAKE'; cappedAtSixty?: boolean; makeupStatus?: string | null
  status?: string; version?: number; submittedAt?: string; updatedAt?: string
}
interface RawStatistics {
  count: number; average: number; maximum: number; minimum: number; median: number; passRate: number
  standardDeviation: number; distribution: Record<string, number>; narrative?: string; savedAnalysis?: string | null
}
interface RawHistory {
  id: string; gradeId: string; action: string; reason?: string; scope?: string
  batchId?: string; actorId?: string; createdAt: string
}
interface RawHistoryCourse {
  offeringId: string; courseId: string; courseCode: string; courseName: string
  academicYear: string; semester: number; className?: string
}
interface RawStudentGrade {
  gradeId: string; offeringId: string; courseId?: string; courseCode: string; courseName: string
  credit?: number; academicYear: string; semester: number; regularScore?: number | null
  makeupRawScore?: number | null; makeupEffectiveScore?: number | null; finalScore?: number | null
  score?: number | null; cappedAtSixty?: boolean; status: string
}
interface RawStudentOverview {
  grades: RawStudentGrade[]; weightedAverage: number; earnedCredits: number; failedCourses: number
}
interface RawRanking { offeringId: string; rank: number; participants: number; percentile?: number; score: number }
interface RawWarning { offeringId: string; courseCode?: string; courseName: string; score?: number; message: string }
interface RawStudentCourse { id: string; code: string; name: string }
interface RawDecisionNode {
  leaf: boolean; feature?: string; threshold?: number; failureProbability: number; samples: number
  failures: number; left?: RawDecisionNode | null; right?: RawDecisionNode | null
}
interface RawPrediction {
  studentId: string; predictedFinalExam: number; intervalLow: number; intervalHigh: number
  failureProbability: number; riskLevel: string; decisionPath?: string[]
}
interface RawPredictionBatch {
  predictions: RawPrediction[]; decisionTree?: RawDecisionNode | null; generatedAt: string; persisted: boolean
}

export interface StudentGradePage extends PageResult<StudentGrade> {
  weightedAverage: number
  earnedCredits: number
  failedCourses: number
}

const courseCache = new Map<string, Course>()
const schemeCache = new Map<string, RawWeightScheme>()
const MAX_GRADE_BATCH_SIZE = 200
const retainedIdempotencyKeys = new Map<string, string>()

interface IdempotentAttempt { fingerprint: string; key: string }
interface GradeActionBody {
  gradeIds: string[]
  examType: 'REGULAR' | 'RETAKE'
  reason: string
  idempotencyKey: string
}
interface PendingWithdrawal {
  attempt: IdempotentAttempt
  body: GradeActionBody
  batchIndex: number
  batchTotal: number
}
const pendingWithdrawals = new Map<string, PendingWithdrawal>()

function pageNumber(page?: number) { return Math.max(0, (page || 1) - 1) }
function stablePayload(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stablePayload).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>).sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => `${JSON.stringify(key)}:${stablePayload(item)}`).join(',')}}`
  }
  return JSON.stringify(value) ?? String(value)
}
function idempotentAttempt(prefix: string, payload: unknown): IdempotentAttempt {
  const fingerprint = `${prefix}:${stablePayload(payload)}`
  let key = retainedIdempotencyKeys.get(fingerprint)
  if (!key) {
    key = crypto.randomUUID()
    retainedIdempotencyKeys.set(fingerprint, key)
  }
  return { fingerprint, key }
}
function ambiguousRequestFailure(cause: unknown): boolean {
  if (!cause || typeof cause !== 'object') return false
  const failure = cause as { code?: unknown; status?: unknown }
  const code = String(failure.code || '')
  return Number(failure.status) === 503
    || ['NETWORK_ERROR', 'TIMEOUT', 'ECONNABORTED', 'ERR_NETWORK',
      'RMI_UNAVAILABLE', 'RMI_TIMEOUT', 'REMOTE_TIMEOUT'].includes(code)
}
function settleIdempotentAttempt(attempt: IdempotentAttempt, cause?: unknown) {
  if (cause && ambiguousRequestFailure(cause)) return
  if (retainedIdempotencyKeys.get(attempt.fingerprint) === attempt.key) {
    retainedIdempotencyKeys.delete(attempt.fingerprint)
  }
}
function batches<T>(items: T[]): T[][] {
  return Array.from({ length: Math.ceil(items.length / MAX_GRADE_BATCH_SIZE) }, (_, index) =>
    items.slice(index * MAX_GRADE_BATCH_SIZE, (index + 1) * MAX_GRADE_BATCH_SIZE))
}
function batchFailure(label: string, completed: number, total: number, cause: unknown): Error {
  const detail = typeof cause === 'object' && cause !== null && 'message' in cause
    ? String((cause as { message: unknown }).message) : '请求失败'
  return new Error(`${label}第 ${completed + 1}/${total} 批失败；前 ${completed} 批已完成，可直接重试剩余数据：${detail}`)
}
function gradeStatus(status?: string | null): GradeRecord['status'] {
  return status === 'SUBMITTED' ? 'SUBMITTED' : status === 'WITHDRAWN' ? 'REVERTED' : 'DRAFT'
}
export function normalizeCourse(raw: RawCourse): Course {
  const course: Course = {
    id: raw.offeringId, courseId: raw.courseId, code: raw.courseCode, name: raw.courseName,
    academicYear: raw.academicYear, semester: String(raw.semester), className: raw.className,
    studentCount: Number(raw.enrolledStudents || 0), credit: Number(raw.credit || 0),
    status: raw.status === 'OPEN' ? 'OPEN' : 'CLOSED',
  }
  courseCache.set(course.id, course)
  return course
}
function normalizeWeights(raw?: RawWeightScheme | null): GradeWeights {
  if (!raw) return cloneWeights(defaultWeights)
  return {
    id: raw.id, offeringId: raw.offeringId, name: raw.name,
    totalWeight: Number(raw.totalWeight), version: Number(raw.version), status: raw.status,
    items: [...(raw.items || [])]
      .sort((left, right) => Number(left.sortOrder) - Number(right.sortOrder))
      .map((item): GradeWeightItem => ({
        id: item.id, itemCode: item.itemCode, itemName: item.itemName,
        weight: Number(item.weight), maxScore: Number(item.maxScore), sortOrder: Number(item.sortOrder),
      })),
  }
}
function normalizeGrade(raw: RawGrade, examType: string): GradeRecord {
  const components = raw.componentScores || {}
  const regularStatus = gradeStatus(raw.status)
  const makeupStatus = raw.makeupStatus ? gradeStatus(raw.makeupStatus) : undefined
  const result: GradeRecord = {
    id: raw.id || '', enrollmentId: raw.enrollmentId, studentId: raw.studentId,
    studentNo: raw.studentNo, studentName: raw.studentName,
    status: examType === 'MAKEUP' ? makeupStatus || 'DRAFT' : regularStatus,
    regularStatus, makeupStatus,
    regularScore: raw.regularScore ?? null, makeupScore: raw.makeupRawScore ?? null,
    totalScore: raw.finalScore ?? raw.regularScore ?? raw.score ?? null,
    schemeId: raw.schemeId, version: Number(raw.version || 0),
    componentScores: Object.fromEntries(Object.entries(components).map(([code, score]) => [code, Number(score)])),
    updatedAt: raw.updatedAt,
  }
  return result
}
export function normalizeStatistics(raw: RawStatistics): GradeStatistics {
  const count = Number(raw.count)
  const excellent = Number(raw.distribution?.['90-100'] || 0)
  return {
    count, average: Number(raw.average), highest: Number(raw.maximum), lowest: Number(raw.minimum),
    passRate: Number(raw.passRate), excellentRate: count > 0 ? excellent * 100 / count : 0, median: Number(raw.median),
    standardDeviation: Number(raw.standardDeviation),
    distribution: Object.entries(raw.distribution || {}).map(([label, count]) => ({ label, count: Number(count) })),
    analysis: raw.savedAnalysis || raw.narrative || '',
  }
}
function toDecisionNode(raw?: RawDecisionNode | null): DecisionNode | undefined {
  if (!raw) return undefined
  if (raw.leaf) return {
    condition: '叶节点',
    result: `挂科概率 ${Number(raw.failureProbability).toFixed(1)}%（${raw.samples} 个样本）`,
  }
  const feature = raw.feature === 'labScore' ? '实验成绩' : '平时成绩'
  return {
    condition: `${feature} ≤ ${Number(raw.threshold).toFixed(1)}`,
    yes: toDecisionNode(raw.left), no: toDecisionNode(raw.right),
  }
}

async function fetchAllGradeRows(offeringId: string): Promise<RawGrade[]> {
  const first = await http.get<RawPage<RawGrade>>(`/teacher/courses/${offeringId}/grade-sheet`, { page: 0, size: 100 })
  if (first.totalPages <= 1) return first.items || []
  const remaining = await Promise.all(
    Array.from({ length: first.totalPages - 1 }, (_, index) =>
      http.get<RawPage<RawGrade>>(`/teacher/courses/${offeringId}/grade-sheet`, { page: index + 1, size: 100 }),
    ),
  )
  return [...(first.items || []), ...remaining.flatMap((item) => item.items || [])]
}

async function ensureCourse(offeringId: string): Promise<Course> {
  const cached = courseCache.get(offeringId)
  if (cached) return { ...cached }
  const first = await http.get<RawPage<RawCourse>>('/teacher/courses', { page: 0, size: 100 })
  const pages = first.totalPages > 1
    ? await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
      http.get<RawPage<RawCourse>>('/teacher/courses', { page: index + 1, size: 100 })))
    : []
  const course = [first, ...pages].flatMap((result) => result.items || [])
    .map(normalizeCourse).find((item) => item.id === offeringId)
  if (!course) throw new Error('课程不存在或当前账号无权访问')
  return course
}

function isRetakeEditable(record: GradeRecord): boolean {
  const regularScore = record.regularScore ?? record.totalScore
  return record.regularStatus === 'SUBMITTED'
    && record.makeupStatus !== 'SUBMITTED'
    && regularScore !== null
    && regularScore !== undefined
    && regularScore < 60
}

function isRetakeDraft(record: GradeRecord): boolean {
  return isRetakeEditable(record)
    && record.makeupScore !== null && record.makeupScore !== undefined
}

export function draftRecordsForExam(records: GradeRecord[], examType: string): GradeRecord[] {
  if (examType === 'MAKEUP') {
    return records.filter(isRetakeDraft)
  }
  return records.filter((record) => record.regularStatus !== 'SUBMITTED')
}

export function persistedGradeIdsForSubmit(records: GradeRecord[], examType: string): string[] {
  return draftRecordsForExam(records, examType).map((record) => record.id).filter(Boolean)
}

export function draftEntriesForExam(records: GradeRecord[], examType: string, weights: GradeWeights) {
  const candidates = examType === 'MAKEUP'
    ? records.filter((record) => isRetakeDraft(record)
      || (isRetakeEditable(record) && record.makeupStatus === 'DRAFT'))
    : draftRecordsForExam(records, examType)
  const selected = candidates.filter((record) => examType === 'MAKEUP'
    || Boolean(record.id) || weights.items.some((item) => record.componentScores[item.itemCode] !== null
      && record.componentScores[item.itemCode] !== undefined))
  return selected.map((record) => {
    if (!record.enrollmentId) throw new Error(`${record.studentName} 缺少选课记录编号`)
    if (examType === 'MAKEUP') {
      return {
        enrollmentId: record.enrollmentId, componentScores: {},
        makeupRawScore: record.makeupScore === null || record.makeupScore === undefined
          ? null : Number(record.makeupScore),
        examType: 'RETAKE', expectedVersion: Number(record.version || 0),
      }
    }
    return {
      enrollmentId: record.enrollmentId,
      componentScores: componentScoresForScheme(record, weights),
      makeupRawScore: null, examType: 'REGULAR', expectedVersion: Number(record.version || 0),
    }
  })
}

async function saveDraftRecords(offeringId: string, records: GradeRecord[], examType: string,
                                onBatchSaved?: (saved: GradeRecord[]) => void): Promise<GradeRecord[]> {
  let scheme = schemeCache.get(offeringId)
  if (!scheme) {
    scheme = await http.get<RawWeightScheme>(`/teacher/courses/${offeringId}/weights`)
    if (scheme) schemeCache.set(offeringId, scheme)
  }
  if (!scheme) throw new Error('请先保存评分系数，再暂存成绩')

  const entries = draftEntriesForExam(records, examType, normalizeWeights(scheme))
  if (!entries.length) {
    throw new Error(examType === 'MAKEUP' ? '请至少填写一名可录入学生的补考成绩' : '当前没有可暂存的正考草稿')
  }
  const groups = batches(entries)
  const savedRecords: GradeRecord[] = []
  for (let index = 0; index < groups.length; index += 1) {
    const request = { schemeId: scheme.id, entries: groups[index] }
    const attempt = idempotentAttempt(`grade-draft:${offeringId}`, request)
    try {
      const result = await http.post<RawGrade[]>(`/teacher/courses/${offeringId}/grades/draft`, {
        ...request, idempotencyKey: attempt.key,
      })
      const normalized = (result || []).map((item) => normalizeGrade(item, examType))
      if (normalized.length !== groups[index]!.length) {
        throw new Error('服务返回的暂存结果数量与请求不一致')
      }
      savedRecords.push(...normalized)
      const byEnrollment = new Map(normalized.map((item) => [item.enrollmentId, item]))
      records.forEach((record) => {
        const saved = byEnrollment.get(record.enrollmentId)
        if (saved) Object.assign(record, mergeGradeRecord(record, saved))
      })
      onBatchSaved?.(normalized)
      settleIdempotentAttempt(attempt)
    } catch (cause) {
      settleIdempotentAttempt(attempt, cause)
      throw batchFailure('暂存成绩', index, groups.length, cause)
    }
  }
  return savedRecords
}

export function predictionInputs(rows: RawGrade[]) {
  return rows.flatMap((row) => {
    if (!row.studentId) return []
    const entries = Object.entries(row.componentScores || {})
    const normalized = (code: string) => code.toUpperCase().replace(/[^A-Z0-9]/g, '')
    const usual = entries.find(([code]) => /USUAL|PROCESS|CONTINUOUS|DAILY/.test(normalized(code)))?.[1]
    const lab = entries.find(([code]) => /LAB|EXPERIMENT|PRACTICAL/.test(normalized(code)))?.[1]
    if (usual === null || usual === undefined || lab === null || lab === undefined) return []
    const usualScore = Number(usual)
    const labScore = Number(lab)
    if (!Number.isFinite(usualScore) || !Number.isFinite(labScore)) return []
    return [{ studentId: row.studentId, usualScore, labScore }]
  })
}

type RawCurrentUser = Omit<Partial<CurrentUser>, 'role' | 'roles'> & {
  role?: Role | string
  roles?: string[]
  authorities?: string[]
  name?: string
  sub?: string
  organizationId?: string
}

export function normalizeCurrentUser(raw: RawCurrentUser): CurrentUser {
  const normalizedRoles = (raw.roles || [])
    .map((role) => String(role).replace(/^ROLE_/, '').toUpperCase())
    .filter((role): role is Role => ['ADMIN', 'TEACHER', 'STUDENT'].includes(role))
  const explicitRole = raw.role ? String(raw.role).replace(/^ROLE_/, '').toUpperCase() as Role : undefined
  const role = explicitRole && ['ADMIN', 'TEACHER', 'STUDENT'].includes(explicitRole)
    ? explicitRole
    : (['ADMIN', 'TEACHER', 'STUDENT'] as Role[]).find((candidate) => normalizedRoles.includes(candidate))
  if (!role) throw new Error('登录响应缺少有效角色')
  const authorityPermissions = (raw.authorities || []).filter((item) => !item.startsWith('ROLE_'))
  return {
    id: String(raw.id || raw.username || raw.sub || ''),
    username: String(raw.username || raw.sub || ''),
    displayName: String(raw.displayName || raw.name || raw.username || raw.sub || '用户'),
    role,
    roles: normalizedRoles.length ? normalizedRoles : [role],
    permissions: [...new Set([...(raw.permissions || []), ...authorityPermissions])],
    organization: raw.organization || raw.organizationId,
  }
}

export const authApi = {
  login: (credentials: { username: string; password: string }) =>
    http.post<RawCurrentUser>('/auth/login', credentials).then(normalizeCurrentUser),
  logout: () => http.post<void>('/auth/logout'),
  me: () => http.get<RawCurrentUser>('/auth/me').then(normalizeCurrentUser),
}

export const teacherApi = {
  courses: async (params?: { academicYear?: string; semester?: string; name?: string; page?: number; size?: number }) => {
    const result = await http.get<RawPage<RawCourse>>('/teacher/courses', {
      academicYear: params?.academicYear, semester: params?.semester ? Number(params.semester) : undefined,
      keyword: params?.name, page: pageNumber(params?.page), size: params?.size || 10,
    })
    return { ...result, page: result.page + 1, items: result.items.map(normalizeCourse) } satisfies PageResult<Course>
  },
  historyCourses: async (params?: { keyword?: string; academicYear?: string; semester?: string; page?: number; size?: number }) => {
    const result = await http.get<RawPage<RawHistoryCourse>>('/teacher/history-courses', {
      keyword: params?.keyword, academicYear: params?.academicYear,
      semester: params?.semester ? Number(params.semester) : undefined,
      page: pageNumber(params?.page), size: params?.size || 10,
    })
    return { ...result, page: result.page + 1,
      items: (result.items || []).map((item): HistoryCourse => ({ ...item, semester: Number(item.semester) })),
    } satisfies PageResult<HistoryCourse>
  },
  historyCourse: (offeringId: string) => http.get<RawHistoryCourse>(`/teacher/history-courses/${offeringId}`)
    .then((item): HistoryCourse => ({ ...item, semester: Number(item.semester) })),
  gradeSheet: async (courseId: string, params?: { examType?: string }) => {
    const [course, rawScheme] = await Promise.all([
      ensureCourse(courseId),
      http.get<RawWeightScheme | null>(`/teacher/courses/${courseId}/weights`),
    ])
    if (rawScheme) schemeCache.set(courseId, rawScheme)
    else schemeCache.delete(courseId)
    const rows = rawScheme ? await fetchAllGradeRows(courseId) : []
    const type = params?.examType || 'REGULAR'
    const records = rows.map((item) => normalizeGrade(item, type))
    return { course, weights: normalizeWeights(rawScheme), records }
  },
  saveWeights: async (courseId: string, weights: GradeWeights) => {
    const existing = schemeCache.get(courseId)
    const items: RawWeightItem[] = weights.items.map((item, index) => ({
      id: item.id,
      itemCode: item.itemCode.trim(),
      itemName: item.itemName.trim(),
      weight: Number(item.weight),
      maxScore: Number(item.maxScore),
      sortOrder: Number(item.sortOrder || index + 1),
    }))
    const saved = await http.put<RawWeightScheme>(`/teacher/courses/${courseId}/weights`, {
      name: weights.name.trim(), items, expectedVersion: existing?.version ?? weights.version ?? 0,
    })
    schemeCache.set(courseId, saved)
    return normalizeWeights(saved)
  },
  saveDraft: (courseId: string, records: GradeRecord[], examType: string,
              onBatchSaved?: (saved: GradeRecord[]) => void) =>
    saveDraftRecords(courseId, records, examType, onBatchSaved),
  submit: async (courseId: string, records: GradeRecord[], examType: string) => {
    const gradeIds = persistedGradeIdsForSubmit(records, examType)
    if (!gradeIds.length) throw new Error('请先暂存待提交成绩')
    const groups = batches(gradeIds)
    for (let index = 0; index < groups.length; index += 1) {
      const request = {
        gradeIds: groups[index]!, examType: examType === 'MAKEUP' ? 'RETAKE' as const : 'REGULAR' as const,
        reason: examType === 'MAKEUP' ? '教师确认提交补考成绩' : '教师确认提交正考成绩',
      }
      const attempt = idempotentAttempt(`grade-submit:${courseId}`, request)
      try {
        await http.post<void>(`/teacher/courses/${courseId}/grades/submit`, {
          ...request, idempotencyKey: attempt.key,
        })
        const submitted = new Set(groups[index])
        records.forEach((record) => {
          if (!submitted.has(record.id)) return
          if (examType === 'MAKEUP') record.makeupStatus = 'SUBMITTED'
          else record.regularStatus = 'SUBMITTED'
          record.status = 'SUBMITTED'
        })
        settleIdempotentAttempt(attempt)
      } catch (cause) {
        settleIdempotentAttempt(attempt, cause)
        throw batchFailure('提交成绩', index, groups.length, cause)
      }
    }
  },
  withdraw: async (courseId: string, examType: string) => {
    const pendingKey = `${courseId}:${examType}`
    let replayedPending = false
    const pending = pendingWithdrawals.get(pendingKey)
    if (pending) {
      try {
        await http.post<void>(`/teacher/courses/${courseId}/grades/withdraw`, pending.body)
        settleIdempotentAttempt(pending.attempt)
        pendingWithdrawals.delete(pendingKey)
        replayedPending = true
      } catch (cause) {
        settleIdempotentAttempt(pending.attempt, cause)
        if (!ambiguousRequestFailure(cause)) pendingWithdrawals.delete(pendingKey)
        throw batchFailure('撤销成绩', pending.batchIndex, pending.batchTotal, cause)
      }
    }
    const rows = await fetchAllGradeRows(courseId)
    const gradeIds = rows.filter((item) => item.id && (
      examType === 'MAKEUP' ? item.makeupStatus === 'SUBMITTED' : item.status === 'SUBMITTED'
    )).map((item) => String(item.id))
    if (!gradeIds.length) {
      if (replayedPending) return
      throw new Error('当前没有可撤销的已提交成绩')
    }
    const groups = batches(gradeIds)
    for (let index = 0; index < groups.length; index += 1) {
      const request = {
        gradeIds: groups[index]!, examType: examType === 'MAKEUP' ? 'RETAKE' as const : 'REGULAR' as const,
        reason: examType === 'MAKEUP' ? '教师主动撤销已提交补考成绩' : '教师主动撤销已提交正考成绩',
      }
      const attempt = idempotentAttempt(`grade-withdraw:${courseId}`, request)
      const body: GradeActionBody = { ...request, idempotencyKey: attempt.key }
      try {
        await http.post<void>(`/teacher/courses/${courseId}/grades/withdraw`, body)
        settleIdempotentAttempt(attempt)
      } catch (cause) {
        settleIdempotentAttempt(attempt, cause)
        if (ambiguousRequestFailure(cause)) {
          pendingWithdrawals.set(pendingKey, { attempt, body, batchIndex: index, batchTotal: groups.length })
        }
        throw batchFailure('撤销成绩', index, groups.length, cause)
      }
    }
  },
  statistics: (courseId: string) =>
    http.get<RawStatistics>(`/teacher/courses/${courseId}/statistics`).then(normalizeStatistics),
  saveAnalysis: (courseId: string, analysis: string) =>
    http.put<{ statistics: RawStatistics; analysis: string }>(`/teacher/courses/${courseId}/statistics`, { analysis })
      .then((item) => ({ ...normalizeStatistics(item.statistics), analysis: item.analysis })),
  courseHistory: async (offeringId: string, params?: { courseId?: string; academicYear?: string; page?: number; size?: number }) => {
    const resolvedCourseId = params?.courseId || (await ensureCourse(offeringId)).courseId
    if (!resolvedCourseId) throw new Error('课程历史目录缺少课程编号')
    const result = await http.get<RawPage<{
      academicYear: string; semester: number; offeringId: string; studentId: string; studentNo: string
      studentName: string; regularScore?: number | null; makeupRawScore?: number | null; finalScore?: number | null
    }>>('/teacher/course-history', {
      courseId: resolvedCourseId, academicYear: params?.academicYear,
      page: pageNumber(params?.page), size: params?.size || 20,
    })
    const items: GradeHistoryEntry[] = result.items.map((item) => ({
      id: `${item.offeringId}:${item.studentId}`, gradeId: item.offeringId, action: 'COURSE_GRADE', createdAt: '',
      academicYear: item.academicYear, semester: String(item.semester), studentNo: item.studentNo,
      studentName: item.studentName, regularScore: item.regularScore, makeupScore: item.makeupRawScore,
      finalScore: item.finalScore,
    }))
    return { ...result, page: result.page + 1, items } satisfies PageResult<GradeHistoryEntry>
  },
  history: async (courseId: string, params?: { gradeId?: string; page?: number; size?: number }) => {
    const result = await http.get<RawPage<RawHistory>>(`/teacher/courses/${courseId}/history`, {
      gradeId: params?.gradeId, page: pageNumber(params?.page), size: params?.size || 20,
    })
    const items: GradeHistoryEntry[] = result.items.map((item) => ({ ...item }))
    return { ...result, page: result.page + 1, items } satisfies PageResult<GradeHistoryEntry>
  },
  predictions: async (courseId: string) => {
    const rows = await fetchAllGradeRows(courseId)
    const students = predictionInputs(rows)
    if (!students.length) throw new Error('当前没有可用于预测的平时与实验成绩')
    const results = await Promise.all(batches(students).map((group) =>
      http.post<RawPredictionBatch>(`/teacher/courses/${courseId}/predictions`, { students: group })))
    const names = new Map(rows.map((row) => [row.studentId, row]))
    const predictions: Prediction[] = results.flatMap((result) => result.predictions).map((item) => {
      const student = names.get(item.studentId)
      return {
        studentId: item.studentId, studentNo: student?.studentNo || '', studentName: student?.studentName || item.studentId,
        predictedFinal: Number(item.predictedFinalExam), lowerBound: Number(item.intervalLow), upperBound: Number(item.intervalHigh),
        riskLevel: ['LOW', 'MEDIUM', 'HIGH'].includes(item.riskLevel) ? item.riskLevel as Prediction['riskLevel'] : 'MEDIUM',
        factors: item.decisionPath || [],
      }
    })
    return { predictions, decisionTree: toDecisionNode(results[0]?.decisionTree) }
  },
  recognize: (_courseId: string, image: File) => {
    const body = new FormData()
    body.append('image', image)
    return http.post<{ text: string; provider: string; requestId: string }>('/ocr/recognize', body)
  },
}

export const studentApi = {
  courses: () => http.get<RawStudentCourse[]>('/student/courses').then((items): StudentCourse[] =>
    (items || []).map((item) => ({ id: item.id, code: item.code, name: item.name }))),
  grades: async (scope: 'current' | 'all', params?: { page?: number; size?: number }): Promise<StudentGradePage> => {
    const result = await http.get<RawStudentOverview>('/student/grades', { scope })
    const all = (result.grades || []).map((item): StudentGrade => {
      const final = Number(item.finalScore ?? item.score ?? item.regularScore ?? 0)
      return {
        id: item.gradeId, offeringId: item.offeringId, courseId: item.courseId, courseCode: item.courseCode,
        courseName: item.courseName, academicYear: item.academicYear, semester: String(item.semester), credits: Number(item.credit || 0),
        regularScore: item.regularScore ?? null, makeupScore: item.makeupRawScore ?? null, finalScore: final,
        displayScore: item.makeupRawScore == null ? String(item.regularScore ?? final) : `${item.regularScore ?? '--'}/${Math.min(60, Number(item.makeupRawScore))}`,
        passed: final >= 60,
      }
    })
    const page = params?.page || 1
    const size = params?.size || 15
    return {
      items: all.slice((page - 1) * size, page * size), page, size, total: all.length,
      totalPages: Math.ceil(all.length / size), weightedAverage: Number(result.weightedAverage || 0),
      earnedCredits: Number(result.earnedCredits || 0), failedCourses: Number(result.failedCourses || 0),
    }
  },
  ranking: (offeringId: string) => http.get<RawRanking>('/student/ranking', { offeringId }).then((item) => ({
    rank: item.rank, totalStudents: item.participants, average: Number(item.score), percentile: Number(item.percentile || 0),
  })),
  warnings: () => http.get<RawWarning[]>('/student/warnings').then((items) => items.map((item): WarningItem => ({
    id: item.offeringId, courseName: item.courseName, level: Number(item.score || 0) < 55 ? 'HIGH' : 'MEDIUM',
    message: item.message, predictedScore: item.score,
  }))),
  risk: (params: { courseId: string; usualScore: number; labScore: number }) =>
    http.get<{
      studentId: string; courseId: string; sampleYears: number; predictedScore: number
      failureProbability: number; level: string; linearRegressionExplanation?: string[]
      decisionPath?: string[]; generatedAt: string
    }>('/student/risk', params).then((item): RiskAssessment => ({
      studentId: item.studentId, courseId: item.courseId, sampleYears: Number(item.sampleYears || 0),
      predictedScore: Number(item.predictedScore), failureProbability: Number(item.failureProbability),
      level: item.level === 'HIGH' ? 'HIGH' : item.level === 'LOW' ? 'LOW' : 'MEDIUM',
      linearRegressionExplanation: item.linearRegressionExplanation || [], decisionPath: item.decisionPath || [],
      generatedAt: item.generatedAt,
    })),
}

interface RawAdminUser {
  id: string; username: string; displayName: string; email?: string; status: string
  roles: string[]; studentId?: string; studentNo?: string; teacherId?: string; teacherNo?: string
  organizationId?: string; organizationName?: string; className?: string; major?: string
  permissionOverrides?: Record<string, boolean>; createdAt?: string; updatedAt?: string
}
interface RawAdminGrade {
  id: string; enrollmentId: string; status: string; version: number; studentId: string; studentNo: string
  studentName: string; offeringId: string; courseId: string; courseCode: string; courseName: string
  academicYear: string; semester: number; className?: string; submittedAt?: string; updatedAt?: string
}
interface RawOrganization { id: string; code: string; name: string; parentId?: string | null }
interface RawRolePermissions { roleId: string; roleCode: string; permissions: string[] }
interface RawPermission { code: string; name: string }
interface RawAuditLog {
  id: string; requestId?: string; actor: string; operation: string; tableName: string
  recordKey: string; success: boolean; detail?: string; createdAt: string
}
interface RawAlert {
  id: string; type: string; severity: string; message: string; status: string
  relatedTable?: string; relatedId?: string; createdAt: string; resolvedAt?: string
}
interface RawReversion {
  id: string; requestNo?: string; scope: string; targetFilter: string; reason: string; status: string
  requestedBy: string; requestedAt: string; approvedAt?: string; executedAt?: string
  reviewer?: string; reviewComment?: string; reviewedAt?: string
}
interface UserMutation extends Partial<ManagedUser> {
  password?: string; newPassword?: string; studentNo?: string; teacherNo?: string; className?: string; major?: string
}

const permissionLabels: Record<string, string> = {
  COURSE_READ: '读取课程', GRADE_READ: '读取授课成绩', GRADING_SCHEME_WRITE: '维护评分方案',
  GRADE_DRAFT_WRITE: '暂存成绩', GRADE_SUBMIT: '提交成绩', GRADE_WITHDRAW: '撤销提交',
  GRADE_ANALYTICS_READ: '成绩统计分析', GRADE_HISTORY_READ: '成绩历史', RISK_ANALYZE: '教师学业预测',
  GRADE_SELF_READ: '查看本人成绩', RISK_SELF_ANALYZE: '本人学业预测', USER_MANAGE: '用户管理',
  ORG_MANAGE: '组织管理', PERMISSION_MANAGE: '权限管理', GRADE_REVERT_SMALL: '小撤销',
  GRADE_REVERT_REQUEST: '发起高风险撤销', GRADE_REVERT_APPROVE: '审批高风险撤销',
  GRADE_RESTORE_ORIGINAL: '原始成绩恢复', AUDIT_READ: '安全审计', INTEGRITY_VERIFY: '完整性验证',
  ALERT_MANAGE: '告警处置',
}
let rolePermissions: RawRolePermissions[] = []
let rolesPromise: Promise<RawRolePermissions[]> | null = null
let rolesLoaded = false

async function loadRolePermissions() {
  if (rolesLoaded) return rolePermissions
  if (!rolesPromise) {
    rolesPromise = http.get<RawRolePermissions[]>('/admin/roles').then((items) => {
      rolePermissions = items || []
      rolesLoaded = true
      return rolePermissions
    }).finally(() => { rolesPromise = null })
  }
  return rolesPromise
}

function adminRoles(roles?: string[]): Role[] {
  const supported = new Set<Role>(['ADMIN', 'TEACHER', 'STUDENT'])
  return [...new Set((roles || [])
    .map((item) => item.replace(/^ROLE_/, '').toUpperCase())
    .filter((item): item is Role => supported.has(item as Role)))]
}
export function normalizeAdminUser(raw: RawAdminUser, defaults: RawRolePermissions[] = rolePermissions): ManagedUser {
  const roles = adminRoles(raw.roles)
  const role = (['ADMIN', 'TEACHER', 'STUDENT'] as Role[]).find((item) => roles.includes(item))
  if (!role) throw new Error('用户响应缺少可交互角色')
  const effective = new Set(defaults
    .filter((item) => roles.includes(item.roleCode.replace(/^ROLE_/, '').toUpperCase() as Role))
    .flatMap((item) => item.permissions || [])
  )
  const overrides = { ...(raw.permissionOverrides || {}) }
  Object.entries(overrides).forEach(([code, granted]) => {
    if (granted) effective.add(code)
    else effective.delete(code)
  })
  const status: ManagedUser['status'] = ['ACTIVE', 'DISABLED', 'LOCKED'].includes(raw.status)
    ? raw.status as ManagedUser['status'] : 'DISABLED'
  return {
    id: raw.id, username: raw.username, displayName: raw.displayName, email: raw.email,
    role, roles, status, enabled: status === 'ACTIVE', permissions: [...effective].sort(),
    permissionOverrides: overrides, studentId: raw.studentId, studentNo: raw.studentNo,
    teacherId: raw.teacherId, teacherNo: raw.teacherNo, organizationId: raw.organizationId,
    organizationName: raw.organizationName, className: raw.className, major: raw.major,
    updatedAt: raw.updatedAt,
  }
}
function normalizeOrganization(raw: RawOrganization): Organization {
  return { id: raw.id, code: raw.code, name: raw.name, parentId: raw.parentId }
}
function normalizeReversion(raw: RawReversion): ReversionRequest {
  return {
    id: raw.id, scope: raw.scope === 'ORIGINAL_RESTORE' ? 'ORIGINAL_RESTORE' : 'LARGE_BATCH',
    targetFilter: raw.targetFilter, reason: raw.reason,
    status: ['PENDING', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED'].includes(raw.status)
      ? raw.status as ReversionRequest['status'] : 'PENDING',
    initiator: raw.requestedBy, reviewer: raw.reviewer, comment: raw.reviewComment,
    createdAt: raw.requestedAt, reviewedAt: raw.reviewedAt,
  }
}

async function reviewReversionRequest(id: string, comment: string, action: 'approve' | 'reject') {
  const path = `/admin/reversion-requests/${id}/${action}`
  try {
    return normalizeReversion(await http.post<RawReversion>(path, { comment }))
  } catch (cause) {
    if (!ambiguousRequestFailure(cause)) throw cause
    try {
      const current = normalizeReversion(await http.get<RawReversion>(`/admin/reversion-requests/${id}`))
      const expected = action === 'approve' ? ['APPROVED', 'EXECUTED'] : ['REJECTED']
      if (expected.includes(current.status)) return current
    } catch {
      // Preserve the original ambiguous failure when reconciliation is also unavailable.
    }
    throw cause
  }
}

export const adminApi = {
  users: async (params: { keyword?: string; role?: string; organizationId?: string; page: number; size: number; includeRolePermissions?: boolean }) => {
    const [result, defaults] = await Promise.all([
      http.get<RawPage<RawAdminUser>>('/admin/users', {
        keyword: params.keyword, role: params.role, organizationId: params.organizationId,
        page: pageNumber(params.page), size: params.size,
      }),
      params.includeRolePermissions ? loadRolePermissions().catch(() => []) : Promise.resolve([]),
    ])
    return { ...result, page: result.page + 1,
      items: result.items.map((item) => normalizeAdminUser(item, defaults)) } satisfies PageResult<ManagedUser>
  },
  saveUser: async (user: UserMutation) => {
    const payload = user.id ? {
      displayName: user.displayName, email: user.email || null,
      status: user.status || (user.enabled ? 'ACTIVE' : 'DISABLED'),
      roleCodes: user.roles?.length ? user.roles : [user.role], newPassword: user.newPassword || null,
      ...(user.permissionOverrides !== undefined ? { permissionOverrides: user.permissionOverrides } : {}),
      ...(user.organizationId !== undefined ? { organizationId: user.organizationId || null } : {}),
      ...((user.roles?.length ? user.roles : [user.role]).includes('STUDENT')
        ? { className: user.className ?? '', major: user.major ?? '' } : {}),
    } : {
      username: user.username, password: user.password, displayName: user.displayName, email: user.email || null,
      roleCodes: [user.role], studentNo: user.studentNo || null, teacherNo: user.teacherNo || null,
      organizationId: user.organizationId || null, className: user.className || null, major: user.major || null,
      ...(user.permissionOverrides !== undefined ? { permissionOverrides: user.permissionOverrides } : {}),
    }
    const raw = user.id
      ? await http.put<RawAdminUser>(`/admin/users/${user.id}`, payload)
      : await http.post<RawAdminUser>('/admin/users', payload)
    return normalizeAdminUser(raw)
  },
  grades: async (params: { course?: string; student?: string; status?: string; page: number; size: number }) => {
    const result = await http.get<RawPage<RawAdminGrade>>('/admin/grades', {
      course: params.course, student: params.student, status: params.status,
      page: pageNumber(params.page), size: params.size,
    })
    return { ...result, page: result.page + 1, items: result.items.map((item): AdminGradeRecord => ({
      ...item, status: item.status === 'SUBMITTED' ? 'SUBMITTED' : 'DRAFT',
      version: Number(item.version), semester: Number(item.semester),
    })) } satisfies PageResult<AdminGradeRecord>
  },
  organizations: () => http.get<RawOrganization[]>('/admin/organizations').then((items) => items.map(normalizeOrganization)),
  saveOrganization: async (organization: Partial<Organization>) => {
    const payload = { code: organization.code, name: organization.name, parentId: organization.parentId || null }
    const raw = organization.id
      ? await http.put<RawOrganization>(`/admin/organizations/${organization.id}`, payload)
      : await http.post<RawOrganization>('/admin/organizations', payload)
    return normalizeOrganization(raw)
  },
  deleteOrganization: (id: string) => http.delete<void>(`/admin/organizations/${id}`),
  permissions: () => http.get<RawPermission[]>('/admin/permissions').then((items): PermissionOption[] =>
    items.map((item) => ({ code: item.code, name: item.name || permissionLabels[item.code] || item.code }))),
  roles: async (): Promise<RolePermissions[]> => (await loadRolePermissions())
    .map((item) => ({ roleId: item.roleId,
      roleCode: item.roleCode.replace(/^ROLE_/, '').toUpperCase() as Role,
      permissions: [...(item.permissions || [])].sort() })),
  updateRolePermissions: async (roleId: string, permissions: string[]): Promise<RolePermissions> => {
    const raw = await http.put<RawRolePermissions>(`/admin/roles/${roleId}/permissions`, { permissions })
    const index = rolePermissions.findIndex((item) => item.roleId === raw.roleId)
    if (index >= 0) rolePermissions[index] = raw
    else rolePermissions.push(raw)
    rolesLoaded = true
    return { roleId: raw.roleId, roleCode: raw.roleCode.replace(/^ROLE_/, '').toUpperCase() as Role,
      permissions: [...(raw.permissions || [])].sort() }
  },
  updatePermissions: async (userId: string, overrides: Record<string, boolean>) => {
    await http.put(`/admin/users/${userId}/permissions`, { overrides })
  },
  smallRevert: async (gradeIds: string[], reason: string) => {
    const request = { gradeIds, reason }
    const attempt = idempotentAttempt('small-revert', request)
    try {
      await http.post<void>('/admin/grades/revert-small', { ...request, idempotencyKey: attempt.key })
      settleIdempotentAttempt(attempt)
    } catch (cause) {
      settleIdempotentAttempt(attempt, cause)
      throw cause
    }
  },
  reversionRequests: async (params: { status?: string; page: number; size: number }) => {
    const result = await http.get<RawPage<RawReversion>>('/admin/reversion-requests', {
      status: params.status, page: pageNumber(params.page), size: params.size,
    })
    return { ...result, page: result.page + 1, items: result.items.map(normalizeReversion) } satisfies PageResult<ReversionRequest>
  },
  createReversionRequest: async (payload: Pick<ReversionRequest, 'scope' | 'targetFilter' | 'reason'>) => {
    const attempt = idempotentAttempt('reversion-request', payload)
    try {
      const result = normalizeReversion(await http.post<RawReversion>('/admin/reversion-requests', {
        ...payload, idempotencyKey: attempt.key,
      }))
      settleIdempotentAttempt(attempt)
      return result
    } catch (cause) {
      settleIdempotentAttempt(attempt, cause)
      throw cause
    }
  },
  approveReversionRequest: (id: string, comment: string) => reviewReversionRequest(id, comment, 'approve'),
  rejectReversionRequest: (id: string, comment: string) => reviewReversionRequest(id, comment, 'reject'),
  auditLogs: async (params: { keyword?: string; page: number; size: number }) => {
    const result = await http.get<RawPage<RawAuditLog>>('/admin/audit-logs', {
      actor: params.keyword, page: pageNumber(params.page), size: params.size,
    })
    const items: AuditLog[] = result.items.map((item) => ({
      id: item.id, actor: item.actor, action: item.operation, resource: `${item.tableName}:${item.recordKey}`,
      detail: item.detail, createdAt: item.createdAt, success: item.success,
    }))
    return { ...result, page: result.page + 1, items } satisfies PageResult<AuditLog>
  },
  verifyAuditChain: () => http.post<{ valid: boolean; checkedEntries: number; firstInvalidSequence?: number }>('/admin/integrity/verify').then((item) => ({
    valid: item.valid, checkedBlocks: item.checkedEntries, brokenAt: item.firstInvalidSequence,
  })),
  alerts: async (params: { status?: string; page: number; size: number }) => {
    const result = await http.get<RawPage<RawAlert>>('/admin/alerts', {
      status: params.status, page: pageNumber(params.page), size: params.size,
    })
    const items: SecurityAlert[] = result.items.map((item) => ({
      id: item.id, severity: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(item.severity)
        ? item.severity as SecurityAlert['severity'] : 'MEDIUM',
      type: item.type, message: item.message, status: item.status === 'RESOLVED' ? 'RESOLVED' : 'OPEN',
      createdAt: item.createdAt, relatedTable: item.relatedTable, relatedId: item.relatedId,
    }))
    return { ...result, page: result.page + 1, items } satisfies PageResult<SecurityAlert>
  },
  updateAlert: (id: string) => http.patch<void>(`/admin/alerts/${id}/resolve`),
  recoveryEvidence: (gradeId: string) => http.get<RecoveryEvidence[]>('/admin/recovery-evidence', { gradeId, limit: 50 }),
  previewRecovery: (sequence: number, reason: string) =>
    http.post<{ sequence: number; eventType: string; aggregateId: string; originalValues: Record<string, string>; createdAt: string }>(
      `/admin/recovery-evidence/${sequence}/preview`, { reason },
    ),
}
