import { afterEach, describe, expect, it, vi } from 'vitest'
import { http } from './http'
import {
  adminApi, draftEntriesForExam, draftRecordsForExam, normalizeAdminUser, normalizeCourse, normalizeCurrentUser,
  normalizeStatistics, predictionInputs,
  persistedGradeIdsForSubmit, studentApi, teacherApi,
} from './services'
import type { GradeRecord, GradeWeights } from '@/types/domain'

afterEach(() => vi.restoreAllMocks())

describe('current user normalization', () => {
  it('derives the highest-priority role when only roles are returned', () => {
    const user = normalizeCurrentUser({
      id: '1', username: 'admin', displayName: '系统管理员', roles: ['ROLE_STUDENT', 'ADMIN'], permissions: ['AUDIT_READ'],
    })
    expect(user.role).toBe('ADMIN')
    expect(user.permissions).toEqual(['AUDIT_READ'])
  })

  it('keeps role authorities out of functional permissions', () => {
    const user = normalizeCurrentUser({
      username: 'teacher', roles: ['TEACHER'], authorities: ['ROLE_TEACHER', 'COURSE_READ', 'GRADE_READ'],
    })
    expect(user.permissions).toEqual(['COURSE_READ', 'GRADE_READ'])
  })

  it('rejects a response without any supported role', () => {
    expect(() => normalizeCurrentUser({ username: 'unknown', roles: ['GUEST'] })).toThrow('缺少有效角色')
  })
})

describe('managed user normalization', () => {
  it('keeps role defaults separate from true and false user overrides', () => {
    const user = normalizeAdminUser({
      id: 'teacher-1', username: 'teacher01', displayName: '张老师', status: 'ACTIVE',
      roles: ['TEACHER'], organizationId: 'org-1', organizationName: '信息工程学院',
      permissionOverrides: { GRADE_SUBMIT: false, GRADE_REVERT_SMALL: true },
    }, [{
      roleId: 'role-teacher', roleCode: 'TEACHER',
      permissions: ['COURSE_READ', 'GRADE_SUBMIT'],
    }])

    expect(user.permissions).toEqual(['COURSE_READ', 'GRADE_REVERT_SMALL'])
    expect(user.permissionOverrides).toEqual({ GRADE_SUBMIT: false, GRADE_REVERT_SMALL: true })
    expect(user.organizationId).toBe('org-1')
    expect(user.organizationName).toBe('信息工程学院')
    expect(user.status).toBe('ACTIVE')
  })

  it('preserves a locked account when saving unrelated profile edits', async () => {
    const locked = normalizeAdminUser({
      id: 'teacher-locked', username: 'locked', displayName: 'Locked Teacher', status: 'LOCKED',
      roles: ['TEACHER'], teacherId: 'teacher-1', teacherNo: 'T001', organizationId: 'org-1',
    })
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      id: locked.id, username: locked.username, displayName: locked.displayName,
      status: 'LOCKED', roles: ['TEACHER'], teacherId: 'teacher-1', teacherNo: 'T001', organizationId: 'org-1',
    })

    await adminApi.saveUser(locked)

    expect(locked.enabled).toBe(false)
    expect(put).toHaveBeenCalledWith('/admin/users/teacher-locked', expect.objectContaining({ status: 'LOCKED' }))
  })

  it('converts the admin grade search page to one-based frontend pagination', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      items: [{ id: 'grade-1', enrollmentId: 'enrollment-1', status: 'SUBMITTED', version: 2,
        studentId: 'student-1', studentNo: '20260001', studentName: 'Student', offeringId: 'offering-1',
        courseId: 'course-1', courseCode: 'CS101', courseName: 'Course', academicYear: '2026-2027', semester: 1 }],
      page: 1, size: 20, total: 21, totalPages: 2,
    })

    const result = await adminApi.grades({ course: 'CS101', page: 2, size: 20 })

    expect(get).toHaveBeenCalledWith('/admin/grades', expect.objectContaining({ page: 1, size: 20 }))
    expect(result.page).toBe(2)
    expect(result.items[0]?.status).toBe('SUBMITTED')
  })

  it('sends an empty role permission set as a full replacement', async () => {
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      roleId: 'role-student', roleCode: 'STUDENT', permissions: [],
    })

    const updated = await adminApi.updateRolePermissions('role-student', [])

    expect(put).toHaveBeenCalledWith('/admin/roles/role-student/permissions', { permissions: [] })
    expect(updated.permissions).toEqual([])
  })

  it('includes deny overrides in the same user creation request', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      id: 'student-1', username: 'student-new', displayName: 'New Student', status: 'ACTIVE',
      roles: ['STUDENT'], permissionOverrides: { RISK_SELF_ANALYZE: false },
    })

    await adminApi.saveUser({
      username: 'student-new', password: 'password1', displayName: 'New Student', role: 'STUDENT',
      studentNo: '20260001', enabled: true, permissions: [], roles: ['STUDENT'],
      permissionOverrides: { RISK_SELF_ANALYZE: false },
    })

    expect(post).toHaveBeenCalledWith('/admin/users', expect.objectContaining({
      permissionOverrides: { RISK_SELF_ANALYZE: false },
    }))
  })
})

