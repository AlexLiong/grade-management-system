<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AlertOctagon, Check, FileClock, Fingerprint, GitCompareArrows, RefreshCw, RotateCcw,
  ListChecks, Search, ShieldCheck, ShieldX, Undo2, UserCheck, X,
} from 'lucide-vue-next'
import PageState from '@/components/PageState.vue'
import { adminApi } from '@/api/services'
import { toAppError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AdminGradeRecord, AuditLog, PageResult, RecoveryEvidence, ReversionRequest, SecurityAlert } from '@/types/domain'

const auth = useAuthStore()
const canSeeAlerts = computed(() => auth.hasPermission('ALERT_MANAGE'))
const canSeeReviews = computed(() => auth.hasAnyPermission(['GRADE_REVERT_REQUEST', 'GRADE_REVERT_APPROVE']))
const canSeeAudit = computed(() => auth.hasPermission('AUDIT_READ'))
const canSeeIntegrity = computed(() => auth.hasAnyPermission(['INTEGRITY_VERIFY', 'GRADE_RESTORE_ORIGINAL']))
const canSeeOperations = computed(() => auth.hasAnyPermission([
  'GRADE_READ', 'GRADE_REVERT_SMALL', 'GRADE_REVERT_REQUEST', 'GRADE_RESTORE_ORIGINAL',
]))
const activeTab = ref(
  canSeeAlerts.value ? 'alerts'
    : canSeeReviews.value ? 'reviews'
      : canSeeAudit.value ? 'audit'
        : canSeeIntegrity.value ? 'integrity' : 'operations',
)
const alertLoading = ref(false)
const alertError = ref('')
const alerts = ref<SecurityAlert[]>([])
const alertQuery = reactive({ status: '', page: 1, size: 15, total: 0 })
const reviewLoading = ref(false)
const reviewError = ref('')
const requests = ref<ReversionRequest[]>([])
const reviewQuery = reactive({ status: 'PENDING', page: 1, size: 15, total: 0 })
const auditLoading = ref(false)
const auditError = ref('')
const audits = ref<AuditLog[]>([])
const auditQuery = reactive({ keyword: '', page: 1, size: 15, total: 0 })
const verifying = ref(false)
const verification = ref<{ valid: boolean; checkedBlocks: number; brokenAt?: number } | null>(null)
const operationLoading = ref(false)
const smallForm = reactive({ gradeIds: '', reason: '' })
const requestForm = reactive<{ scope: ReversionRequest['scope']; targetFilter: string; reason: string }>({
  scope: 'LARGE_BATCH', targetFilter: '', reason: '',
})
const reviewDialog = ref(false)
const reviewTarget = ref<ReversionRequest | null>(null)
const reviewAction = ref<'approve' | 'reject'>('approve')
const reviewComment = ref('')
const reviewSubmitting = ref(false)
const retryingApproved = computed(() => reviewTarget.value?.status === 'APPROVED')
function canApproveRequest(item: ReversionRequest) {
  return auth.hasPermission('GRADE_REVERT_APPROVE')
    && (item.scope === 'ORIGINAL_RESTORE'
      ? auth.hasPermission('GRADE_RESTORE_ORIGINAL') : auth.hasPermission('GRADE_READ'))
}
const gradeLoading = ref(false)
const gradeError = ref('')
const grades = ref<AdminGradeRecord[]>([])
const selectedGrades = ref<AdminGradeRecord[]>([])
const gradeTable = ref<{ clearSelection: () => void } | null>(null)
const gradeQuery = reactive({ course: '', student: '', status: 'SUBMITTED', page: 1, size: 20, total: 0 })
const recoveryGradeId = ref('')
const recoveryReason = ref('管理员核对数据库外安全副本')
const recoveryLoading = ref(false)
const recoveryItems = ref<RecoveryEvidence[]>([])
const recoveryPreview = ref<Record<string, string> | null>(null)
const recoveryPreviewSequence = ref<number | null>(null)
const recoveryPreviewOpen = ref(false)

