<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, toRaw } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import {
  ArrowLeft, CheckCircle2, ClipboardPaste, FileImage, FileSpreadsheet, Mic, MicOff, Plus,
  RotateCcw, Save, Send, Settings2, Trash2, Upload,
} from 'lucide-vue-next'
import PageState from '@/components/PageState.vue'
import { teacherApi } from '@/api/services'
import { toAppError } from '@/api/http'
import {
  appendWeightItem, applyParsedScores, calculateTotal, cloneWeights, defaultWeights, finalExamItem,
  MAX_WEIGHT_ITEMS, mergeGradeRecord, missingComponentCodes, nextDirtyState, parseScoreTextDetailed,
  removeWeightItem, renameWeightItemCode, validWeights, weightTotal,
  type ParsedScore, type ParsedScoreTarget, type RejectedScore,
} from '@/utils/grade'
import type { Course, ExamType, GradeRecord, GradeWeightItem, GradeWeights } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'

interface RecognitionResultLike {
  readonly length: number
  [index: number]: { readonly transcript: string }
}
interface RecognitionEventLike extends Event {
  readonly resultIndex: number
  readonly results: { readonly length: number; [index: number]: RecognitionResultLike }
}
interface RecognitionErrorLike extends Event { readonly error?: string }
interface RecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: RecognitionEventLike) => void) | null
  onerror: ((event: RecognitionErrorLike) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const courseId = computed(() => String(route.params.courseId))
const loading = ref(false)
const error = ref('')
const course = ref<Course | null>(null)
const records = ref<GradeRecord[]>([])
const weights = reactive<GradeWeights>(cloneWeights(defaultWeights))
const examType = ref<ExamType>('REGULAR')
const page = ref(1)
const pageSize = ref(20)
const saving = ref(false)
const schemeDirty = ref(false)
const scoreDirty = ref(false)
const dirty = computed(() => schemeDirty.value || scoreDirty.value)
const dirtyGradeKeys = reactive(new Set<string>())
const batchOpen = ref(false)
const batchTab = ref('paste')
const batchComponentCode = ref('')
const pastedText = ref('')
const voiceText = ref('')
const voiceActive = ref(false)
const voiceError = ref('')
const ocrFile = ref<File | null>(null)
const ocrParsed = ref<ParsedScore[]>([])
const ocrRejected = ref<RejectedScore[]>([])
const ocrText = ref('')
const ocrProvider = ref('')
const ocrRequestId = ref('')
const ocrLoading = ref(false)
const importFeedback = ref<{ type: 'success' | 'warning'; message: string } | null>(null)
let recognition: RecognitionLike | null = null
const weightItemKeys = new WeakMap<object, string>()
const weightItemCodeDrafts = reactive(new Map<string, string>())
let nextWeightItemKey = 0

const totalWeight = computed(() => weightTotal(weights))
const activeWeightItems = computed(() => [...weights.items].sort((left, right) => left.sortOrder - right.sortOrder))
const isCourseOpen = computed(() => course.value?.status === 'OPEN')
const editable = computed(() => isCourseOpen.value && records.value.some((record) => canEditRecord(record)))
const hasSubmitted = computed(() => records.value.some((record) => record.status === 'SUBMITTED'))
const canEditScores = computed(() => editable.value && auth.hasPermission('GRADE_DRAFT_WRITE'))
const canEditWeights = computed(() => isCourseOpen.value && examType.value === 'REGULAR'
  && records.value.every((record) => record.regularStatus !== 'SUBMITTED')
  && auth.hasPermission('GRADING_SCHEME_WRITE'))
const hasPersistedRegularGrades = computed(() => records.value.some((record) => Boolean(record.id)))
const pageRecords = computed(() => records.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const sheetStatus = computed(() => {
  const relevant = examType.value === 'MAKEUP' ? records.value.filter(isMakeupEligible) : records.value
  if (relevant.length && relevant.every((record) => record.status === 'SUBMITTED')) return 'SUBMITTED'
  return relevant.some((record) => record.status === 'REVERTED') ? 'REVERTED' : 'DRAFT'
})
const speechSupported = computed(() => {
  const speechWindow = window as typeof window & {
    SpeechRecognition?: new () => RecognitionLike
    webkitSpeechRecognition?: new () => RecognitionLike
  }
  return Boolean(speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition)
})

function gradeStatusLabel(status?: string) {
  return status === 'SUBMITTED' ? '已提交' : status === 'REVERTED' ? '已撤销' : '暂存'
}

function courseStatusLabel(status?: string) {
  return status === 'OPEN' ? '进行中' : '已结课'
}

function recalculate(record: GradeRecord) {
  record.totalScore = calculateTotal(record, weights)
  record.regularScore = record.totalScore
  markRecordDirty(record)
}

function gradeRowKey(record: GradeRecord) {
  return record.id || record.enrollmentId || record.studentId
}

function gradeDirtyKey(record: GradeRecord) {
  return record.enrollmentId || record.studentId
}

function markRecordDirty(record: GradeRecord) {
  dirtyGradeKeys.add(gradeDirtyKey(record))
  scoreDirty.value = true
}

function onMakeupChanged(record: GradeRecord) {
  markRecordDirty(record)
}

function weightItemKey(item: GradeWeightItem) {
  if (item.id) return `saved:${item.id}`
  const target = toRaw(item)
  let key = weightItemKeys.get(target)
  if (!key) {
    key = `new:${++nextWeightItemKey}`
    weightItemKeys.set(target, key)
  }
  return key
}

function isPersistedWeightLocked(item: GradeWeightItem) {
  return Boolean(item.id && hasPersistedRegularGrades.value)
}

function refreshRegularTotals() {
  records.value.forEach((record) => {
    record.totalScore = calculateTotal(record, weights)
    record.regularScore = record.totalScore
  })
}

function onWeightDefinitionChanged() {
  refreshRegularTotals()
  schemeDirty.value = true
}

function weightItemCodeValue(item: GradeWeightItem) {
  return weightItemCodeDrafts.get(weightItemKey(item)) ?? item.itemCode
}

function updateWeightItemCodeDraft(item: GradeWeightItem, value: string) {
  weightItemCodeDrafts.set(weightItemKey(item), value)
}

function addWeightItem() {
  if (!canEditWeights.value) return
  if (!appendWeightItem(weights)) {
    ElMessage.warning(`评分项最多允许 ${MAX_WEIGHT_ITEMS} 个`)
    return
  }
  onWeightDefinitionChanged()
}

function deleteWeightItem(item: GradeWeightItem) {
  if (!canEditWeights.value) return
  if (isPersistedWeightLocked(item)) {
    ElMessage.warning('已有成绩草稿时不能删除已保存的评分项')
    return
  }
  const target = toRaw(item)
  const index = weights.items.findIndex((candidate) => toRaw(candidate) === target)
  const removed = removeWeightItem(weights, index)
  if (!removed) {
    ElMessage.warning('评分方案至少保留一个评分项')
    return
  }
  records.value.forEach((record) => { delete record.componentScores[removed.itemCode] })
  if (batchComponentCode.value === removed.itemCode) {
    batchComponentCode.value = finalExamItem(weights)?.itemCode || weights.items[0]?.itemCode || ''
  }
  onWeightDefinitionChanged()
}

function changeWeightItemCode(item: GradeWeightItem, value: string) {
  if (!canEditWeights.value) return
  if (isPersistedWeightLocked(item)) {
    ElMessage.warning('已有成绩草稿时不能修改已保存评分项的编码')
    return
  }
  const result = renameWeightItemCode(weights, item, records.value, value)
  weightItemCodeDrafts.set(weightItemKey(item), result.ok ? result.newCode : result.oldCode)
  if (!result.ok) {
    const messages = {
      INVALID: '评分项编码必须以大写字母开头，且只能包含大写字母、数字和下划线',
      DUPLICATE: '评分项编码不能重复',
      SCORE_CONFLICT: '新编码已存在成绩数据，为避免覆盖请使用其他编码',
    }
    ElMessage.warning(messages[result.reason])
    return
  }
  if (batchComponentCode.value === result.oldCode) batchComponentCode.value = result.newCode
  onWeightDefinitionChanged()
}

function replaceWeights(next: GradeWeights) {
  const copy = cloneWeights(next)
  Object.assign(weights, copy)
  weights.items = copy.items
  weightItemCodeDrafts.clear()
  const preferred = finalExamItem(weights)
  batchComponentCode.value = preferred?.itemCode || weights.items[0]?.itemCode || ''
}

function isMakeupEligible(record: GradeRecord) {
  const regular = record.regularScore ?? record.totalScore
  return record.regularStatus === 'SUBMITTED' && regular !== null && regular !== undefined && regular < 60
}

function canEditRecord(record: GradeRecord) {
  if (examType.value === 'MAKEUP') {
    return record.makeupStatus !== 'SUBMITTED' && isMakeupEligible(record)
  }
  return record.regularStatus !== 'SUBMITTED'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await teacherApi.gradeSheet(courseId.value, { examType: examType.value })
    course.value = result.course
    replaceWeights(result.weights || defaultWeights)
    records.value = (result.records || []).map((record) => ({
      ...record, componentScores: { ...record.componentScores },
    }))
    records.value.forEach((record) => {
      if (record.totalScore === null || record.totalScore === undefined) record.totalScore = calculateTotal(record, weights)
    })
    page.value = 1
    dirtyGradeKeys.clear()
    schemeDirty.value = false
    scoreDirty.value = false
  } catch (cause) {
    error.value = toAppError(cause).message
  } finally { loading.value = false }
}

async function saveWeights() {
  if (!validWeights(weights)) {
    ElMessage.warning('请检查方案名称、评分项编码与名称、权重，且合计必须为 100%')
    return
  }
  const creatingFirstScheme = !weights.id
  const hasEnteredScores = records.value.some((record) => Object.values(record.componentScores)
    .some((score) => score !== null && score !== undefined))
  saving.value = true
  try {
    const saved = await teacherApi.saveWeights(courseId.value, cloneWeights(weights))
    replaceWeights(saved)
    refreshRegularTotals()
    schemeDirty.value = false
    if (hasEnteredScores) {
      records.value.filter((record) => Object.values(record.componentScores)
        .some((score) => score !== null && score !== undefined)).forEach(markRecordDirty)
    }
    if (creatingFirstScheme) await load()
    ElMessage.success('评分系数已保存')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { saving.value = false }
}

function validateForSubmit() {
  if (!isCourseOpen.value) return '已结课课程不可提交成绩'
  if (dirty.value) return '存在未暂存的修改，请先暂存后再提交'
  if (examType.value === 'REGULAR') {
    if (!validWeights(weights)) return '评分系数合计必须为 100%'
    const drafts = records.value.filter(canEditRecord)
    if (!drafts.length) return '当前没有可提交的正考草稿'
    const incomplete = drafts.some((record) => missingComponentCodes(record, weights).length > 0)
    if (incomplete) return '仍有学生的成绩项未填写完整'
  } else {
    const drafts = records.value.filter(canEditRecord)
    if (!drafts.length) return '当前没有可提交的补考草稿'
    if (drafts.some((record) => record.makeupScore === null || record.makeupScore === undefined)) {
      return '仍有应参加补考的学生未填写补考成绩'
    }
  }
  return ''
}

async function saveDraft() {
  if (examType.value === 'REGULAR' && schemeDirty.value) {
    ElMessage.warning('评分方案尚未保存，请先保存评分方案再暂存成绩')
    return
  }
  const pendingRecords = records.value.filter((record) => dirtyGradeKeys.has(gradeDirtyKey(record)))
  if (!pendingRecords.length) {
    ElMessage.warning('当前没有待暂存的成绩修改')
    return
  }
  saving.value = true
  try {
    const saved = await teacherApi.saveDraft(courseId.value, pendingRecords, examType.value, (batch) => {
      batch.forEach((record) => dirtyGradeKeys.delete(gradeDirtyKey(record)))
      scoreDirty.value = dirtyGradeKeys.size > 0
    })
    const byEnrollment = new Map(saved.map((item) => [item.enrollmentId, item]))
    records.value = records.value.map((item) => {
      const updated = byEnrollment.get(item.enrollmentId)
      return updated ? mergeGradeRecord(item, updated) : item
    })
    dirtyGradeKeys.clear()
    scoreDirty.value = false
    ElMessage.success('成绩已暂存')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { saving.value = false }
}

async function changeExamType(value: string | number | boolean) {
  const next = String(value) as ExamType
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('切换成绩类别会丢弃尚未暂存的修改，确定继续吗？', '切换成绩类别', {
        confirmButtonText: '切换', cancelButtonText: '继续编辑',
      })
    } catch {
      examType.value = next === 'REGULAR' ? 'MAKEUP' : 'REGULAR'
      return
    }
  }
  examType.value = next
  await load()
}

async function submitGrades() {
  const validation = validateForSubmit()
  if (validation) { ElMessage.warning(validation); return }
  try {
    await ElMessageBox.confirm('提交后成绩将锁定，并写入安全审计链。确定继续吗？', '提交成绩', {
      confirmButtonText: '确认提交', cancelButtonText: '再检查一下', type: 'warning',
    })
  } catch { return }
  saving.value = true
  try {
    await teacherApi.submit(courseId.value, records.value, examType.value)
    await load()
    schemeDirty.value = false
    scoreDirty.value = false
    ElMessage.success('成绩提交成功')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { saving.value = false }
}

async function withdraw() {
  const label = examType.value === 'MAKEUP' ? '补考成绩' : '正考成绩'
  try {
    await ElMessageBox.confirm(`撤销已提交的${label}，并记录审计日志。`, '撤销提交', {
      confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning',
    })
    await teacherApi.withdraw(courseId.value, examType.value)
    await load()
    ElMessage.success(`${label}已撤销，可继续修改`)
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(toAppError(cause).message)
  }
}

function openBatch() {
  const preferred = finalExamItem(weights)
  if (!batchComponentCode.value || !weights.items.some((item) => item.itemCode === batchComponentCode.value)) {
    batchComponentCode.value = preferred?.itemCode || weights.items[0]?.itemCode || ''
  }
  importFeedback.value = null
  batchOpen.value = true
}

function parsedTarget(): ParsedScoreTarget | null {
  if (examType.value === 'MAKEUP') return { type: 'MAKEUP' }
  if (!weights.items.some((item) => item.itemCode === batchComponentCode.value)) return null
  return { type: 'COMPONENT', itemCode: batchComponentCode.value }
}

function applyParsed(parsed: ParsedScore[]) {
  const target = parsedTarget()
  if (!target) {
    ElMessage.warning('请选择要写入的评分项')
    return null
  }
  const result = applyParsedScores(records.value.filter(canEditRecord), parsed, target)
  if (examType.value === 'REGULAR') {
    records.value.forEach((record) => {
      record.totalScore = calculateTotal(record, weights)
      record.regularScore = record.totalScore
    })
  }
  scoreDirty.value = nextDirtyState(scoreDirty.value, result.matched)
  result.matchedRecords.forEach(markRecordDirty)
  return result
}

function reportImportResult(result: ReturnType<typeof applyParsedScores>, rejected: RejectedScore[]) {
  const issues: string[] = []
  if (result.ambiguous.length) {
    const names = result.ambiguous.slice(0, 3).map((item) => item.studentName || item.studentNo).join('、')
    issues.push(`${result.ambiguous.length} 条姓名重名，已拒绝（${names}）`)
  }
  if (result.unmatched.length) {
    const identities = result.unmatched.slice(0, 3).map((item) => item.studentNo || item.studentName).join('、')
    issues.push(`${result.unmatched.length} 条未匹配（${identities}）`)
  }
  if (rejected.length) {
    const rows = rejected.slice(0, 2).map((item) => `${item.source}：${item.reason}`).join('；')
    issues.push(`${rejected.length} 条无效数据（${rows}）`)
  }
  const message = `已匹配 ${result.matched} 人${issues.length ? `；${issues.join('；')}` : ''}`
  const type = result.matched > 0 && issues.length === 0 ? 'success' : 'warning'
  importFeedback.value = { type, message }
  ElMessage({ type, message })
  return result.matched > 0 && issues.length === 0
}

function applyText(text: string) {
  const parsedText = parseScoreTextDetailed(text)
  if (!parsedText.parsed.length) {
    const rows = parsedText.rejected.slice(0, 2).map((item) => `${item.source}：${item.reason}`).join('；')
    const suffix = parsedText.rejected.length ? `；无效数据：${rows}` : ''
    const message = `未识别到“学号/姓名 + 分数”数据${suffix}`
    importFeedback.value = { type: 'warning', message }
    ElMessage.warning(message)
    return
  }
  const result = applyParsed(parsedText.parsed)
  if (!result) return
  if (reportImportResult(result, parsedText.rejected)) closeBatch()
}

function onCsv(file: UploadFile) {
  if (!file.raw) return
  if (file.raw.size > 2 * 1024 * 1024) { ElMessage.warning('CSV 文件不能超过 2MB'); return }
  const reader = new FileReader()
  reader.onload = () => {
    pastedText.value = String(reader.result || '')
    batchTab.value = 'paste'
    ElMessage.success('CSV 已读取，请核对后应用')
  }
  reader.onerror = () => ElMessage.error('CSV 文件读取失败')
  reader.readAsText(file.raw, 'UTF-8')
}

function startVoice() {
  if (!speechSupported.value) return
  const speechWindow = window as typeof window & {
    SpeechRecognition?: new () => RecognitionLike
    webkitSpeechRecognition?: new () => RecognitionLike
  }
  const Constructor = speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition
  if (!Constructor) return
  voiceError.value = ''
  recognition?.abort()
  recognition = new Constructor()
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = false
  recognition.onresult = (event) => {
    const chunks: string[] = []
    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const text = event.results[index]?.[0]?.transcript
      if (text) chunks.push(text)
    }
    voiceText.value = `${voiceText.value} ${chunks.join(' ')}`.trim()
  }
  recognition.onerror = (event) => {
    voiceError.value = event.error === 'not-allowed' ? '麦克风权限被拒绝，请在浏览器设置中允许后重试。' : '语音识别中断，请重试。'
    voiceActive.value = false
  }
  recognition.onend = () => { voiceActive.value = false }
  try { recognition.start(); voiceActive.value = true }
  catch { voiceError.value = '语音识别暂时无法启动，请稍后重试。' }
}

function stopVoice() { recognition?.stop(); voiceActive.value = false }

function onOcrFile(file: UploadFile) {
  if (!file.raw) return
  if (!file.raw.type.startsWith('image/')) { ElMessage.warning('请选择图片文件'); return }
  if (file.raw.size > 5 * 1024 * 1024) { ElMessage.warning('图片不能超过 5MB'); return }
  ocrFile.value = file.raw
  ocrParsed.value = []
  ocrRejected.value = []
  ocrText.value = ''
  ocrProvider.value = ''
  ocrRequestId.value = ''
}

function clearOcrFile() {
  ocrFile.value = null
  ocrParsed.value = []
  ocrRejected.value = []
  ocrText.value = ''
  ocrProvider.value = ''
  ocrRequestId.value = ''
}

function parseOcrResult() {
  const result = parseScoreTextDetailed(ocrText.value)
  ocrParsed.value = result.parsed
  ocrRejected.value = result.rejected
}

async function runOcr() {
  if (!ocrFile.value) return
  ocrLoading.value = true
  try {
    const result = await teacherApi.recognize(courseId.value, ocrFile.value)
    ocrText.value = result.text || ''
    ocrProvider.value = result.provider || ''
    ocrRequestId.value = result.requestId || ''
    parseOcrResult()
    if (!ocrParsed.value.length) ElMessage.warning('服务未识别到有效成绩，请更换清晰图片')
  } catch (cause) {
    const appError = toAppError(cause)
    ElMessage.error(appError.status === 404 ? '当前环境未配置 OCR 服务' : appError.message)
  } finally { ocrLoading.value = false }
}

function applyOcr() {
  parseOcrResult()
  if (!ocrParsed.value.length) {
    const rows = ocrRejected.value.slice(0, 2).map((item) => `${item.source}：${item.reason}`).join('；')
    const message = `未匹配任何学生，请核对识别原文${rows ? `；无效数据：${rows}` : ''}`
    importFeedback.value = { type: 'warning', message }
    ElMessage.warning(message)
    return
  }
  const result = applyParsed(ocrParsed.value)
  if (!result) return
  if (reportImportResult(result, ocrRejected.value)) closeBatch()
}

function clearTransientInput() {
  recognition?.abort()
  recognition = null
  voiceActive.value = false
  voiceText.value = ''
  voiceError.value = ''
  pastedText.value = ''
  ocrFile.value = null
  ocrParsed.value = []
  ocrRejected.value = []
  ocrText.value = ''
  ocrProvider.value = ''
  ocrRequestId.value = ''
  importFeedback.value = null
}
function closeBatch() { batchOpen.value = false; clearTransientInput() }
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('存在尚未暂存的修改，仍要离开吗？', '未保存修改', {
      confirmButtonText: '离开', cancelButtonText: '继续编辑',
    })
    return true
  } catch { return false }
})
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); clearTransientInput() })
</script>

