<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import {
  AlertTriangle,
  Building2,
  GraduationCap,
  Pencil,
  Plus,
  RefreshCw,
  School,
  Search,
  Trash2,
  UserPlus,
  UsersRound,
  X,
} from "lucide-vue-next";
import { api } from "../api";

const props = defineProps({
  user: { type: Object, default: null },
});
const emit = defineEmits(["notice", "error"]);

const LEVELS = [
  { key: "COLLEGE", name: "学院", icon: Building2 },
  { key: "MAJOR", name: "专业", icon: School },
  { key: "CLASS", name: "班级", icon: GraduationCap },
];

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

const perms = computed(() => {
  const raw = props.user ? props.user.permissions : null;
  return Array.isArray(raw) ? raw : String(raw || "").split(",");
});
const canOrgAdmin = computed(() => perms.value.includes("ORG_ADMIN"));

const level = ref("COLLEGE");
const loading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const dialog = ref("");
const optionsLoading = ref(false);
const options = ref({ colleges: [], majors: [], classes: [] });
const rows = reactive({ COLLEGE: [], MAJOR: [], CLASS: [] });
const filters = reactive({ collegeId: "", majorId: "" });

const form = ref({});
const editing = ref(false);
const target = ref(null);
const impact = ref(null);
const impactLoading = ref(false);
const members = ref([]);
const memberTotal = ref(0);
const memberLoading = ref(false);
const students = ref([]);
const studentKeyword = ref("");
const chosenStudents = ref([]);
const assignClassId = ref("");
const assigning = ref(false);
const studentsLoading = ref(false);

