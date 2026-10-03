<script setup>
import { computed, onMounted, ref, watch } from "vue";
import {
  AlertTriangle,
  BookOpen,
  CalendarClock,
  ChevronLeft,
  ChevronRight,
  ClipboardCheck,
  ListChecks,
  RefreshCw,
  Search,
  X,
} from "lucide-vue-next";
import { api } from "../api";

const props = defineProps({
  user: { type: Object, default: null },
  /**
   * 渲染范围：auto（默认，按权限显示自己的标签页）、enroll / publish / records（只显示其中一块）。
   * 管理员「选课管理」页（App.vue）用 publish / records 内嵌本组件的两个区块。
   */
  view: { type: String, default: "auto" },
  /** 内嵌模式：不再渲染自己的标签栏，只保留一个刷新按钮。 */
  embedded: { type: Boolean, default: false },
});
const emit = defineEmits(["notice", "error"]);

const ACTION_NAMES = {
  SELECT: "选课",
  DROP: "退课",
  ADMIN_ASSIGN: "教务代选",
  ADMIN_REMOVE: "教务代退",
  AUTO_REFUND: "自动退回",
};
const SOURCE_NAMES = {
  SELECTION: "学生选课",
  ADMIN: "教务代选",
  SEED: "初始化导入",
};
const STATUS_NAMES = {
  OPEN: "进行中",
  CLOSED: "已结束",
  CANCELLED: "已取消",
  ACTIVE: "已选上",
  DROPPED: "已退课",
};

/** 后端部分接口返回分页对象 {items,total,page,size}，部分返回数组，这里统一取值。 */
function listOf(result, key) {
  if (Array.isArray(result)) return result;
  if (result && Array.isArray(result[key])) return result[key];
  if (result && Array.isArray(result.items)) return result.items;
  return [];
}
function totalOf(result, fallback) {
  if (result && typeof result.total === "number") return result.total;
  return fallback;
}
function message(e) {
  return (e && e.message) || "请求失败";
}
function fmtTime(value) {
  if (!value) return "--";
  return String(value).replace("T", " ").slice(0, 16);
}
function toLocalInput(value) {
  if (!value) return "";
  const text = String(value).replace(" ", "T");
  return text.length >= 16 ? text.slice(0, 16) : text;
}
function statusName(row) {
  return row.statusName || STATUS_NAMES[row.status] || row.status || "--";
}
function statusBadge(row) {
  const key = String(row.status || "");
  if (key === "OPEN") return "green";
  if (key === "CLOSED") return "amber";
  if (key === "CANCELLED") return "red";
  // 只有中文状态名时按名称兜底着色。
  const name = String(row.statusName || "");
  if (name.includes("进行") || name.includes("发布")) return "green";
  if (name.includes("结束") || name.includes("关闭") || name.includes("结算"))
    return "amber";
  if (name.includes("取消")) return "red";
  return "neutral";
}
function actionName(action) {
  return ACTION_NAMES[action] || action || "--";
}
function sourceName(source) {
  return SOURCE_NAMES[source] || source || "--";
}

const perms = computed(() => {
  const raw = props.user ? props.user.permissions : null;
  return Array.isArray(raw) ? raw : String(raw || "").split(",");
});
const isStudent = computed(() => !!(props.user && props.user.role === "STUDENT"));
const canEnroll = computed(() => isStudent.value || perms.value.includes("SELECTION_ENROLL"));
const canAdmin = computed(() => perms.value.includes("SELECTION_ADMIN"));

/**
 * 管理员只有「选课批次」与「选课记录」两块：原先独立的「批量选课」已并入
 * 每门课程的选课弹窗（App.vue「课程与选课」标签页的「选课」按钮），此处不再重复提供。
 * 学生视角保持原样，只有「选课中心」。
 */
const ALL_TABS = computed(() => [
  ...(canEnroll.value ? [{ key: "enroll", name: "选课中心", icon: BookOpen }] : []),
  ...(canAdmin.value
    ? [
        { key: "publish", name: "选课批次", icon: CalendarClock },
        { key: "records", name: "选课记录", icon: ListChecks },
      ]
    : []),
]);
const TABS = computed(() => {
  const wanted = String(props.view || "auto");
  if (!wanted || wanted === "auto") return ALL_TABS.value;
  return ALL_TABS.value.filter((t) => t.key === wanted);
});
const tab = ref("");