describe('course state normalization', () => {
  const course = (status: string) => normalizeCourse({
    offeringId: `offering-${status}`, courseId: 'course-1', courseCode: 'CS101', courseName: '软件工程',
    credit: 3, academicYear: '2026-2027', semester: 1, status, enrolledStudents: 30,
  })

  it('only treats the backend OPEN offering state as editable', () => {
    expect(course('OPEN').status).toBe('OPEN')
    expect(course('CLOSED').status).toBe('CLOSED')
    expect(course('ACTIVE').status).toBe('CLOSED')
    expect(course('').status).toBe('CLOSED')
  })
})

describe('history course paging', () => {
  it('passes independent filters to the server and preserves its exact total', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      items: [{ offeringId: 'offering-history', courseId: 'course-history', courseCode: 'CS_100%',
        courseName: '程序设计', academicYear: '2026-2027', semester: '2', className: '计科一班' }],
      page: 2, size: 10, total: 1001, totalPages: 101,
    })

    const result = await teacherApi.historyCourses({
      keyword: 'CS_100%', academicYear: '2026-2027', semester: '2', page: 3, size: 10,
    })

    expect(get).toHaveBeenCalledWith('/teacher/history-courses', {
      keyword: 'CS_100%', academicYear: '2026-2027', semester: 2, page: 2, size: 10,
    })
    expect(result).toMatchObject({ page: 3, total: 1001, totalPages: 101 })
    expect(result.items[0]?.semester).toBe(2)
  })
})

describe('statistics normalization', () => {
  const statistics = {
    count: 0, average: 0, maximum: 0, minimum: 0, median: 0, passRate: 0,
    standardDeviation: 0, distribution: {}, narrative: 'generated narrative',
  }

  it('prefers a saved analysis loaded by a fresh statistics request', () => {
    expect(normalizeStatistics({ ...statistics, savedAnalysis: 'saved teacher analysis' }).analysis)
      .toBe('saved teacher analysis')
  })

  it('falls back to the generated narrative when no analysis was saved', () => {
    expect(normalizeStatistics(statistics).analysis).toBe('generated narrative')
  })

  it('derives the excellent rate from the 90-100 distribution band', () => {
    expect(normalizeStatistics({
      ...statistics, count: 8, distribution: { '90-100': 2, '80-89': 3 },
    }).excellentRate).toBe(25)
    expect(normalizeStatistics(statistics).excellentRate).toBe(0)
  })
})

describe('prediction feature mapping', () => {
  const row = (componentScores: Record<string, number>, regularScore = 75) => ({
    id: 'grade-1', enrollmentId: 'enrollment-1', studentId: 'student-1', studentNo: '20260001',
    studentName: 'Student', componentScores, regularScore,
  })

  it('only emits rows with explicitly recognized usual and lab features', () => {
    expect(predictionInputs([
      row({ DAILY: 82, LAB: 91, FINAL: 76 }),
      { ...row({ FINAL: 88 }), studentId: 'student-total-only' },
      { ...row({ USUAL: 80 }), studentId: 'student-no-lab' },
      { ...row({ LAB: 90 }), studentId: 'student-no-usual' },
    ])).toEqual([{ studentId: 'student-1', usualScore: 82, labScore: 91 }])
  })

  it('does not synthesize model features from regular score or zero', () => {
    expect(predictionInputs([
      { ...row({ ATTENDANCE: 100, HOMEWORK: 90, LAB: 85 }, 95), studentId: 'student-2' },
      { ...row({}, 95), studentId: 'student-3' },
    ])).toEqual([])
  })
})