const levelLabel = computed(() => {
  const item = LEVELS.find((t) => t.key === level.value);
  return item ? item.name : "组织";
});
const currentRows = computed(() => rows[level.value] || []);
const filterMajorOptions = computed(() =>
  options.value.majors.filter(
    (m) => !filters.collegeId || m.collegeId === filters.collegeId,
  ),
);
const formMajorOptions = computed(() =>
  options.value.majors.filter(
    (m) =>
      !form.value.college ||
      m.collegeName === form.value.college ||
      m.collegeId === form.value.college,
  ),
);
const visibleStudents = computed(() => {
  const keyword = studentKeyword.value.trim();
  if (!keyword) return students.value;
  return students.value.filter(
    (s) =>
      String(s.name || "").includes(keyword) ||
      String(s.username || "").includes(keyword) ||
      String(s.className || "").includes(keyword),
  );
});
const impactTotal = computed(() =>
  impact.value ? countOf(impact.value.total) : 0,
);
function countOf(value) {
  if (Array.isArray(value)) return value.length;
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function levelPath(which) {
  let path = "/organizations?level=" + which + "&page=1&size=200";
  if (which === "MAJOR" && filters.collegeId)
    path += "&collegeId=" + encodeURIComponent(filters.collegeId);
  if (which === "CLASS") {
    if (filters.collegeId)
      path += "&collegeId=" + encodeURIComponent(filters.collegeId);
    if (filters.majorId)
      path += "&majorId=" + encodeURIComponent(filters.majorId);
  }
  return path;
}

async function loadLevel(which) {
  const active = which || level.value;
  loading.value = true;
  try {
    const result = await api(levelPath(active));
    rows[active] = listOf(result, "items");
  } catch (e) {
    emit("error", message(e));
  } finally {
    loading.value = false;
  }
}

async function loadOptions() {
  optionsLoading.value = true;
  try {
    const result = await api("/organizations/options");
    options.value = {
      colleges: listOf(result, "colleges"),
      majors: listOf(result, "majors"),
      classes: listOf(result, "classes"),
    };
  } catch (e) {
    emit("error", message(e));
  } finally {
    optionsLoading.value = false;
  }
}

async function refresh() {
  await loadOptions();
  await loadLevel();
}

async function switchLevel(key) {
  if (loading.value || key === level.value) return;
  level.value = key;
  await loadLevel(key);
}

function closeDialog() {
  if (saving.value || deleting.value || assigning.value) return;
  dialog.value = "";
}

function openForm(row) {
  editing.value = !!row;
  if (level.value === "COLLEGE") {
    form.value = {
      id: row ? row.id : "",
      name: row ? row.name || "" : "",
      shortName: row ? row.shortName || "" : "",
      description: row ? row.description || "" : "",
      enabled: row ? Number(row.enabled) : 1,
    };
  } else if (level.value === "MAJOR") {
    form.value = {
      id: row ? row.id : "",
      name: row ? row.name || "" : "",
      degree: row ? row.degree || "工学学士" : "工学学士",
      years: row ? Number(row.years) || 4 : 4,
      college:
        (row ? row.collegeName : "") ||
        (options.value.colleges[0] ? options.value.colleges[0].name : ""),
      enabled: row ? Number(row.enabled) : 1,
    };
  } else {
    form.value = {
      id: row ? row.id : "",
      name: row ? row.name || "" : "",
      gradeYear: row
        ? row.gradeYear || ""
        : String(new Date().getFullYear()),
      college: row ? row.collegeName || "" : "",
      major: row ? row.majorName || "" : "",
      enabled: row ? Number(row.enabled) : 1,
    };
  }
  dialog.value = "form";
}

async function submitForm() {
  if (saving.value) return;
  const current = form.value;
  const name = String(current.name || "").trim();
  if (!name) {
    emit("error", "请填写" + levelLabel.value + "名称");
    return;
  }
  // 编号就是数据库主键 id：新建时由后端自动生成，修改时沿用原值。
  // 表单里没有编号输入框，编辑时只把 id 作为「改哪一条」的定位参数回传。
  const body = {
    level: level.value,
    name,
    enabled: Number(current.enabled) ? 1 : 0,
  };
  if (editing.value && current.id) body.id = current.id;
  if (level.value === "COLLEGE") {
    body.shortName = String(current.shortName || "").trim();
    body.description = String(current.description || "").trim();
  } else if (level.value === "MAJOR") {
    if (!current.college) {
      emit("error", "请选择所属学院");
      return;
    }
    body.degree = String(current.degree || "").trim();
    body.years = Number(current.years) || 4;
    // 下拉框选中即名称：collegeId / college 都提交学院名称，后端按名称或编号均可解析。
    body.college = current.college;
    body.collegeId = current.college;
  } else {
    if (!current.major) {
      emit("error", "请选择所属专业");
      return;
    }
    body.gradeYear = String(current.gradeYear || "").trim();
    body.major = current.major;
    body.majorId = current.major;
    body.college = current.college || "";
    body.collegeId = current.college || "";
  }
  saving.value = true;
  try {
    // 响应 {ok:true, id}：id 是后端生成的主键编号，直接用它给用户回执。
    const saved = await api("/organizations/save", body);
    dialog.value = "";
    const codeSuffix = saved && saved.id ? "（编号 " + saved.id + "）" : "";
    emit(
      "notice",
      (editing.value ? "已更新" : "已新增") +
        levelLabel.value +
        "：" +
        name +
        codeSuffix,
    );
    await loadOptions();
    await loadLevel();
  } catch (e) {
    emit("error", message(e));
  } finally {
    saving.value = false;
  }
}

async function askDelete(row) {
  target.value = row;
  impact.value = null;
  impactLoading.value = true;
  dialog.value = "delete";
  try {
    impact.value = await api(
      "/organizations/impact?level=" +
        level.value +
        "&id=" +
        encodeURIComponent(row.id),
    );
  } catch (e) {
    emit("error", message(e));
  } finally {
    impactLoading.value = false;
  }
}

async function remove(force) {
  const row = target.value;
  if (!row || deleting.value) return;
  deleting.value = true;
  try {
    await api("/organizations/delete", {
      level: level.value,
      id: row.id,
      force: !!force,
    });
    dialog.value = "";
    emit("notice", "已删除" + levelLabel.value + "：" + row.name);
    await loadOptions();
    await loadLevel();
  } catch (e) {
    if (!force && e && e.status === 409) {
      const confirmed = window.confirm(
        message(e) +
          "\n\n是否强制删除？其下级组织及账号关联仍会被后端校验。",
      );
      if (confirmed) {
        deleting.value = false;
        await remove(true);
        return;
      }
    }
    emit("error", message(e));
  } finally {
    deleting.value = false;
  }
}

async function openMembers(row) {
  members.value = [];
  memberTotal.value = 0;
  memberLoading.value = true;
  dialog.value = "members";
  try {
    const result = await api(
      "/organizations/members?level=" +
        level.value +
        "&id=" +
        encodeURIComponent(row.id) +
        "&page=1&size=200",
    );
    members.value = listOf(result, "items");
    memberTotal.value = totalOf(result, members.value.length);
  } catch (e) {
    emit("error", message(e));
  } finally {
    memberLoading.value = false;
  }
}

async function openAssign(row) {
  chosenStudents.value = [];
  studentKeyword.value = "";
  students.value = [];
  assignClassId.value = row
    ? row.id
    : options.value.classes[0]
      ? options.value.classes[0].id
      : "";
  studentsLoading.value = true;
  dialog.value = "assign";
  try {
    // 用组织域自己的学生列表接口：只持有 ORG_ADMIN 的管理员也能取到名单，
    // 不依赖需要 USER_ADMIN 权限的 /users。
    const result = await api("/organizations/students?size=300");
    students.value = listOf(result, "items");
  } catch (e) {
    emit("error", message(e));
  } finally {
    studentsLoading.value = false;
  }
}

async function submitAssign() {
  const ids = chosenStudents.value.slice();
  if (assigning.value) return;
  if (!assignClassId.value) {
    emit("error", "请选择目标班级");
    return;
  }
  if (!ids.length) {
    emit("error", "请至少选择一名学生");
    return;
  }
  assigning.value = true;
  try {
    await api("/organizations/assign", {
      level: "CLASS",
      id: assignClassId.value,
      studentIds: ids,
    });
    dialog.value = "";
    emit("notice", "已把 " + ids.length + " 名学生调入所选班级");
    await loadLevel("CLASS");
  } catch (e) {
    emit("error", message(e));
  } finally {
    assigning.value = false;
  }
}

onMounted(async () => {
  if (!canOrgAdmin.value) return;
  await refresh();
});
</script>

<template>
  <div v-if="!canOrgAdmin" class="empty">
    <AlertTriangle :size="30" />
    <p>当前账号没有组织管理权限（ORG_ADMIN）</p>
  </div>
  <div v-else class="org-view">
    <div class="org-tabs no-print">
      <button
        v-for="t in LEVELS"
        :key="t.key"
        class="org-tab"
        :class="{ active: level === t.key }"
        :disabled="loading"
        @click="switchLevel(t.key)"
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

    <div class="org-filters no-print">
      <label v-if="level === 'MAJOR'">
        所属学院
        <select v-model="filters.collegeId" :disabled="loading" @change="loadLevel()">
          <option value="">全部学院</option>
          <option v-for="c in options.colleges" :key="c.id" :value="c.id">
            {{ c.name }}
          </option>
        </select>
      </label>
      <template v-if="level === 'CLASS'">
        <label>
          所属学院
          <select
            v-model="filters.collegeId"
            :disabled="loading"
            @change="
              filters.majorId = '';
              loadLevel();
            "
          >
            <option value="">全部学院</option>
            <option v-for="c in options.colleges" :key="c.id" :value="c.id">
              {{ c.name }}
            </option>
          </select>
        </label>
        <label>
          所属专业
          <select v-model="filters.majorId" :disabled="loading" @change="loadLevel()">
            <option value="">全部专业</option>
            <option v-for="m in filterMajorOptions" :key="m.id" :value="m.id">
              {{ m.name }}
            </option>
          </select>
        </label>
      </template>
      <span class="muted org-count">
        共 {{ currentRows.length }} 条{{ optionsLoading ? " · 选项加载中" : "" }}
      </span>
      <div class="toolbar-actions org-actions">
        <button
          v-if="level === 'CLASS'"
          class="secondary"
          :disabled="loading || studentsLoading"
          @click="openAssign()"
        >
          <UserPlus :size="15" />批量调入学生
        </button>
        <button class="primary" :disabled="loading" @click="openForm()">
          <Plus :size="16" />新增{{ levelLabel }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="muted org-loading">加载中…</div>

    <div v-if="level === 'COLLEGE'" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>学院</th>
            <th>简称</th>
            <th>专业</th>
            <th>班级</th>
            <th>学生</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in currentRows" :key="row.id">
            <td>
              <strong>{{ row.name }}</strong>
              <small class="block muted">{{ row.id }}</small>
            </td>
            <td>{{ row.shortName || "--" }}</td>
            <td>{{ row.majorCount }}</td>
            <td>{{ row.classCount }}</td>
            <td>{{ row.studentCount }}</td>
            <td>
              <span class="badge" :class="Number(row.enabled) ? 'green' : 'red'">
                {{ Number(row.enabled) ? "启用" : "停用" }}
              </span>
            </td>
            <td>
              <div class="toolbar-actions">
                <button
                  class="icon-button"
                  :title="'编辑 ' + row.name"
                  :aria-label="'编辑 ' + row.name"
                  @click="openForm(row)"
                >
                  <Pencil :size="16" />
                </button>
                <button
                  class="icon-button"
                  :title="'删除 ' + row.name"
                  :aria-label="'删除 ' + row.name"
                  @click="askDelete(row)"
                >
                  <Trash2 :size="16" />
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!currentRows.length">
            <td colspan="7" class="empty">暂无学院数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else-if="level === 'MAJOR'" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>专业</th>
            <th>所属学院</th>
            <th>学位</th>
            <th>学制</th>
            <th>班级</th>
            <th>学生</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in currentRows" :key="row.id">
            <td>
              <strong>{{ row.name }}</strong>
              <small class="block muted">{{ row.id }}</small>
            </td>
            <td>{{ row.collegeName || "--" }}</td>
            <td>{{ row.degree || "--" }}</td>
            <td>{{ row.years }} 年</td>
            <td>{{ row.classCount }}</td>
            <td>{{ row.studentCount }}</td>
            <td>
              <span class="badge" :class="Number(row.enabled) ? 'green' : 'red'">
                {{ Number(row.enabled) ? "启用" : "停用" }}
              </span>
            </td>
            <td>
              <div class="toolbar-actions">
                <button
                  class="icon-button"
                  :title="'编辑 ' + row.name"
                  :aria-label="'编辑 ' + row.name"
                  @click="openForm(row)"
                >
                  <Pencil :size="16" />
                </button>
                <button
                  class="icon-button"
                  :title="'删除 ' + row.name"
                  :aria-label="'删除 ' + row.name"
                  @click="askDelete(row)"
                >
                  <Trash2 :size="16" />
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!currentRows.length">
            <td colspan="8" class="empty">暂无专业数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>班级</th>
            <th>年级</th>
            <th>所属专业</th>
            <th>所属学院</th>
            <th>学生</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in currentRows" :key="row.id">
            <td>
              <strong>{{ row.name }}</strong>
              <small class="block muted">{{ row.id }}</small>
            </td>
            <td>{{ row.gradeYear || "--" }}</td>
            <td>{{ row.majorName || "--" }}</td>
            <td>{{ row.collegeName || "--" }}</td>
            <td>{{ row.studentCount }}</td>
            <td>
              <span class="badge" :class="Number(row.enabled) ? 'green' : 'red'">
                {{ Number(row.enabled) ? "启用" : "停用" }}
              </span>
            </td>
            <td>
              <div class="toolbar-actions">
                <button
                  class="icon-button"
                  :title="'查看 ' + row.name + ' 成员'"
                  :aria-label="'查看 ' + row.name + ' 成员'"
                  @click="openMembers(row)"
                >
                  <UsersRound :size="16" />
                </button>
                <button
                  class="icon-button"
                  :title="'向 ' + row.name + ' 调入学生'"
                  :aria-label="'向 ' + row.name + ' 调入学生'"
                  @click="openAssign(row)"
                >
                  <UserPlus :size="16" />
                </button>
                <button
                  class="icon-button"
                  :title="'编辑 ' + row.name"
                  :aria-label="'编辑 ' + row.name"
                  @click="openForm(row)"
                >
                  <Pencil :size="16" />
                </button>
                <button
                  class="icon-button"
                  :title="'删除 ' + row.name"
                  :aria-label="'删除 ' + row.name"
                  @click="askDelete(row)"
                >
                  <Trash2 :size="16" />
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!currentRows.length">
            <td colspan="7" class="empty">暂无班级数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="dialog" class="modal-backdrop no-print" @click.self="closeDialog">
      <section class="modal" role="dialog" aria-modal="true">
        <div class="modal-heading">
          <h2 v-if="dialog === 'form'">
            {{ editing ? "编辑" : "新增" }}{{ levelLabel }}
          </h2>
          <h2 v-else-if="dialog === 'delete'">删除{{ levelLabel }}</h2>
          <h2 v-else-if="dialog === 'members'">班级成员</h2>
          <h2 v-else>批量调入学生</h2>
          <button
            class="icon-button"
            aria-label="关闭对话框"
            :disabled="saving || deleting || assigning"
            @click="closeDialog"
          >
            <X :size="20" />
          </button>
        </div>

        <form v-if="dialog === 'form'" @submit.prevent="submitForm">
          <div class="form-grid">
            <label>
              {{ levelLabel }}名称
              <input v-model="form.name" maxlength="100" required />
            </label>
            <!-- 编号就是数据库主键 id：由后端在新建时自动生成，编辑时不可修改，
                 因此表单里不提供输入框，只在列表里作为只读信息展示。 -->
            <template v-if="level === 'COLLEGE'">
              <label>
                简称
                <input v-model="form.shortName" maxlength="50" />
              </label>
              <label>
                简介
                <input v-model="form.description" maxlength="200" />
              </label>
            </template>
            <template v-else-if="level === 'MAJOR'">
              <label>
                所属学院
                <select v-model="form.college" required>
                  <option value="">请选择学院</option>
                  <option v-for="c in options.colleges" :key="c.id" :value="c.name">
                    {{ c.name }}
                  </option>
                </select>
              </label>
              <label>
                学位
                <input v-model="form.degree" maxlength="50" />
              </label>
              <label>
                学制（年）
                <input v-model.number="form.years" type="number" min="1" max="8" />
              </label>
            </template>
            <template v-else>
              <label>
                所属学院
                <select
                  v-model="form.college"
                  required
                  @change="form.major = ''"
                >
                  <option value="">请选择学院</option>
                  <option v-for="c in options.colleges" :key="c.id" :value="c.name">
                    {{ c.name }}
                  </option>
                </select>
              </label>
              <label>
                所属专业
                <select v-model="form.major" required>
                  <option value="">请选择专业</option>
                  <option v-for="m in formMajorOptions" :key="m.id" :value="m.name">
                    {{ m.name }}
                  </option>
                </select>
              </label>
              <label>
                年级
                <input v-model="form.gradeYear" maxlength="10" placeholder="如 2023" />
              </label>
            </template>
          </div>
          <label class="check-label">
            <input v-model.number="form.enabled" type="checkbox" :true-value="1" :false-value="0" />
            启用
          </label>
          <div class="modal-actions">
            <button type="button" class="secondary" :disabled="saving" @click="closeDialog">
              取消
            </button>
            <button class="primary" :disabled="saving">
              {{ saving ? "保存中…" : "保存" }}
            </button>
          </div>
        </form>

        <div v-else-if="dialog === 'delete'">
          <p>
            确认删除{{ levelLabel }}「<strong>{{ target ? target.name : "" }}</strong
            >」？此操作不可撤销。
          </p>
          <div v-if="impactLoading" class="muted">正在统计影响面…</div>
          <div v-else-if="impact" class="org-impact">
            <p class="muted">该{{ levelLabel }}当前关联：</p>
            <div class="org-impact-grid">
              <span>下级专业 <strong>{{ countOf(impact.majors) }}</strong></span>
              <span>下级班级 <strong>{{ countOf(impact.classes) }}</strong></span>
              <span>关联学生 <strong>{{ countOf(impact.students) }}</strong></span>
              <span>关联课程 <strong>{{ countOf(impact.courses) }}</strong></span>
            </div>
            <p v-if="impactTotal > 0" class="failed">
              存在 {{ impactTotal }} 项关联数据，后端将拒绝直接删除；确认后会以强制方式重试一次。
            </p>
            <p v-else class="success-text">暂无关联数据，可以直接删除。</p>
          </div>
          <div class="modal-actions">
            <button type="button" class="secondary" :disabled="deleting" @click="closeDialog">
              取消
            </button>
            <button
              type="button"
              class="danger"
              :disabled="deleting || impactLoading"
              @click="remove(false)"
            >
              {{ deleting ? "删除中…" : "确认删除" }}
            </button>
          </div>
        </div>

        <div v-else-if="dialog === 'members'">
          <div v-if="memberLoading" class="muted">加载中…</div>
          <div v-else class="table-wrap org-modal-table">
            <table>
              <thead>
                <tr>
                  <th>姓名 / 账号</th>
                  <th>学院</th>
                  <th>专业</th>
                  <th>班级</th>
                  <th>角色</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="m in members" :key="m.id">
                  <td>
                    <strong>{{ m.name }}</strong>
                    <small class="block muted">{{ m.username }}</small>
                  </td>
                  <td>{{ m.collegeName || "--" }}</td>
                  <td>{{ m.majorName || "--" }}</td>
                  <td>{{ m.className || "--" }}</td>
                  <td>{{ m.role }}</td>
                  <td>
                    <span class="badge" :class="Number(m.enabled) ? 'green' : 'red'">
                      {{ Number(m.enabled) ? "启用" : "停用" }}
                    </span>
                  </td>
                </tr>
                <tr v-if="!members.length">
                  <td colspan="6" class="empty">该班级暂无成员</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="muted">共 {{ memberTotal }} 人</p>
        </div>

        <div v-else>
          <div class="org-impact-grid">
            <label>
              目标班级
              <select v-model="assignClassId" :disabled="assigning">
                <option value="">请选择班级</option>
                <option v-for="c in options.classes" :key="c.id" :value="c.id">
                  {{ c.name }}{{ c.majorName ? " · " + c.majorName : "" }}
                </option>
              </select>
            </label>
            <label>
              搜索学生
              <span class="search">
                <Search :size="16" />
                <input
                  v-model="studentKeyword"
                  placeholder="姓名 / 学号 / 班级"
                  aria-label="搜索学生"
                  :disabled="assigning"
                />
              </span>
            </label>
          </div>
          <div v-if="studentsLoading" class="muted">学生名单加载中…</div>
          <div v-else class="org-student-list">
            <label v-for="s in visibleStudents" :key="s.id" class="org-student">
              <input
                v-model="chosenStudents"
                type="checkbox"
                :value="s.id"
                :disabled="assigning"
              />
              <span>{{ s.name }}</span>
              <small class="muted">{{ s.username }}</small>
              <small class="muted">{{ s.className || "未分班" }}</small>
            </label>
            <p v-if="!visibleStudents.length" class="muted">没有符合条件的学生</p>
          </div>
          <div class="modal-actions">
            <span class="muted org-chosen">已选 {{ chosenStudents.length }} 人</span>
            <button type="button" class="secondary" :disabled="assigning" @click="closeDialog">
              取消
            </button>
            <button
              type="button"
              class="primary"
              :disabled="assigning || !chosenStudents.length"
              @click="submitAssign"
            >
              {{ assigning ? "提交中…" : "确认调入" }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
