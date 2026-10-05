<script setup>
/**
 * 语音录入面板（成绩录入辅助）。
 *
 * 两种输入通道共用同一套解析逻辑（`frontend/src/voice.js`）：
 *   1. **语音**：浏览器 `SpeechRecognition`（Chrome/Edge）。音频由浏览器厂商处理；
 *      切换录入对象用「录入对象」下拉框或上一行/下一行按钮。
 *   2. **文本**：不支持语音时（Firefox/Safari 等）退化为输入框，能力完全相同。
 *
 * 组件只产出"结构化分数"，不直接改成绩表；父组件收到 `apply` 后才写入表单，
 * 之后照旧走暂存/提交与审计流程。
 */
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { Mic, MicOff, Keyboard, Check, ChevronLeft, ChevronRight } from "lucide-vue-next";
import {
  buildVoiceComponents,
  createVoiceSession,
  parseUtterance,
  speechSupport,
  toCellUpdates,
} from "../voice.js";

const props = defineProps({
  open: { type: Boolean, default: false },
  components: { type: Array, default: () => [] },
  /** 可录入的学生行：用于行选择下拉与上一行/下一行切换。 */
  rows: { type: Array, default: () => [] },
  /** 当前录入对象（`rows` 中的一行）。 */
  target: { type: Object, default: () => null },
  rowDefaults: { type: Object, default: () => ({}) },
});
const emit = defineEmits(["close", "apply", "select-target"]);

const voiceComponents = computed(() => buildVoiceComponents(props.components));
const supported = speechSupport();
const listening = ref(false);
const transcript = ref("");
const interim = ref("");
const errorText = ref("");
const lastApplied = ref("");
let session = null;

const parsed = computed(() =>
  transcript.value.trim()
    ? parseUtterance(transcript.value, voiceComponents.value, { defaults: props.rowDefaults })
    : null,
);
// 排障钩子：浏览器测试脚本用它核对"界面里真正生效的文本/解析结果"，
// 避免断言只看 DOM 而推断不出内部状态（生产运行时只是挂了一个只读快照）。
if (typeof window !== "undefined") {
  Object.defineProperty(window, "__voiceState", {
    configurable: true,
    get: () => ({
      transcript: transcript.value,
      components: voiceComponents.value,
      parsed: parsed.value,
      updates: updates.value,
      target: rowKey(props.target),
      rowCount: props.rows.length,
    }),
  });
}
const updates = computed(() => (parsed.value ? toCellUpdates(parsed.value, voiceComponents.value) : []));
const targetLabel = computed(() => {
  if (!props.target) return "未选择学生";
  const id = props.target.username || props.target.student_id || "";
  return `${id} ${props.target.name || ""}`.trim();
});
const targetKey = computed(() => rowKey(props.target));
const currentIndex = computed(() => props.rows.findIndex((row) => rowKey(row) === targetKey.value));

/** 行的稳定标识：学号优先，其次学生 id。 */
function rowKey(row) {
  return row ? String(row.username || row.student_id || row.id || "") : "";
}

function gotoRow(row) {
  if (!row || rowKey(row) === targetKey.value) return;
  emit("select-target", row);
}

/** 上一行/下一行：越界时停在首尾，不循环。 */
function stepRow(delta) {
  if (!props.rows.length) return;
  const index = currentIndex.value < 0 ? 0 : currentIndex.value + delta;
  gotoRow(props.rows[Math.max(0, Math.min(props.rows.length - 1, index))]);
}

function startListening() {
  errorText.value = "";
  if (!supported) {
    errorText.value = "当前浏览器不支持语音识别，请使用下方文本框输入同样的口令";
    return;
  }
  session = createVoiceSession({
    onResult(text, isFinal) {
      if (isFinal) {
        transcript.value = `${transcript.value} ${text}`.trim();
        interim.value = "";
      } else interim.value = text;
    },
    onError(error) {
      errorText.value =
        error === "not-allowed"
          ? "麦克风权限被拒绝，请在浏览器地址栏允许后重试"
          : `语音识别失败：${error}`;
      listening.value = false;
    },
    onEnd() {
      listening.value = false;
    },
  });
  listening.value = true;
  session.start();
}

function stopListening() {
  listening.value = false;
  session?.stop();
  session = null;
}

function apply() {
  if (!parsed.value || !updates.value.length) return;
  emit("apply", {
    target: props.target,
    cells: updates.value.map((cell) => ({ key: cell.key, label: cell.label, value: cell.value })),
    utterance: transcript.value.trim(),
  });
  lastApplied.value = updates.value.map((cell) => `${cell.label} ${cell.value}`).join(" · ");
  transcript.value = "";
  interim.value = "";
}

/**
 * 清空"这一批识别结果"的临时状态。
 *
 * 注意：**不动 transcript**。换录入对象时（尤其是"下一行 78 88 92"这种口令+分数混说）
 * 待解析文本必须留着，否则切完行分数就没了。
 */
function clearPending() {
  interim.value = "";
  errorText.value = "";
}

function reset() {
  transcript.value = "";
  clearPending();
  lastApplied.value = "";
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      stopListening();
      reset();
    }
  },
);
watch(
  () => props.target?.student_id ?? props.target?.username,
  () => {
    // 换录入对象时只清"实时文本/错误提示"，**保留待解析文本**：
    // 现实里常说"下一行 78 88 92"，切完行这三个数字还要接着解析。
    clearPending();
    lastApplied.value = "";
  },
);

