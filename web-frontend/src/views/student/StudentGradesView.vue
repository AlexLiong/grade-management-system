<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Activity, AlertTriangle, BellRing, BookOpenCheck, Printer, RefreshCw, ShieldCheck, Trophy } from 'lucide-vue-next'
import MetricStrip from '@/components/MetricStrip.vue'
import PageState from '@/components/PageState.vue'
import { studentApi } from '@/api/services'
import { toAppError } from '@/api/http'
import { displayedCourseScore } from '@/utils/grade'
import type { RiskAssessment, StudentCourse, StudentGrade, WarningItem } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'

const scope = ref<'current' | 'all'>('current')
const auth = useAuthStore()
const grades = ref<StudentGrade[]>([])
const warnings = ref<WarningItem[]>([])
const warningsLoading = ref(false)
const warningsError = ref('')
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(15)
const total = ref(0)
const ranking = ref<{ rank: number; totalStudents: number; average: number; percentile?: number } | null>(null)
const weightedAverage = ref(0)
const overviewCredits = ref(0)
const overviewFailed = ref(0)
const riskLoading = ref(false)
const generatedAt = ref('')
const riskDialog = ref(false)
const riskResult = ref<RiskAssessment | null>(null)
const riskForm = reactive({ courseId: '', usualScore: 80, labScore: 80 })
const riskCourses = ref<StudentCourse[]>([])
const riskCoursesLoading = ref(false)
const riskCoursesError = ref('')

const metrics = computed(() => [
  { label: '加权平均', value: weightedAverage.value.toFixed(1), detail: scope.value === 'current' ? '本学期' : '在校课程' },
  { label: '课程排名', value: ranking.value ? `${ranking.value.rank} / ${ranking.value.totalStudents}` : '--', detail: grades.value[0]?.courseName, tone: 'success' as const },
  { label: '已获学分', value: overviewCredits.value.toFixed(1), detail: scope.value === 'current' ? '本学期' : '累计' },
  { label: '未通过课程', value: overviewFailed.value, detail: scope.value === 'current' ? '本学期' : '累计', tone: overviewFailed.value ? 'danger' as const : 'success' as const },
])

function warningType(level: WarningItem['level']) {
  return level === 'HIGH' ? 'error' : level === 'MEDIUM' ? 'warning' : 'info'
}

async function loadGrades() {
  loading.value = true
  error.value = ''
  try {
    const result = await studentApi.grades(scope.value, { page: page.value, size: size.value })
    grades.value = result.items || []
    total.value = result.total || 0
    weightedAverage.value = result.weightedAverage
    overviewCredits.value = result.earnedCredits
    overviewFailed.value = result.failedCourses
    const rankingResults = await Promise.allSettled(
      grades.value.map((grade) => grade.offeringId ? studentApi.ranking(grade.offeringId) : Promise.reject(new Error('无开课编号'))),
    )
    rankingResults.forEach((item, index) => {
      if (item.status !== 'fulfilled') return
      const grade = grades.value[index]
      if (grade) { grade.rank = item.value.rank; grade.totalStudents = item.value.totalStudents }
    })
    ranking.value = rankingResults.find((item) => item.status === 'fulfilled')?.value || null
  } catch (cause) { error.value = toAppError(cause).message }
  finally { loading.value = false }
}

async function loadOverview() {
  warningsLoading.value = true
  warningsError.value = ''
  try {
    warnings.value = await studentApi.warnings()
  } catch (cause) {
    warningsError.value = toAppError(cause).message
  } finally {
    warningsLoading.value = false
  }
}

async function changeScope(value: string | number) {
  scope.value = String(value) as 'current' | 'all'
  page.value = 1
  await loadGrades()
}

async function loadRiskCourses() {
  riskCoursesLoading.value = true
  riskCoursesError.value = ''
  try {
    riskCourses.value = await studentApi.courses()
  } catch (cause) {
    riskCoursesError.value = toAppError(cause).message
  } finally {
    riskCoursesLoading.value = false
  }
}

