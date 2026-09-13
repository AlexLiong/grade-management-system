<script setup>
import { ref, computed, onMounted, watch, nextTick } from "vue";
import {
  GraduationCap,
  LayoutList,
  ChartColumn,
  ShieldCheck,
  Users,
  BookOpen,
  LogOut,
  Search,
  Printer,
  Save,
  Send,
  Undo2,
  Trash2,
  Settings2,
  ChevronLeft,
  ChevronRight,
  Plus,
  X,
  ScanText,
  RefreshCw,
  KeyRound,
  CheckCircle2,
  AlertTriangle,
  FileCheck,
  ClipboardList,
} from "lucide-vue-next";
import { api } from "./api";

const user = ref(null),
  boot = ref(true),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const username = ref(""),
  password = ref(""),
  page = ref("grades"),
  courses = ref([]),
  courseId = ref(""),
  term = ref(""),
  search = ref("");
const roster = ref([]),
  grades = ref([]),
  statistics = ref(null),
  prediction = ref(null),
  transcript = ref([]),
  users = ref([]),
  ledger = ref(null),
  integrity = ref(null),
  classification = ref(null);
const modal = ref(""),
  weights = ref({}),
  analysis = ref(""),
  userForm = ref({}),
  courseForm = ref({}),
  enrollStudent = ref(""),
  reason = ref(""),
  confirmation = ref(""),
  transitionAction = ref(""),
  dirty = ref(false),
  tablePage = ref(1),
  ocrText = ref(""),
  ocrProgress = ref(0),
  oldPassword = ref(""),
  newPassword = ref(""),
  printing = ref(false);
