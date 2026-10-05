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
  Mic,
  RefreshCw,
  KeyRound,
  CheckCircle2,
  AlertTriangle,
  FileCheck,
  ClipboardList,
  Building2,
  ClipboardCheck,
  CalendarClock,
  ListChecks,
  UserCheck,
  UserMinus,
} from "lucide-vue-next";
import { api } from "./api";
import OrganizationView from "./components/OrganizationView.vue";
import SelectionView from "./components/SelectionView.vue";
import RecognizePreview from "./components/RecognizePreview.vue";
import VoicePanel from "./components/VoicePanel.vue";
import {
  buildPreview,
  defaultColumnMap,
  loadCanvas,
  mapColumns,
  recognizeBest,
  summarize,
} from "./ocr";

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
const orgOptions = ref({ colleges: [], majors: [], classes: [] });
const teachers = ref([]);
const modal = ref(""),
  weights = ref({}),
  analysis = ref(""),
  userForm = ref({}),
  courseForm = ref({}),
  reason = ref(""),
  confirmation = ref(""),
  transitionAction = ref(""),
  dirty = ref(false),
  tablePage = ref(1),
  ocrStage = ref(""),
  ocrProgress = ref(0),
  ocrError = ref(""),
  ocrPreview = ref(null),
  ocrSheets = ref([]),
  voiceTargetRow = ref(null),
  oldPassword = ref(""),
  newPassword = ref(""),
  printing = ref(false);
/* 侧栏宽度：由鼠标拖拽右侧手柄自由调节，宽度本身持久化到 localStorage。
   为保证「拖窄时仍然好看」，宽度分成三档：
     - >= 168px：完整宽度（图标 + 文字）
     - 118–167px：紧凑档，导航仍显示文字但收紧内边距
     - < 118px：仅图标档，只保留图标并用 title 提供悬浮提示
   拖动过程中禁用文本选择，松手后写回 localStorage。 */