<template>
  <div class="page-stack gradebook-page">
    <header class="page-header">
      <div>
        <button class="back-link" type="button" @click="router.push('/teacher/courses')"><ArrowLeft :size="16" />返回课程</button>
        <h1>{{ course?.name || '成绩录入' }}</h1>
        <p v-if="course">{{ course.code }} · {{ course.academicYear }} 第{{ course.semester }}学期 · {{ course.className }}</p>
      </div>
      <div class="page-actions">
        <el-tag :type="course?.status === 'OPEN' ? 'success' : 'info'" effect="plain">{{ courseStatusLabel(course?.status) }}</el-tag>
        <el-tag :type="sheetStatus === 'SUBMITTED' ? 'success' : 'warning'" effect="plain">{{ gradeStatusLabel(sheetStatus) }}</el-tag>
        <el-button v-if="canEditScores" :icon="ClipboardPaste" @click="openBatch">智能录入</el-button>
        <el-button v-if="canEditScores" :icon="Save" :loading="saving" @click="saveDraft">暂存</el-button>
        <el-button v-if="editable && auth.hasPermission('GRADE_SUBMIT')" type="primary" :icon="Send" :loading="saving" @click="submitGrades">提交</el-button>
        <el-button v-if="isCourseOpen && hasSubmitted && auth.hasPermission('GRADE_WITHDRAW')" type="warning" plain :icon="RotateCcw" @click="withdraw">撤销提交</el-button>
      </div>
    </header>

    <PageState :loading="loading" :error="error" :empty="false" @retry="load">
      <div class="page-stack">
        <section class="mode-toolbar surface">
          <div>
            <span>成绩类别</span>
            <el-segmented v-model="examType" :options="[{ label: '正考成绩', value: 'REGULAR' }, { label: '补考成绩', value: 'MAKEUP' }]" @change="changeExamType" />
          </div>
        </section>

        <section v-if="examType === 'REGULAR'" class="surface weight-panel">
          <div class="section-heading">
            <div><Settings2 :size="18" /><h2>评分系数</h2></div>
            <div class="weight-heading-actions">
              <span :class="{ invalid: totalWeight !== 100 }">合计 {{ totalWeight }}%</span>
              <el-tooltip content="新增评分项">
                <span><el-button circle :icon="Plus" aria-label="新增评分项" :disabled="!canEditWeights || weights.items.length >= MAX_WEIGHT_ITEMS" @click="addWeightItem" /></span>
              </el-tooltip>
            </div>
          </div>
          <div class="scheme-name-row"><span>方案名称</span><el-input v-model="weights.name" maxlength="64" :disabled="!canEditWeights" @input="schemeDirty = true" /></div>
          <div class="weight-editor">
            <div class="weight-editor-head" aria-hidden="true"><span>评分项编码</span><span>评分项名称</span><span>权重</span><span>满分</span><span></span></div>
            <div v-for="item in activeWeightItems" :key="weightItemKey(item)" class="weight-editor-row">
              <el-tooltip :disabled="!isPersistedWeightLocked(item)" content="已有成绩草稿，编码已锁定">
                <el-input :model-value="weightItemCodeValue(item)" maxlength="32" aria-label="评分项编码" :disabled="!canEditWeights || isPersistedWeightLocked(item)" @update:model-value="updateWeightItemCodeDraft(item, String($event))" @change="changeWeightItemCode(item, String($event))" />
              </el-tooltip>
              <el-input v-model="item.itemName" maxlength="64" aria-label="评分项名称" :disabled="!canEditWeights" @input="schemeDirty = true" />
              <div class="weight-value"><el-input-number v-model="item.weight" :min="0.01" :max="100" :step="5" :precision="2" :disabled="!canEditWeights" aria-label="评分项权重" @change="onWeightDefinitionChanged" /><small>%</small></div>
              <span class="fixed-score">100</span>
              <el-tooltip :content="isPersistedWeightLocked(item) ? '已有成绩草稿，不能删除' : weights.items.length <= 1 ? '至少保留一个评分项' : '删除评分项'">
                <span><el-button text type="danger" :icon="Trash2" aria-label="删除评分项" :disabled="!canEditWeights || isPersistedWeightLocked(item) || weights.items.length <= 1" @click="deleteWeightItem(item)" /></span>
              </el-tooltip>
            </div>
            <div v-if="canEditWeights" class="weight-panel-footer"><el-button type="primary" :icon="CheckCircle2" :loading="saving" @click="saveWeights">保存评分方案</el-button></div>
          </div>
        </section>

        <section class="surface table-surface">
          <div class="section-heading"><div><FileSpreadsheet :size="18" /><h2>{{ examType === 'REGULAR' ? '正考成绩表' : '补考成绩表' }}</h2></div><span>{{ records.length }} 名学生</span></div>
          <el-table :data="pageRecords" stripe :row-key="gradeRowKey" table-layout="fixed" empty-text="暂无学生名单">
            <el-table-column prop="studentNo" label="学号" min-width="130" fixed />
            <el-table-column prop="studentName" label="姓名" min-width="100" fixed><template #default="scope"><strong>{{ scope.row.studentName }}</strong></template></el-table-column>
            <template v-if="examType === 'REGULAR'">
              <el-table-column v-for="item in activeWeightItems" :key="item.itemCode" :label="`${item.itemName} ${item.weight}%`" min-width="126" align="center">
                <template #default="scope"><el-input-number v-model="scope.row.componentScores[item.itemCode]" :min="0" :max="item.maxScore" :precision="1" :controls="false" :disabled="!canEditScores || !canEditRecord(scope.row)" class="score-input" @change="recalculate(scope.row)" /></template>
              </el-table-column>
              <el-table-column label="总评" width="90" align="center"><template #default="scope"><strong :class="{ 'score-fail': scope.row.totalScore !== null && scope.row.totalScore < 60 }">{{ scope.row.totalScore ?? '--' }}</strong></template></el-table-column>
            </template>
            <template v-else>
              <el-table-column label="正考" width="100" align="center"><template #default="scope">{{ scope.row.regularScore ?? scope.row.totalScore ?? '--' }}</template></el-table-column>
              <el-table-column label="补考卷面" min-width="160" align="center">
                <template #default="scope"><div class="makeup-cell"><el-input-number v-model="scope.row.makeupScore" :min="0" :max="100" :precision="1" :controls="false" :disabled="!canEditScores || !canEditRecord(scope.row)" class="score-input" @change="onMakeupChanged(scope.row)" /><small v-if="scope.row.makeupScore > 60">计入成绩：60</small><small v-else-if="!isMakeupEligible(scope.row)">正考未提交或已通过</small></div></template>
              </el-table-column>
              <el-table-column label="成绩显示" width="120" align="center"><template #default="scope"><strong>{{ scope.row.regularScore ?? scope.row.totalScore ?? '--' }}/{{ scope.row.makeupScore == null ? '--' : Math.min(60, scope.row.makeupScore) }}</strong></template></el-table-column>
            </template>
          </el-table>
          <div v-if="records.length > pageSize" class="pagination-row"><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="records.length" layout="total, sizes, prev, pager, next" :page-sizes="[10,20,50,100]" /></div>
        </section>
      </div>
    </PageState>

    <el-dialog v-model="batchOpen" title="智能成绩录入" width="min(720px, 94vw)" destroy-on-close :before-close="closeBatch">
      <div v-if="examType === 'REGULAR'" class="batch-target-row"><span>写入评分项</span><el-select v-model="batchComponentCode" filterable><el-option v-for="item in activeWeightItems" :key="item.itemCode" :label="`${item.itemName} (${item.itemCode})`" :value="item.itemCode" /></el-select></div>
      <el-alert v-if="importFeedback" :title="importFeedback.message" :type="importFeedback.type" show-icon :closable="false" />
      <el-tabs v-model="batchTab">
        <el-tab-pane name="paste">
          <template #label><span class="tab-label"><ClipboardPaste :size="15" />批量粘贴</span></template>
          <div class="import-pane">
            <el-input v-model="pastedText" type="textarea" :rows="9" resize="vertical" placeholder="每行一条：学号,姓名,分数&#10;或直接粘贴：张三 88 李四 76" />
            <div class="import-actions"><el-upload :auto-upload="false" :show-file-list="false" accept=".csv,text/csv" :on-change="onCsv"><el-button :icon="Upload">读取 CSV</el-button></el-upload><el-button type="primary" :disabled="!pastedText.trim()" @click="applyText(pastedText)">核对并应用</el-button></div>
          </div>
        </el-tab-pane>
        <el-tab-pane name="voice">
          <template #label><span class="tab-label"><Mic :size="15" />语音输入</span></template>
          <div class="import-pane">
            <el-alert v-if="!speechSupported" title="当前浏览器不支持 Web Speech API，请使用最新版 Chrome 或 Edge，或改用批量粘贴。" type="warning" show-icon :closable="false" />
            <el-alert v-else title="识别内容只保存在当前页面内存，关闭窗口或离开页面后立即清除。" type="info" show-icon :closable="false" />
            <el-input v-model="voiceText" type="textarea" :rows="7" placeholder="例如：张三 88，李四 76" />
            <p v-if="voiceError" class="inline-error">{{ voiceError }}</p>
            <div class="import-actions"><el-button v-if="!voiceActive" :icon="Mic" :disabled="!speechSupported" @click="startVoice">开始识别</el-button><el-button v-else type="danger" plain :icon="MicOff" @click="stopVoice">停止识别</el-button><el-button type="primary" :disabled="!voiceText.trim()" @click="applyText(voiceText)">核对并应用</el-button></div>
          </div>
        </el-tab-pane>
        <el-tab-pane name="ocr">
          <template #label><span class="tab-label"><FileImage :size="15" />图片 OCR</span></template>
          <div class="import-pane">
            <el-alert title="图片将通过受控接口临时处理；识别服务不可用时不会生成替代结果。" type="info" show-icon :closable="false" />
            <el-upload drag :auto-upload="false" :limit="1" accept="image/png,image/jpeg,image/webp" :on-change="onOcrFile" :on-remove="clearOcrFile"><FileImage :size="30" /><div class="el-upload__text">拖入成绩单图片，或点击选择</div><template #tip><span>PNG / JPG / WebP，最大 5MB</span></template></el-upload>
            <div v-if="ocrText || ocrProvider || ocrRequestId" class="ocr-result">
              <div class="ocr-meta"><span>服务：{{ ocrProvider || '--' }}</span><span>请求：{{ ocrRequestId || '--' }}</span></div>
              <el-input v-model="ocrText" type="textarea" :rows="6" resize="vertical" aria-label="OCR 识别原文" @input="parseOcrResult" />
            </div>
            <div v-if="ocrParsed.length || ocrRejected.length" class="ocr-preview"><strong>解析到 {{ ocrParsed.length }} 条有效数据<span v-if="ocrRejected.length">，{{ ocrRejected.length }} 条无效数据将被拒绝</span></strong><span v-for="(item, index) in ocrParsed.slice(0, 6)" :key="index">{{ item.studentNo || item.studentName }} {{ item.score }}</span></div>
            <div class="import-actions"><el-button :disabled="!ocrFile" :loading="ocrLoading" @click="runOcr">开始识别</el-button><el-button type="primary" :disabled="!ocrParsed.length" @click="applyOcr">核对并应用</el-button></div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer><el-button @click="closeBatch">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.scheme-name-row { display: grid; grid-template-columns: 92px minmax(220px, 420px); align-items: center; gap: 12px; padding: 4px 18px 10px; }