const components = [
  ["regular", "平时"],
  ["attendance", "考勤"],
  ["homework", "作业"],
  ["lab", "实验"],
  ["midterm", "期中"],
  ["finalExam", "期末"],
];
const roles = { TEACHER: "教师", STUDENT: "学生", ADMIN: "管理员" };
const permissionNames = {
  QUERY: "查询",
  ENTRY: "成绩录入",
  MAINTAIN: "成绩维护",
  PREDICT: "学业预测",
  GRADE_ADMIN: "成绩管理",
  USER_ADMIN: "人员与权限",
  AUDIT: "安全审计",
};
const rolePermissions = {
  TEACHER: ["QUERY", "ENTRY", "MAINTAIN", "PREDICT"],
  STUDENT: ["QUERY", "PREDICT"],
  ADMIN: ["GRADE_ADMIN", "USER_ADMIN", "AUDIT"],
};
const perms = computed(() =>
  Array.isArray(user.value?.permissions)
    ? user.value.permissions
    : (user.value?.permissions || "").split(","),
);
const can = (p) => perms.value.includes(p);
const isTeacher = computed(() => user.value?.role === "TEACHER");
const isStudent = computed(() => user.value?.role === "STUDENT");
const isAdmin = computed(() => user.value?.role === "ADMIN");
const selected = computed(() =>
  courses.value.find((c) => c.id === courseId.value),
);
const currentWeights = computed(() =>
  selected.value ? JSON.parse(selected.value.weights) : {},
);
const activeComponents = computed(() =>
  components.filter(([key]) => Number(currentWeights.value[key]) > 0),
);
const visibleCourses = computed(() =>
  courses.value.filter(
    (c) =>
      (!term.value || c.term === term.value) && c.name.includes(search.value),
  ),
);
const terms = computed(() =>
  [...new Set(courses.value.map((c) => c.term))].sort().reverse(),
);
const rows = computed(() =>
  roster.value.map((s) => ({
    ...s,
    grade: grades.value.find((g) => g.student_id === s.id),
  })),
);
const pagedRows = computed(() =>
  printing.value
    ? rows.value
    : rows.value.slice((tablePage.value - 1) * 10, tablePage.value * 10),
);
const submitted = computed(
  () =>
    grades.value.length > 0 &&
    grades.value.every((g) => g.state === "SUBMITTED"),
);
const completed = computed(
  () => grades.value.filter((g) => total(g.scores) !== null).length,
);
const gradeMean = computed(() => {
  const values = grades.value
    .map((g) => total(g.scores))
    .filter((v) => v !== null);
  return values.length
    ? (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1)
    : "--";
});
const nav = computed(() => [
  ...((isTeacher.value && can("QUERY")) || (isAdmin.value && can("GRADE_ADMIN"))
    ? [
        { key: "grades", name: "课程成绩", icon: LayoutList },
        { key: "analysis", name: "统计分析", icon: ChartColumn },
      ]
    : []),
  ...(isStudent.value && can("QUERY")
    ? [{ key: "transcript", name: "我的成绩", icon: ClipboardList }]
    : []),
  ...(isStudent.value && can("PREDICT")
    ? [{ key: "prediction", name: "学业预警", icon: ChartColumn }]
    : []),
  ...(isAdmin.value && can("GRADE_ADMIN")
    ? [{ key: "courses", name: "课程与选课", icon: BookOpen }]
    : []),
  ...(isAdmin.value && can("USER_ADMIN")
    ? [{ key: "users", name: "人员与权限", icon: Users }]
    : []),
  ...(isAdmin.value && can("AUDIT")
    ? [{ key: "audit", name: "安全审计", icon: ShieldCheck }]
    : []),
]);
const pageName = computed(
  () => nav.value.find((n) => n.key === page.value)?.name || "账户设置",
);
function total(scores = {}) {
  let sum = 0;
  for (const [key] of activeComponents.value) {
    if (scores[key] === null || scores[key] === undefined || scores[key] === "")
      return null;
    sum += (Number(scores[key]) * Number(currentWeights.value[key])) / 100;
  }
  return Math.round(sum * 100) / 100;
}
function effective(scores = {}) {
  const t = total(scores);
  if (t === null) return null;
  const m = scores.makeup;
  if (m === null || m === undefined || m === "") return t;
  return Math.max(t, Math.min(60, Number(m)));
}
function display(value) {
  return value === null || value === undefined
    ? "--"
    : Number(value).toFixed(1);
}
async function run(fn, message = "") {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await fn();
    if (message) notice.value = message;
  } catch (e) {
    error.value = e.message;
    if (e.status === 401) user.value = null;
  } finally {
    busy.value = false;
  }
}
async function loadCourses() {
  if (can("QUERY") || can("GRADE_ADMIN") || can("AUDIT")) {
    courses.value = (await api("/courses?size=300")).items;
    if (!courses.value.some((c) => c.id === courseId.value))
      courseId.value = courses.value[0]?.id || "";
  }
}
async function loadCourse() {
  prediction.value = null;
  tablePage.value = 1;
  dirty.value = false;
  if (!selected.value) return;
  if (isStudent.value) return;
  const result = await Promise.all([
    api("/roster?courseId=" + courseId.value),
    api("/grades?courseId=" + courseId.value + "&size=300"),
    api("/statistics?courseId=" + courseId.value),
  ]);
  roster.value = result[0];
  grades.value = result[1].items;
  statistics.value = result[2];
  analysis.value = result[2].analysis.content;
}
async function loadPage() {
  if (page.value === "users") {
    users.value = (await api("/users?size=300")).items;
    return;
  }
  if (page.value === "audit") {
    ledger.value = await api("/audit");
    return;
  }
  if (page.value === "transcript") {
    transcript.value = await api("/transcript");
    return;
  }
  if (page.value === "courses") {
    if (can("USER_ADMIN")) users.value = (await api("/users?size=300")).items;
    return;
  }
  await loadCourse();
}
async function initialize() {
  await loadCourses();
  page.value = nav.value[0]?.key || "account";
  await loadPage();
}
async function login() {
  await run(async () => {
    const result = await api("/login", {
      username: username.value,
      password: password.value,
    });
    user.value = result.user;
    password.value = "";
    await initialize();
  });
}
async function navigate(key) {
  if (dirty.value && !window.confirm("尚有未保存的成绩，确认离开？")) return;
  page.value = key;
  dirty.value = false;
  await run(loadPage);
}
async function selectCourse() {
  await run(loadCourse);
}
function score(row, key, event) {
  let grade = grades.value.find((g) => g.student_id === row.id);
  if (!grade) {
    grade = { student_id: row.id, state: "DRAFT", scores: {}, version: null };
    grades.value.push(grade);
  }
  grade.scores[key] =
    event.target.value === "" ? null : Number(event.target.value);
  dirty.value = true;
}
async function saveGrades() {
  await run(async () => {
    const data = grades.value.filter((g) => g.state === "DRAFT");
    await api("/grades/save", {
      courseId: courseId.value,
      courseVersion: Number(selected.value.version),
      grades: data.map((g) => ({
        studentId: g.student_id,
        version: g.version === null ? null : Number(g.version),
        scores: g.scores,
      })),
    });
    await loadCourses();
    await loadCourse();
  }, "成绩已暂存");
}
function askTransition(action) {
  transitionAction.value = action;
  reason.value = "";
  confirmation.value = "";
  modal.value = "transition";
}
async function transition() {
  await run(async () => {
    await api("/grades/transition", {
      courseId: courseId.value,
      courseVersion: Number(selected.value.version),
      action: transitionAction.value,
      reason: reason.value,
      confirmation: confirmation.value,
    });
    modal.value = "";
    await loadCourses();
    await loadCourse();
  }, "成绩状态已更新");
}
async function saveWeights() {
  await run(async () => {
    await api("/weights", {
      courseId: courseId.value,
      version: Number(selected.value.version),
      weights: weights.value,
    });
    modal.value = "";
    await loadCourses();
    await loadCourse();
  }, "成绩系数已更新");
}
async function saveAnalysis() {
  await run(async () => {
    await api("/analysis", {
      courseId: courseId.value,
      content: analysis.value,
      version: Number(statistics.value.analysis.version),
    });
    await loadCourse();
  }, "分析已保存");
}
async function predict() {
  await run(async () => {
    prediction.value = await api("/predict", { courseId: courseId.value });
  });
}
function editUser(u) {
  userForm.value = u
    ? {
        ...u,
        permissions: u.permissions.split(","),
        enabled: Number(u.enabled),
        password: "",
      }
    : {
        username: "",
        name: "",
        role: "STUDENT",
        permissions: ["QUERY", "PREDICT"],
        department: "信息工程学院",
        enabled: 1,
        password: "",
      };
  modal.value = "user";
}
async function saveUser() {
  await run(async () => {
    await api("/users/save", userForm.value);
    modal.value = "";
    users.value = (await api("/users?size=300")).items;
  }, "人员信息已保存");
}
function editCourse(c) {
  courseForm.value = c
    ? { ...c, teacherId: c.teacher_id }
    : { code: "", name: "", term: "2026-1", teacherId: "", credits: 3 };
  modal.value = "course";
}
async function saveCourse() {
  await run(async () => {
    await api("/courses/save", courseForm.value);
    modal.value = "";
    await loadCourses();
  }, "课程已保存");
}
async function enrollment(remove = false, studentId = enrollStudent.value) {
  await run(
    async () => {
      await api("/enrollments", {
        courseId: courseId.value,
        studentId,
        remove,
      });
      await loadCourses();
      roster.value = await api("/roster?courseId=" + courseId.value);
    },
    remove ? "已退选" : "已选课",
  );
}
async function openEnrollment(c) {
  courseId.value = c.id;
  await run(async () => {
    roster.value = await api("/roster?courseId=" + c.id);
    modal.value = "enroll";
  });
}
async function recognize(event) {
  const file = event.target.files[0];
  if (!file) return;
  await run(async () => {
    if (file.size > 10 * 1024 * 1024) throw new Error("图片不能超过 10 MB");
    const { createWorker } = await import("tesseract.js");
    let worker;
    const url = URL.createObjectURL(file);
    try {
      worker = await createWorker("eng", 1, {
        workerPath: "/ocr/worker.min.js",
        corePath: "/ocr/core",
        langPath: "/ocr/lang",
        logger: (m) => {
          if (m.status === "recognizing text")
            ocrProgress.value = Math.round(m.progress * 100);
        },
      });
      ocrText.value = (await worker.recognize(url)).data.text;
    } finally {
      if (worker) await worker.terminate();
      URL.revokeObjectURL(url);
      event.target.value = "";
    }
  });
}
function applyOcr() {
  error.value = "";
  try {
    let count = 0;
    const changes = [];
    for (const line of ocrText.value.trim().split("\n")) {
      if (!line.trim()) continue;
      const cells = line.trim().split(/[,，\s]+/);
      const row = roster.value.find((r) => r.username === cells[0]);
      if (!row) throw new Error("无法匹配学号：" + cells[0]);
      if (cells.length !== activeComponents.value.length + 1)
        throw new Error("成绩列数不匹配：" + cells[0]);
      const values = cells.slice(1).map(Number);
      if (values.some((n) => !Number.isFinite(n) || n < 0 || n > 100))
        throw new Error("识别分数无效：" + cells[0]);
      changes.push([row, values]);
    }
    for (const [row, values] of changes) {
      values.forEach((v, i) =>
        score(row, activeComponents.value[i][0], {
          target: { value: String(v) },
        }),
      );
      count++;
    }
    modal.value = "";
    notice.value = `已填入 ${count} 人成绩，待暂存`;
    ocrText.value = "";
  } catch (e) {
    error.value = e.message;
  }
}
async function review() {
  await run(async () => {
    await api("/audit/review", {
      resource: confirmation.value,
      comment: reason.value,
    });
    modal.value = "";
    ledger.value = await api("/audit");
  }, "复核意见已记录");
}
async function print() {
  printing.value = true;
  await nextTick();
  window.print();
  printing.value = false;
}
watch([term, search], async () => {
  if (
    !["grades", "analysis", "prediction"].includes(page.value) ||
    busy.value ||
    dirty.value
  )
    return;
  if (
    visibleCourses.value.length &&
    !visibleCourses.value.some((c) => c.id === courseId.value)
  ) {
    courseId.value = visibleCourses.value[0].id;
    await run(loadCourse);
  }
});
watch(modal, (value, previous) => {
  if (previous === "ocr" && !value) ocrText.value = "";
});
onMounted(async () => {
  try {
    user.value = await api("/me");
    await initialize();
  } catch (e) {
    if (e.status !== 401) error.value = e.message;
  } finally {
    boot.value = false;
  }
});
</script>