/* ---------------------------------------------------------------- 公共数据 */
const loadingEnroll = ref(false);
const loadingPublish = ref(false);
const loadingRecords = ref(false);
const catalogLoading = ref(false);
const batches = ref([]);
const mySelections = ref([]);
const catalog = ref([]);
const orgOptions = ref({ colleges: [], majors: [], classes: [] });
const workingCourse = ref("");

function isWorking(batch, course) {
  return workingCourse.value === batch.id + ":" + course.id;
}

async function loadEnroll() {
  loadingEnroll.value = true;
  try {
    const [available, mine] = await Promise.all([
      api("/selections/available"),
      api("/selections/my"),
    ]);
    batches.value = listOf(available);
    mySelections.value = listOf(mine);
  } catch (e) {
    emit("error", message(e));
  } finally {
    loadingEnroll.value = false;
  }
}

async function chooseCourse(batch, course) {
  if (workingCourse.value) return;
  workingCourse.value = batch.id + ":" + course.id;
  try {
    await api("/selections/select", { publishId: batch.id, courseId: course.id });
    emit("notice", "已选上课程：" + course.name);
    await loadEnroll();
  } catch (e) {
    emit("error", message(e));
  } finally {
    workingCourse.value = "";
  }
}

async function dropCourse(batch, course) {
  if (workingCourse.value) return;
  workingCourse.value = batch.id + ":" + course.id;
  try {
    await api("/selections/drop", { publishId: batch.id, courseId: course.id });
    emit("notice", "已退课：" + course.name);
    await loadEnroll();
  } catch (e) {
    emit("error", message(e));
  } finally {
    workingCourse.value = "";
  }
}

/* ------------------------------------------------------------ 教务管理数据 */
const publishRows = ref([]);
const publishTotal = ref(0);
const publishPage = ref(1);
const publishFilter = ref({ status: "", term: "" });
const publishSize = 100;

const publishTerms = computed(() => {
  const values = publishRows.value.map((r) => r.term).filter(Boolean);
  return [...new Set(values)];
});

async function loadCatalog() {
  if (catalog.value.length || catalogLoading.value) return;
  catalogLoading.value = true;
  try {
    const result = await api("/courses/catalog?size=300");
    catalog.value = listOf(result, "items");
  } catch (e) {
    emit("error", message(e));
  } finally {
    catalogLoading.value = false;
  }
}

async function loadOptions() {
  try {
    const result = await api("/organizations/options");
    orgOptions.value = {
      colleges: listOf(result, "colleges"),
      majors: listOf(result, "majors"),
      classes: listOf(result, "classes"),
    };
  } catch (e) {
    emit("error", message(e));
  }
}

async function loadPublishes() {
  loadingPublish.value = true;
  try {
    let path = "/selections?page=" + publishPage.value + "&size=" + publishSize;
    if (publishFilter.value.status)
      path += "&status=" + encodeURIComponent(publishFilter.value.status);
    if (publishFilter.value.term)
      path += "&term=" + encodeURIComponent(publishFilter.value.term);
    const result = await api(path);
    publishRows.value = listOf(result, "items");
    publishTotal.value = totalOf(result, publishRows.value.length);
  } catch (e) {
    emit("error", message(e));
  } finally {
    loadingPublish.value = false;
  }
}

function searchPublishes() {
  publishPage.value = 1;
  loadPublishes();
}

function turnPublish(delta) {
  const next = publishPage.value + delta;
  if (next < 1) return;
  publishPage.value = next;
  loadPublishes();
}

/* ------------------------------------------------------------ 批次维护表单 */
const dialog = ref("");
const editing = ref(false);
const saving = ref(false);
const formLoading = ref(false);
const form = ref({});
const courseSearch = ref("");

const visibleCatalog = computed(() => {
  const keyword = courseSearch.value.trim();
  if (!keyword) return catalog.value;
  return catalog.value.filter(
    (c) =>
      String(c.name || "").includes(keyword) ||
      String(c.code || "").includes(keyword) ||
      String(c.teacherName || "").includes(keyword),
  );
});
const formTermCourses = computed(() =>
  visibleCatalog.value.filter(
    (c) => !form.value.term || !c.term || c.term === form.value.term,
  ),
);