async function openRiskDialog() {
  riskResult.value = null
  riskDialog.value = true
  if (!riskCourses.value.length) await loadRiskCourses()
  if (!riskForm.courseId && riskCourses.value.length) {
    riskForm.courseId = riskCourses.value[0]?.id || ''
  }
}

async function generateRisk() {
  if (!riskForm.courseId) { ElMessage.warning('请选择课程'); return }
  riskLoading.value = true
  try {
    const result = await studentApi.risk({ ...riskForm })
    riskResult.value = result
    const course = riskCourses.value.find((item) => item.id === result.courseId)
    const warning: WarningItem = {
      id: `risk:${result.courseId}`, courseName: course?.name || '所选课程', level: result.level,
      message: `模型预测成绩 ${result.predictedScore.toFixed(1)}，挂科概率 ${result.failureProbability.toFixed(1)}%`,
      predictedScore: result.predictedScore,
    }
    warnings.value = [warning, ...warnings.value.filter((item) => item.id !== warning.id)]
    generatedAt.value = result.generatedAt
    ElMessage.success('学业风险分析已更新')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { riskLoading.value = false }
}

function printPage() { window.print() }
onMounted(() => { loadGrades(); loadOverview() })
onBeforeUnmount(() => { generatedAt.value = '' })
</script>

<template>
  <div class="page-stack student-page">
    <header class="page-header">
      <div><span class="eyebrow">学生工作台</span><h1>我的成绩</h1><p>查看本人课程成绩、排名和学业预警。</p></div>
      <div class="page-actions"><el-button :icon="Printer" class="no-print" @click="printPage">打印成绩单</el-button><el-button v-if="auth.hasPermission('RISK_SELF_ANALYZE')" type="primary" :icon="RefreshCw" class="no-print" @click="openRiskDialog">生成学业预警</el-button></div>
    </header>

    <MetricStrip :items="metrics" />

    <section v-if="warningsLoading" class="clear-band"><RefreshCw :size="18" /><div><strong>正在加载学业提醒</strong></div></section>
    <section v-else-if="warningsError" class="warning-band"><el-alert title="学业提醒加载失败" :description="warningsError" type="error" show-icon :closable="false" /></section>
    <section v-else-if="warnings.length" class="warning-band">
      <div class="warning-heading"><BellRing :size="18" /><strong>学业提醒</strong><span v-if="generatedAt">更新于 {{ generatedAt }}</span></div>
      <div class="warning-list">
        <el-alert v-for="item in warnings" :key="item.id" :title="item.courseName" :description="item.message" :type="warningType(item.level)" show-icon :closable="false" />
      </div>
    </section>
    <section v-else class="clear-band"><ShieldCheck :size="18" /><div><strong>当前没有学业预警</strong><span>请继续保持稳定的学习状态</span></div></section>

    <section class="surface table-surface">
      <div class="grades-toolbar">
        <div><BookOpenCheck :size="18" /><h2>课程成绩</h2></div>
        <el-segmented :model-value="scope" :options="[{ label: '本学期', value: 'current' }, { label: '全部成绩', value: 'all' }]" class="no-print" @change="changeScope" />
      </div>
      <PageState :loading="loading" :error="error" :empty="!grades.length" empty-title="暂无成绩" empty-description="课程成绩提交后会在这里显示" @retry="loadGrades">
        <el-table :data="grades" stripe>
          <el-table-column prop="courseCode" label="课程号" min-width="120" />
          <el-table-column prop="courseName" label="课程名称" min-width="180"><template #default="scope"><strong>{{ scope.row.courseName }}</strong></template></el-table-column>
          <el-table-column label="学年学期" min-width="180"><template #default="scope">{{ scope.row.academicYear }} · 第{{ scope.row.semester }}学期</template></el-table-column>
          <el-table-column prop="credits" label="学分" width="75" align="center" />
          <el-table-column prop="regularScore" label="正考" width="80" align="center"><template #default="scope">{{ scope.row.regularScore ?? '--' }}</template></el-table-column>
          <el-table-column label="补考卷面" width="100" align="center"><template #default="scope">{{ scope.row.makeupScore ?? '--' }}</template></el-table-column>
          <el-table-column label="成绩显示" width="115" align="center"><template #default="scope"><strong :class="{ 'score-fail': !scope.row.passed }">{{ scope.row.displayScore || displayedCourseScore(scope.row.regularScore ?? scope.row.finalScore, scope.row.makeupScore) }}</strong></template></el-table-column>
          <el-table-column label="结果" width="90"><template #default="scope"><el-tag :type="scope.row.passed ? 'success' : 'danger'" effect="plain">{{ scope.row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column>
          <el-table-column label="课程排名" width="110" align="center"><template #default="scope"><span v-if="scope.row.rank"><Trophy :size="14" class="inline-icon" />{{ scope.row.rank }}/{{ scope.row.totalStudents }}</span><span v-else>--</span></template></el-table-column>
        </el-table>
        <div v-if="total > size" class="pagination-row no-print"><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,15,30]" layout="total, sizes, prev, pager, next" @change="loadGrades" /></div>
      </PageState>
    </section>

    <el-alert class="score-note" type="info" :closable="false" show-icon><template #title><span><AlertTriangle :size="14" class="inline-icon" />成绩按“正考/补考”显示；补考卷面超过 60 分时，计入成绩按 60 分显示。</span></template></el-alert>

    <el-dialog v-model="riskDialog" title="学业风险分析" width="min(580px, 94vw)" destroy-on-close class="no-print">
      <el-form label-position="top" class="risk-form">
        <el-form-item label="课程" required><el-select v-model="riskForm.courseId" filterable :loading="riskCoursesLoading" :disabled="riskCoursesLoading || !riskCourses.length" placeholder="选择本人已修读课程"><el-option v-for="item in riskCourses" :key="item.id" :label="`${item.code} ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="当前平时成绩" required><el-input-number v-model="riskForm.usualScore" :min="0" :max="100" :precision="1" /></el-form-item>
        <el-form-item label="当前实验成绩" required><el-input-number v-model="riskForm.labScore" :min="0" :max="100" :precision="1" /></el-form-item>
      </el-form>
      <el-alert v-if="riskCoursesError" title="课程列表加载失败" :description="riskCoursesError" type="error" show-icon :closable="false" />
      <el-alert v-else-if="!riskCoursesLoading && !riskCourses.length" title="当前没有可分析的已选课程" type="warning" show-icon :closable="false" />
      <section v-if="riskResult" class="risk-result">
        <div class="risk-result-heading"><Activity :size="20" /><strong>预测 {{ riskResult.predictedScore.toFixed(1) }} 分</strong><el-tag :type="riskResult.level === 'HIGH' ? 'danger' : riskResult.level === 'MEDIUM' ? 'warning' : 'success'" effect="plain">{{ riskResult.level === 'HIGH' ? '高风险' : riskResult.level === 'MEDIUM' ? '中风险' : '低风险' }}</el-tag></div>
        <dl><dt>挂科概率</dt><dd>{{ riskResult.failureProbability.toFixed(1) }}%</dd><dt>历史样本</dt><dd>{{ riskResult.sampleYears }} 个学年</dd></dl>
        <ul v-if="riskResult.linearRegressionExplanation.length || riskResult.decisionPath.length"><li v-for="item in [...riskResult.linearRegressionExplanation, ...riskResult.decisionPath]" :key="item">{{ item }}</li></ul>
      </section>
      <template #footer><el-button @click="riskDialog = false">关闭</el-button><el-button type="primary" :icon="Activity" :loading="riskLoading" :disabled="riskCoursesLoading || !riskForm.courseId" @click="generateRisk">开始分析</el-button></template>
    </el-dialog>
  </div>
</template>
