<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, BookOpenCheck, FileClock, GraduationCap, Search, X } from 'lucide-vue-next'
import PageState from '@/components/PageState.vue'
import { teacherApi } from '@/api/services'
import { toAppError } from '@/api/http'
import type { GradeHistoryEntry, HistoryCourse } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const offeringId = computed(() => {
  const value = route.params.offeringId
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
})
const mode = ref<'course' | 'changes'>('course')
const courses = ref<HistoryCourse[]>([])
const rows = ref<GradeHistoryEntry[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const query = reactive({ academicYear: '', gradeId: '', page: 1, size: 20 })
const catalogFilters = reactive({ keyword: '', academicYear: '', semester: '', page: 1, size: 10 })
const now = new Date().getFullYear()
const years = Array.from({ length: 10 }, (_, index) => `${now - index}-${now - index + 1}`)
const selectedCourse = ref<HistoryCourse | null>(null)
const catalogYears = computed(() => [...new Set([...years, ...courses.value.map((item) => item.academicYear)])]
  .sort((left, right) => right.localeCompare(left)))
const catalogTotal = ref(0)
let loadSequence = 0

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    if (!offeringId.value) {
      const result = await teacherApi.historyCourses({
        keyword: catalogFilters.keyword.trim() || undefined,
        academicYear: catalogFilters.academicYear || undefined,
        semester: catalogFilters.semester || undefined,
        page: catalogFilters.page,
        size: catalogFilters.size,
      })
      if (sequence !== loadSequence) return
      courses.value = result.items || []
      catalogTotal.value = result.total || 0
      selectedCourse.value = null
      rows.value = []
      total.value = 0
      return
    }
    const course = await teacherApi.historyCourse(offeringId.value)
    if (sequence !== loadSequence) return
    selectedCourse.value = course
    const result = mode.value === 'course'
      ? await teacherApi.courseHistory(offeringId.value, {
          courseId: course.courseId,
          academicYear: query.academicYear || undefined,
          page: query.page,
          size: query.size,
        })
      : await teacherApi.history(offeringId.value, {
          gradeId: query.gradeId.trim() || undefined,
          page: query.page,
          size: query.size,
        })
    if (sequence !== loadSequence) return
    rows.value = result.items || []
    total.value = result.total || 0
  } catch (cause) {
    if (sequence === loadSequence) {
      rows.value = []
      total.value = 0
      error.value = toAppError(cause).message
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function changeMode(value: string | number | boolean) {
  mode.value = String(value) as 'course' | 'changes'
  query.page = 1
  await load()
}

function openCourse(course: HistoryCourse) {
  router.push(`/teacher/history/${course.offeringId}`)
}

function resetCatalogFilters() {
  Object.assign(catalogFilters, { keyword: '', academicYear: '', semester: '', page: 1 })
  load()
}

function searchCatalog() {
  catalogFilters.page = 1
  load()
}

watch(() => [catalogFilters.keyword, catalogFilters.academicYear, catalogFilters.semester], () => {
  catalogFilters.page = 1
})

watch(offeringId, (current, previous) => {
  if (current !== previous) {
    mode.value = 'course'
    Object.assign(query, { academicYear: '', gradeId: '', page: 1 })
  }
  load()
}, { immediate: true })
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div>
        <button v-if="offeringId" class="back-link" type="button" @click="router.push('/teacher/history')"><ArrowLeft :size="16" />返回历史课程</button>
        <span v-else class="eyebrow">教师工作台</span>
        <h1>{{ selectedCourse?.courseName || '历年成绩' }}</h1>
        <p v-if="selectedCourse">{{ selectedCourse.courseCode }} · {{ selectedCourse.academicYear }} 第{{ selectedCourse.semester }}学期 · {{ selectedCourse.className || '未设置教学班' }}</p>
        <p v-else>选择已结课的授课班，查看同一课程的历年成绩与变更轨迹。</p>
      </div>
    </header>

    <template v-if="!offeringId">
      <section class="filter-bar catalog-filter" aria-label="历史课程筛选">
        <el-select v-model="catalogFilters.academicYear" clearable placeholder="全部学年" aria-label="学年">
          <el-option v-for="year in catalogYears" :key="year" :label="year" :value="year" />
        </el-select>
        <el-select v-model="catalogFilters.semester" clearable placeholder="全部学期" aria-label="学期">
          <el-option label="第一学期" value="1" /><el-option label="第二学期" value="2" />
        </el-select>
        <el-input v-model="catalogFilters.keyword" clearable placeholder="课程名称或课程号" :prefix-icon="Search" aria-label="课程名称或课程号" @keyup.enter="searchCatalog" />
        <el-button type="primary" :icon="Search" @click="searchCatalog">查询</el-button>
        <el-tooltip content="清空筛选"><el-button :icon="X" circle aria-label="清空筛选" @click="resetCatalogFilters" /></el-tooltip>
      </section>
      <section class="surface table-surface">
        <div class="section-heading"><div><BookOpenCheck :size="19" /><h2>历史课程</h2></div><span>共 {{ catalogTotal }} 门</span></div>
        <PageState :loading="loading" :error="error" :empty="!courses.length" :empty-title="catalogFilters.keyword || catalogFilters.academicYear || catalogFilters.semester ? '没有匹配的历史课程' : '暂无已结课课程'" @retry="load">
          <el-table :data="courses" stripe table-layout="fixed">
            <el-table-column prop="courseCode" label="课程号" min-width="120" />
            <el-table-column prop="courseName" label="课程名称" min-width="180"><template #default="scope"><strong>{{ scope.row.courseName }}</strong></template></el-table-column>
            <el-table-column label="开课信息" min-width="190"><template #default="scope">{{ scope.row.academicYear }} · 第{{ scope.row.semester }}学期</template></el-table-column>
            <el-table-column prop="className" label="教学班" min-width="140"><template #default="scope">{{ scope.row.className || '--' }}</template></el-table-column>
            <el-table-column label="状态" width="92"><el-tag type="info" effect="plain">已结课</el-tag></el-table-column>
            <el-table-column label="操作" width="105" fixed="right"><template #default="scope"><el-button link type="primary" :icon="ArrowRight" @click="openCourse(scope.row)">查看</el-button></template></el-table-column>
          </el-table>
          <div v-if="catalogTotal > catalogFilters.size" class="pagination-row catalog-pagination">
            <el-pagination v-model:current-page="catalogFilters.page" :page-size="catalogFilters.size" :total="catalogTotal" layout="total, prev, pager, next" @current-change="load" />
          </div>
        </PageState>
      </section>
    </template>

    <template v-else>
      <section class="mode-toolbar surface"><div><span>记录类型</span><el-segmented :model-value="mode" :options="[{ label: '历年成绩', value: 'course' }, { label: '变更记录', value: 'changes' }]" @change="changeMode" /></div></section>
      <section class="filter-bar history-filter">
        <el-select v-if="mode === 'course'" v-model="query.academicYear" clearable placeholder="全部学年" @change="query.page = 1; load()"><el-option v-for="year in years" :key="year" :label="year" :value="year" /></el-select>
        <el-input v-else v-model="query.gradeId" clearable placeholder="成绩记录编号" :prefix-icon="Search" @keyup.enter="query.page = 1; load()" />
        <el-button type="primary" :icon="Search" @click="query.page = 1; load()">查询</el-button>
      </section>
      <section class="surface table-surface">
        <div class="section-heading"><div><component :is="mode === 'course' ? GraduationCap : FileClock" :size="18" /><h2>{{ mode === 'course' ? '历年成绩' : '变更记录' }}</h2></div><span>共 {{ total }} 条</span></div>
        <PageState :loading="loading" :error="error" :empty="!rows.length" :empty-title="mode === 'course' ? '暂无历年成绩' : '暂无变更记录'" @retry="load">
          <el-table v-if="mode === 'course'" :data="rows" stripe>
            <el-table-column prop="academicYear" label="学年" min-width="130" /><el-table-column prop="semester" label="学期" width="80" align="center" />
            <el-table-column prop="studentNo" label="学号" min-width="130" /><el-table-column prop="studentName" label="姓名" min-width="100"><template #default="scope"><strong>{{ scope.row.studentName }}</strong></template></el-table-column>
            <el-table-column prop="regularScore" label="正考" width="90" align="center"><template #default="scope">{{ scope.row.regularScore ?? '--' }}</template></el-table-column>
            <el-table-column prop="makeupScore" label="补考卷面" width="105" align="center"><template #default="scope">{{ scope.row.makeupScore ?? '--' }}</template></el-table-column>
            <el-table-column prop="finalScore" label="最终成绩" width="100" align="center"><template #default="scope"><strong>{{ scope.row.finalScore ?? '--' }}</strong></template></el-table-column>
          </el-table>
          <el-table v-else :data="rows" stripe>
            <el-table-column prop="gradeId" label="成绩记录" min-width="190" /><el-table-column prop="action" label="动作" min-width="150"><template #default="scope"><strong>{{ scope.row.action }}</strong></template></el-table-column>
            <el-table-column prop="reason" label="原因" min-width="230"><template #default="scope">{{ scope.row.reason || '--' }}</template></el-table-column>
            <el-table-column prop="scope" label="范围" min-width="100" /><el-table-column prop="batchId" label="批次" min-width="170" />
            <el-table-column prop="actorId" label="操作人" min-width="120" /><el-table-column prop="createdAt" label="时间" min-width="180" />
          </el-table>
          <div v-if="total > query.size" class="pagination-row"><el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next" @current-change="load" /></div>
        </PageState>
      </section>
    </template>
  </div>
</template>

<style scoped>
@media (max-width: 680px) {
  .catalog-filter {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .catalog-filter > * {
    min-width: 0;
  }

  .catalog-filter :deep(.el-input) {
    grid-column: 1 / -1;
  }

  .catalog-filter :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }

  .catalog-pagination {
    justify-content: center;
    overflow: hidden;
    padding: 12px 8px;
  }

  .catalog-pagination :deep(.el-pager) {
    display: none;
  }
}
</style>