const SIDEBAR_MIN = 68;
const SIDEBAR_MAX = 420;
const SIDEBAR_ICON_ONLY = 118;
const SIDEBAR_COMFORTABLE = 168;
const SIDEBAR_DEFAULT = 216;
function clampSidebarWidth(value) {
  return Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, Math.round(value)));
}
/** 已保存的宽度；null 表示用户从未拖过，此时沿用样式表里的默认值（含小屏断点）。 */
const savedSidebarWidth = (() => {
  try {
    const raw = window.localStorage?.getItem("campus.sidebarWidth");
    const parsed = raw === null || raw === undefined ? NaN : Number(raw);
    return Number.isFinite(parsed) ? clampSidebarWidth(parsed) : null;
  } catch (e) {
    return null;
  }
})();
const sidebarWidth = ref(savedSidebarWidth);
const sidebarResizing = ref(false);
const sidebarIconOnly = computed(
  () => sidebarWidth.value !== null && sidebarWidth.value < SIDEBAR_ICON_ONLY,
);
const sidebarCompact = computed(
  () =>
    sidebarWidth.value !== null &&
    sidebarWidth.value >= SIDEBAR_ICON_ONLY &&
    sidebarWidth.value < SIDEBAR_COMFORTABLE,
);
/** 只有用户真正拖过才写内联宽度，避免覆盖样式表里的小屏断点。 */
const sidebarStyle = computed(() =>
  sidebarWidth.value === null ? {} : { "--sidebar-width": sidebarWidth.value + "px" },
);
function startSidebarResize(event) {
  if (event.button !== undefined && event.button !== 0) return;
  event.preventDefault();
  const startX = event.clientX;
  const startWidth =
    sidebarWidth.value ??
    document.querySelector("aside.sidebar")?.getBoundingClientRect().width ??
    SIDEBAR_DEFAULT;
  sidebarResizing.value = true;
  document.body.classList.add("resizing-sidebar");
  const onMove = (moveEvent) => {
    sidebarWidth.value = clampSidebarWidth(startWidth + moveEvent.clientX - startX);
  };
  const onUp = () => {
    window.removeEventListener("mousemove", onMove);
    window.removeEventListener("mouseup", onUp);
    sidebarResizing.value = false;
    document.body.classList.remove("resizing-sidebar");
    if (sidebarWidth.value !== null)
      try {
        window.localStorage?.setItem("campus.sidebarWidth", String(sidebarWidth.value));
      } catch (e) {
        /* 隐私模式下 localStorage 可能不可用，忽略即可 */
      }
  };
  window.addEventListener("mousemove", onMove);
  window.addEventListener("mouseup", onUp);
}
/** 双击手柄恢复默认宽度（并清掉持久化值，让默认值继续跟随小屏断点）。 */
function resetSidebarWidth() {
  sidebarWidth.value = null;
  try {
    window.localStorage?.removeItem("campus.sidebarWidth");
  } catch (e) {
    /* 忽略 */
  }
}
/* 管理员「选课管理」页的标签页：courses / publish / records。 */
const selectionTab = ref("courses");
/* 「按课程选课」弹窗状态。 */
const enrollCourse = ref(null);
const enrollStudents = ref([]);
const enrollStudentIds = ref([]);
const enrollClassName = ref("");
const enrollKeyword = ref("");
const enrollRoster = ref([]);
const enrollResult = ref(null);
const enrollLoading = ref(false);
const enrollWorking = ref(false);
const enrollRemoved = ref(false);
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
  ORG_ADMIN: "组织管理",
  SELECTION_ADMIN: "选课管理",
  SELECTION_ENROLL: "网上选课",
};
const rolePermissions = {
  TEACHER: ["QUERY", "ENTRY", "MAINTAIN", "PREDICT"],
  STUDENT: ["QUERY", "PREDICT", "SELECTION_ENROLL"],
  ADMIN: [
    "GRADE_ADMIN",
    "USER_ADMIN",
    "AUDIT",
    "ORG_ADMIN",
    "SELECTION_ADMIN",
  ],
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
/**
 * 最新学期（课程列表里最靠后的 term）。
 *
 * <p>学业预警只对「正在进行中」的课程有意义——学生的期末成绩一旦录入就没有可预测对象，
 * 因此课程选择器里把最新学期的课程排在最前，空结果提示也指向它。
 */
const latestTerm = computed(() => terms.value[0] || "");
/**
 * 学业记录统计口径（重修友好）：
 * - 已获课程：按**课程代码去重**后已通过的课程数 —— 重修通过只算一门，不会因为修了两次算两门；
 * - 已获学分：同样按代码去重，只取通过的那次，所以重修不会重复计学分；
 * - 未通过课程：**到目前为止仍未通过**的课程代码数 —— 已经重修通过的课程不再算「未通过」。
 */
const passedCourseCount = computed(
  () =>
    new Set(transcript.value.filter((g) => !g.failed).map((g) => g.code)).size,
);
const earnedCredits = computed(() => {
  const seen = new Set();
  let sum = 0;
  for (const g of transcript.value) {
    if (g.failed || seen.has(g.code)) continue;
    seen.add(g.code);
    sum += Number(g.credits) || 0;
  }
  return sum;
});
const pendingCourseCount = computed(() => {
  const passed = new Set(
    transcript.value.filter((g) => !g.failed).map((g) => g.code),
  );
  const pending = new Set(
    transcript.value
      .filter((g) => g.failed && !passed.has(g.code))
      .map((g) => g.code),
  );
  return pending.size;
});
const gradeMean = computed(() => {
  const values = grades.value
    .map((g) => total(g.scores))
    .filter((v) => v !== null);
  return values.length
    ? (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1)
    : "--";
});
/**
 * 侧栏导航。管理员顺序固定为：课程成绩、统计分析、人员与权限、组织管理、选课管理、安全审计（最后一项）。
 * 「安全审计」必须排在管理员导航的最后；学生与教师看不到该项，顺序不受影响。
 */
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
  ...(isAdmin.value && can("USER_ADMIN")
    ? [{ key: "users", name: "人员与权限", icon: Users }]
    : []),
  ...(isAdmin.value && can("ORG_ADMIN")
    ? [{ key: "organization", name: "组织管理", icon: Building2 }]
    : []),
  /* 学生：网上选课（只有选课台）；管理员：选课管理（课程与选课 / 选课批次 / 选课记录）。 */
  ...(isStudent.value && can("SELECTION_ENROLL")
    ? [{ key: "selection", name: "网上选课", icon: ClipboardCheck }]
    : []),
  ...(isAdmin.value && can("SELECTION_ADMIN")
    ? [{ key: "selection", name: "选课管理", icon: ClipboardCheck }]
    : []),
  ...(isAdmin.value && can("AUDIT")
    ? [{ key: "audit", name: "安全审计", icon: ShieldCheck }]
    : []),
]);
const userMajorOptions = computed(() =>
  orgOptions.value.majors.filter(
    (m) =>
      !userForm.value.college_id ||
      m.collegeId === userForm.value.college_id ||
      m.collegeName === userForm.value.college,
  ),
);
const userClassOptions = computed(() =>
  orgOptions.value.classes.filter(
    (c) =>
      !userForm.value.major_id ||
      c.majorId === userForm.value.major_id ||
      c.majorName === userForm.value.major,
  ),
);
const teacherOptions = computed(() => {
  const list = teachers.value.length
    ? teachers.value
    : users.value.filter((u) => u.role === "TEACHER");
  return list.filter(
    (t) => t.enabled === undefined || t.enabled === null || Number(t.enabled),
  );
});
/** 课程对话框里管理员是否自己挑过「开设院系」；挑过之后不再被教师所在院系覆盖。 */
const collegeTouched = ref(false);
/**
 * 按院系名称查组织编号。
 *
 * 课程列表与教师列表都只带 `collegeName`，而保存课程要的是组织编号（后端 `resolveOwn`
 * 只认编号），所以要在这里做一次名称→编号的换算。
 */
function collegeIdByName(name) {
  const text = name === null || name === undefined ? "" : String(name).trim();
  if (!text) return "";
  const hit = orgOptions.value.colleges.find((c) => c.name === text);
  return hit ? hit.id : "";
}
const pageName = computed(
  () => nav.value.find((n) => n.key === page.value)?.name || "账户设置",
);
/**
 * 左下角身份行：角色 + 所在学院 + 专业 + 班级。
 * `/me` 返回的 collegeName / majorName / className 对管理员均为 null，
 * 这里统一做空值过滤，避免出现「管理员 · null」。
 */
