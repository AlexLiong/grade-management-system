<script setup>
/**
 * 识别结果预览确认（OCR 与语音共用）。
 *
 * 职责边界：
 *   - 只负责"展示 + 编辑 + 勾选 + 汇报"，不认识 tesseract 也不调用后端；
 *   - 单元格编辑、整行跳过都只改本地副本，父组件收到 `confirm` 后才会写入录入表单；
 *   - 因此"未经确认不入表单"这条约束在组件层面就成立，可被浏览器测试直接断言。
 */
import { computed, reactive, watch } from "vue";
import { AlertTriangle, CheckCircle2, SkipForward } from "lucide-vue-next";

const props = defineProps({
  open: { type: Boolean, default: false },
  source: { type: String, default: "local-ocr" },
  rows: { type: Array, default: () => [] },
  components: { type: Array, default: () => [] },
  columns: { type: Array, default: () => [] },
  columnMap: { type: Array, default: () => [] },
  summary: { type: Object, default: () => ({}) },
  busy: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "confirm", "update:columnMap"]);

/** 本地可编辑副本：`enabled` 表示是否参与填入。 */
const draft = reactive({ rows: [] });

watch(
  () => props.rows,
  (rows) => {
    draft.rows = (rows || []).map((row) => ({
      ...row,
      enabled: row.matched && row.cells.some((cell) => cell.value !== null),
      cells: row.cells.map((cell) => ({ ...cell })),
    }));
  },
  { immediate: true, deep: false },
);

const usable = computed(() => draft.rows.filter((row) => row.enabled));
const skippedRows = computed(() => draft.rows.filter((row) => !row.enabled));
const filledCells = computed(() =>
  draft.rows.reduce(
    (total, row) => total + row.cells.filter((cell) => row.enabled && cell.value !== null).length,
    0,
  ),
);
const suspiciousCells = computed(() =>
  draft.rows.reduce((total, row) => total + row.cells.filter((cell) => cell.needsCheck).length, 0),
);

function setValue(row, cell, value) {
  const text = String(value ?? "").trim();
  if (!text) {
    cell.value = null;
    return;
  }
  const numeric = Number(text);
  cell.value = Number.isFinite(numeric) ? Math.round(numeric * 100) / 100 : null;
  cell.needsCheck = false;
  row.issues = row.issues.filter((issue) => !issue.startsWith(cell.label));
}

function toggleRow(row) {
  row.enabled = !row.enabled;
}

function updateMap(index, value) {
  const next = [...props.columnMap];
  next[index] = value === "" ? null : Number(value);
  emit("update:columnMap", next);
}

function confirm() {
  emit(
    "confirm",
    usable.value.map((row) => ({
      username: row.username,
      studentId: row.studentId,
      name: row.name,
      cells: row.cells
        .filter((cell) => cell.value !== null)
        .map((cell) => ({ key: cell.key, label: cell.label, value: cell.value })),
    })),
  );
}
</script>