/** 后端可能返回数组，也可能返回逗号分隔字符串，这里统一成数组。 */
function asArray(value) {
  if (Array.isArray(value)) return value.filter((v) => v !== null && v !== undefined);
  if (typeof value === "string" && value.trim())
    return value
      .split(",")
      .map((v) => v.trim())
      .filter(Boolean);
  return [];
}
/** 编辑批次时回填课程：优先 courseIds，其次由 courses[] 推导。 */
function courseIdsOf(row) {
  const direct = asArray(row.courseIds);
  if (direct.length) return direct;
  return asArray(row.courses)
    .map((c) => c.id)
    .filter((v) => v !== undefined);
}
/** 选课范围下拉绑定的是名称，后端同时给了 *Names 与编号，取不到名称时退回编号（后端也接受编号）。 */
function scopeValuesOf(row, namesKey, idsKey, poolKey) {
  const names = asArray(row[namesKey]);
  if (names.length) return names;
  const pool = orgOptions.value[poolKey] || [];
  return asArray(row[idsKey]).map((id) => {
    const option = pool.find((o) => o.id === id);
    return option ? option.name : id;
  });
}

async function openPublishForm(row) {
  editing.value = !!row;
  courseSearch.value = "";
  form.value = {
    id: row ? row.id : "",
    name: row ? row.name || "" : "",
    term: row ? row.term || "" : "",
    startTime: toLocalInput(row ? row.start_time || row.startTime : ""),
    endTime: toLocalInput(row ? row.end_time || row.endTime : ""),
    minEnroll: row ? Number(row.minEnroll) || 0 : 10,
    maxCredits: row ? Number(row.maxCredits) || 0 : 30,
    allowAdd: row ? Number(row.allowAdd) : 1,
    allowDrop: row ? Number(row.allowDrop) : 1,
    allowRetake: row ? Number(row.allowRetake) : 0,
    note: row ? row.note || "" : "",
    courseIds: [],
    scopeCollegeIds: [],
    scopeMajorIds: [],
    scopeClassIds: [],
    scopeLabel: row ? row.scopeLabel || "" : "",
  };
  dialog.value = "form";
  formLoading.value = true;
  try {
    await Promise.all([loadCatalog(), loadOptions()]);
    if (row) {
      form.value.courseIds = courseIdsOf(row);
      form.value.scopeCollegeIds = scopeValuesOf(
        row,
        "scopeCollegeNames",
        "scopeCollegeIds",
        "colleges",
      );
      form.value.scopeMajorIds = scopeValuesOf(
        row,
        "scopeMajorNames",
        "scopeMajorIds",
        "majors",
      );
      form.value.scopeClassIds = scopeValuesOf(
        row,
        "scopeClassNames",
        "scopeClassIds",
        "classes",
      );
    }
  } finally {
    formLoading.value = false;
  }
}

async function submitPublish() {
  if (saving.value) return;
  const current = form.value;
  const name = String(current.name || "").trim();
  const term = String(current.term || "").trim();
  if (!name) {
    emit("error", "请填写批次名称");
    return;
  }
  if (!term) {
    emit("error", "请填写学年学期");
    return;
  }
  if (!current.startTime || !current.endTime) {
    emit("error", "请选择选课起止时间");
    return;
  }
  if (String(current.endTime) <= String(current.startTime)) {
    emit("error", "结束时间必须晚于开始时间");
    return;
  }
  if (!current.courseIds.length) {
    emit("error", "请至少选择一门课程");
    return;
  }
  const body = {
    name,
    term,
    courseIds: current.courseIds.slice(),
    scopeCollegeIds: current.scopeCollegeIds.slice(),
    scopeMajorIds: current.scopeMajorIds.slice(),
    scopeClassIds: current.scopeClassIds.slice(),
    startTime: current.startTime,
    endTime: current.endTime,
    minEnroll: Number(current.minEnroll) || 0,
    maxCredits: Number(current.maxCredits) || 0,
    allowAdd: Number(current.allowAdd) ? 1 : 0,
    allowDrop: Number(current.allowDrop) ? 1 : 0,
    allowRetake: Number(current.allowRetake) ? 1 : 0,
    note: String(current.note || "").trim(),
  };
  if (editing.value && current.id) body.id = current.id;
  saving.value = true;
  try {
    await api("/selections/save", body);
    dialog.value = "";
    emit("notice", (editing.value ? "已更新选课批次：" : "已发布选课批次：") + name);
    await loadPublishes();
  } catch (e) {
    emit("error", message(e));
  } finally {
    saving.value = false;
  }
}

async function closePublish(row) {
  if (!window.confirm("确认关闭批次「" + row.name + "」？关闭后学生不能再选课或退课。"))
    return;
  workingAction.value = "close:" + row.id;
  try {
    await api("/selections/close", { id: row.id });
    emit("notice", "已关闭批次：" + row.name);
    await loadPublishes();
  } catch (e) {
    emit("error", message(e));
  } finally {
    workingAction.value = "";
  }
}