describe('grade draft selection', () => {
  const rows: GradeRecord[] = [
    { id: 'submitted', studentId: '1', studentNo: '1', studentName: '甲', status: 'SUBMITTED' as const,
      regularStatus: 'SUBMITTED' as const, makeupStatus: 'DRAFT' as const, regularScore: 55, makeupScore: 70,
      componentScores: {} },
    { id: 'draft', studentId: '2', studentNo: '2', studentName: '乙', status: 'DRAFT' as const,
      regularStatus: 'DRAFT' as const, makeupStatus: 'DRAFT' as const, regularScore: 58, makeupScore: 50,
      componentScores: {} },
    { id: 'retake-submitted', studentId: '3', studentNo: '3', studentName: '丙', status: 'SUBMITTED' as const,
      regularStatus: 'SUBMITTED' as const, makeupStatus: 'SUBMITTED' as const, regularScore: 50, makeupScore: 60,
      componentScores: {} },
  ]

  it('does not resend submitted regular grades in a mixed class', () => {
    expect(draftRecordsForExam(rows, 'REGULAR').map((row) => row.id)).toEqual(['draft'])
  })

  it('only selects failed submitted regular grades without a submitted retake', () => {
    expect(draftRecordsForExam(rows, 'MAKEUP').map((row) => row.id)).toEqual(['submitted'])
  })

  it('submits persisted draft ids without implicitly saving drafts', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(undefined)

    expect(persistedGradeIdsForSubmit(rows, 'REGULAR')).toEqual(['draft'])
    await teacherApi.submit('offering-1', rows, 'REGULAR')

    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith('/teacher/courses/offering-1/grades/submit', expect.objectContaining({
      gradeIds: ['draft'], examType: 'REGULAR',
    }))
  })

  it('requires a draft to be persisted before submission', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue(undefined)
    const unsaved: GradeRecord[] = [{ ...rows[1]!, id: '' }]

    await expect(teacherApi.submit('offering-1', unsaved, 'REGULAR')).rejects.toThrow('请先暂存')
    expect(post).not.toHaveBeenCalled()
  })

  it('serializes every backend scheme item under its exact code', () => {
    const scheme: GradeWeights = {
      id: 'scheme-8', name: '实验+项目+期末', totalWeight: 100, version: 1,
      items: [
        { id: 'lab', itemCode: 'LAB', itemName: '安全实验', weight: 40, maxScore: 100, sortOrder: 1 },
        { id: 'project', itemCode: 'PROJECT', itemName: '课程项目', weight: 30, maxScore: 100, sortOrder: 2 },
        { id: 'final', itemCode: 'FINAL', itemName: '期末考试', weight: 30, maxScore: 100, sortOrder: 3 },
      ],
    }
    const draft: GradeRecord = {
      id: 'grade-8', enrollmentId: 'enrollment-8', studentId: 'student-8', studentNo: '20260008',
      studentName: '测试学生', status: 'DRAFT', regularStatus: 'DRAFT', version: 4,
      componentScores: { LAB: 86, PROJECT: 93, FINAL: 78 },
    }

    expect(draftEntriesForExam([draft], 'REGULAR', scheme)).toEqual([{
      enrollmentId: 'enrollment-8',
      componentScores: { LAB: 86, PROJECT: 93, FINAL: 78 },
      makeupRawScore: null, examType: 'REGULAR', expectedVersion: 4,
    }])
  })

  it('serializes explicit nulls so clearing the last persisted component removes the old value', () => {
    const scheme: GradeWeights = {
      id: 'scheme-clear', name: '期末', totalWeight: 100, version: 1,
      items: [{ id: 'final', itemCode: 'FINAL', itemName: '期末', weight: 100, maxScore: 100, sortOrder: 1 }],
    }
    const cleared: GradeRecord = {
      id: 'grade-clear', enrollmentId: 'enrollment-clear', studentId: 'student-clear', studentNo: '20260009',
      studentName: '清空测试', status: 'DRAFT', regularStatus: 'DRAFT', version: 2,
      componentScores: { FINAL: null },
    }

    expect(draftEntriesForExam([cleared], 'REGULAR', scheme)).toEqual([{
      enrollmentId: 'enrollment-clear', componentScores: { FINAL: null }, makeupRawScore: null,
      examType: 'REGULAR', expectedVersion: 2,
    }])
  })

  it('serializes an explicit retake null so a persisted draft can be cleared', () => {
    const cleared: GradeRecord = {
      id: 'grade-retake-clear', enrollmentId: 'enrollment-retake-clear', studentId: 'student-retake-clear',
      studentNo: '20260010', studentName: '补考清空测试', status: 'SUBMITTED', regularStatus: 'SUBMITTED',
      makeupStatus: 'DRAFT', regularScore: 52, makeupScore: null, version: 5, componentScores: {},
    }

    const scheme: GradeWeights = { id: 'scheme-retake', name: '期末', totalWeight: 100, version: 1, items: [] }

    expect(draftEntriesForExam([cleared], 'MAKEUP', scheme)).toEqual([{
      enrollmentId: 'enrollment-retake-clear', componentScores: {}, makeupRawScore: null,
      examType: 'RETAKE', expectedVersion: 5,
    }])
  })

  it('does not send a null retake for an eligible student who never had a retake draft', () => {
    const untouched: GradeRecord = {
      id: 'grade-no-retake', enrollmentId: 'enrollment-no-retake', studentId: 'student-no-retake',
      studentNo: '20260011', studentName: '未录入补考', status: 'SUBMITTED', regularStatus: 'SUBMITTED',
      regularScore: 50, makeupScore: null, version: 2, componentScores: {},
    }
    const scheme: GradeWeights = { id: 'scheme-retake', name: '期末', totalWeight: 100, version: 1, items: [] }

    expect(draftEntriesForExam([untouched], 'MAKEUP', scheme)).toEqual([])
  })
})