const identityParts = computed(() => {
  const current = user.value;
  if (!current) return [];
  const parts = [];
  const push = (value) => {
    const text =
      value === null || value === undefined ? "" : String(value).trim();
    if (text) parts.push(text);
  };
  push(roles[current.role]);
  push(current.collegeName);
  push(current.majorName);
  push(current.className);
  return parts;
});
const identityLine = computed(() => identityParts.value.join(" · "));
/* 管理员「选课管理」页的 3 个标签页。 */
const adminSelectionTabs = computed(() => [
  { key: "courses", name: "课程与选课", icon: BookOpen },
  { key: "publish", name: "选课批次", icon: CalendarClock },
  { key: "records", name: "选课记录", icon: ListChecks },
]);
/** 课程授课教师：优先用 /courses 返回的 teacherName，退回本地教师表与编号。 */
function teacherNameOf(course) {
  if (!course) return "--";
  if (course.teacherName) return course.teacherName;
  const found = users.value.find((u) => u.id === course.teacher_id);
  return (found && found.name) || course.teacher_id || "--";
}
/** 「按课程选课」弹窗：班级下拉选中的班级对应的在读学生。 */
const enrollClassMembers = computed(() => {
  const name = enrollClassName.value;
  if (!name) return [];
  return enrollStudents.value.filter(
    (s) => s.className === name || s.classNameRaw === name,
  );
});
/** 勾选学生 + 整班学生，并集去重后的待处理名单。 */
const enrollSelectedIds = computed(() => {
  const ids = new Set(enrollStudentIds.value);
  for (const s of enrollClassMembers.value) ids.add(s.id);
  return [...ids];
});
const enrollVisibleStudents = computed(() => {
  const keyword = enrollKeyword.value.trim().toLowerCase();
  if (!keyword) return enrollStudents.value;
  return enrollStudents.value.filter((s) =>
    [s.name, s.username, s.className, s.majorName, s.collegeName]
      .map((v) => String(v || "").toLowerCase())
      .some((v) => v.includes(keyword)),
  );
});
/** 已在课程名单里的学生编号，用于在列表里标注「已选」。 */
const enrolledIds = computed(
  () => new Set(enrollRoster.value.map((s) => s.id)),
);
/**
 * 当前课程里的重修学生编号。
 *
 * <p>重修与课程名无关，只能由「该生此前学期修过同一课程号且未通过」推出来，
 * 后端在 `/roster` 的每条记录上给出 `retake`，这里建成集合供勾选列表标注。
 */