const workingAction = ref("");
const cancelTarget = ref(null);
const cancelReason = ref("");
const settleTarget = ref(null);
const settleResult = ref(null);

function openCancel(row) {
  cancelTarget.value = row;
  cancelReason.value = "";
  dialog.value = "cancel";
}

async function submitCancel() {
  if (workingAction.value || !cancelTarget.value) return;
  const reason = cancelReason.value.trim();
  if (!reason) {
    emit("error", "请填写取消原因");
    return;
  }
  workingAction.value = "cancel";
  try {
    await api("/selections/cancel", { id: cancelTarget.value.id, reason });
    emit("notice", "已取消批次：" + cancelTarget.value.name);
    dialog.value = "";
    await loadPublishes();
  } catch (e) {
    emit("error", message(e));
  } finally {
    workingAction.value = "";
  }
}

async function openSettle(row) {
  settleTarget.value = row;
  settleResult.value = null;
  workingAction.value = "settle:" + row.id;
  dialog.value = "settle";
  try {
    settleResult.value = await api("/selections/settle", { id: row.id });
    emit("notice", "结算完成，自动退回 " + (settleResult.value.refunded || 0) + " 人次");
    await loadPublishes();
  } catch (e) {
    emit("error", message(e));
  } finally {
    workingAction.value = "";
  }
}

/* ---------------------------------------------------------------- 选课记录 */
const recordRows = ref([]);
const recordTotal = ref(0);
const recordPage = ref(1);
const recordFilter = ref({ publishId: "", courseId: "", action: "" });
const recordSize = 100;

async function loadRecords() {
  loadingRecords.value = true;
  try {
    let path = "/selections/records?page=" + recordPage.value + "&size=" + recordSize;
    if (recordFilter.value.publishId)
      path += "&publishId=" + encodeURIComponent(recordFilter.value.publishId);
    if (recordFilter.value.courseId)
      path += "&courseId=" + encodeURIComponent(recordFilter.value.courseId);
    if (recordFilter.value.action)
      path += "&action=" + encodeURIComponent(recordFilter.value.action);
    const result = await api(path);
    recordRows.value = listOf(result, "items");
    recordTotal.value = totalOf(result, recordRows.value.length);
  } catch (e) {
    emit("error", message(e));
  } finally {
    loadingRecords.value = false;
  }
}

function searchRecords() {
  recordPage.value = 1;
  loadRecords();
}

function turnRecord(delta) {
  const next = recordPage.value + delta;
  if (next < 1) return;
  recordPage.value = next;
  loadRecords();
}

/* ------------------------------------------------------------------ 生命周期 */
const loading = computed(
  () => loadingEnroll.value || loadingPublish.value || loadingRecords.value,
);

async function loadTab() {
  if (tab.value === "enroll") {
    await loadEnroll();
    return;
  }
  if (!publishRows.value.length) await loadPublishes();
  if (tab.value === "publish") return;
  await loadCatalog();
  if (tab.value === "records") await loadRecords();
}

function firstTab() {
  return TABS.value.length ? TABS.value[0].key : "";
}

async function switchTab(key) {
  if (key === tab.value) return;
  tab.value = key;
  await loadTab();
}

/** 刷新：强制重取当前区块数据（loadTab 在已有批次数据时会跳过 loadPublishes）。 */
async function refresh() {
  if (tab.value === "enroll") {
    await loadEnroll();
    return;
  }
  if (tab.value === "publish") {
    await loadPublishes();
    return;
  }
  if (tab.value === "records") {
    await Promise.all([loadPublishes(), loadCatalog(), loadRecords()]);
    return;
  }
  await loadTab();
}

onMounted(async () => {
  tab.value = firstTab();
  await loadTab();
});

// 内嵌使用时 props.view 变化要重取数据（App.vue 还会用 :key 强制重建）。
watch(
  () => props.view,
  async () => {
    tab.value = firstTab();
    await loadTab();
  },
);
</script>