onBeforeUnmount(() => stopListening());
</script>

<template>
  <div v-if="open" class="voice-panel" data-testid="voice-panel">
    <div class="voice-target-row">
      <label class="voice-target-picker">
        录入对象
        <select
          data-testid="voice-target-select"
          :value="targetKey"
          :disabled="!rows.length"
          @change="gotoRow(rows.find((row) => rowKey(row) === $event.target.value))"
        >
          <option v-for="(row, index) in rows" :key="rowKey(row)" :value="rowKey(row)">
            第 {{ index + 1 }} 行 · {{ row.username }} {{ row.name || "" }}
          </option>
        </select>
      </label>
      <div class="voice-target-nav">
        <button
          class="icon-button"
          data-testid="voice-prev"
          title="上一行"
          aria-label="上一行"
          :disabled="currentIndex <= 0"
          @click="stepRow(-1)"
        >
          <ChevronLeft :size="16" />
        </button>
        <span class="voice-target-label" data-testid="voice-target">
          当前行：{{ targetLabel }}（第 {{ Math.max(1, currentIndex + 1) }} / {{ rows.length }} 行）
        </span>
        <button
          class="icon-button"
          data-testid="voice-next"
          title="下一行"
          aria-label="下一行"
          :disabled="currentIndex < 0 || currentIndex >= rows.length - 1"
          @click="stepRow(1)"
        >
          <ChevronRight :size="16" />
        </button>
      </div>
    </div>

    <div class="voice-controls">
      <button
        v-if="supported"
        class="secondary"
        data-testid="voice-mic"
        @click="listening ? stopListening() : startListening()"
      >
        <Mic v-if="!listening" :size="16" /><MicOff v-else :size="16" />
        {{ listening ? "停止识别" : "开始识别" }}
      </button>
      <span v-else class="voice-unsupported" data-testid="voice-unsupported">
        <MicOff :size="14" />当前浏览器不支持语音识别，请用文本框输入
      </span>
      <button class="secondary" data-testid="voice-reset" @click="reset">清空</button>
    </div>

    <label class="voice-input">
      <span><Keyboard :size="14" />识别文本 / 手工输入</span>
    </label>
    <textarea
      v-model="transcript"
      data-testid="voice-text"
      rows="3"
      spellcheck="false"
      placeholder="例如：平时八十五 实验九十二 期末八十七；也可以直接说三个分数"
    ></textarea>
    <p v-if="interim" class="voice-interim" data-testid="voice-interim">识别中：{{ interim }}</p>
    <p v-if="errorText" class="voice-error" data-testid="voice-error">{{ errorText }}</p>

    <div v-if="parsed" class="voice-result" data-testid="voice-result">
      <table class="voice-table">
        <thead>
          <tr><th>成绩项</th><th>识别值</th></tr>
        </thead>
        <tbody>
          <tr v-for="cell in updates" :key="cell.key">
            <td>{{ cell.label }}</td>
            <td data-testid="voice-value">{{ cell.value }}</td>
          </tr>
          <tr v-if="!updates.length">
            <td colspan="2" class="empty">没有解析出分数，请换一种说法</td>
          </tr>
        </tbody>
      </table>
      <p v-if="parsed.rejected.length" class="voice-error" data-testid="voice-rejected">
        {{ parsed.rejected.map((r) => r.text + "（" + r.reason + "）").join("；") }}
      </p>
      <p v-if="parsed.unknown.length" class="voice-unknown" data-testid="voice-unknown">
        {{ parsed.unknown.join("；") }}
      </p>
      <p v-if="parsed.unmatched.length" class="voice-hint" data-testid="voice-unmatched">
        这些数字没有可用空位（该行已有成绩）：{{ parsed.unmatched.join("、") }}。
        要覆盖已有分数，请带上成绩项名称，例如「期末九十九」。
      </p>
    </div>

    <footer class="modal-actions">
      <span v-if="lastApplied" class="voice-applied" data-testid="voice-applied">
        <Check :size="14" />已填入 {{ lastApplied }}（仍需暂存）
      </span>
      <button class="secondary" @click="emit('close')">关闭</button>
      <button
        class="primary"
        data-testid="voice-apply"
        :disabled="!updates.length || !props.target"
        @click="apply"
      >
        填入当前行
      </button>
    </footer>
  </div>
</template>

<style scoped>
.voice-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.voice-target-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.voice-target-picker {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.voice-target-picker select {
  flex: 1;
  min-width: 0;
}
.voice-target-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}
.voice-target-label {
  font-size: 12px;
  color: #5b6472;
}
.voice-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.voice-unsupported {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #8a6d1f;
}
.voice-input {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.voice-input span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #5b6472;
  font-size: 12px;
}
.voice-input textarea {
  width: 100%;
  resize: vertical;
}
.voice-interim,
.voice-unknown,
.voice-error,
.voice-applied {
  margin: 0;
  font-size: 12px;
}
.voice-interim {
  color: #2b6cb0;
}
.voice-unknown {
  color: #8a6d1f;
}
.voice-hint {
  margin: 0;
  font-size: 12px;
  color: #8a6d1f;
}
.voice-error {
  color: #c53030;
}
.voice-applied {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: auto;
  color: #2f855a;
}
.voice-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.voice-table th,
.voice-table td {
  padding: 5px 8px;
  border-bottom: 1px solid #eef1f5;
  text-align: left;
}
.voice-table .empty {
  color: #7b8494;
  text-align: center;
}
</style>