<template>
  <div v-if="open" class="recognize-preview" data-testid="recognize-preview">
    <header class="recognize-head">
      <div>
        <strong>{{ source === "voice" ? "语音录入确认" : "识别结果确认" }}</strong>
        <span class="recognize-meta">
          识别 {{ rows.length }} 行 · 可填入 <b data-testid="usable-count">{{ usable.length }}</b> 行 ·
          分数 <b data-testid="filled-count">{{ filledCells }}</b> 个 ·
          待核对 <b data-testid="suspicious-count">{{ suspiciousCells }}</b> 个
        </span>
      </div>
      <span class="badge" :class="skippedRows.length ? 'amber' : 'green'">
        {{ skippedRows.length ? skippedRows.length + " 行不会填入" : "全部可填入" }}
      </span>
    </header>

    <div v-if="components.length > 1" class="column-map" data-testid="column-map">
      <span class="column-map-title">列对应</span>
      <label v-for="(component, index) in components" :key="component.key">
        {{ component.label }}
        <select
          :aria-label="'列对应 ' + component.label"
          :value="columnMap[index] === null || columnMap[index] === undefined ? '' : String(columnMap[index])"
          @change="updateMap(index, $event.target.value)"
        >
          <option value="">不导入</option>
          <option v-for="(column, columnIndex) in columns" :key="columnIndex" :value="String(columnIndex)">
            第 {{ columnIndex + 1 }} 列
          </option>
        </select>
      </label>
    </div>

    <div class="table-wrap recognize-table-wrap">
      <table class="recognize-table">
        <thead>
          <tr>
            <th>填入</th>
            <th>学号</th>
            <th>姓名</th>
            <th v-for="component in components" :key="component.key">{{ component.label }}</th>
            <th>行置信度</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, rowIndex) in draft.rows"
            :key="rowIndex"
            :class="{
              'row-skip': !row.enabled,
              'row-warn': row.enabled && row.issues.length > 0,
            }"
            :data-testid="'preview-row-' + rowIndex"
          >
            <td>
              <input
                type="checkbox"
                :checked="row.enabled"
                :aria-label="`填入第 ${rowIndex + 1} 行`"
                @change="toggleRow(row)"
              />
            </td>
            <td class="recognize-id">
              <span data-testid="preview-username">{{ row.username || "—" }}</span>
              <span v-if="row.corrected" class="badge amber" title="OCR 结果经混淆纠正">已纠正</span>
            </td>
            <td>{{ row.name || "—" }}</td>
            <td v-for="cell in row.cells" :key="cell.key">
              <input
                class="recognize-cell"
                :class="{ 'cell-check': cell.needsCheck }"
                :aria-label="`预览 ${row.username || rowIndex} ${cell.label}`"
                :value="cell.value === null ? '' : cell.value"
                :disabled="!row.enabled"
                inputmode="decimal"
                @input="setValue(row, cell, $event.target.value)"
              />
            </td>
            <td>
              <span class="badge" :class="row.confidence >= 80 ? 'green' : row.confidence >= 60 ? 'amber' : 'red'">
                {{ row.confidence }}%
              </span>
            </td>
            <td class="recognize-issues">
              <span v-if="!row.issues.length" class="ok"><CheckCircle2 :size="14" />正常</span>
              <span v-for="issue in row.issues" :key="issue" class="warn">
                <AlertTriangle :size="14" />{{ issue }}
              </span>
            </td>
          </tr>
          <tr v-if="!draft.rows.length">
            <td :colspan="components.length + 5" class="empty">没有识别到任何数据行，请换一张更清晰的照片</td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer class="modal-actions">
      <span v-if="skippedRows.length" class="recognize-hint">
        <SkipForward :size="14" />未勾选的行不会写入表单，可稍后手工录入
      </span>
      <button class="secondary" :disabled="busy" @click="emit('close')">取消</button>
      <button
        class="primary"
        data-testid="confirm-fill"
        :disabled="busy || !usable.length"
        @click="confirm"
      >
        确认填入（{{ usable.length }} 行）
      </button>
    </footer>
  </div>
</template>

<style scoped>
.recognize-preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.recognize-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.recognize-meta {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #5b6472;
}
.column-map {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 8px 10px;
  border: 1px solid #e3e7ee;
  border-radius: 8px;
  background: #f8fafc;
}
.column-map-title {
  font-size: 12px;
  color: #5b6472;
}
.column-map label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.recognize-table-wrap {
  max-height: 46vh;
  overflow: auto;
}
.recognize-table {
  width: 100%;
  min-width: 520px;
  border-collapse: collapse;
  font-size: 13px;
}
.recognize-table th,
.recognize-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #eef1f5;
  text-align: left;
  white-space: nowrap;
}
.recognize-table thead th {
  position: sticky;
  top: 0;
  background: #fff;
  z-index: 1;
}
.recognize-id {
  display: flex;
  align-items: center;
  gap: 6px;
}
.recognize-cell {
  width: 74px;
  padding: 4px 6px;
  text-align: right;
}
.recognize-cell.cell-check {
  background: #fff8e1;
  border-color: #f0c36d;
}
.row-skip {
  opacity: 0.55;
}
.row-warn {
  background: #fffdf5;
}
.recognize-issues {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.recognize-issues .ok {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #2f855a;
}
.recognize-issues .warn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #b7791f;
}
.recognize-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  font-size: 12px;
  color: #5b6472;
}
.empty {
  text-align: center;
  color: #7b8494;
  padding: 18px 0;
}
</style>