<template>
  <div v-if="!TABS.length" class="empty">
    <AlertTriangle :size="30" />
    <p>当前账号没有选课权限</p>
  </div>
  <div v-else class="sel-view">
    <div v-if="!embedded" class="sel-tabs no-print">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="sel-tab"
        :class="{ active: tab === t.key }"
        @click="switchTab(t.key)"
      >
        <component :is="t.icon" :size="16" />{{ t.name }}
      </button>
      <button
        class="icon-button"
        title="刷新"
        aria-label="刷新"
        :disabled="loading"
        @click="refresh"
      >
        <RefreshCw :size="17" :class="{ spinning: loading }" />
      </button>
    </div>
    <div v-else class="section-toolbar no-print">
      <span class="muted">{{ TABS.length ? TABS[0].name : "" }}</span>
      <button
        class="icon-button"
        title="刷新"
        aria-label="刷新"
        :disabled="loading"
        @click="refresh"
      >
        <RefreshCw :size="17" :class="{ spinning: loading }" />
      </button>
    </div>

    <!-- ===================================================== 学生选课中心 -->
    <section v-if="tab === 'enroll'">
      <div class="section-toolbar">
        <span class="muted">共 {{ batches.length }} 个可选课批次</span>
      </div>
      <div v-if="loadingEnroll" class="muted sel-loading">加载中…</div>
      <div v-else-if="!batches.length" class="empty">
        <ClipboardCheck :size="30" />
        <p>当前没有可选课的批次</p>
      </div>
      <div v-for="b in batches" :key="b.id" class="sel-card">
        <div class="sel-card-head">
          <div>
            <h3>{{ b.name }}</h3>
            <p class="muted">
              {{ b.term }} <span class="dot">·</span> {{ fmtTime(b.start_time) }} ~
              {{ fmtTime(b.end_time) }}
            </p>
          </div>
          <div class="sel-card-tags">
            <span class="badge" :class="Number(b.allowAdd) ? 'green' : 'neutral'">
              {{ Number(b.allowAdd) ? "可选" : "不可选" }}
            </span>
            <span class="badge" :class="Number(b.allowDrop) ? 'green' : 'neutral'">
              {{ Number(b.allowDrop) ? "可退" : "不可退" }}
            </span>
            <span class="badge" :class="statusBadge(b)">{{ statusName(b) }}</span>
          </div>
        </div>
        <p v-if="b.note" class="muted">{{ b.note }}</p>
        <p class="weight-summary">
          <span>最低开课人数 {{ b.minEnroll }}</span>
          <span>学分上限 {{ b.maxCredits }}</span>
          <span v-if="Number(b.allowRetake)">允许重修</span>
        </p>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>课程</th>
                <th>授课教师</th>
                <th>开设学院</th>
                <th>学分</th>
                <th>已选人数</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in b.courses || []" :key="c.id">
                <td>
                  <strong>{{ c.name }}</strong>
                  <!-- 重修只是「同一课程号在本学年重新修读」，课程名与重修无关，因此用徽标标注状态 -->
                  <span v-if="c.retake" class="badge amber" title="此前学期该课程未通过，本学期重修同一课程号">重修</span>
                  <small class="block muted">{{ c.code }}</small>
                </td>
                <td>{{ c.teacherName || "--" }}</td>
                <td>{{ c.collegeName || "--" }}</td>
                <td>{{ c.credits }}</td>
                <td>{{ c.enrolled }}</td>
                <td>
                  <span v-if="c.selected" class="badge green">已选</span>
                  <span
                    v-else-if="!c.eligible"
                    class="badge red"
                    :title="c.reason || '不满足选课条件'"
                  >
                    {{ c.reason || "不可选" }}
                  </span>
                  <span v-else class="badge neutral">可选</span>
                </td>
                <td>
                  <button
                    v-if="c.selected"
                    class="secondary"
                    :disabled="!Number(b.allowDrop) || !!workingCourse"
                    @click="dropCourse(b, c)"
                  >
                    {{ isWorking(b, c) ? "退课中…" : "退课" }}
                  </button>
                  <button
                    v-else
                    class="primary"
                    :title="c.reason || ''"
                    :disabled="!c.eligible || !Number(b.allowAdd) || !!workingCourse"
                    @click="chooseCourse(b, c)"
                  >
                    {{ isWorking(b, c) ? "选课中…" : "选课" }}
                  </button>
                </td>
              </tr>
              <tr v-if="!(b.courses || []).length">
                <td colspan="7" class="empty">该批次暂未开放课程</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <h3 class="sel-section-title">我的选课</h3>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>课程</th>
              <th>学期</th>
              <th>学分</th>
              <th>授课教师</th>
              <th>开设学院</th>
              <th>来源</th>
              <th>选课时间</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in mySelections" :key="m.id">
              <td>
                <strong>{{ m.name }}</strong>
                <span v-if="m.retake" class="badge amber" title="此前学期该课程未通过，本学期重修同一课程号">重修</span>
                <small class="block muted">{{ m.code }}</small>
              </td>
              <td>{{ m.term }}</td>
              <td>{{ m.credits }}</td>
              <td>{{ m.teacherName || "--" }}</td>
              <td>{{ m.collegeName || "--" }}</td>
              <td>{{ sourceName(m.source) }}</td>
              <td>{{ fmtTime(m.selected_at) }}</td>
              <td>
                <span v-if="m.retake" class="badge amber">重修中</span>
                <span v-else class="badge" :class="m.status === 'DROPPED' ? 'red' : 'green'">
                  {{ STATUS_NAMES[m.status] || m.status }}
                </span>
              </td>
            </tr>
            <tr v-if="!mySelections.length">
              <td colspan="8" class="empty">还没有选课记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ===================================================== 教务：选课批次 -->
    <section v-else-if="tab === 'publish'">
      <div class="sel-filters no-print">
        <label>
          状态
          <select v-model="publishFilter.status" :disabled="loadingPublish" @change="searchPublishes">
            <option value="">全部状态</option>
            <option value="OPEN">进行中</option>
            <option value="CLOSED">已关闭</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </label>
        <label>
          学期
          <input
            v-model="publishFilter.term"
            list="sel-publish-terms"
            placeholder="全部学期"
            :disabled="loadingPublish"
            @keyup.enter="searchPublishes"
          />
          <datalist id="sel-publish-terms">
            <option v-for="t in publishTerms" :key="t" :value="t"></option>
          </datalist>
        </label>
        <button class="secondary" :disabled="loadingPublish" @click="searchPublishes">
          <Search :size="15" />查询
        </button>
        <span class="muted">共 {{ publishTotal }} 个批次</span>
        <button class="primary" :disabled="loadingPublish" @click="openPublishForm()">
          <CalendarClock :size="16" />新建批次
        </button>
      </div>

      <div v-if="loadingPublish" class="muted sel-loading">加载中…</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>批次</th>
              <th>起止时间</th>
              <th>课程数</th>
              <th>已选人数</th>
              <th>门槛 / 上限</th>
              <th>发布</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in publishRows" :key="row.id">
              <td>
                <strong>{{ row.name }}</strong>
                <small class="block muted">{{ row.term }}</small>
              </td>
              <td class="nowrap">
                {{ fmtTime(row.start_time) }}<br />{{ fmtTime(row.end_time) }}
              </td>
              <td>{{ row.courseCount }}</td>
              <td>{{ row.selectedCount }}</td>
              <td class="nowrap">
                {{ row.minEnroll }} 人 / {{ row.maxCredits }} 学分
              </td>
              <td>
                {{ row.published_by || "--" }}
                <small class="block muted">{{ fmtTime(row.published_at) }}</small>
              </td>
              <td>
                <span class="badge" :class="statusBadge(row)">{{ statusName(row) }}</span>
              </td>
              <td>
                <div class="toolbar-actions">
                  <button
                    class="icon-button"
                    :title="'编辑 ' + row.name"
                    :aria-label="'编辑 ' + row.name"
                    :disabled="!!workingAction"
                    @click="openPublishForm(row)"
                  >
                    <CalendarClock :size="16" />
                  </button>
                  <button
                    class="secondary"
                    :disabled="!!workingAction"
                    @click="closePublish(row)"
                  >
                    关闭
                  </button>
                  <button
                    class="secondary"
                    :disabled="!!workingAction"
                    @click="openCancel(row)"
                  >
                    取消
                  </button>
                  <button
                    class="secondary"
                    :disabled="!!workingAction"
                    @click="openSettle(row)"
                  >
                    结算
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!publishRows.length">
              <td colspan="8" class="empty">暂无选课批次</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ publishPage }} 页 · 共 {{ publishTotal }} 条</span>
        <div>
          <button
            class="icon-button"
            aria-label="上一页"
            :disabled="loadingPublish || publishPage <= 1"
            @click="turnPublish(-1)"
          >
            <ChevronLeft :size="18" />
          </button>
          <button
            class="icon-button"
            aria-label="下一页"
            :disabled="loadingPublish || publishPage * publishSize >= publishTotal"
            @click="turnPublish(1)"
          >
            <ChevronRight :size="18" />
          </button>
        </div>
      </div>
    </section>

    <!-- ===================================================== 教务：选课记录 -->
    <section v-else>
      <div class="sel-filters no-print">
        <label>
          批次
          <select v-model="recordFilter.publishId" :disabled="loadingRecords" @change="searchRecords">
            <option value="">全部批次</option>
            <option v-for="row in publishRows" :key="row.id" :value="row.id">
              {{ row.name }}
            </option>
          </select>
        </label>
        <label>
          课程
          <select v-model="recordFilter.courseId" :disabled="loadingRecords" @change="searchRecords">
            <option value="">全部课程</option>
            <option v-for="c in catalog" :key="c.id" :value="c.id">
              {{ c.code }} · {{ c.name }}
            </option>
          </select>
        </label>
        <label>
          动作
          <select v-model="recordFilter.action" :disabled="loadingRecords" @change="searchRecords">
            <option value="">全部动作</option>
            <option value="SELECT">选课</option>
            <option value="DROP">退课</option>
            <option value="ADMIN_ASSIGN">教务代选</option>
            <option value="ADMIN_REMOVE">教务代退</option>
            <option value="AUTO_REFUND">自动退回</option>
          </select>
        </label>
        <button class="secondary" :disabled="loadingRecords" @click="searchRecords">
          <RefreshCw :size="15" />刷新
        </button>
        <span class="muted">共 {{ recordTotal }} 条记录</span>
      </div>

      <div v-if="loadingRecords" class="muted sel-loading">加载中…</div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>批次</th>
              <th>课程</th>
              <th>学号</th>
              <th>学生</th>
              <th>动作</th>
              <th>原因</th>
              <th>操作人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, index) in recordRows" :key="index">
              <td class="nowrap">{{ fmtTime(r.created_at) }}</td>
              <td>{{ r.publishId }}</td>
              <td>
                <strong>{{ r.courseName }}</strong>
                <small class="block muted">{{ r.code }}</small>
              </td>
              <td>{{ r.studentId }}</td>
              <td>{{ r.studentName }}</td>
              <td>
                <span class="badge neutral">{{ r.actionName || actionName(r.action) }}</span>
              </td>
              <td>{{ r.reason || "--" }}</td>
              <td>{{ r.operator || "--" }}</td>
            </tr>
            <tr v-if="!recordRows.length">
              <td colspan="8" class="empty">暂无选课操作记录</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>第 {{ recordPage }} 页 · 共 {{ recordTotal }} 条</span>
        <div>
          <button
            class="icon-button"
            aria-label="上一页"
            :disabled="loadingRecords || recordPage <= 1"
            @click="turnRecord(-1)"
          >
            <ChevronLeft :size="18" />
          </button>
          <button
            class="icon-button"
            aria-label="下一页"
            :disabled="loadingRecords || recordPage * recordSize >= recordTotal"
            @click="turnRecord(1)"
          >
            <ChevronRight :size="18" />
          </button>
        </div>
      </div>
    </section>

    <div v-if="dialog" class="modal-backdrop no-print" @click.self="!saving && (dialog = '')">
      <section class="modal" role="dialog" aria-modal="true">
        <div class="modal-heading">
          <h2 v-if="dialog === 'form'">
            {{ editing ? "编辑选课批次" : "新建选课批次" }}
          </h2>
          <h2 v-else-if="dialog === 'cancel'">取消选课批次</h2>
          <h2 v-else>结算开课人数</h2>
          <button
            class="icon-button"
            aria-label="关闭对话框"
            :disabled="saving || !!workingAction"
            @click="dialog = ''"
          >
            <X :size="20" />
          </button>
        </div>

        <form v-if="dialog === 'form'" @submit.prevent="submitPublish">
          <div class="form-grid">
            <label>
              批次名称
              <input v-model="form.name" maxlength="100" required />
            </label>
            <label>
              学年学期
              <input v-model="form.term" maxlength="30" placeholder="如 2025-2026-1" required />
            </label>
            <label>
              开始时间
              <input v-model="form.startTime" type="datetime-local" required />
            </label>
            <label>
              结束时间
              <input v-model="form.endTime" type="datetime-local" required />
            </label>
            <label>
              最低开课人数
              <input v-model.number="form.minEnroll" type="number" min="0" />
            </label>
            <label>
              学分上限
              <input v-model.number="form.maxCredits" type="number" min="0" max="40" />
            </label>
          </div>
          <div class="sel-switches">
            <label class="check-label">
              <input v-model.number="form.allowAdd" type="checkbox" :true-value="1" :false-value="0" />
              允许选课
            </label>
            <label class="check-label">
              <input v-model.number="form.allowDrop" type="checkbox" :true-value="1" :false-value="0" />
              允许退课
            </label>
            <label class="check-label">
              <input v-model.number="form.allowRetake" type="checkbox" :true-value="1" :false-value="0" />
              允许重修
            </label>
          </div>
          <label>
            备注
            <input v-model="form.note" maxlength="200" />
          </label>
          <fieldset>
            <legend>选课范围（不勾选表示不限）</legend>
            <p v-if="editing && form.scopeLabel" class="muted">
              原有范围：{{ form.scopeLabel }}
            </p>
            <div class="sel-scope">
              <label>
                学院
                <select v-model="form.scopeCollegeIds" multiple size="5">
                  <option v-for="c in orgOptions.colleges" :key="c.id" :value="c.name">
                    {{ c.name }}
                  </option>
                </select>
              </label>
              <label>
                专业
                <select v-model="form.scopeMajorIds" multiple size="5">
                  <option v-for="m in orgOptions.majors" :key="m.id" :value="m.name">
                    {{ m.name }}
                  </option>
                </select>
              </label>
              <label>
                班级
                <select v-model="form.scopeClassIds" multiple size="5">
                  <option v-for="c in orgOptions.classes" :key="c.id" :value="c.name">
                    {{ c.name }}
                  </option>
                </select>
              </label>
            </div>
          </fieldset>
          <fieldset>
            <legend>开放课程（已选 {{ form.courseIds.length }} 门）</legend>
            <span class="search">
              <Search :size="16" />
              <input
                v-model="courseSearch"
                placeholder="按课程名 / 代码 / 教师筛选"
                aria-label="筛选课程"
              />
            </span>
            <div v-if="formLoading || catalogLoading" class="muted">
              课程目录加载中…
            </div>
            <div v-else class="sel-checklist">
              <label v-for="c in formTermCourses" :key="c.id" class="sel-check-item">
                <input v-model="form.courseIds" type="checkbox" :value="c.id" />
                <span>{{ c.code }} · {{ c.name }}</span>
                <small class="muted">
                  {{ c.term }} · {{ c.credits }} 学分{{
                    c.teacherName ? " · " + c.teacherName : ""
                  }}
                </small>
              </label>
              <p v-if="!formTermCourses.length" class="muted">没有符合条件的课程</p>
            </div>
          </fieldset>
          <p v-if="editing" class="muted">
            已回填原有课程与选课范围；如需调整请直接修改，范围留空表示不限。
          </p>
          <div class="modal-actions">
            <button type="button" class="secondary" :disabled="saving" @click="dialog = ''">
              取消
            </button>
            <button class="primary" :disabled="saving">
              {{ saving ? "保存中…" : "保存批次" }}
            </button>
          </div>
        </form>

        <div v-else-if="dialog === 'cancel'">
          <p>
            取消批次「<strong>{{ cancelTarget ? cancelTarget.name : "" }}</strong
            >」后，已选学生会被自动退回。请填写取消原因。
          </p>
          <label>
            取消原因
            <input v-model="cancelReason" maxlength="200" required />
          </label>
          <div class="modal-actions">
            <button
              type="button"
              class="secondary"
              :disabled="!!workingAction"
              @click="dialog = ''"
            >
              返回
            </button>
            <button
              type="button"
              class="danger"
              :disabled="!!workingAction"
              @click="submitCancel"
            >
              {{ workingAction === "cancel" ? "处理中…" : "确认取消批次" }}
            </button>
          </div>
        </div>

        <div v-else>
          <p>
            批次「<strong>{{ settleTarget ? settleTarget.name : "" }}</strong
            >」结算中，低于最低开课人数的课程会自动退回学生选课。
          </p>
          <p v-if="workingAction" class="muted">结算处理中…</p>
          <template v-else-if="settleResult">
            <p class="success-text">
              已结算：自动退回 {{ settleResult.refunded || 0 }} 人次
            </p>
            <div v-if="(settleResult.cancelled || []).length" class="sel-checklist">
              <div
                v-for="(item, index) in settleResult.cancelled"
                :key="index"
                class="sel-check-item"
              >
                <span v-if="typeof item === 'object'">
                  {{ item.code ? item.code + " · " : "" }}{{ item.name || item.courseName }}
                  <small v-if="item.reason" class="muted">（{{ item.reason }}）</small>
                </span>
                <span v-else>{{ item }}</span>
              </div>
            </div>
            <p v-else class="muted">没有因人数不足而取消的课程。</p>
          </template>
          <div class="modal-actions">
            <button type="button" class="primary" @click="dialog = ''">关闭</button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