const retakeIds = computed(
  () => new Set(enrollRoster.value.filter((s) => s.retake).map((s) => s.id)),
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
/** 组织选项接口可能返回 {colleges,majors,classes} 或分页对象，这里统一取值。 */
function optionsList(result, key) {
  if (Array.isArray(result)) return result;
  if (result && Array.isArray(result[key])) return result[key];
  if (result && Array.isArray(result.items)) return result.items;
  return [];
}
async function loadOrganizations() {
  try {
    const result = await api("/organizations/options");
    orgOptions.value = {
      colleges: optionsList(result, "colleges"),
      majors: optionsList(result, "majors"),
      classes: optionsList(result, "classes"),
    };
  } catch (e) {
    // 选项接口失败不应让整页报错，下拉框退化为空列表。
    orgOptions.value = { colleges: [], majors: [], classes: [] };
  }
}
async function loadTeachers() {
  try {
    teachers.value = optionsList(await api("/users/teachers"), "items");
  } catch (e) {
    teachers.value = [];
  }
}
async function loadSelectionCourses() {
  await loadCourses();
  await loadTeachers();
  // 课程归属校验要求「开设院系」，课程对话框要靠它渲染院系下拉；没有就补一次。
  if (!orgOptions.value.colleges.length) await loadOrganizations();
  if (can("USER_ADMIN") && !users.value.length)
    users.value = (await api("/users?size=300")).items;
}
/** 管理员「选课管理」页切换标签页；后两个标签页由 SelectionView 自管数据。 */
async function switchSelectionTab(key) {
  if (key === selectionTab.value) return;
  selectionTab.value = key;
  if (key === "courses") await run(loadSelectionCourses);
}
async function loadPage() {
  if (page.value === "organization") return;
  if (page.value === "selection") {
    // 学生选课台与管理员的后两个标签页都由 SelectionView 自己加载。
    if (isAdmin.value && selectionTab.value === "courses")
      await loadSelectionCourses();
    return;
  }
  if (page.value === "users") {
    users.value = (await api("/users?size=300")).items;
    await loadOrganizations();
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
    // /login 返回的 user 只含账号本身；左下角身份行需要 /me 才有的
    // collegeName / majorName / className，所以登录后立刻补一次 /me。
    const profile = await api("/me");
    user.value = profile || result.user;
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
/** 组织下拉统一按名称操作；班级取选项里的原始 name（接口返回的 className 是“2023级xx班”展示名）。 */
function classRawName(id, display) {
  const option = id
    ? orgOptions.value.classes.find((c) => c.id === id)
    : null;
  return option ? option.name : String(display || "").replace(/^\d+级/, "");
}
function selectUserCollege() {
  const option = orgOptions.value.colleges.find(
    (c) => c.name === userForm.value.college,
  );
  userForm.value.college_id = option ? option.id : "";
  userForm.value.major = "";
  userForm.value.major_id = "";
  userForm.value.klass = "";
  userForm.value.class_id = "";
}
function selectUserMajor() {
  const option = orgOptions.value.majors.find(
    (m) => m.name === userForm.value.major,
  );
  userForm.value.major_id = option ? option.id : "";
  userForm.value.klass = "";
  userForm.value.class_id = "";
}
function editUser(u) {
  userForm.value = u
    ? {
        ...u,
        permissions: u.permissions.split(","),
        enabled: Number(u.enabled),
        password: "",
        college: u.collegeName || "",
        major: u.majorName || "",
        klass: classRawName(u.class_id, u.className),
      }
    : {
        username: "",
        name: "",
        role: "STUDENT",
        permissions: ["QUERY", "PREDICT", "SELECTION_ENROLL"],
        college: "",
        major: "",
        klass: "",
        college_id: "",
        major_id: "",
        class_id: "",
        enabled: 1,
        password: "",
      };
  modal.value = "user";
}
async function saveUser() {
  await run(async () => {
    const body = { ...userForm.value };
    // 组织归属统一按名称提交；后端返回的 *Name 是展示名（班级为“2023级xx班”），不能回传。
    delete body.klass;
    delete body.className;
    delete body.collegeName;
    delete body.majorName;
    body.college = body.college || "";
    body.major = body.major || "";
    if (body.role === "STUDENT") body.class = userForm.value.klass || "";
    else delete body.class;
    await api("/users/save", body);
    modal.value = "";
    users.value = (await api("/users?size=300")).items;
  }, "人员信息已保存");
}
/**
 * 打开课程对话框。
 *
 * 「开设院系」由任课教师所在院系**自动带出**，管理员仍可改：
 * 后端 `saveCourse` 要求 `collegeId`（或院系名称）非空，缺失会直接 400「必须指定开设院系」，
 * 而课程列表只给了 `collegeName`，所以这里把名称回填成组织编号；
 * 新建或原课程没有院系时留空，交给 `syncCollegeWithTeacher` 按教师带出。
 */
function editCourse(c) {
  courseForm.value = c
    ? { ...c, teacherId: c.teacher_id, collegeId: c.college_id || collegeIdByName(c.collegeName) }
    : { code: "", name: "", term: "2026-1", teacherId: "", credits: 3, collegeId: "" };
  collegeTouched.value = Boolean(courseForm.value.collegeId);
  modal.value = "course";
  // 列表可能是在组织选项加载前就拿到的，这里补一次回填
  if (!collegeTouched.value) nextTick(syncCollegeWithTeacher);
}
/**
 * 让「开设院系」跟随任课教师（教师所在院系即默认开设院系）。
 * 管理员一旦自己选过院系（collegeTouched），就不再被覆盖。
 */
function syncCollegeWithTeacher() {
  if (collegeTouched.value) return;
  const teacher = teacherOptions.value.find((t) => t.id === courseForm.value.teacherId);
  const collegeId = teacher ? collegeIdByName(teacher.collegeName) : null;
  if (collegeId) courseForm.value.collegeId = collegeId;
}
async function saveCourse() {
  await run(async () => {
    const body = { ...courseForm.value };
    // 后端按院系编号（或名称）解析开设院系；这里再兜一次底，避免把「未指定」提交上去
    if (!body.collegeId && !body.college) {
      syncCollegeWithTeacher();
      body.collegeId = courseForm.value.collegeId;
    }
    await api("/courses/save", body);
    modal.value = "";
    await loadCourses();
  }, "课程已保存");
}
/**
 * 打开「按课程选课」弹窗：显示课程信息与当前名单人数，
 * 学生名单来自组织域接口 GET /organizations/students?size=300（不需要 USER_ADMIN 的 /users）。
 */
async function openEnrollment(c) {
  courseId.value = c.id;
  enrollCourse.value = c;
  enrollStudentIds.value = [];
  enrollClassName.value = "";
  enrollKeyword.value = "";
  enrollResult.value = null;
  enrollRemoved.value = false;
  enrollLoading.value = true;
  modal.value = "enroll";
  try {
    const roster = await api("/roster?courseId=" + c.id);
    enrollRoster.value = Array.isArray(roster) ? roster : optionsList(roster, "items");
  } catch (e) {
    enrollRoster.value = [];
    error.value = e.message;
  }
  try {
    enrollStudents.value = optionsList(
      await api("/organizations/students?size=300"),
      "items",
    );
  } catch (e) {
    enrollStudents.value = [];
    error.value = e.message + "（学生列表不可用，仍可按班级整班处理）";
  }
  await loadOrganizations();
  enrollLoading.value = false;
}
/** 提交按课程选课 / 退课：POST /enrollments/batch。 */
async function submitEnrollment(remove) {
  if (enrollWorking.value || !enrollCourse.value) return;
  const ids = enrollSelectedIds.value;
  const className = enrollClassName.value;
  if (!ids.length && !className) {
    error.value = "请选择学生或班级";
    return;
  }
  enrollWorking.value = true;
  enrollRemoved.value = !!remove;
  error.value = "";
  notice.value = "";
  try {
    const body = { courseId: enrollCourse.value.id };
    if (ids.length) body.studentIds = ids;
    if (className) body.className = className;
    if (remove) body.remove = true;
    const result = await api("/enrollments/batch", body);
    enrollResult.value = result || {};
    enrollRoster.value = await api(
      "/roster?courseId=" + enrollCourse.value.id,
    );
    await loadCourses();
    notice.value =
      (remove ? "退课完成" : "选课完成") +
      "：新增 " +
      (enrollResult.value.added || 0) +
      " · 跳过 " +
      (enrollResult.value.skipped || 0) +
      " · 移除 " +
      (enrollResult.value.removed || 0);
  } catch (e) {
    enrollResult.value = null;
    error.value = e.message;
  } finally {
    enrollWorking.value = false;
  }
}
/** 识别阶段的中文说明：进度条下方的提示文案。 */
const OCR_STAGES = {
  skew: "正在估计倾斜角…",
  variant: "正在尝试多套预处理…",
  recognize: "正在逐行识别…",
  done: "识别完成",
};
const ocrStageLabel = computed(() => OCR_STAGES[ocrStage.value] || "正在准备…");
const ocrComponents = computed(() =>
  activeComponents.value.map(([key, label]) => ({ key, label })),
);
const ocrColumns = computed(() => ocrSheets.value.map((_, index) => index));
const ocrColumnMap = ref([]);
const ocrRows = computed(() =>
  ocrPreview.value
    ? mapColumns({
        rows: ocrPreview.value.rows,
        tokens: ocrPreview.value.tokens,
        components: ocrComponents.value,
        roster: roster.value,
        columnMap: ocrColumnMap.value,
      })
    : [],
);
const ocrSummary = computed(() => summarize({ rows: ocrRows.value }, ocrComponents.value));
/**
 * 允许"仅一位数字不同"的学号纠正。
 *
 * 真实教务系统的学号常是连号（20231530 / 20241530），OCR 也常把数字看错一位；
 * 开启后这类差异会匹配到名册里最接近的那位学生，并在预览表标注"请核对"，
 * 由教师最终决定要不要勾选。仍保留"编辑距离 ≤ 1"与"必须命中名册"两条底线。
 */
const ALLOW_DIGIT_CORRECTION = true;

/**
 * 图片识别入口：本地预处理 + 多策略识别 + 结构化。
 *
 * 图片只经 `createObjectURL` 在浏览器内读取，**不上传任何字节**；
 * 识别结果先进入预览确认，教师点"确认填入"后才写进录入表单。
 */
async function recognize(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  ocrError.value = "";
  ocrPreview.value = null;
  await run(async () => {
    if (file.size > 10 * 1024 * 1024) throw new Error("图片不能超过 10 MB");
    if (!activeComponents.value.length)
      throw new Error("该课程还没有设置成绩系数，请先设置成绩项");
    const url = URL.createObjectURL(file);
    try {
      const image = await loadImageElement(url);
      const canvas = loadCanvas(image);
      const result = await recognizeBest(
        canvas,
        (info) => {
          ocrStage.value = info.stage;
          ocrProgress.value = Math.round((info.progress || 0) * 100);
        },
        {
          roster: roster.value,
          components: ocrComponents.value,
          goodEnough: 1,
          allowDigitCorrection: ALLOW_DIGIT_CORRECTION,
        },
      );
      if (!result.best || !result.best.rows.length)
        throw new Error("没有识别到任何文本行，请换一张更清晰的照片");
      const preview = buildPreview(
        result.best.rows,
        ocrComponents.value,
        roster.value,
        defaultColumnMap(result.best.score?.columns ?? 0, ocrComponents.value.length),
        { allowDigitCorrection: ALLOW_DIGIT_CORRECTION },
      );
      ocrSheets.value = preview.columns;
      ocrColumnMap.value = defaultColumnMap(preview.columns.length, ocrComponents.value.length);
      ocrPreview.value = {
        rows: preview.rows,
        tokens: preview.tokens,
        variant: result.best.id,
        skew: result.skew,
      };
      notice.value = `识别完成（${result.best.label}）：共 ${preview.rows.length} 行，可直接填入 ${preview.summary.usable} 行`;    } finally {
      URL.revokeObjectURL(url);
      event.target.value = "";
      ocrStage.value = "";
      ocrProgress.value = 0;
    }
  });
  if (error.value) ocrError.value = error.value;
}

function loadImageElement(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("图片无法读取，请换一张 PNG/JPG 图片"));
    image.src = url;
  });
}