describe('large grade batch handling', () => {
  const record = (index: number): GradeRecord => ({
    id: `grade-${index}`, enrollmentId: `enrollment-${index}`, studentId: `student-${index}`,
    studentNo: String(20260000 + index), studentName: `学生${index}`, status: 'DRAFT', regularStatus: 'DRAFT',
    makeupStatus: 'DRAFT', version: 0, componentScores: { FINAL: 80 },
  })

  it('submits at most 200 grades per request with independent idempotency keys', async () => {
    const rows = Array.from({ length: 401 }, (_, index) => record(index))
    const post = vi.spyOn(http, 'post').mockResolvedValue(undefined)

    await teacherApi.submit('offering-large-submit', rows, 'REGULAR')

    expect(post).toHaveBeenCalledTimes(3)
    expect(post.mock.calls.map((call) => (call[1] as { gradeIds: string[] }).gradeIds.length)).toEqual([200, 200, 1])
    const keys = post.mock.calls.map((call) => (call[1] as { idempotencyKey: string }).idempotencyKey)
    expect(new Set(keys).size).toBe(3)
  })

  it('marks completed submit batches so a retry sends only the failed remainder', async () => {
    const rows = Array.from({ length: 201 }, (_, index) => record(index))
    const post = vi.spyOn(http, 'post')
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce({ code: 'RMI_CONFLICT', message: '版本冲突' })

    await expect(teacherApi.submit('offering-partial-submit', rows, 'REGULAR'))
      .rejects.toThrow('版本冲突')
    expect(rows.slice(0, 200).every((item) => item.regularStatus === 'SUBMITTED')).toBe(true)
    expect(rows[200]?.regularStatus).toBe('DRAFT')

    post.mockClear()
    post.mockResolvedValue(undefined)
    await teacherApi.submit('offering-partial-submit', rows, 'REGULAR')
    expect((post.mock.calls[0]?.[1] as { gradeIds: string[] }).gradeIds).toEqual(['grade-200'])
  })

  it('reuses a submit idempotency key after an ambiguous RMI failure only', async () => {
    const rows = [record(901)]
    const post = vi.spyOn(http, 'post')
      .mockRejectedValueOnce({ code: 'RMI_UNAVAILABLE', status: 503, message: '远程响应丢失' })

    await expect(teacherApi.submit('offering-submit-retry', rows, 'REGULAR')).rejects.toThrow('远程响应丢失')
    const firstKey = (post.mock.calls[0]?.[1] as { idempotencyKey: string }).idempotencyKey

    post.mockResolvedValueOnce(undefined)
    await teacherApi.submit('offering-submit-retry', rows, 'REGULAR')
    const retryKey = (post.mock.calls[1]?.[1] as { idempotencyKey: string }).idempotencyKey
    expect(retryKey).toBe(firstKey)

    rows[0]!.status = 'DRAFT'
    rows[0]!.regularStatus = 'DRAFT'
    post.mockResolvedValueOnce(undefined)
    await teacherApi.submit('offering-submit-retry', rows, 'REGULAR')
    const nextActionKey = (post.mock.calls[2]?.[1] as { idempotencyKey: string }).idempotencyKey
    expect(nextActionKey).not.toBe(firstKey)
  })

  it('keeps draft, submit, and withdraw keys within the REST limit for a 36-character offering id', async () => {
    const offeringId = '12345678-1234-1234-1234-123456789012'
    const scheme = {
      id: 'scheme-key-limit', offeringId, name: '期末', totalWeight: 100, version: 1, status: 'ACTIVE',
      items: [{ id: 'weight-final', itemCode: 'FINAL', itemName: '期末', weight: 100, maxScore: 100, sortOrder: 1 }],
    }
    const rows = [record(902)]
    vi.spyOn(http, 'put').mockResolvedValue(scheme)
    await teacherApi.saveWeights(offeringId, scheme)
    vi.spyOn(http, 'get').mockResolvedValue({
      items: [{ ...rows[0], status: 'SUBMITTED' }], page: 0, size: 100, total: 1, totalPages: 1,
    })
    const post = vi.spyOn(http, 'post').mockImplementation(async (url, body) => {
      if (String(url).endsWith('/grades/draft')) {
        const entry = (body as { entries: Array<{ enrollmentId: string }> }).entries[0]!
        return [{ ...rows[0], enrollmentId: entry.enrollmentId, status: 'DRAFT', version: 1 }]
      }
      return undefined
    })

    await teacherApi.saveDraft(offeringId, rows, 'REGULAR')
    await teacherApi.submit(offeringId, rows, 'REGULAR')
    await teacherApi.withdraw(offeringId, 'REGULAR')

    const keys = post.mock.calls.map((call) => (call[1] as { idempotencyKey: string }).idempotencyKey)
    expect(keys).toHaveLength(3)
    keys.forEach((key) => expect(key).toMatch(/^[0-9a-f-]{36}$/i))
    expect(keys.every((key) => key.length <= 80)).toBe(true)
  })

  it('keeps only the failed draft rows pending after a partial save', async () => {
    const offeringId = 'offering-partial-draft'
    const scheme = {
      id: 'scheme-partial', offeringId, name: '期末', totalWeight: 100, version: 1, status: 'ACTIVE',
      items: [{ id: 'weight-final', itemCode: 'FINAL', itemName: '期末', weight: 100, maxScore: 100, sortOrder: 1 }],
    }
    vi.spyOn(http, 'put').mockResolvedValue(scheme)
    await teacherApi.saveWeights(offeringId, scheme)

    const rows = Array.from({ length: 201 }, (_, index) => record(index))
    const pending = new Set(rows.map((item) => item.enrollmentId!))
    const post = vi.spyOn(http, 'post')
      .mockImplementationOnce(async (_url, body) => {
        const entries = (body as { entries: Array<{ enrollmentId: string }> }).entries
        return entries.map((entry, index) => ({
          ...record(index), id: `saved-${entry.enrollmentId}`, enrollmentId: entry.enrollmentId, version: 1,
        }))
      })
      .mockRejectedValueOnce({ code: 'RMI_CONFLICT', message: '后续批次失败' })

    await expect(teacherApi.saveDraft(offeringId, rows, 'REGULAR', (saved) =>
      saved.forEach((item) => pending.delete(item.enrollmentId!)))).rejects.toThrow('后续批次失败')
    expect(pending).toEqual(new Set(['enrollment-200']))
    expect(rows[0]?.id).toBe('saved-enrollment-0')

    post.mockClear()
    post.mockImplementation(async (_url, body) => {
      const entries = (body as { entries: Array<{ enrollmentId: string }> }).entries
      return entries.map((entry) => ({ ...record(200), id: 'saved-final', enrollmentId: entry.enrollmentId, version: 1 }))
    })
    await teacherApi.saveDraft(offeringId, rows.filter((item) => pending.has(item.enrollmentId!)), 'REGULAR',
      (saved) => saved.forEach((item) => pending.delete(item.enrollmentId!)))
    expect((post.mock.calls[0]?.[1] as { entries: unknown[] }).entries).toHaveLength(1)
    expect(pending.size).toBe(0)
  })

  it('withdraws at most 200 submitted grades per request', async () => {
    const rawRows = Array.from({ length: 201 }, (_, index) => ({
      id: `grade-${index}`, enrollmentId: `enrollment-${index}`, studentId: `student-${index}`,
      studentNo: String(index), studentName: `学生${index}`, status: 'SUBMITTED', componentScores: {},
    }))
    vi.spyOn(http, 'get').mockResolvedValue({ items: rawRows, page: 0, size: 100, total: 201, totalPages: 1 })
    const post = vi.spyOn(http, 'post').mockResolvedValue(undefined)

    await teacherApi.withdraw('offering-large-withdraw', 'REGULAR')

    expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls.map((call) => (call[1] as { gradeIds: string[] }).gradeIds.length)).toEqual([200, 1])
  })

  it('replays an ambiguously failed withdrawal with the same request key before refreshing', async () => {
    const rawRow = {
      id: 'grade-withdraw-retry', enrollmentId: 'enrollment-withdraw-retry', studentId: 'student-1',
      studentNo: '1', studentName: '学生', status: 'SUBMITTED', componentScores: {},
    }
    vi.spyOn(http, 'get')
      .mockResolvedValueOnce({ items: [rawRow], page: 0, size: 100, total: 1, totalPages: 1 })
      .mockResolvedValueOnce({ items: [], page: 0, size: 100, total: 0, totalPages: 0 })
    const post = vi.spyOn(http, 'post')
      .mockRejectedValueOnce({ code: 'TIMEOUT', message: '响应超时' })
      .mockResolvedValueOnce(undefined)

    await expect(teacherApi.withdraw('offering-withdraw-retry', 'REGULAR')).rejects.toThrow('响应超时')
    await expect(teacherApi.withdraw('offering-withdraw-retry', 'REGULAR')).resolves.toBeUndefined()

    const keys = post.mock.calls.map((call) => (call[1] as { idempotencyKey: string }).idempotencyKey)
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
  })
})