.scheme-name-row > span, .batch-target-row > span { color: var(--muted); font-size: 12px; }
.weight-heading-actions { display: flex; align-items: center; gap: 10px; }
.weight-editor { padding: 6px 18px 18px; }
.weight-editor-head, .weight-editor-row { display: grid; grid-template-columns: minmax(140px, 1fr) minmax(160px, 1.4fr) minmax(145px, .8fr) 64px 36px; align-items: center; gap: 10px; }
.weight-editor-head { min-height: 30px; color: var(--muted); font-size: 11px; }
.weight-editor-row { min-height: 52px; border-top: 1px solid var(--line); }
.weight-value { display: grid; grid-template-columns: minmax(0, 1fr) 14px; align-items: center; gap: 4px; }
.weight-value .el-input-number { width: 100%; }
.weight-value small, .fixed-score { color: var(--muted); font-size: 12px; }
.fixed-score { text-align: center; }
.weight-panel-footer { display: flex; justify-content: flex-end; padding-top: 14px; border-top: 1px solid var(--line); }
.batch-target-row { display: grid; grid-template-columns: 92px minmax(0, 1fr); align-items: center; gap: 12px; margin-bottom: 10px; }
@media (max-width: 760px) {
  .weight-editor-head { display: none; }
  .weight-editor-row { grid-template-columns: minmax(110px, 1fr) minmax(130px, 1.2fr) 110px 34px; padding: 10px 0; }
  .weight-editor-row .fixed-score { display: none; }
}
@media (max-width: 560px) {
  .scheme-name-row, .batch-target-row { grid-template-columns: 1fr; }
  .weight-editor-row { grid-template-columns: 1fr 1fr 34px; }
  .weight-editor-row > :first-child { grid-column: 1 / 2; }
  .weight-editor-row > :nth-child(2) { grid-column: 2 / 4; }
  .weight-value { grid-column: 1 / 3; }
}
</style>
