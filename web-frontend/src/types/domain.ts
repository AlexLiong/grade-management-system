export type Role = 'TEACHER' | 'STUDENT' | 'ADMIN'
export type GradeStatus = 'DRAFT' | 'SUBMITTED' | 'REVERTED'
export type CourseStatus = 'OPEN' | 'CLOSED'
export type ExamType = 'REGULAR' | 'MAKEUP'

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  role: Role
  roles?: Role[]
  permissions: string[]
  organization?: string
}

export interface Course {
  id: string
  courseId?: string
  code: string
  name: string
  academicYear: string
  semester: string
  className?: string
  studentCount: number
  credit?: number
  status: CourseStatus
  submittedAt?: string
}

export interface HistoryCourse {
  offeringId: string
  courseId: string
  courseCode: string
  courseName: string
  academicYear: string
  semester: number
  className?: string
}

export interface GradeWeightItem {
  id?: string
  itemCode: string
  itemName: string
  weight: number
  maxScore: number
  sortOrder: number
}

export interface GradeWeights {
  id?: string
  offeringId?: string
  name: string
  totalWeight: number
  version: number
  status?: string
  items: GradeWeightItem[]
}

export interface GradeRecord {
  id: string
  enrollmentId?: string
  studentId: string
  studentNo: string
  studentName: string
  regularScore?: number | null
  makeupScore?: number | null
  totalScore?: number | null
  displayScore?: string
  status: GradeStatus
  regularStatus?: GradeStatus
  makeupStatus?: GradeStatus
  updatedAt?: string
  version?: number
  schemeId?: string
  componentScores: Record<string, number | null>
}

export interface GradeStatistics {
  count: number
  average: number
  highest: number
  lowest: number
  passRate: number
  excellentRate: number
  distribution: Array<{ label: string; count: number }>
  analysis?: string
  median?: number
  standardDeviation?: number
}

export interface GradeHistoryEntry {
  id: string
  gradeId: string
  action: string
  reason?: string
  scope?: string
  batchId?: string
  actorId?: string
  createdAt: string
  academicYear?: string
  semester?: string
  studentNo?: string
  studentName?: string
  regularScore?: number | null
  makeupScore?: number | null
  finalScore?: number | null
}

export interface Prediction {
  studentId: string
  studentNo: string
  studentName: string
  predictedFinal: number
  lowerBound: number
  upperBound: number
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  factors?: string[]
}

export interface DecisionNode {
  condition: string
  result?: string
  yes?: DecisionNode
  no?: DecisionNode
}

export interface StudentGrade {
  id: string
  offeringId?: string
  courseId?: string
  courseCode: string
  courseName: string
  academicYear: string
  semester: string
  credits?: number
  regularScore?: number | null
  makeupScore?: number | null
  finalScore?: number | null
  displayScore?: string
  passed: boolean
  rank?: number
  totalStudents?: number
}

export interface StudentCourse {
  id: string
  code: string
  name: string
}

export interface WarningItem {
  id: string
  courseName: string
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  message: string
  predictedScore?: number
  createdAt?: string
}

export interface RiskAssessment {
  studentId: string
  courseId: string
  sampleYears: number
  predictedScore: number
  failureProbability: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  linearRegressionExplanation: string[]
  decisionPath: string[]
  generatedAt: string
}

export interface Organization {
  id: string
  code?: string
  name: string
  parentId?: string | null
  type?: string
}

export interface ManagedUser {
  id: string
  username: string
  displayName: string
  role: Role
  roles: Role[]
  organizationId?: string
  organizationName?: string
  studentId?: string
  studentNo?: string
  teacherId?: string
  teacherNo?: string
  className?: string
  major?: string
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  enabled: boolean
  permissions: string[]
  permissionOverrides: Record<string, boolean>
  updatedAt?: string
  email?: string
}

export interface AdminGradeRecord {
  id: string
  enrollmentId: string
  status: 'DRAFT' | 'SUBMITTED'
  version: number
  studentId: string
  studentNo: string
  studentName: string
  offeringId: string
  courseId: string
  courseCode: string
  courseName: string
  academicYear: string
  semester: number
  className?: string
  submittedAt?: string
  updatedAt?: string
}

export interface PermissionOption {
  code: string
  name: string
}

export interface RolePermissions {
  roleId: string
  roleCode: Role
  permissions: string[]
}

export interface AuditLog {
  id: string
  sequence?: number
  actor: string
  actorRole?: string
  action: string
  resource: string
  detail?: string
  ipAddress?: string
  createdAt: string
  previousHash?: string
  hash?: string
  chainValid?: boolean
  success?: boolean
}

export interface SecurityAlert {
  id: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  type: string
  message: string
  actor?: string
  status: 'OPEN' | 'RESOLVED'
  createdAt: string
  relatedTable?: string
  relatedId?: string
}

export interface RecoveryEvidence {
  sequence: number
  eventType: string
  aggregateType: string
  aggregateId: string
  previousHash: string
  entryHash: string
  createdAt: string
  actor: string
}

export interface ReversionRequest {
  id: string
  scope: 'LARGE_BATCH' | 'ORIGINAL_RESTORE'
  targetFilter: string
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'FAILED'
  initiator: string
  reviewer?: string
  comment?: string
  createdAt: string
  reviewedAt?: string
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface ApiEnvelope<T> {
  success: boolean
  data: T
  message?: string
  error?: {
    code: string
    message: string
    fieldErrors?: Record<string, string>
    traceId?: string
  }
}