/** 预览确认后写入录入表单（不提交，仍需教师点"暂存"）。 */
function applyRecognized(rows) {
  error.value = "";
  let filled = 0;
  for (const item of rows) {
    const row = roster.value.find((r) => r.username === item.username);
    if (!row) continue;
    for (const cell of item.cells)
      score(row, cell.key, { target: { value: String(cell.value) } });
    filled++;
  }
  modal.value = "";
  ocrPreview.value = null;
  notice.value = `已填入 ${filled} 人成绩，待暂存`;
}

/** 语音结果写入当前行。 */
function applyVoice({ target, cells }) {
  if (!target) return;
  let row = roster.value.find((r) => r.id === target.id);
  if (!row) row = roster.value.find((r) => r.username === target.username);
  if (!row) {
    error.value = "当前行已不在名单中，请重新选择课程";
    return;
  }
  for (const cell of cells) score(row, cell.key, { target: { value: String(cell.value) } });
  notice.value = `已填入 ${row.username} 的 ${cells.length} 项成绩，待暂存`;
}

/** 打开语音面板前先记录当前行：优先用户点过的行，否则从第一行开始。 */
function openVoice(row) {
  const target = row || voiceTargetRow.value || rows.value[0] || null;
  voiceTargetRow.value = target;
  modal.value = "voice";
}
const voiceDefaults = computed(() => {
  const grade = voiceTargetRow.value?.grade;
  if (!grade) return {};
  const scores = grade.scores ?? {};
  const defaults = {};
  for (const [key] of activeComponents.value) defaults[key] = scores[key] ?? null;
  return defaults;
});
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
/**
 * 换任课教师时同步「开设院系」：默认取该教师所在院系。
 * 只在管理员没自己挑过院系时生效，避免覆盖人工选择。
 */
