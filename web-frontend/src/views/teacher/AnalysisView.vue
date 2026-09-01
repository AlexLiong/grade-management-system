<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Bot, FilePenLine, Printer, RefreshCw, Save, ShieldAlert } from 'lucide-vue-next'
import DecisionTreeNode from '@/components/DecisionTreeNode.vue'
import MetricStrip from '@/components/MetricStrip.vue'
import PageState from '@/components/PageState.vue'
import { teacherApi } from '@/api/services'
import { toAppError } from '@/api/http'
import type { DecisionNode, GradeStatistics, Prediction } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const courseId = computed(() => String(route.params.courseId))
const loading = ref(false)
const error = ref('')
const statistics = ref<GradeStatistics | null>(null)
const analysisText = ref('')
const saving = ref(false)
const predicting = ref(false)
const predictions = ref<Prediction[]>([])
const decisionTree = ref<DecisionNode | null>(null)
const maxDistribution = computed(() => Math.max(1, ...(statistics.value?.distribution || []).map((item) => item.count)))
const metrics = computed(() => {
  const item = statistics.value
  return [
    { label: '参考人数', value: item?.count || 0, detail: '已录入成绩' },
    { label: '平均分', value: item?.average?.toFixed(1) || '--', tone: 'default' as const },
    { label: '最高 / 最低', value: item ? `${item.highest} / ${item.lowest}` : '--' },
    { label: '及格率', value: item ? `${item.passRate.toFixed(1)}%` : '--', tone: 'success' as const },
    { label: '优秀率', value: item ? `${item.excellentRate.toFixed(1)}%` : '--', tone: 'warning' as const },
  ]
})

function riskType(level: Prediction['riskLevel']) {
  return level === 'HIGH' ? 'danger' : level === 'MEDIUM' ? 'warning' : 'success'
}
function riskLabel(level: Prediction['riskLevel']) {
  return level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '需关注' : '低风险'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    statistics.value = await teacherApi.statistics(courseId.value)
    analysisText.value = statistics.value.analysis || ''
  } catch (cause) { error.value = toAppError(cause).message }
  finally { loading.value = false }
}

async function saveAnalysis() {
  if (!analysisText.value.trim()) { ElMessage.warning('请输入成绩分析结论'); return }
  saving.value = true
  try {
    statistics.value = await teacherApi.saveAnalysis(courseId.value, analysisText.value.trim())
    analysisText.value = statistics.value.analysis || analysisText.value
    ElMessage.success('成绩分析已保存')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { saving.value = false }
}

async function generatePrediction() {
  predicting.value = true
  predictions.value = []
  decisionTree.value = null
  try {
    const result = await teacherApi.predictions(courseId.value)
    predictions.value = result.predictions || []
    decisionTree.value = result.decisionTree || null
    if (!predictions.value.length) ElMessage.info('当前历史样本不足，暂时无法生成预测')
  } catch (cause) { ElMessage.error(toAppError(cause).message) }
  finally { predicting.value = false }
}
function printPage() { window.print() }

onMounted(load)
onBeforeUnmount(() => { predictions.value = []; decisionTree.value = null })
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><button class="back-link" type="button" @click="router.push('/teacher/courses')"><ArrowLeft :size="16" />返回课程</button><h1>成绩统计与预警</h1><p>统计结果来源于已录入成绩；预测只作教学参考，不写入正式成绩。</p></div>
      <div class="page-actions"><el-button :icon="Printer" class="no-print" @click="printPage">打印</el-button><el-button v-if="auth.hasPermission('RISK_ANALYZE')" type="primary" :icon="Bot" :loading="predicting" class="no-print" @click="generatePrediction">生成预警</el-button></div>
    </header>

    <PageState :loading="loading" :error="error" :empty="!statistics" empty-title="暂无统计数据" @retry="load">
      <div class="page-stack">
        <MetricStrip :items="metrics" />
        <div class="analysis-grid">
          <section class="surface distribution-panel">
            <div class="section-heading"><div><FilePenLine :size="18" /><h2>成绩分布</h2></div><span>百分制</span></div>
            <div v-if="statistics?.distribution?.length" class="bar-chart" role="img" aria-label="成绩分数段分布">
              <div v-for="item in statistics.distribution" :key="item.label" class="bar-column">
                <span>{{ item.count }}</span><div class="bar-track"><div class="bar-fill" :style="{ height: `${Math.max(5, item.count / maxDistribution * 100)}%` }" /></div><small>{{ item.label }}</small>
              </div>
            </div>
            <div v-else class="compact-empty">暂无分布数据</div>
          </section>
          <section class="surface analysis-editor">
            <div class="section-heading"><div><FilePenLine :size="18" /><h2>成绩分析表</h2></div><span>教师填写</span></div>
            <div class="editor-body"><el-input v-model="analysisText" type="textarea" :rows="10" maxlength="3000" show-word-limit placeholder="结合试题难度、知识点掌握、分数分布和改进措施填写分析结论" /><div class="editor-actions"><el-button v-if="auth.hasPermission('GRADE_ANALYTICS_READ')" type="primary" :icon="Save" :loading="saving" @click="saveAnalysis">保存分析</el-button></div></div>
          </section>
        </div>

        <section v-if="auth.hasPermission('RISK_ANALYZE')" class="surface prediction-panel">
          <div class="section-heading"><div><Bot :size="18" /><h2>学业预测</h2></div><span>临时结果</span></div>
          <el-alert title="预测结果仅当前教师可见，离开页面后清除；它不是正式成绩。" type="info" show-icon :closable="false" />
          <div v-if="predicting" class="prediction-loading"><RefreshCw :size="22" class="spin" />模型正在分析历史样本</div>
          <el-table v-else-if="predictions.length" :data="predictions" stripe>
            <el-table-column prop="studentNo" label="学号" min-width="125" /><el-table-column prop="studentName" label="姓名" min-width="100" />
            <el-table-column label="预测期末" width="105"><template #default="scope"><strong>{{ scope.row.predictedFinal.toFixed(1) }}</strong></template></el-table-column>
            <el-table-column label="预测区间" width="135"><template #default="scope">{{ scope.row.lowerBound.toFixed(1) }} - {{ scope.row.upperBound.toFixed(1) }}</template></el-table-column>
            <el-table-column label="风险" width="100"><template #default="scope"><el-tag :type="riskType(scope.row.riskLevel)" effect="plain">{{ riskLabel(scope.row.riskLevel) }}</el-tag></template></el-table-column>
            <el-table-column label="主要因素" min-width="220"><template #default="scope">{{ scope.row.factors?.join('、') || '历史平时与实验成绩' }}</template></el-table-column>
          </el-table>
          <div v-else class="compact-empty"><ShieldAlert :size="28" /><span>点击“生成预警”后显示本次分析结果</span></div>
        </section>

        <section v-if="auth.hasPermission('RISK_ANALYZE') && decisionTree" class="surface tree-panel">
          <div class="section-heading"><div><Bot :size="18" /><h2>决策树规则</h2></div><span>模型结构</span></div>
          <div class="tree-scroll"><DecisionTreeNode :node="decisionTree" /></div>
        </section>
      </div>
    </PageState>
  </div>
</template>