describe('admin idempotency and review adapters', () => {
  it('reuses a small-reversion key after a timeout and releases it after success', async () => {
    const post = vi.spyOn(http, 'post')
      .mockRejectedValueOnce({ code: 'TIMEOUT', message: '响应超时' })
      .mockResolvedValue(undefined)

    await expect(adminApi.smallRevert(['grade-retry'], '修正录入错误')).rejects.toMatchObject({ code: 'TIMEOUT' })
    await adminApi.smallRevert(['grade-retry'], '修正录入错误')
    await adminApi.smallRevert(['grade-retry'], '修正录入错误')

    const keys = post.mock.calls.map((call) => (call[1] as { idempotencyKey: string }).idempotencyKey)
    expect(keys[1]).toBe(keys[0])
    expect(keys[2]).not.toBe(keys[1])
  })

  it('maps reviewer metadata returned by the reversion contract', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({
      items: [{ id: 'request-1', scope: 'LARGE_BATCH', targetFilter: 'gradeIds:g-1', reason: '批量修正',
        status: 'REJECTED', requestedBy: 'admin01', requestedAt: '2026-08-31T09:00:00Z',
        reviewer: 'admin02', reviewComment: '目标范围有误', reviewedAt: '2026-08-31T10:00:00Z' }],
      page: 0, size: 20, total: 1, totalPages: 1,
    })

    const result = await adminApi.reversionRequests({ page: 1, size: 20 })

    expect(result.items[0]).toMatchObject({
      reviewer: 'admin02', comment: '目标范围有误', reviewedAt: '2026-08-31T10:00:00Z',
    })
  })

  it('reuses a high-risk request key after a network failure', async () => {
    const response = { id: 'request-1', scope: 'LARGE_BATCH', targetFilter: 'gradeIds:g-1',
      reason: '批量修正错误成绩', status: 'PENDING', requestedBy: 'admin01', requestedAt: '2026-08-31T09:00:00Z' }
    const post = vi.spyOn(http, 'post')
      .mockRejectedValueOnce({ code: 'NETWORK_ERROR', message: '连接中断' })
      .mockResolvedValueOnce(response)

    const payload = { scope: 'LARGE_BATCH' as const, targetFilter: 'gradeIds:g-1', reason: '批量修正错误成绩' }
    await expect(adminApi.createReversionRequest(payload)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    await adminApi.createReversionRequest(payload)

    const keys = post.mock.calls.map((call) => (call[1] as { idempotencyKey: string }).idempotencyKey)
    expect(keys[1]).toBe(keys[0])
  })

  it('reconciles an approval after an ambiguous response instead of leaving it pending', async () => {
    vi.spyOn(http, 'post').mockRejectedValueOnce({ code: 'TIMEOUT', message: '响应超时' })
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      id: 'request-timeout', scope: 'LARGE_BATCH', targetFilter: 'gradeIds:g-1', reason: '批量修正错误成绩',
      status: 'EXECUTED', requestedBy: 'admin01', requestedAt: '2026-08-31T09:00:00Z',
      reviewer: 'admin02', reviewComment: '确认目标无误', reviewedAt: '2026-08-31T10:00:00Z',
    })

    const result = await adminApi.approveReversionRequest('request-timeout', '确认目标无误')

    expect(get).toHaveBeenCalledWith('/admin/reversion-requests/request-timeout')
    expect(result).toMatchObject({ id: 'request-timeout', status: 'EXECUTED', reviewer: 'admin02' })
  })

  it('keeps an ambiguous review failure when reconciliation still reports pending', async () => {
    const timeout = { code: 'RMI_UNAVAILABLE', status: 503, message: '远程响应丢失' }
    vi.spyOn(http, 'post').mockRejectedValueOnce(timeout)
    vi.spyOn(http, 'get').mockResolvedValue({
      id: 'request-pending', scope: 'LARGE_BATCH', targetFilter: 'gradeIds:g-2', reason: '批量修正错误成绩',
      status: 'PENDING', requestedBy: 'admin01', requestedAt: '2026-08-31T09:00:00Z',
    })

    await expect(adminApi.rejectReversionRequest('request-pending', '目标范围不正确')).rejects.toBe(timeout)
  })
})