watch(
  () => courseForm.value.teacherId,
  () => {
    if (modal.value === "course" && !collegeTouched.value) syncCollegeWithTeacher();
  },
);
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
/** 模态框切换时的清理：识别预览与语音状态都不跨次残留。 */
watch(modal, (value, previous) => {
  if (previous === "ocr" && !value) {
    ocrPreview.value = null;
    ocrSheets.value = [];
    ocrColumnMap.value = [];
    ocrError.value = "";
  }
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
    <div class="login-caption">教务工作台<span>ACADEMIC AFFAIRS</span></div>
  </main>
  <div
    v-else
    class="app-shell"
    :class="{ 'sidebar-compact': sidebarCompact, 'sidebar-icon-only': sidebarIconOnly }"
    :style="sidebarStyle"
  >
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
          :title="item.name"
          :aria-label="item.name"
          :aria-current="page === item.key ? 'page' : undefined"
          @click="navigate(item.key)"
        >
          <component :is="item.icon" :size="18" /><span class="nav-name">{{
            item.name
          }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="security-stamp">
          <ShieldCheck :size="17" /><span>HTTPS 安全连接</span>
        </div>
        <button
          class="identity"
          :title="identityLine"
          :aria-label="identityLine"
          @click="navigate('account')"
        >
          <span class="avatar">{{ user.name.slice(0, 1) }}</span
          ><span class="identity-text"
            ><strong class="identity-name">{{ user.name }}</strong
            ><small class="identity-meta">{{ identityLine }}</small></span
          ><Settings2 class="identity-cog" :size="16" />
        </button>
      </div>
      <!-- 拖拽调节侧栏宽度：拖动左右移动，双击恢复默认宽度 -->
      <div
        class="sidebar-resizer no-print"
        role="separator"
        aria-orientation="vertical"
        :aria-label="'拖动调节导航栏宽度（当前 ' + (sidebarWidth ?? SIDEBAR_DEFAULT) + ' 像素，双击恢复默认）'"
        :aria-valuenow="sidebarWidth ?? SIDEBAR_DEFAULT"
        :aria-valuemin="SIDEBAR_MIN"
        :aria-valuemax="SIDEBAR_MAX"
        :class="{ active: sidebarResizing }"
        title="拖动调节导航栏宽度，双击恢复默认"
        @mousedown="startSidebarResize"
        @dblclick="resetSidebarWidth"
      >
        <span class="sidebar-resizer-grip" aria-hidden="true"></span>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar no-print">
        <span>{{ pageName }}</span>
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
                    data-testid="ocr-open"
                    :disabled="busy"
                    @click="
                      modal = 'ocr';
                      ocrPreview = null;
                      ocrError = '';
                    "
                  >
                    <ScanText :size="16" />识别成绩单</button
                  ><button
                    v-if="isTeacher && can('ENTRY') && !submitted"
                    class="secondary"
                    data-testid="voice-open"
                    :disabled="busy"
                    @click="openVoice(null)"
                  >
                    <Mic :size="16" />语音录入</button
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
                        ><!-- 重修只看学生在该课程号上的历史：课程名本身与重修无关 -->
                        <span
                          v-if="row.retake"
                          class="badge amber"
                          title="该生此前学期此课程未通过，本学期重修同一课程号"
                          >重修</span
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
                        <div class="status-cell">
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
                          <button
                            v-if="isTeacher && can('ENTRY') && !submitted"
                            class="icon-button voice-row"
                            :aria-label="`为 ${row.username} 语音录入`"
                            :data-testid="'voice-row-' + row.username"
                            :disabled="busy"
                            @click="openVoice(row)"
                          >
                            <Mic :size="14" />
                          </button>
                        </div>
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
              <!-- 预测对象是「已录入平时/实验、期末未考」的学生，因此只对进行中的课程有意义 -->
              <p v-if="isStudent" class="muted" style="margin: 0 0 14px">
                学业预警针对<strong>正在进行中</strong>的课程：已录入平时与实验、期末尚未考试时，
                可以预估期末与总评成绩。请在上方课程选择里选一门当前学期（{{
                  latestTerm || "最新学期"
                }}）的课程。</p>
              <p v-else class="muted" style="margin: 0 0 14px">
                学业预警针对<strong>正在进行中</strong>的课程：预估尚未录入期末成绩的学生可能得到的总评，
                并对临界分数给出风险提示。</p>
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
                          <!-- 预测对象是「已有平时/实验、期末还没考」的学生；
                               课程已出分时本人这条成绩是完整的，因此没有可预测对象。 -->
                          <template v-if="isStudent">
                            《{{ selected?.name || "该课程" }}》本学期的期末成绩已经录入，
                            预测对象为空。学业预警针对<strong>正在进行中</strong>的课程
                            （已录入平时与实验、期末未考）；请在下方课程选择里选一门当前学期
                            （{{ latestTerm || "最新学期" }}）的课程再试。
                          </template>
                          <template v-else>
                            该课程暂无「已录入平时与实验、期末未录入」的学生，
                            因此没有需要预测的对象。学业预警针对<strong>正在进行中</strong>的课程。
                          </template>
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
              <span>已获课程</span
              ><strong>{{ passedCourseCount }}<small>门</small></strong>
            </div>
            <div>
              <span>已获学分</span
              ><strong>{{ earnedCredits }}</strong>
            </div>
            <div>
              <span>未通过课程</span
              ><strong :class="{ failed: pendingCourseCount > 0 }"
                >{{ pendingCourseCount }}<small>门</small></strong
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
                    <strong>{{ g.name }}</strong>
                    <!-- 重修与课程名无关：同一课程号在后续学年重新修读，这里只标状态 -->
                    <span
                      v-if="g.retake"
                      class="badge amber"
                      title="此前学期该课程未通过，本次为重修"
                      >重修</span
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
        <template v-if="page === 'selection'">
          <!-- 管理员：网上选课 + 课程与选课合并为一个「选课管理」页，内含 3 个标签页。 -->
          <template v-if="isAdmin">
            <div class="sel-tabs no-print">
              <button
                v-for="t in adminSelectionTabs"
                :key="t.key"
                class="sel-tab"
                :class="{ active: selectionTab === t.key }"
                @click="switchSelectionTab(t.key)"
              >
                <component :is="t.icon" :size="16" />{{ t.name }}
              </button>
            </div>
            <template v-if="selectionTab === 'courses'">
              <div class="section-toolbar">
                <span class="muted">{{ courses.length }} 门课程</span>
                <div class="toolbar-actions">
                  <button
                    class="icon-button"
                    title="刷新课程"
                    aria-label="刷新课程"
                    :disabled="busy"
                    @click="run(loadSelectionCourses)"
                  >
                    <RefreshCw :size="17" :class="{ spinning: busy }" />
                  </button>
                  <button class="primary" @click="editCourse()">
                    <Plus :size="16" />新建课程
                  </button>
                </div>
              </div>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>课程</th>
                      <th>学期</th>
                      <th>学分</th>
                      <th>授课教师</th>
                      <th>开设学院</th>
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
                      <td>{{ teacherNameOf(c) }}</td>
                      <td>{{ c.collegeName || "--" }}</td>
                      <td>
                        <div class="toolbar-actions">
                          <button
                            class="secondary"
                            :title="'为 ' + c.name + ' 选课'"
                            @click="openEnrollment(c)"
                          >
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
                    <tr v-if="!courses.length">
                      <td colspan="6" class="empty">暂无课程</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
            <SelectionView
              v-else
              :key="selectionTab"
              :user="user"
              :view="selectionTab"
              embedded
              @notice="notice = $event"
              @error="error = $event"
            />
          </template>
          <!-- 学生：只显示选课台，保持原行为不变。 -->
          <SelectionView
            v-else
            :user="user"
            @notice="notice = $event"
            @error="error = $event"
          />
        </template>
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
        <OrganizationView
          v-if="page === 'organization' && isAdmin"
          :user="user"
          @notice="notice = $event"
          @error="error = $event"
        />
        <footer class="workspace-footer">
          <span>知序 · 高校成绩管理</span
          ><span>学业记录 / {{ new Date().getFullYear() }}</span>
        </footer>
      </main>
    </div>
    <div
      v-if="modal"
      class="modal-backdrop no-print"
      @click.self="!busy && !enrollWorking && (modal = '')"
    >
      <section
        class="modal"
        :class="{ wide: modal === 'enroll' || modal === 'ocr' }"
        role="dialog"
        aria-modal="true"
        :aria-label="
          {
            weights: '成绩系数',
            transition: '确认成绩操作',
            user: '人员信息',
            course: '课程信息',
            enroll: '按课程选课',
            ocr: '识别成绩单',
            voice: '语音录入',
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
                enroll: "按课程选课",
                ocr: "识别成绩单",
                voice: "语音录入",
                review: "审计复核",
              }[modal]
            }}
          </h2>
          <button
            class="icon-button"
            aria-label="关闭对话框"
            :disabled="busy || enrollWorking"
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
            ><label v-if="userForm.role !== 'ADMIN'"
              >学院<select
                v-model="userForm.college"
                required
                @change="selectUserCollege"
              >
                <option value="">选择学院</option>
                <option v-for="c in orgOptions.colleges" :key="c.id" :value="c.name">
                  {{ c.name }}
                </option>
              </select></label
            ><label v-if="userForm.role !== 'ADMIN'"
              >专业<select
                v-model="userForm.major"
                required
                @change="selectUserMajor"
              >
                <option value="">选择专业</option>
                <option v-for="m in userMajorOptions" :key="m.id" :value="m.name">
                  {{ m.name }}
                </option>
              </select></label
            ><label v-if="userForm.role === 'STUDENT'"
              >班级<select v-model="userForm.klass" required>
                <option value="">选择班级</option>
                <option v-for="c in userClassOptions" :key="c.id" :value="c.name">
                  {{ c.name }}
                </option>
              </select></label
            ><p
              v-if="userForm.role !== 'ADMIN' && !orgOptions.colleges.length"
              class="muted"
            >
              组织选项不可用，请先在「组织管理」维护学院 / 专业 / 班级。
            </p>
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
                v-if="teacherOptions.length"
                v-model="courseForm.teacherId"
                required
              >
                <option value="" disabled>选择教师</option>
                <option
                  v-for="t in teacherOptions"
                  :key="t.id"
                  :value="t.id"
                >
                  {{ t.name }}{{ t.collegeName ? " · " + t.collegeName : "" }}
                </option></select
              ><input
                v-else
                v-model="courseForm.teacherId"
                required
                placeholder="教师编号"
            /></label>
            <!-- 开设院系：默认随授课教师所在院系自动带出，管理员可自行改选（留空会被后端拒绝） -->
            <label
              >开设院系<select
                v-if="orgOptions.colleges.length"
                v-model="courseForm.collegeId"
                data-testid="course-college"
                required
                @change="collegeTouched = true"
              >
                <option value="" disabled>选择开设院系</option>
                <option v-for="c in orgOptions.colleges" :key="c.id" :value="c.id">
                  {{ c.name }}
                </option></select
              ><input
                v-else
                v-model="courseForm.collegeId"
                data-testid="course-college"
                required
                placeholder="院系编号（如 C01001）"
            /></label>
          </div>
          <div class="modal-actions">
            <button class="primary" :disabled="busy">保存课程</button>
          </div>
        </form>
        <!-- 按课程选课：可选一个/多个学生，或某个班级的全部学生，两种方式并集去重。 -->
        <div v-if="modal === 'enroll'" class="enroll-body">
          <div v-if="enrollCourse" class="enroll-course">
            <div>
              <span class="course-code">{{ enrollCourse.code }}</span>
              <strong>{{ enrollCourse.name }}</strong>
            </div>
            <p class="muted">
              {{ enrollCourse.term }} 学期 <span class="dot">·</span>
              {{ enrollCourse.credits }} 学分 <span class="dot">·</span> 授课教师
              {{ teacherNameOf(enrollCourse) }} <span class="dot">·</span> 开设学院
              {{ enrollCourse.collegeName || "--" }}
            </p>
            <p class="muted">
              当前名单 <strong>{{ enrollRoster.length }}</strong> 人
              <span v-if="enrollLoading"> · 加载中…</span>
            </p>
          </div>
          <label>
            班级（整班处理）
            <select v-model="enrollClassName" :disabled="enrollWorking">
              <option value="">不按班级整班处理</option>
              <option v-for="c in orgOptions.classes" :key="c.id" :value="c.name">
                {{ c.name }}{{ c.majorName ? " · " + c.majorName : "" }}
              </option>
            </select>
          </label>
          <p v-if="enrollClassName" class="muted enroll-hint">
            班级「{{ enrollClassName }}」共 {{ enrollClassMembers.length }} 名在读学生
          </p>
          <div class="enroll-search">
            <span class="search">
              <Search :size="16" />
              <input
                v-model="enrollKeyword"
                placeholder="按姓名 / 学号 / 班级 / 专业筛选"
                aria-label="筛选学生"
                :disabled="enrollWorking"
              />
            </span>
            <span class="muted">
              已勾选 {{ enrollStudentIds.length }} 人<span
                v-if="enrollClassName"
              >
                · 整班 {{ enrollClassMembers.length }} 人</span
              >
            </span>
          </div>
          <div v-if="enrollLoading" class="muted">学生名单加载中…</div>
          <div v-else class="org-student-list enroll-student-list">
            <label v-for="s in enrollVisibleStudents" :key="s.id" class="org-student">
              <input
                v-model="enrollStudentIds"
                type="checkbox"
                :value="s.id"
                :disabled="enrollWorking"
              />
              <span>{{ s.name }}</span>
              <small class="muted">{{ s.username }}</small>
              <small class="muted enroll-student-class">{{
                s.className || "未分班"
              }}</small>
              <span v-if="retakeIds.has(s.id)" class="badge amber" title="该生此前学期此课程未通过，本学期重修同一课程号">重修</span>
              <span v-if="enrolledIds.has(s.id)" class="badge green">已选</span>
            </label>
            <p v-if="!enrollVisibleStudents.length" class="muted">
              没有符合条件的学生
            </p>
          </div>
          <p class="muted enroll-hint">
            将处理 <strong>{{ enrollSelectedIds.length }}</strong> 名学生（勾选与整班并集去重）
          </p>

          <div v-if="enrollResult" class="sel-result">
            <div class="sel-result-head">
              <CheckCircle2 :size="18" />
              <strong>{{ enrollRemoved ? "退课结果" : "选课结果" }}</strong>
            </div>
            <p class="weight-summary">
              <span>新增 {{ enrollResult.added || 0 }}</span>
              <span>跳过 {{ enrollResult.skipped || 0 }}</span>
              <span>移除 {{ enrollResult.removed || 0 }}</span>
              <span
                v-if="
                  enrollResult.classStudents !== undefined &&
                  enrollResult.classStudents !== null
                "
                >班级 {{ enrollResult.classStudents }} 人</span
              >
              <span class="failed"
                >失败 {{ (enrollResult.failed || []).length }}</span
              >
            </p>
            <div v-if="(enrollResult.failed || []).length" class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>学号</th>
                    <th>姓名</th>
                    <th>失败原因</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(f, index) in enrollResult.failed" :key="index">
                    <td>{{ f.studentId }}</td>
                    <td>{{ f.name || "--" }}</td>
                    <td class="failed">{{ f.reason }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="modal-actions">
            <button
              type="button"
              class="secondary"
              :disabled="enrollWorking"
              @click="modal = ''"
            >
              关闭
            </button>
            <button
              type="button"
              class="danger"
              :disabled="enrollWorking || !enrollSelectedIds.length"
              @click="submitEnrollment(true)"
            >
              <UserMinus :size="16" />{{
                enrollWorking && enrollRemoved ? "退课中…" : "退课"
              }}
            </button>
            <button
              type="button"
              class="primary"
              :disabled="enrollWorking || !enrollSelectedIds.length"
              @click="submitEnrollment(false)"
            >
              <UserCheck :size="16" />{{
                enrollWorking && !enrollRemoved ? "选课中…" : "选课"
              }}
            </button>
          </div>
        </div>
        <div v-if="modal === 'ocr'">
          <label
            >成绩单图片<input
              type="file"
              accept="image/png,image/jpeg,image/webp"
              data-testid="ocr-file"
              :disabled="busy"
              @change="recognize" /></label
          ><progress v-if="busy || ocrStage" :value="ocrProgress" max="100"></progress>
          <p v-if="busy || ocrStage" class="ocr-stage" data-testid="ocr-stage">{{ ocrStageLabel }}</p>
          <p v-if="ocrError" class="error" data-testid="ocr-error">{{ ocrError }}</p>
          <RecognizePreview
            :open="!!ocrPreview"
            source="local-ocr"
            :rows="ocrRows"
            :components="ocrComponents"
            :columns="ocrColumns"
            :column-map="ocrColumnMap"
            :summary="ocrSummary"
            :busy="busy"
            @update:column-map="ocrColumnMap = $event"
            @close="modal = ''"
            @confirm="applyRecognized"
          />
          <div v-if="!ocrPreview && !busy" class="ocr-tips">
            <p>
              支持的版式：<strong>一人一行</strong>的成绩单 —— 学号在最左列，右边依次是本课程的成绩列（数字）。
              打印件、Excel/网页截图、手机拍的纸面照片都可以；深色背景截图会自动反色。
            </p>
            <p>
              暂不支持：学生放在列、成绩放成行的转置表；合并单元格跨多行的表头；手写分数（会尽力识别，但需要人工核对）。
            </p>
            <p>识别在浏览器本地完成，图片不会上传服务器；结果需你确认后才写入表单。</p>
          </div>
        </div>
        <div v-if="modal === 'voice'">
          <VoicePanel
            :open="true"
            :components="ocrComponents"
            :rows="rows"
            :target="voiceTargetRow"
            :row-defaults="voiceDefaults"
            @select-target="voiceTargetRow = $event"
            @apply="applyVoice"
            @close="modal = ''"
          />
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
