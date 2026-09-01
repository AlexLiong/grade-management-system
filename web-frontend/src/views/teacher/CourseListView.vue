<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BarChart3, BookOpenCheck, ClipboardPen, History, Search, X } from 'lucide-vue-next'
import PageState from '@/components/PageState.vue'
import { teacherApi } from '@/api/services'
import { toAppError } from '@/api/http'
import type { Course, PageResult } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const canWriteGrades = computed(() => auth.hasAnyPermission([
  'GRADE_DRAFT_WRITE', 'GRADING_SCHEME_WRITE', 'GRADE_SUBMIT', 'GRADE_WITHDRAW',
]))
const loading = ref(false)
const error = ref('')
const courses = ref<Course[]>([])
const total = ref(0)
const filters = reactive({ academicYear: '', semester: '', name: '', page: 1, size: 10 })
const years = computed(() => {
  const today = new Date()
  const currentStartYear = today.getMonth() >= 7 ? today.getFullYear() : today.getFullYear() - 1
  return Array.from({ length: 6 }, (_, index) => {
    const startYear = currentStartYear - index
    return `${startYear}-${startYear + 1}`
  })
})

function statusType(status?: string) {
  return status === 'OPEN' ? 'success' : 'info'
}
function statusLabel(status?: string) {
  return status === 'OPEN' ? '进行中' : '已结课'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await teacherApi.courses({ ...filters, name: filters.name.trim() || undefined })
    if (Array.isArray(result)) {
      courses.value = result
      total.value = result.length
    } else {
      const page = result as PageResult<Course>
      courses.value = page.items || []
      total.value = page.total || 0
    }
  } catch (cause) {
    error.value = toAppError(cause).message
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(filters, { academicYear: '', semester: '', name: '', page: 1 })
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">教师工作台</span><h1>我的课程</h1><p>按学年学期或课程名称定位课程，进入成绩录入与分析。</p></div>
    </header>

    <section class="filter-bar" aria-label="课程筛选">
      <el-select v-model="filters.academicYear" clearable placeholder="全部学年" aria-label="学年" @change="filters.page = 1; load()">
        <el-option v-for="year in years" :key="year" :label="year" :value="year" />
      </el-select>
      <el-select v-model="filters.semester" clearable placeholder="全部学期" aria-label="学期" @change="filters.page = 1; load()">
        <el-option label="第一学期" value="1" /><el-option label="第二学期" value="2" />
      </el-select>
      <el-input v-model="filters.name" clearable placeholder="课程名称或课程号" :prefix-icon="Search" @keyup.enter="filters.page = 1; load()" />
      <el-button type="primary" :icon="Search" @click="filters.page = 1; load()">查询</el-button>
      <el-tooltip content="清空筛选"><el-button :icon="X" circle aria-label="清空筛选" @click="reset" /></el-tooltip>
    </section>

    <section class="surface table-surface">
      <div class="section-heading"><div><BookOpenCheck :size="19" /><h2>课程清单</h2></div><span>共 {{ total }} 门</span></div>
      <PageState :loading="loading" :error="error" :empty="!courses.length" empty-title="暂无授课课程" @retry="load">
        <el-table :data="courses" stripe table-layout="fixed">
          <el-table-column prop="code" label="课程号" min-width="120" />
          <el-table-column prop="name" label="课程名称" min-width="180"><template #default="scope"><strong>{{ scope.row.name }}</strong></template></el-table-column>
          <el-table-column label="开课信息" min-width="190"><template #default="scope">{{ scope.row.academicYear }} · 第{{ scope.row.semester }}学期</template></el-table-column>
          <el-table-column prop="className" label="教学班" min-width="140" />
          <el-table-column prop="studentCount" label="人数" width="82" align="center" />
          <el-table-column label="课程状态" width="102"><template #default="scope"><el-tag :type="statusType(scope.row.status)" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="285" fixed="right">
            <template #default="scope">
              <el-button v-if="auth.hasPermission(['COURSE_READ', 'GRADE_READ'])" :type="scope.row.status === 'OPEN' && canWriteGrades ? 'primary' : undefined" link :icon="scope.row.status === 'OPEN' && canWriteGrades ? ClipboardPen : BookOpenCheck" @click="router.push(`/teacher/courses/${scope.row.id}/grades`)">{{ scope.row.status === 'OPEN' && canWriteGrades ? '录入' : '查看' }}</el-button>
              <el-button v-if="scope.row.status === 'OPEN' && auth.hasPermission(['COURSE_READ', 'GRADE_READ', 'GRADE_ANALYTICS_READ'])" link :icon="BarChart3" @click="router.push(`/teacher/courses/${scope.row.id}/analysis`)">分析</el-button>
              <el-button v-if="scope.row.status === 'CLOSED' && auth.hasPermission('GRADE_HISTORY_READ')" link :icon="History" @click="router.push(`/teacher/history/${scope.row.id}`)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="total > filters.size" class="pagination-row">
          <el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" :total="total" layout="total, prev, pager, next" @current-change="load" />
        </div>
      </PageState>
    </section>
  </div>
</template>