describe('nullable scheme and prediction adapters', () => {
  it('does not request the grade sheet until a first grading scheme exists', async () => {
    const get = vi.spyOn(http, 'get').mockImplementation(async (url) => {
      if (url === '/teacher/courses') return {
        items: [{ offeringId: 'offering-new', courseId: 'course-new', courseCode: 'NEW101', courseName: '新课程',
          credit: 2, academicYear: '2026-2027', semester: 1, status: 'OPEN', enrolledStudents: 0 }],
        page: 0, size: 100, total: 1, totalPages: 1,
      }
      if (url === '/teacher/courses/offering-new/weights') return null
      throw new Error(`unexpected request ${url}`)
    })

    const result = await teacherApi.gradeSheet('offering-new')

    expect(result.records).toEqual([])
    expect(result.weights.id).toBeUndefined()
    expect(get.mock.calls.some(([url]) => String(url).includes('/grade-sheet'))).toBe(false)
  })

  it('splits more than 200 prediction inputs and merges all response rows', async () => {
    const rows = Array.from({ length: 201 }, (_, index) => ({
      id: `grade-${index}`, enrollmentId: `enrollment-${index}`, studentId: `student-${index}`,
      studentNo: String(index), studentName: `学生${index}`, componentScores: { DAILY: 80, LAB: 85 },
    }))
    vi.spyOn(http, 'get').mockResolvedValue({ items: rows, page: 0, size: 500, total: 201, totalPages: 1 })
    const post = vi.spyOn(http, 'post').mockImplementation(async (_url, body) => ({
      predictions: (body as { students: Array<{ studentId: string }> }).students.map((student) => ({
        studentId: student.studentId, predictedFinalExam: 75, intervalLow: 65, intervalHigh: 85,
        failureProbability: 20, riskLevel: 'LOW', decisionPath: [],
      })),
      generatedAt: '2026-08-31T00:00:00Z', persisted: false,
    }))

    const result = await teacherApi.predictions('offering-large-prediction')

    expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls.map((call) => (call[1] as { students: unknown[] }).students.length)).toEqual([200, 1])
    expect(result.predictions).toHaveLength(201)
  })
})

describe('student course adapter', () => {
  it('maps the enrolled course catalog used by the risk selector', async () => {
    vi.spyOn(http, 'get').mockResolvedValue([{ id: 'course-1', code: 'CS101', name: '程序设计' }])

    await expect(studentApi.courses()).resolves.toEqual([{ id: 'course-1', code: 'CS101', name: '程序设计' }])
  })
})