const openAlertCount = computed(() => alerts.value.filter((item) => item.status === 'OPEN').length)
function alertType(severity: SecurityAlert['severity']) {
  return severity === 'CRITICAL' || severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'info'
}
function severityLabel(severity: SecurityAlert['severity']) {
  return { CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低' }[severity]
}
function alertStatusLabel(status: SecurityAlert['status']) {
  return { OPEN: '待处理', RESOLVED: '已关闭' }[status]
}
function requestScopeLabel(scope: ReversionRequest['scope']) {
  return scope === 'LARGE_BATCH' ? '大撤销' : '原始恢复'
}
function isSelfRequest(item: ReversionRequest) {
  return item.initiator === auth.user?.id || item.initiator === auth.user?.username || item.initiator === auth.user?.displayName
}
function shortenHash(hash?: string) {
  if (!hash) return '--'
  return hash.length > 18 ? `${hash.slice(0, 9)}…${hash.slice(-7)}` : hash
}

function gradeStatusLabel(status: AdminGradeRecord['status']) {
  return { DRAFT: '暂存', SUBMITTED: '已提交' }[status]
}

async function loadGrades() {
  if (!auth.hasPermission('GRADE_READ')) return
  gradeLoading.value = true
  gradeError.value = ''
  try {
    const result = await adminApi.grades({
      course: gradeQuery.course.trim() || undefined,
      student: gradeQuery.student.trim() || undefined,
      status: gradeQuery.status || undefined,
      page: gradeQuery.page,
      size: gradeQuery.size,
    })
    grades.value = result.items || []
    gradeQuery.total = result.total || 0
  } catch (cause) { gradeError.value = toAppError(cause).message }
  finally { gradeLoading.value = false }
}

function searchGrades() {
  clearGradeSelection()
  gradeQuery.page = 1
  loadGrades()
}

function clearGradeSelection() {
  selectedGrades.value = []
  gradeTable.value?.clearSelection()
}

function handleGradeSelection(rows: AdminGradeRecord[]) {
  const currentPageIds = new Set(grades.value.map((item) => item.id))
  const selected = new Map(selectedGrades.value
    .filter((item) => !currentPageIds.has(item.id)).map((item) => [item.id, item]))
  rows.forEach((item) => selected.set(item.id, item))
  selectedGrades.value = [...selected.values()]
}

function useSelectionForSmallReversion() {
  if (!selectedGrades.value.length || selectedGrades.value.length > 10) {
    ElMessage.warning('请选择 1 至 10 条成绩')
    return
  }
  if (selectedGrades.value.some((item) => item.status !== 'SUBMITTED')) {
    ElMessage.warning('小撤销只能选择已提交成绩')
    return
  }
  smallForm.gradeIds = selectedGrades.value.map((item) => item.id).join('\n')
}

function useSelectionForLargeReversion() {
  if (!selectedGrades.value.length || selectedGrades.value.length > 500) {
    ElMessage.warning('请选择 1 至 500 条成绩')
    return
  }
  requestForm.scope = 'LARGE_BATCH'
  requestForm.targetFilter = selectedGrades.value.map((item) => item.id).join('\n')
}

async function useSelectionForRecovery() {
  if (selectedGrades.value.length !== 1) {
    ElMessage.warning('请选择一条成绩查询账本证据')
    return
  }
  recoveryGradeId.value = selectedGrades.value[0]!.id
  activeTab.value = 'integrity'
  await loadRecoveryEvidence()
}

async function loadAlerts() {
  alertLoading.value = true
  alertError.value = ''
  try {
    const result: PageResult<SecurityAlert> = await adminApi.alerts({
      status: alertQuery.status || undefined, page: alertQuery.page, size: alertQuery.size,
    })
    alerts.value = result.items || []
    alertQuery.total = result.total || 0
  } catch (cause) { alertError.value = toAppError(cause).message }
  finally { alertLoading.value = false }
}

async function updateAlert(item: SecurityAlert) {
  try {
    await adminApi.updateAlert(item.id)
    ElMessage.success('告警已关闭')
    await loadAlerts()
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
}

async function loadRequests() {
  reviewLoading.value = true
  reviewError.value = ''
  try {
    const result = await adminApi.reversionRequests({
      status: reviewQuery.status || undefined, page: reviewQuery.page, size: reviewQuery.size,
    })
    requests.value = result.items || []
    reviewQuery.total = result.total || 0
  } catch (cause) { reviewError.value = toAppError(cause).message }
  finally { reviewLoading.value = false }
}

function openReview(item: ReversionRequest, action: 'approve' | 'reject') {
  if (isSelfRequest(item)) { ElMessage.warning('发起人不能审批自己的请求'); return }
  reviewTarget.value = item
  reviewAction.value = action
  reviewComment.value = ''
  reviewDialog.value = true
}

async function submitReview() {
  if (!reviewTarget.value) return
  if (reviewComment.value.trim().length < 3) { ElMessage.warning('请填写复核意见'); return }
  if (reviewAction.value === 'approve' && !canApproveRequest(reviewTarget.value)) {
    ElMessage.warning('当前账号没有执行该恢复操作所需的全部权限')
    return
  }
  reviewSubmitting.value = true
  try {
    let reviewed: ReversionRequest
    if (reviewAction.value === 'approve') {
      reviewed = await adminApi.approveReversionRequest(reviewTarget.value.id, reviewComment.value.trim())
    } else {
      reviewed = await adminApi.rejectReversionRequest(reviewTarget.value.id, reviewComment.value.trim())
    }
    ElMessage.success(reviewAction.value === 'approve'
      ? (reviewed.status === 'EXECUTED' ? '请求已批准，目标操作已执行' : '请求已批准，目标操作等待重试执行')
      : '请求已驳回')
    reviewDialog.value = false
    await loadRequests()
    await loadAudit()
  } catch (cause) {
    const failure = toAppError(cause)
    await loadRequests()
    ElMessage.error(['NETWORK_ERROR', 'TIMEOUT', 'RMI_UNAVAILABLE', 'RMI_TIMEOUT', 'REMOTE_TIMEOUT'].includes(failure.code)
      || failure.status === 503
      ? `${failure.message}；系统已重新查询当前复核状态，请核对列表后再操作`
      : failure.message)
  }
  finally { reviewSubmitting.value = false }
}

async function loadAudit() {
  auditLoading.value = true
  auditError.value = ''
  try {
    const result = await adminApi.auditLogs({
      keyword: auditQuery.keyword.trim() || undefined, page: auditQuery.page, size: auditQuery.size,
    })
    audits.value = result.items || []
    auditQuery.total = result.total || 0
  } catch (cause) { auditError.value = toAppError(cause).message }
  finally { auditLoading.value = false }
}

async function verifyChain() {
  verifying.value = true
  verification.value = null
  try {
    verification.value = await adminApi.verifyAuditChain()
    ElMessage({ type: verification.value.valid ? 'success' : 'error', message: verification.value.valid ? '成绩证据账本链验证通过' : '成绩证据账本链存在断点，请立即处置' })
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { verifying.value = false }
}

async function loadRecoveryEvidence() {
  if (!recoveryGradeId.value.trim()) { ElMessage.warning('请输入成绩记录编号'); return }
  recoveryLoading.value = true
  recoveryItems.value = []
  try {
    recoveryItems.value = await adminApi.recoveryEvidence(recoveryGradeId.value.trim())
    if (!recoveryItems.value.length) ElMessage.info('安全账本中没有该成绩的历史快照')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { recoveryLoading.value = false }
}

async function previewRecovery(item: RecoveryEvidence) {
  if (recoveryReason.value.trim().length < 5) { ElMessage.warning('请填写至少 5 个字的核对原因'); return }
  recoveryLoading.value = true
  try {
    const result = await adminApi.previewRecovery(item.sequence, recoveryReason.value.trim())
    recoveryPreview.value = result.originalValues
    recoveryPreviewSequence.value = item.sequence
    recoveryPreviewOpen.value = true
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { recoveryLoading.value = false }
}

function parseGradeIds() {
  return [...new Set(smallForm.gradeIds.split(/[\s,，;；]+/).map((item) => item.trim()).filter(Boolean))]
}

async function smallRevert() {
  const ids = parseGradeIds()
  if (!ids.length || ids.length > 10) { ElMessage.warning('小撤销必须填写 1 至 10 个成绩记录编号'); return }
  if (smallForm.reason.trim().length < 5) { ElMessage.warning('撤销原因至少 5 个字'); return }
  try {
    await ElMessageBox.confirm(`将 ${ids.length} 条成绩恢复为暂存状态，确定继续吗？`, '执行小撤销', {
      confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  operationLoading.value = true
  try {
    await adminApi.smallRevert(ids, smallForm.reason.trim())
    Object.assign(smallForm, { gradeIds: '', reason: '' })
    clearGradeSelection()
    ElMessage.success('小撤销已执行并记录审计日志')
    await Promise.all([loadAudit(), loadGrades()])
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { operationLoading.value = false }
}

async function createHighRiskRequest() {
  if (!requestForm.targetFilter.trim() || requestForm.reason.trim().length < 5) {
    ElMessage.warning('请填写目标范围和至少 5 个字的申请原因')
    return
  }
  const rawTarget = requestForm.targetFilter.trim()
  let canonicalTarget = rawTarget
  if (requestForm.scope === 'LARGE_BATCH') {
    const ids = [...new Set(rawTarget.replace(/^gradeIds:/, '').split(/[\s,，;；]+/).map((item) => item.trim()).filter(Boolean))]
    if (!ids.length || ids.length > 500) { ElMessage.warning('大撤销必须填写 1 至 500 个成绩记录编号'); return }
    canonicalTarget = `gradeIds:${ids.join(',')}`
  } else {
    const sequence = rawTarget.replace(/^(RECOVERY:|ledgerSequence:|sequence:)/, '')
    if (!/^\d+$/.test(sequence) || Number(sequence) <= 0) { ElMessage.warning('原始恢复必须填写有效的账本快照序号'); return }
    canonicalTarget = `RECOVERY:${sequence}`
  }
  try {
    await ElMessageBox.confirm(
      `${requestScopeLabel(requestForm.scope)}必须由另一名管理员复核，批准后将自动执行。确定发起吗？`,
      '发起高风险操作',
      { confirmButtonText: '发起复核', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  operationLoading.value = true
  try {
    await adminApi.createReversionRequest({
      scope: requestForm.scope, targetFilter: canonicalTarget, reason: requestForm.reason.trim(),
    })
    Object.assign(requestForm, { targetFilter: '', reason: '' })
    ElMessage.success('复核请求已提交，等待另一名管理员处理')
    activeTab.value = 'reviews'
    await loadRequests()
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { operationLoading.value = false }
}

function onTabChange(name: string | number) {
  if (name === 'alerts') loadAlerts()
  if (name === 'reviews') loadRequests()
  if (name === 'audit') loadAudit()
  if (name === 'operations') loadGrades()
}

onMounted(() => { onTabChange(activeTab.value) })
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">管理员工作台</span><h1>安全审计与成绩治理</h1><p>复核异常行为，验证成绩证据账本链，并通过双人审批执行高风险恢复操作。</p></div>
      <div class="page-actions"><el-tag v-if="openAlertCount" type="danger" effect="plain">{{ openAlertCount }} 条待处理</el-tag><el-button :icon="RefreshCw" @click="onTabChange(activeTab)">刷新</el-button></div>
    </header>

    <section class="surface security-tabs">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane v-if="canSeeAlerts" name="alerts"><template #label><span class="tab-label"><AlertOctagon :size="16" />异常告警</span></template>
          <div class="security-pane">
            <div class="pane-toolbar"><el-select v-model="alertQuery.status" clearable placeholder="全部状态" @change="alertQuery.page = 1; loadAlerts()"><el-option label="待处理" value="OPEN" /><el-option label="已关闭" value="RESOLVED" /></el-select><span>统计异常、越权尝试与非正常操作模式</span></div>
            <PageState :loading="alertLoading" :error="alertError" :empty="!alerts.length" empty-title="暂无安全告警" @retry="loadAlerts">
              <el-table :data="alerts" stripe>
                <el-table-column label="级别" width="85"><template #default="scope"><el-tag :type="alertType(scope.row.severity)" effect="dark">{{ severityLabel(scope.row.severity) }}</el-tag></template></el-table-column>
                <el-table-column prop="type" label="检测类型" min-width="145" /><el-table-column prop="message" label="告警内容" min-width="300" />
                <el-table-column prop="createdAt" label="发现时间" min-width="170" />
                <el-table-column label="状态" width="95"><template #default="scope"><el-tag :type="scope.row.status === 'OPEN' ? 'danger' : 'success'" effect="plain">{{ alertStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="120" fixed="right"><template #default="scope"><el-button v-if="scope.row.status === 'OPEN' && auth.hasPermission('ALERT_MANAGE')" link type="primary" :icon="Check" @click="updateAlert(scope.row)">关闭告警</el-button><span v-else class="muted-text">{{ scope.row.status === 'RESOLVED' ? '已处置' : '只读' }}</span></template></el-table-column>
              </el-table>
              <div v-if="alertQuery.total > alertQuery.size" class="pagination-row"><el-pagination v-model:current-page="alertQuery.page" :page-size="alertQuery.size" :total="alertQuery.total" layout="total, prev, pager, next" @current-change="loadAlerts" /></div>
            </PageState>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canSeeReviews" name="reviews"><template #label><span class="tab-label"><UserCheck :size="16" />复核中心</span></template>
          <div class="security-pane">
            <div class="pane-toolbar"><el-select v-model="reviewQuery.status" placeholder="请求状态" @change="reviewQuery.page = 1; loadRequests()"><el-option label="待复核" value="PENDING" /><el-option label="已批准" value="APPROVED" /><el-option label="已驳回" value="REJECTED" /><el-option label="已执行" value="EXECUTED" /></el-select><span>发起人与审批人必须为不同管理员</span></div>
            <PageState :loading="reviewLoading" :error="reviewError" :empty="!requests.length" empty-title="暂无待复核请求" @retry="loadRequests">
              <el-table :data="requests" stripe>
                <el-table-column label="操作" width="105"><template #default="scope"><el-tag :type="scope.row.scope === 'LARGE_BATCH' ? 'danger' : 'warning'" effect="plain">{{ requestScopeLabel(scope.row.scope) }}</el-tag></template></el-table-column>
                <el-table-column prop="targetFilter" label="目标范围" min-width="230" show-overflow-tooltip /><el-table-column prop="reason" label="申请原因" min-width="240" show-overflow-tooltip />
                <el-table-column prop="initiator" label="发起人" min-width="110" /><el-table-column prop="createdAt" label="发起时间" min-width="170" />
                <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.status === 'PENDING' ? 'warning' : scope.row.status === 'REJECTED' ? 'danger' : 'success'" effect="plain">{{ scope.row.status }}</el-tag></template></el-table-column>
                <el-table-column label="复核" width="180" fixed="right">
                  <template #default="scope">
                    <template v-if="scope.row.status === 'PENDING' && auth.hasPermission('GRADE_REVERT_APPROVE')">
                      <el-tooltip v-if="isSelfRequest(scope.row)" content="不能审批自己发起的请求"><span><el-button link disabled :icon="ShieldX">本人发起</el-button></span></el-tooltip>
                      <template v-else><el-button v-if="canApproveRequest(scope.row)" link type="primary" :icon="Check" @click="openReview(scope.row, 'approve')">批准</el-button><el-button link type="danger" :icon="X" @click="openReview(scope.row, 'reject')">驳回</el-button></template>
                    </template>
                    <el-button v-else-if="scope.row.status === 'APPROVED' && canApproveRequest(scope.row) && !isSelfRequest(scope.row)" link type="warning" :icon="RefreshCw" @click="openReview(scope.row, 'approve')">重试执行</el-button>
                    <span v-else-if="scope.row.status === 'PENDING'" class="muted-text">无审批权限</span>
                    <el-tooltip v-else-if="scope.row.comment" :content="`${scope.row.comment}${scope.row.reviewedAt ? ` · ${scope.row.reviewedAt}` : ''}`"><span class="muted-text">{{ scope.row.reviewer || '--' }}</span></el-tooltip>
                    <span v-else class="muted-text">{{ scope.row.reviewer || '--' }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="reviewQuery.total > reviewQuery.size" class="pagination-row"><el-pagination v-model:current-page="reviewQuery.page" :page-size="reviewQuery.size" :total="reviewQuery.total" layout="total, prev, pager, next" @current-change="loadRequests" /></div>
            </PageState>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canSeeAudit" name="audit"><template #label><span class="tab-label"><FileClock :size="16" />审计日志</span></template>
          <div class="security-pane">
            <div class="pane-toolbar audit-toolbar"><el-input v-model="auditQuery.keyword" clearable placeholder="操作人账号（精确匹配）" :prefix-icon="Search" @keyup.enter="auditQuery.page = 1; loadAudit()" /><el-button type="primary" :icon="Search" @click="auditQuery.page = 1; loadAudit()">查询</el-button></div>
            <PageState :loading="auditLoading" :error="auditError" :empty="!audits.length" empty-title="暂无审计日志" @retry="loadAudit">
              <el-table :data="audits" stripe>
                <el-table-column prop="actor" label="操作人" min-width="110" /><el-table-column prop="action" label="动作" min-width="150" /><el-table-column prop="resource" label="资源" min-width="175" />
                <el-table-column prop="detail" label="详情" min-width="250" show-overflow-tooltip /><el-table-column prop="createdAt" label="时间" min-width="170" />
                <el-table-column label="结果" width="90"><template #default="scope"><el-tag :type="scope.row.success === false ? 'danger' : 'success'" effect="plain">{{ scope.row.success === false ? '失败' : '成功' }}</el-tag></template></el-table-column>
              </el-table>
              <div v-if="auditQuery.total > auditQuery.size" class="pagination-row"><el-pagination v-model:current-page="auditQuery.page" :page-size="auditQuery.size" :total="auditQuery.total" layout="total, prev, pager, next" @current-change="loadAudit" /></div>
            </PageState>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canSeeIntegrity" name="integrity"><template #label><span class="tab-label"><Fingerprint :size="16" />完整性验证</span></template>
          <div class="security-pane integrity-pane">
            <div class="integrity-status"><component :is="verification?.valid === false ? ShieldX : ShieldCheck" :size="38" /><div><strong v-if="!verification">成绩证据账本链等待验证</strong><strong v-else>{{ verification.valid ? '成绩证据账本链完整' : '成绩证据账本链验证失败' }}</strong><span v-if="verification">已检查 {{ verification.checkedBlocks }} 个区块<span v-if="verification.brokenAt">，断点位于 #{{ verification.brokenAt }}</span></span><span v-else>逐块验证前序哈希与当前摘要</span></div><el-button v-if="auth.hasPermission('INTEGRITY_VERIFY')" type="primary" :icon="GitCompareArrows" :loading="verifying" @click="verifyChain">立即验证</el-button></div>
            <el-alert title="验证失败时请暂停成绩高风险操作，并根据审计日志与网络副本进行追溯。" type="warning" show-icon :closable="false" />
            <section v-if="auth.hasPermission('GRADE_RESTORE_ORIGINAL')" class="recovery-section">
              <div class="operation-title"><Fingerprint :size="20" /><div><h2>成绩证据账本快照</h2><p>从追加式成绩证据账本查询原始快照，不依赖当前数据库成绩。</p></div></div>
              <div class="recovery-query"><el-input v-model="recoveryGradeId" placeholder="成绩记录编号" clearable /><el-input v-model="recoveryReason" placeholder="核对原因" /><el-button type="primary" :icon="Search" :loading="recoveryLoading" @click="loadRecoveryEvidence">查询证据</el-button></div>
              <el-table v-if="recoveryItems.length" :data="recoveryItems" size="small">
                <el-table-column prop="sequence" label="#" width="75" /><el-table-column prop="eventType" label="事件" min-width="145" /><el-table-column prop="actor" label="操作人" min-width="110" /><el-table-column prop="createdAt" label="时间" min-width="175" />
                <el-table-column label="条目哈希" min-width="185"><template #default="scope"><el-tooltip :content="scope.row.entryHash"><code>{{ shortenHash(scope.row.entryHash) }}</code></el-tooltip></template></el-table-column>
                <el-table-column label="操作" width="100"><template #default="scope"><el-button link type="primary" @click="previewRecovery(scope.row)">查看原始值</el-button></template></el-table-column>
              </el-table>
            </section>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="canSeeOperations" name="operations"><template #label><span class="tab-label"><Undo2 :size="16" />撤销与恢复</span></template>
          <div class="security-pane operation-columns">
            <section v-if="auth.hasPermission('GRADE_READ')" class="operation-block grade-lookup-block">
              <div class="operation-title"><ListChecks :size="20" /><div><h2>成绩记录</h2><p>按课程或学生检索并选择撤销目标。</p></div></div>
              <div class="grade-lookup-toolbar">
                <el-input v-model="gradeQuery.course" clearable placeholder="课程代码或名称" :prefix-icon="Search" @keyup.enter="searchGrades" />
                <el-input v-model="gradeQuery.student" clearable placeholder="学号或姓名" :prefix-icon="Search" @keyup.enter="searchGrades" />
                <el-select v-model="gradeQuery.status" clearable placeholder="全部状态" @change="searchGrades"><el-option label="已提交" value="SUBMITTED" /><el-option label="暂存" value="DRAFT" /></el-select>
                <el-button type="primary" :icon="Search" @click="searchGrades">查询</el-button>
              </div>
              <PageState :loading="gradeLoading" :error="gradeError" :empty="!grades.length" empty-title="没有匹配的成绩记录" @retry="loadGrades">
                <div class="grade-selection-actions">
                  <span>已选择 {{ selectedGrades.length }} 条</span>
                  <el-button v-if="auth.hasPermission('GRADE_REVERT_SMALL')" :disabled="!selectedGrades.length" type="warning" plain :icon="RotateCcw" @click="useSelectionForSmallReversion">带入小撤销</el-button>
                  <el-button v-if="auth.hasPermission('GRADE_REVERT_REQUEST')" :disabled="!selectedGrades.length" type="danger" plain :icon="UserCheck" @click="useSelectionForLargeReversion">带入大撤销</el-button>
                  <el-button v-if="auth.hasPermission('GRADE_RESTORE_ORIGINAL')" :disabled="selectedGrades.length !== 1" plain :icon="Fingerprint" @click="useSelectionForRecovery">查询账本证据</el-button>
                </div>
                <el-table ref="gradeTable" :data="grades" row-key="id" stripe @selection-change="handleGradeSelection">
                  <el-table-column type="selection" width="48" reserve-selection />
                  <el-table-column label="成绩编号" min-width="175" show-overflow-tooltip><template #default="scope"><code>{{ scope.row.id }}</code></template></el-table-column>
                  <el-table-column label="学生" min-width="150"><template #default="scope"><strong>{{ scope.row.studentName || '--' }}</strong><span class="table-secondary">{{ scope.row.studentNo || '--' }}</span></template></el-table-column>
                  <el-table-column label="课程" min-width="180"><template #default="scope"><strong>{{ scope.row.courseName || '--' }}</strong><span class="table-secondary">{{ scope.row.courseCode || '--' }}</span></template></el-table-column>
                  <el-table-column label="开课" min-width="165"><template #default="scope">{{ scope.row.academicYear }} 第 {{ scope.row.semester }} 学期<span class="table-secondary">{{ scope.row.className || '--' }}</span></template></el-table-column>
                  <el-table-column label="状态" width="92"><template #default="scope"><el-tag :type="scope.row.status === 'SUBMITTED' ? 'success' : 'info'" effect="plain">{{ gradeStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
                  <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
                </el-table>
                <div v-if="gradeQuery.total > gradeQuery.size" class="pagination-row"><el-pagination v-model:current-page="gradeQuery.page" :page-size="gradeQuery.size" :total="gradeQuery.total" layout="total, prev, pager, next" @current-change="loadGrades" /></div>
              </PageState>
            </section>
            <section class="operation-block"><div class="operation-title"><RotateCcw :size="20" /><div><h2>小撤销</h2><p>将最多 10 条已提交成绩恢复为暂存，教师可重新修改。</p></div></div><el-form label-position="top"><el-form-item label="成绩记录编号" required><el-input v-model="smallForm.gradeIds" type="textarea" :rows="4" placeholder="每行一个编号，最多 10 个" /></el-form-item><el-form-item label="撤销原因" required><el-input v-model="smallForm.reason" type="textarea" :rows="3" maxlength="300" show-word-limit /></el-form-item><el-button v-if="auth.hasPermission('GRADE_REVERT_SMALL') && auth.hasPermission('GRADE_READ')" type="warning" plain :icon="RotateCcw" :loading="operationLoading" @click="smallRevert">执行小撤销</el-button><el-alert v-else title="当前账号缺少小撤销或成绩读取权限" type="info" :closable="false" /></el-form></section>
            <section class="operation-block high-risk"><div class="operation-title"><AlertOctagon :size="20" /><div><h2>高风险操作申请</h2><p>大撤销和原始恢复由另一名管理员复核后执行。</p></div></div><el-form label-position="top"><el-form-item label="操作类型" required><el-segmented v-model="requestForm.scope" :options="[{ label: '大撤销', value: 'LARGE_BATCH' }, { label: '原始恢复', value: 'ORIGINAL_RESTORE' }]" /></el-form-item><el-form-item :label="requestForm.scope === 'LARGE_BATCH' ? '成绩记录编号' : '账本快照序号'" required><el-input v-model="requestForm.targetFilter" type="textarea" :rows="4" :placeholder="requestForm.scope === 'LARGE_BATCH' ? '每行一个成绩记录编号' : '安全副本证据中的快照序号'" /></el-form-item><el-form-item label="申请原因" required><el-input v-model="requestForm.reason" type="textarea" :rows="3" maxlength="300" show-word-limit /></el-form-item><el-button v-if="auth.hasPermission('GRADE_REVERT_REQUEST') && (requestForm.scope === 'LARGE_BATCH' || auth.hasPermission('GRADE_RESTORE_ORIGINAL'))" type="danger" plain :icon="UserCheck" :loading="operationLoading" @click="createHighRiskRequest">提交双人复核</el-button><el-alert v-else title="当前账号没有此类高风险操作权限" type="info" :closable="false" /></el-form></section>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="reviewDialog" :title="reviewAction === 'approve' ? (retryingApproved ? '重试已批准操作' : '批准高风险操作') : '驳回高风险操作'" width="min(520px, 94vw)" destroy-on-close>
      <el-alert v-if="reviewAction === 'approve'" :title="retryingApproved ? '审批记录已经存在，本次只重试原目标执行，不会新增审批。' : '批准后后端将在事务中执行操作，并写入完整审计记录。'" type="warning" show-icon :closable="false" />
      <dl v-if="reviewTarget" class="review-summary"><dt>操作</dt><dd>{{ requestScopeLabel(reviewTarget.scope) }}</dd><dt>目标</dt><dd>{{ reviewTarget.targetFilter }}</dd><dt>发起人</dt><dd>{{ reviewTarget.initiator }}</dd><dt>原因</dt><dd>{{ reviewTarget.reason }}</dd></dl>
      <el-input v-model="reviewComment" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="填写复核意见" />
      <template #footer><el-button @click="reviewDialog = false">取消</el-button><el-button :type="reviewAction === 'approve' ? 'primary' : 'danger'" :icon="reviewAction === 'approve' ? Check : X" :loading="reviewSubmitting" @click="submitReview">{{ reviewAction === 'approve' ? (retryingApproved ? '重试执行' : '批准并执行') : '确认驳回' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="recoveryPreviewOpen" title="安全副本原始值" width="min(620px, 94vw)" destroy-on-close>
      <el-alert title="以下内容来自追加式成绩证据账本，仅供核对；恢复仍需提交双人复核。" type="warning" show-icon :closable="false" />
      <dl class="recovery-values"><template v-for="(value, key) in recoveryPreview" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
      <template #footer><el-button @click="recoveryPreviewOpen = false">关闭</el-button><el-button type="primary" :icon="UserCheck" @click="recoveryPreviewOpen = false; activeTab = 'operations'; requestForm.scope = 'ORIGINAL_RESTORE'; requestForm.targetFilter = String(recoveryPreviewSequence || '')">发起恢复复核</el-button></template>
    </el-dialog>
  </div>
</template>