<template>
  <div v-if="boot" class="boot">知序 · 正在连接</div>
  <main v-else-if="!user" class="login-page">
    <div class="login-brand">
      <GraduationCap :size="34" /><span>知序<small>高校成绩管理</small></span>
    </div>
    <form class="login-form" @submit.prevent="login">
      <span class="eyebrow">CAMPUS / ACADEMIC RECORDS</span>
      <h1>登录教务工作台</h1>
      <p class="muted">2026 — 2027 学年</p>
      <label
        >账号<input
          v-model="username"
          autocomplete="username"
          required
          placeholder="请输入账号"
      /></label>
      <label
        >密码<input
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          placeholder="请输入密码"
      /></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary login-submit" :disabled="busy">
        {{ busy ? "正在登录…" : "登录" }}<ChevronRight :size="18" />
      </button>
      <div class="login-secure">
        <ShieldCheck :size="16" />安全连接 · 身份验证
      </div>
    </form>
    <div class="login-caption">信息工程学院<span>ACADEMIC AFFAIRS</span></div>
  </main>
  <div v-else class="app-shell">
    <aside class="sidebar no-print">
      <div class="brand">
        <GraduationCap :size="28" /><span>知序<small>高校成绩管理</small></span>
      </div>
      <div class="workspace-label">教务工作台</div>
      <nav>
        <button
          v-for="item in nav"
          :key="item.key"
          :class="{ active: page === item.key }"
          @click="navigate(item.key)"
        >
          <component :is="item.icon" :size="18" />{{ item.name }}
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="security-stamp">
          <ShieldCheck :size="17" />HTTPS 安全连接
        </div>
        <button class="identity" @click="navigate('account')">
          <span class="avatar">{{ user.name.slice(0, 1) }}</span
          ><span
            >{{ user.name }}<small>{{ roles[user.role] }}</small></span
          ><Settings2 :size="16" />
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar no-print">
        <span>信息工程学院 <span class="slash">/</span> {{ pageName }}</span>
        <div>
          <span class="role-label">{{ roles[user.role] }}</span
          ><button
            class="icon-button"
            title="退出登录"
            aria-label="退出登录"
            @click="
              run(async () => {
                await api('/logout', {});
                user = null;
              })
            "
          >
            <LogOut :size="18" />
          </button>
        </div>
      </header>
      <main class="workspace">
        <div class="heading">
          <div>
            <span class="eyebrow">{{
              isStudent ? "MY ACADEMICS" : "ACADEMIC WORKSPACE"
            }}</span>
            <h1>{{ pageName }}</h1>
          </div>
          <div class="heading-actions no-print">
            <button
              v-if="['grades', 'analysis', 'transcript'].includes(page)"
              class="secondary"
              @click="print"
            >
              <Printer :size="16" />打印</button
            ><button
              class="icon-button"
              title="刷新"
              aria-label="刷新"
              :disabled="busy"
              @click="
                run(async () => {
                  await loadCourses();
                  await loadPage();
                })
              "
            >
              <RefreshCw :size="18" :class="{ spinning: busy }" />
            </button>
          </div>
        </div>
        <div v-if="error" class="message error no-print" role="alert">
          <AlertTriangle :size="18" /><span>{{ error }}</span
          ><button
            class="icon-button"
            aria-label="关闭错误"
            @click="error = ''"
          >
            <X :size="16" />
          </button>
        </div>
        <div v-if="notice" class="message success no-print" role="status">
          <CheckCircle2 :size="18" />{{ notice }}
        </div>
        <template v-if="['grades', 'analysis', 'prediction'].includes(page)">
          <div class="course-filter no-print">
            <label class="search"
              ><Search :size="16" /><input
                v-model="search"
                placeholder="查找课程"
                aria-label="查找课程"
                :disabled="busy || dirty" /></label
            ><select
              v-model="term"
              aria-label="学年学期"
              :disabled="busy || dirty"
            >
              <option value="">全部学期</option>
              <option v-for="t in terms" :key="t">{{ t }}</option></select
            ><select
              v-model="courseId"
              aria-label="选择课程"
              :disabled="busy || dirty"
              @change="selectCourse"
            >
              <option v-for="c in visibleCourses" :key="c.id" :value="c.id">
                {{ c.name }} · {{ c.term }}
              </option>
            </select>
          </div>
          <div v-if="!visibleCourses.length" class="empty">
            <BookOpen :size="30" />
            <p>暂无符合条件的课程</p>
          </div>
          <template v-else-if="selected">
            <div class="course-heading">
              <div>
                <span class="course-code">{{ selected.code }}</span>
                <h2>{{ selected.name }}</h2>
                <p class="muted">
                  {{ selected.term }} 学期 <span class="dot">·</span>
                  {{ selected.credits }} 学分 <span class="dot">·</span> 百分制
                </p>
              </div>
              <span
                v-if="!isStudent"
                class="badge"
                :class="submitted ? 'green' : 'amber'"
                >{{ submitted ? "已提交" : "暂存中" }}</span
              >
            </div>
            <template v-if="page === 'grades'">
              <div class="metrics">
                <div>
                  <span>选课人数</span
                  ><strong>{{ roster.length }}<small>人</small></strong>
                </div>
                <div>
                  <span>完整成绩</span
                  ><strong
                    >{{ completed }}<small>/ {{ roster.length }}</small></strong
                  >
                </div>
                <div>
                  <span>正考平均分</span
                  ><strong>{{ gradeMean }}<small>分</small></strong>
                </div>
                <div>
                  <span>当前版本</span
                  ><strong>{{ selected.version }}<small>版</small></strong>
                </div>
              </div>
              <div class="section-toolbar no-print">
                <div class="weight-summary">
                  <span v-for="[key, name] in activeComponents" :key="key"
                    >{{ name }} {{ currentWeights[key] }}%</span
                  ><button
                    v-if="isTeacher && can('MAINTAIN')"
                    class="icon-button"
                    title="设置成绩系数"
                    aria-label="设置成绩系数"
                    :disabled="submitted || busy"
                    @click="
                      weights = { ...currentWeights };
                      modal = 'weights';
                    "
                  >
                    <Settings2 :size="16" />
                  </button>
                </div>
                <div class="toolbar-actions">
                  <span v-if="dirty" class="unsaved">未保存</span
                  ><button
                    v-if="isTeacher && can('ENTRY') && !submitted"
                    class="secondary"
                    :disabled="busy"
                    @click="
                      modal = 'ocr';
                      ocrText = '';
                    "
                  >
                    <ScanText :size="16" />识别成绩单</button
                  ><button
                    v-if="isTeacher && can('ENTRY') && !submitted"
                    class="secondary"
                    :disabled="busy || !grades.length"
                    @click="saveGrades"
                  >
                    <Save :size="16" />暂存</button
                  ><button
                    v-if="isTeacher && can('MAINTAIN') && !submitted"
                    class="primary"
                    :disabled="busy || dirty || !grades.length"
                    @click="askTransition('SUBMIT')"
                  >
                    <Send :size="16" />提交</button
                  ><button
                    v-if="isTeacher && can('MAINTAIN') && submitted"
                    class="secondary"
                    :disabled="busy"
                    @click="askTransition('WITHDRAW')"
                  >
                    <Undo2 :size="16" />撤销提交</button
                  ><button
                    v-if="isAdmin && can('GRADE_ADMIN') && submitted"
                    class="secondary"
                    :disabled="busy"
                    @click="askTransition('SMALL_REVOKE')"
                  >
                    <Undo2 :size="16" />小撤销</button
                  ><button
                    v-if="isAdmin && can('GRADE_ADMIN') && submitted"
                    class="danger"
                    :disabled="busy"
                    @click="askTransition('DELETE_ALL')"
                  >
                    <Trash2 :size="16" />大撤销
                  </button>
                </div>
              </div>
              <div class="table-wrap">
                <table class="grade-table">
                  <thead>
                    <tr>
                      <th>学生</th>
                      <th v-for="[key, name] in activeComponents" :key="key">
                        {{ name }}<small>{{ currentWeights[key] }}%</small>
                      </th>
                      <th>正考总评</th>
                      <th>补考卷面</th>
                      <th>补考计分</th>
                      <th>状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in pagedRows" :key="row.id">
                      <td class="student-cell">
                        <strong>{{ row.name }}</strong
                        ><small>{{ row.username }}</small>
                      </td>
                      <td v-for="[key, name] in activeComponents" :key="key">
                        <input
                          v-if="isTeacher && can('ENTRY') && !submitted"
                          type="number"
                          min="0"
                          max="100"
                          step="0.01"
                          :aria-label="row.username + ' ' + name"
                          :value="row.grade?.scores[key] ?? ''"
                          @input="score(row, key, $event)"
                        /><span v-else>{{
                          display(row.grade?.scores[key])
                        }}</span>
                      </td>
                      <td>
                        <strong
                          :class="{
                            failed:
                              total(row.grade?.scores) !== null &&
                              total(row.grade?.scores) < 60,
                          }"
                          >{{ display(total(row.grade?.scores)) }}</strong
                        >
                      </td>
                      <td>
                        <input
                          v-if="isTeacher && can('ENTRY') && !submitted && effective(row.grade?.scores) !== null && effective(row.grade?.scores) < 60"
                          type="number"
                          min="0"
                          max="100"
                          step="0.01"
                          :aria-label="row.username + ' 补考'"
                          :value="row.grade?.scores.makeup ?? ''"
                          @input="score(row, 'makeup', $event)"
                        /><span v-else>{{
                          display(row.grade?.scores.makeup)
                        }}</span>
                      </td>
                      <td>
                        {{
                          row.grade?.scores.makeup == null
                            ? "--"
                            : display(Math.min(60, row.grade.scores.makeup))
                        }}
                      </td>
                      <td>
                        <span
                          class="status-dot"
                          :class="
                            row.grade?.state === 'SUBMITTED' ? 'green' : 'amber'
                          "
                        ></span
                        >{{
                          row.grade?.state === "SUBMITTED"
                            ? "已提交"
                            : row.grade
                              ? "暂存"
                              : "未录入"
                        }}
                      </td>
                    </tr>
                    <tr v-if="!rows.length">
                      <td colspan="11" class="empty">暂无选课学生</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="pagination no-print">
                <span>共 {{ rows.length }} 位学生</span>
                <div>
                  <button
                    class="icon-button"
                    title="上一页"
                    aria-label="上一页"
                    :disabled="tablePage === 1"
                    @click="tablePage--"
                  >
                    <ChevronLeft :size="18" /></button
                  ><span
                    >{{ tablePage }} /
                    {{ Math.max(1, Math.ceil(rows.length / 10)) }}</span
                  ><button
                    class="icon-button"
                    title="下一页"
                    aria-label="下一页"
                    :disabled="tablePage * 10 >= rows.length"
                    @click="tablePage++"
                  >
                    <ChevronRight :size="18" />
                  </button>
                </div>
              </div>
              <div v-if="statistics?.anomalies.length" class="alert-band">
                <AlertTriangle :size="18" />
                <div>
                  <strong
                    >{{ statistics.anomalies.length }} 项成绩异常待复核</strong
                  >
                  <p
                    v-for="(a, i) in statistics.anomalies.slice(0, 4)"
                    :key="i"
                  >
                    {{
                      a.studentId === "CLASS"
                        ? "班级"
                        : roster.find((s) => s.id === a.studentId)?.name
                    }}
                    · {{ a.message }}
                  </p>
                </div>
              </div>
            </template>
            <template v-if="page === 'analysis' && statistics">
              <div class="metrics">
                <div>
                  <span>有效成绩</span
                  ><strong>{{ statistics.count }}<small>人</small></strong>
                </div>
                <div>
                  <span>正考平均分</span
                  ><strong
                    >{{ display(statistics.mean) }}<small>分</small></strong
                  >
                </div>
                <div>
                  <span>正考及格率</span
                  ><strong
                    >{{ display(statistics.passRate) }}<small>%</small></strong
                  >
                </div>
                <div>
                  <span>异常提醒</span
                  ><strong
                    >{{ statistics.anomalies.length }}<small>项</small></strong
                  >
                </div>
              </div>
              <section class="analysis-grid">
                <div>
                  <h3>分数分布</h3>
                  <div class="chart" role="img" aria-label="班级成绩分布柱状图">
                    <div
                      v-for="(count, band) in statistics.bands"
                      :key="band"
                      class="bar-column"
                    >
                      <span>{{ count }}</span>
                      <div
                        class="bar"
                        :style="{
                          height:
                            Math.max(
                              3,
                              (count /
                                Math.max(
                                  1,
                                  ...Object.values(statistics.bands),
                                )) *
                                150,
                            ) + 'px',
                        }"
                      ></div>
                      <small>{{ band }}</small>
                    </div>
                  </div>
                </div>
                <div>
                  <div class="section-title">
                    <h3>教学分析</h3>
                    <button
                      v-if="isTeacher && can('MAINTAIN')"
                      class="secondary no-print"
                      :disabled="busy || !analysis.trim()"
                      @click="saveAnalysis"
                    >
                      <Save :size="15" />保存
                    </button>
                  </div>
                  <textarea
                    v-model="analysis"
                    aria-label="教学分析"
                    :readonly="!isTeacher || !can('MAINTAIN')"
                    maxlength="10000"
                    rows="8"
                    placeholder="成绩分析与教学改进意见"
                  ></textarea>
                </div>
              </section>
              <section v-if="statistics.anomalies.length" class="section">
                <h3>异常检测</h3>
                <div
                  v-for="(a, i) in statistics.anomalies"
                  :key="i"
                  class="anomaly-row"
                >
                  <span class="badge amber">{{ a.rule }}</span
                  ><strong>{{
                    roster.find((s) => s.id === a.studentId)?.name || "班级"
                  }}</strong
                  ><span>{{ a.message }}</span>
                </div>
              </section>
            </template>
            <section
              v-if="
                (page === 'analysis' && isTeacher && can('PREDICT')) ||
                page === 'prediction'
              "
              class="section"
            >
              <div class="section-title">
                <h3>学业预警</h3>
                <button
                  class="primary no-print"
                  :disabled="busy"
                  @click="predict"
                >
                  <ChartColumn :size="16" />生成预警
                </button>
              </div>
              <template v-if="prediction"
                ><div class="model-meta">
                  <span
                    >历史年份 {{ prediction.trainingYears.join(" / ") }}</span
                  ><span>{{ prediction.samples }} 条训练样本</span
                  ><span
                    >{{ prediction.validationYear }} 年验证 RMSE
                    {{ display(prediction.holdoutRmse) }}</span
                  >
                </div>
                <div class="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>学生</th>
                        <th>线性回归总评</th>
                        <th>决策树总评</th>
                        <th>估计区间</th>
                        <th>学业状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in prediction.results" :key="p.studentId">
                        <td>
                          {{
                            isStudent
                              ? user.name
                              : roster.find((s) => s.id === p.studentId)?.name
                          }}
                        </td>
                        <td>{{ display(p.predictedTotal) }}</td>
                        <td>{{ display(p.treeTotal) }}</td>
                        <td>{{ display(p.low) }} – {{ display(p.high) }}</td>
                        <td>
                          <span
                            class="badge"
                            :class="
                              p.warning ? 'red' : p.risk ? 'amber' : 'green'
                            "
                            >{{
                              p.warning
                                ? "挂科预警"
                                : p.risk
                                  ? "临界风险"
                                  : "正常"
                            }}</span
                          >
                        </td>
                      </tr>
                      <tr v-if="!prediction.results.length">
                        <td colspan="5" class="empty">
                          暂无可预测的未完成成绩
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <details v-if="isTeacher">
                  <summary>回归系数与决策树</summary>
                  <pre
                    >{{ prediction.linearFormula }}
{{ prediction.tree }}</pre
                  >
                </details></template
              >
              <div v-else class="empty compact">尚未生成预警</div>
            </section>
          </template>
        </template>
        <template v-if="page === 'transcript'"
          ><div class="metrics">
            <div>
              <span>已修课程</span
              ><strong>{{ transcript.length }}<small>门</small></strong>
            </div>
            <div>
              <span>已获学分</span
              ><strong>{{
                transcript
                  .filter((g) => !g.failed)
                  .reduce((s, g) => s + Number(g.credits), 0)
              }}</strong>
            </div>
            <div>
              <span>未通过课程</span
              ><strong :class="{ failed: transcript.some((g) => g.failed) }"
                >{{ transcript.filter((g) => g.failed).length
                }}<small>门</small></strong
              >
            </div>
          </div>
          <div class="course-filter no-print">
            <select v-model="term" aria-label="成绩学期">
              <option value="">在校全部学期</option>
              <option v-for="t in terms" :key="t">{{ t }}</option>
            </select>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>课程名称</th>
                  <th>学期</th>
                  <th>学分</th>
                  <th>正考 / 补考</th>
                  <th>最终成绩</th>
                  <th>本人排名</th>
                  <th>结果</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="g in transcript.filter(
                    (g) => !term || g.term === term,
                  )"
                  :key="g.id"
                >
                  <td>
                    <strong>{{ g.name }}</strong
                    ><small class="block muted">{{ g.code }}</small>
                  </td>
                  <td>{{ g.term }}</td>
                  <td>{{ g.credits }}</td>
                  <td>
                    {{ display(g.total)
                    }}{{ g.makeup === null ? "" : " / " + display(g.makeup) }}
                  </td>
                  <td>{{ display(g.effective) }}</td>
                  <td>{{ g.rank }} / {{ g.classSize }}</td>
                  <td>
                    <span class="badge" :class="g.failed ? 'red' : 'green'">{{
                      g.failed ? "未通过" : "已通过"
                    }}</span>
                  </td>
                </tr>
                <tr v-if="!transcript.length">
                  <td colspan="7" class="empty">暂无已提交成绩</td>
                </tr>
              </tbody>
            </table>
          </div></template
        >
        <template v-if="page === 'users'"
          ><div class="section-toolbar">
            <span class="muted">{{ users.length }} 位人员</span
            ><button class="primary" @click="editUser()">
              <Plus :size="16" />新增人员
            </button>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>姓名 / 账号</th>
                  <th>角色</th>
                  <th>组织</th>
                  <th>权限</th>
                  <th>状态</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="u in users" :key="u.id">
                  <td>
                    <strong>{{ u.name }}</strong
                    ><small class="block muted">{{ u.username }}</small>
                  </td>
                  <td>{{ roles[u.role] }}</td>
                  <td>{{ u.department }}</td>
                  <td>
                    <div class="permission-tags">
                      <span
                        v-for="p in u.permissions.split(',').filter(Boolean)"
                        :key="p"
                        >{{ permissionNames[p] }}</span
                      >
                    </div>
                  </td>
                  <td>
                    <span
                      class="badge"
                      :class="Number(u.enabled) ? 'green' : 'red'"
                      >{{ Number(u.enabled) ? "启用" : "停用" }}</span
                    >
                  </td>
                  <td>
                    <button
                      class="icon-button"
                      :title="'编辑 ' + u.username"
                      :aria-label="'编辑 ' + u.username"
                      @click="editUser(u)"
                    >
                      <Settings2 :size="17" />
                    </button>
                  </td>
                </tr>
              </tbody>
            </table></div
        ></template>
        <template v-if="page === 'courses'"
          ><div class="section-toolbar">
            <span class="muted">{{ courses.length }} 门课程</span
            ><button class="primary" @click="editCourse()">
              <Plus :size="16" />新建课程
            </button>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>课程</th>
                  <th>学期</th>
                  <th>学分</th>
                  <th>授课教师</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in courses" :key="c.id">
                  <td>
                    <strong>{{ c.name }}</strong
                    ><small class="block muted">{{ c.code }}</small>
                  </td>
                  <td>{{ c.term }}</td>
                  <td>{{ c.credits }}</td>
                  <td>
                    {{
                      users.find((u) => u.id === c.teacher_id)?.name ||
                      c.teacher_id
                    }}
                  </td>
                  <td>
                    <div class="toolbar-actions">
                      <button class="secondary" @click="openEnrollment(c)">
                        <Users :size="15" />选课</button
                      ><button
                        class="icon-button"
                        :title="'编辑 ' + c.name"
                        :aria-label="'编辑 ' + c.name"
                        @click="editCourse(c)"
                      >
                        <Settings2 :size="17" />
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table></div
        ></template>
        <template v-if="page === 'audit'"
          ><div class="section-toolbar">
            <span class="badge green" v-if="ledger?.verified"
              ><ShieldCheck :size="14" />账本与链上记录校验通过</span
            >
            <div class="toolbar-actions">
              <button
                class="secondary"
                :disabled="busy"
                @click="
                  run(async () => {
                    integrity = await api('/integrity');
                  })
                "
              >
                <FileCheck :size="16" />核查成绩完整性</button
              ><button
                class="secondary"
                :disabled="busy"
                @click="
                  run(async () => {
                    classification = await api('/audit/classify', {});
                  })
                "
              >
                <ChartColumn :size="16" />分析操作序列</button
              ><button
                class="primary"
                @click="
                  modal = 'review';
                  reason = '';
                  confirmation = '';
                "
              >
                <Plus :size="16" />记录复核
              </button>
            </div>
          </div>
          <div
            v-if="integrity"
            class="alert-band"
            :class="{ healthy: integrity.verified }"
          >
            <ShieldCheck :size="20" />
            <div>
              <strong>{{
                integrity.verified ? "成绩与独立账本一致" : "检测到成绩异常"
              }}</strong>
              <p>
                核查 {{ integrity.checked }} 条 · 异常
                {{ integrity.issues.length }} 条 · 待同步
                {{ integrity.outbox.pending }} 条
              </p>
              <details v-for="issue in integrity.issues" :key="issue.id">
                <summary>{{ issue.id }} · {{ issue.issue }}</summary>
                <pre>{{ JSON.stringify(issue.original, null, 2) }}</pre>
              </details>
            </div>
          </div>
          <div v-if="classification" class="section">
            <h3>LSTM 操作序列检测</h3>
            <p class="muted">
              模型数据集 {{ classification.dataset }} · 验证准确率
              {{ display(classification.validationAccuracy * 100) }}%
            </p>
            <div
              v-for="(c, i) in classification.results.filter((r) => r.review)"
              :key="i"
              class="anomaly-row"
            >
              <span class="badge amber">待复核</span
              ><span>{{ c.actor }} · {{ c.action }}</span
              ><span>{{ display(c.probability * 100) }}%</span>
            </div>
            <p
              v-if="!classification.results.some((r) => r.review)"
              class="muted"
            >
              本次未发现需要复核的操作序列
            </p>
          </div>
          <div v-if="ledger" class="ledger-head">
            <span>账本头哈希</span><code>{{ ledger.head }}</code>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>操作时间</th>
                  <th>操作人</th>
                  <th>事件</th>
                  <th>资源</th>
                  <th>变更数</th>
                  <th>证据</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="e in [...(ledger?.events || [])].reverse()"
                  :key="e.id"
                >
                  <td class="nowrap">
                    {{ new Date(e.time).toLocaleString("zh-CN") }}
                  </td>
                  <td>{{ e.actor }}</td>
                  <td>
                    <span
                      class="badge"
                      :class="
                        e.action.includes('DENIED') ||
                        e.action.includes('ANOMALY')
                          ? 'amber'
                          : 'neutral'
                      "
                      >{{ e.action }}</span
                    >
                  </td>
                  <td class="resource-cell">{{ e.resource }}</td>
                  <td>{{ e.changes.length }}</td>
                  <td>
                    <details>
                      <summary>查看</summary>
                      <pre>{{ JSON.stringify(e, null, 2) }}</pre>
                    </details>
                  </td>
                </tr>
              </tbody>
            </table>
          </div></template
        >
        <template v-if="page === 'account'"
          ><form
            class="account-form"
            @submit.prevent="
              run(async () => {
                await api('/password', { oldPassword, newPassword });
                user = null;
                oldPassword = '';
                newPassword = '';
              }, '密码已更改，请重新登录')
            "
          >
            <h3>{{ user.name }} · {{ roles[user.role] }}</h3>
            <label
              >原密码<input
                v-model="oldPassword"
                type="password"
                autocomplete="current-password"
                required /></label
            ><label
              >新密码<input
                v-model="newPassword"
                type="password"
                autocomplete="new-password"
                minlength="12"
                maxlength="72"
                required /></label
            ><button class="primary" :disabled="busy">
              <KeyRound :size="16" />更改密码
            </button>
          </form></template
        >
        <footer class="workspace-footer">
          <span>知序 · 信息工程学院</span
          ><span>学业记录 / {{ new Date().getFullYear() }}</span>
        </footer>
      </main>
    </div>
    <div
      v-if="modal"
      class="modal-backdrop no-print"
      @click.self="!busy && (modal = '')"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-label="
          {
            weights: '成绩系数',
            transition: '确认成绩操作',
            user: '人员信息',
            course: '课程信息',
            enroll: '选课名单',
            ocr: '识别成绩单',
            review: '审计复核',
          }[modal]
        "
      >
        <div class="modal-heading">
          <h2>
            {{
              {
                weights: "成绩系数",
                transition: "确认成绩操作",
                user: "人员信息",
                course: "课程信息",
                enroll: "选课名单",
                ocr: "识别成绩单",
                review: "审计复核",
              }[modal]
            }}
          </h2>
          <button
            class="icon-button"
            aria-label="关闭对话框"
            :disabled="busy"
            @click="modal = ''"
          >
            <X :size="20" />
          </button>
        </div>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <form v-if="modal === 'weights'" @submit.prevent="saveWeights">
          <div class="form-grid">
            <label v-for="[key, name] in components" :key="key"
              >{{ name }} (%)<input
                v-model.number="weights[key]"
                type="number"
                min="0"
                max="100"
                step="0.01"
                required
            /></label>
          </div>
          <p
            :class="
              Object.values(weights).reduce((a, b) => a + Number(b), 0) === 100
                ? 'success-text'
                : 'failed'
            "
          >
            总计
            {{ Object.values(weights).reduce((a, b) => a + Number(b), 0) }}%
          </p>
          <button class="primary" :disabled="busy">保存系数</button>
        </form>
        <form v-if="modal === 'transition'" @submit.prevent="transition">
          <p>
            {{
              {
                SUBMIT: "确认提交本课程全部成绩？",
                WITHDRAW: "确认将已提交成绩撤回为暂存状态？",
                SMALL_REVOKE: "确认将本课程成绩转为暂存，允许教师重新录入？",
                DELETE_ALL:
                  "确认删除本课程全部成绩记录？此操作将保留独立审计证据。",
              }[transitionAction]
            }}
          </p>
          <label v-if="isAdmin"
            >撤销原因<textarea
              v-model="reason"
              required
              maxlength="500"
              rows="3"
            ></textarea></label
          ><label v-if="transitionAction === 'DELETE_ALL'"
            >确认课程编号：{{ courseId }}<input v-model="confirmation" required
          /></label>
          <div class="modal-actions">
            <button type="button" class="secondary" @click="modal = ''">
              取消</button
            ><button
              :class="transitionAction === 'DELETE_ALL' ? 'danger' : 'primary'"
              :disabled="busy"
            >
              确认
            </button>
          </div>
        </form>
        <form v-if="modal === 'user'" @submit.prevent="saveUser">
          <div class="form-grid">
            <label
              >账号<input
                v-model="userForm.username"
                required
                maxlength="60" /></label
            ><label
              >姓名<input
                v-model="userForm.name"
                required
                maxlength="80" /></label
            ><label
              >角色<select
                v-model="userForm.role"
                :disabled="!!userForm.id"
                @change="
                  userForm.permissions = [...rolePermissions[userForm.role]]
                "
              >
                <option v-for="(name, role) in roles" :key="role" :value="role">
                  {{ name }}
                </option>
              </select></label
            ><label
              >组织<input
                v-model="userForm.department"
                required
                maxlength="100"
            /></label>
          </div>
          <label
            >{{ userForm.id ? "重置密码（留空不更改）" : "初始密码"
            }}<input
              v-model="userForm.password"
              type="password"
              autocomplete="new-password"
              minlength="12"
              maxlength="72"
              :required="!userForm.id"
          /></label>
          <fieldset>
            <legend>功能权限</legend>
            <label
              v-for="p in rolePermissions[userForm.role]"
              :key="p"
              class="check-label"
              ><input
                v-model="userForm.permissions"
                type="checkbox"
                :value="p"
              />{{ permissionNames[p] }}</label
            >
          </fieldset>
          <label class="check-label"
            ><input
              type="checkbox"
              :checked="userForm.enabled === 1"
              @change="userForm.enabled = $event.target.checked ? 1 : 0"
            />账号启用</label
          >
          <div class="modal-actions">
            <button class="primary" :disabled="busy">保存人员</button>
          </div>
        </form>
        <form v-if="modal === 'course'" @submit.prevent="saveCourse">
          <label
            >课程名称<input v-model="courseForm.name" required maxlength="100"
          /></label>
          <div class="form-grid">
            <label
              >课程代码<input
                v-model="courseForm.code"
                :readonly="!!courseForm.id"
                required /></label
            ><label
              >学年学期<input
                v-model="courseForm.term"
                :readonly="!!courseForm.id"
                pattern="20[0-9]{2}-[12]"
                required /></label
            ><label
              >学分<input
                v-model.number="courseForm.credits"
                type="number"
                min="0.5"
                max="30"
                step="0.5"
                required /></label
            ><label
              >授课教师<select
                v-if="users.length"
                v-model="courseForm.teacherId"
                required
              >
                <option value="" disabled>选择教师</option>
                <option
                  v-for="u in users.filter(
                    (u) => u.role === 'TEACHER' && Number(u.enabled),
                  )"
                  :key="u.id"
                  :value="u.id"
                >
                  {{ u.name }}
                </option></select
              ><input
                v-else
                v-model="courseForm.teacherId"
                required
                placeholder="教师编号"
            /></label>
          </div>
          <div class="modal-actions">
            <button class="primary" :disabled="busy">保存课程</button>
          </div>
        </form>
        <div v-if="modal === 'enroll'">
          <div class="inline-form">
            <select
              v-if="users.length"
              v-model="enrollStudent"
              aria-label="选课学生"
            >
              <option value="">选择学生</option>
              <option
                v-for="u in users.filter(
                  (u) =>
                    u.role === 'STUDENT' &&
                    Number(u.enabled) &&
                    !roster.some((s) => s.id === u.id),
                )"
                :key="u.id"
                :value="u.id"
              >
                {{ u.name }} · {{ u.username }}
              </option></select
            ><input
              v-else
              v-model="enrollStudent"
              aria-label="学生编号"
            /><button
              class="primary"
              :disabled="busy || !enrollStudent"
              @click="enrollment()"
            >
              <Plus :size="16" />选课
            </button>
          </div>
          <div v-for="s in roster" :key="s.id" class="roster-item">
            <span
              >{{ s.name }} <small class="muted">{{ s.username }}</small></span
            ><button
              class="icon-button"
              :title="'退选 ' + s.username"
              :aria-label="'退选 ' + s.username"
              :disabled="busy"
              @click="enrollment(true, s.id)"
            >
              <X :size="16" />
            </button>
          </div>
        </div>
        <div v-if="modal === 'ocr'">
          <label
            >成绩单图片<input
              type="file"
              accept="image/png,image/jpeg,image/webp"
              :disabled="busy"
              @change="recognize" /></label
          ><progress v-if="busy" :value="ocrProgress" max="100"></progress
          ><label
            >识别结果<textarea
              v-model="ocrText"
              rows="8"
              :placeholder="
                '学号 ' + activeComponents.map((c) => c[1]).join(' ')
              "
              spellcheck="false"
            ></textarea>
          </label>
          <div class="import-columns">
            <span>学号</span
            ><span v-for="[key, name] in activeComponents" :key="key">{{
              name
            }}</span>
          </div>
          <div class="modal-actions">
            <button
              class="primary"
              :disabled="busy || !ocrText.trim()"
              @click="applyOcr"
            >
              确认填入
            </button>
          </div>
        </div>
        <form v-if="modal === 'review'" @submit.prevent="review">
          <label
            >复核资源 / 事件编号<input
              v-model="confirmation"
              required
              maxlength="200" /></label
          ><label
            >复核意见<textarea
              v-model="reason"
              required
              rows="5"
              maxlength="1000"
            ></textarea></label
          ><button class="primary" :disabled="busy">提交复核</button>
        </form>
      </section>
    </div>
  </div>
</template>
