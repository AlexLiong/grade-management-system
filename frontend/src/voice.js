/**
 * 成绩语音录入 —— 中文口语到结构化分数的解析，以及可选的浏览器语音识别接入。
 *
 * 设计要点（对应 docs/ocr-voice-design.md）：
 *
 * 1. **解析与识别分离**：`parseUtterance` 是纯函数，不依赖浏览器 API，
 *    因此可以在 Node 里直接做单元测试；`createVoiceSession` 只负责把
 *    `SpeechRecognition` 的结果喂给它。这样即使浏览器不支持语音识别，
 *    "口述 → 结构化分数" 的能力仍然可用（改用键盘输入同一段文本）。
 * 2. **中文数字全量支持**：零/〇/一/二/两/三/…/十/百，支持 "八十五"、"一百"、
 *    "点五"、"60分"、"85.5" 等口语与书面混合写法。
 * 3. **对齐已知成绩项**：只接受调用方传入的成绩项别名（来自课程权重表），
 *    不臆造列名；没提到名称的数字按"从左到右填入空位"处理，并在结果里标出来。
 * 4. **不越界**：解析出的数值必须在 0–100，否则作为 rejected 返回，界面必须提示。
 */

/* ------------------------------------------------------------ 中文数字 */

const DIGITS = {
  零: 0,
  "〇": 0,
  一: 1,
  壹: 1,
  二: 2,
  两: 2,
  贰: 2,
  三: 3,
  叁: 3,
  四: 4,
  肆: 4,
  五: 5,
  伍: 5,
  六: 6,
  陆: 6,
  七: 7,
  柒: 7,
  八: 8,
  捌: 8,
  九: 9,
  玖: 9,
};

const UNITS = { 十: 10, 拾: 10, 百: 100, 佰: 100 };

const CN_NUMBER = /[零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰]+/;
/**
 * 中文数字 → 数值。支持 0–999 的常见表达：
 * "八十五"=85、"一百"=100、"六十"=60、"十五"=15、"十"=10、"三"=3。
 * 无法解析时返回 null。
 */
export function chineseToNumber(text) {
  const source = String(text || "").trim();
  if (!source) return null;
  if (/^\d+(?:\.\d+)?$/.test(source)) return Number(source);
  let total = 0;
  let section = 0;
  let current = 0;
  let matched = false;
  for (const ch of source) {
    if (ch in DIGITS) {
      current = DIGITS[ch];
      matched = true;
    } else if (ch in UNITS) {
      const unit = UNITS[ch];
      if (unit === 10) section += (current || 1) * 10;
      else section += (current || 1) * 100;
      current = 0;
      matched = true;
    } else if (/\s/.test(ch)) {
      continue;
    } else {
      return null;
    }
  }
  total += section + current;
  return matched ? total : null;
}

/** 把口语里的数字片段（含阿拉伯数字）统一转成数值。 */
export function toNumber(text) {
  const trimmed = String(text || "").trim();
  if (!trimmed) return null;
  const direct = Number(trimmed);
  if (Number.isFinite(direct)) return direct;
  const mixed = trimmed.match(/^(\d*)([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰]+)(?:点([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖]+))?$/);
  if (mixed) {
    const head = mixed[1] ? Number(mixed[1]) : 0;
    const tail = chineseToNumber(mixed[2]);
    if (tail === null) return null;
    let value = head * 100 + tail;
    if (mixed[3]) {
      const fraction = [...mixed[3]].map((ch) => DIGITS[ch]).filter((d) => d !== undefined);
      if (fraction.length) value += Number(`0.${fraction.join("")}`);
    }
    return value;
  }
  const decimal = trimmed.match(/^(\d+)[.点]([零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖\d]+)$/);
  if (decimal) {
    const fraction = [...decimal[2]]
      .map((ch) => (/\d/.test(ch) ? ch : DIGITS[ch]))
      .filter((d) => d !== undefined)
      .join("");
    return Number(`${decimal[1]}.${fraction}`);
  }
  return chineseToNumber(trimmed);
}

/**
 * 扫描一句话，切成有序的语义单元：成绩项名 / 数值 / 无法识别的词。
 *
 * 之所以不用"按空格切词"：语音识别给出的结果常常没有空格
 * （"平时八十五实验九十二"），必须按字符流扫描才能正确断句。
 *
 * @returns {Array<{type:"component"|"number"|"unknown", name?:string, value?:number, text:string}>}
 */
export function scanUtterance(utterance, components = []) {
  const text = String(utterance || "").replace(/[，,。；;！!？?、]/g, " ");
  const names = [];
  for (const component of components)
    for (const alias of [...new Set([component.label, ...(component.aliases ?? [])])])
      if (alias) names.push({ key: component.key, alias });
  names.sort((a, b) => b.alias.length - a.alias.length);

  const units = [];
  let index = 0;
  let unknown = "";
  const flushUnknown = () => {
    const trimmed = unknown.trim();
    if (trimmed) units.push({ type: "unknown", text: trimmed });
    unknown = "";
  };

  while (index < text.length) {
    const ch = text[index];
    if (/\s/.test(ch)) {
      index++;
      continue;
    }
    // 1) 成绩项名称（最长匹配优先）
    const hit = names.find((item) => text.startsWith(item.alias, index));
    if (hit) {
      flushUnknown();
      units.push({ type: "component", name: hit.key, text: hit.alias });
      index += hit.alias.length;
      continue;
    }
    // 2) 阿拉伯数字（含小数）
    const digit = /^\d+(?:\.\d+)?/.exec(text.slice(index));
    if (digit) {
      flushUnknown();
      const value = Number(digit[0]);
      index += digit[0].length;
      // 数字后紧跟"分"时一并吃掉
      if (text[index] === "分") index++;
      units.push({ type: "number", value, text: digit[0] });
      continue;
    }
    // 3) 中文数字（含"点"小数）
    const cn = /^[零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾百佰]+/.exec(text.slice(index));
    if (cn) {
      let cursor = index + cn[0].length;
      let decimalText = "";
      if (text[cursor] === "点") {
        const fraction = /^[零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖\d]+/.exec(text.slice(cursor + 1));
        if (fraction) {
          decimalText = fraction[0];
          cursor += 1 + fraction[0].length;
        }
      }
      const value = toNumber(cn[0] + (decimalText ? `点${decimalText}` : ""));
      if (value !== null) {
        flushUnknown();
        index = cursor;
        if (text[index] === "分") index++;
        units.push({ type: "number", value, text: cn[0] + (decimalText ? `点${decimalText}` : "") });
        continue;
      }
    }
    unknown += ch;
    index++;
  }
  flushUnknown();
  return units;
}

/**
 * 解析一句口述，返回每个成绩项的取值。
 *
 * @param {string} utterance 口述文本，如 "平时八十五，实验九十二，期末八十七分"
 * @param {Array<{key:string,label:string,aliases?:string[]}>} components 成绩项（顺序即列顺序）
 * @param {{defaults?:Record<string,number|null>}} [options]
 *        defaults：该行已有值；未提到的项保持不变而不是清空。
 * @returns {{
 *   values: Record<string, number|null>,
 *   mentioned: string[],
 *   unknown: string[],
 *   rejected: Array<{text:string, value:number, reason:string}>,
 *   unmatched: number[],
 *   rest: string
 * }}
 */
export function parseUtterance(utterance, components = [], options = {}) {
  const text = String(utterance || "").replace(/[，,。；;！!？?、]/g, " ");
  const values = {};
  for (const component of components)
    values[component.key] = options.defaults?.[component.key] ?? null;
  const mentioned = [];
  const unknown = [];
  const rejected = [];

  const units = scanUtterance(text, components);
  const stray = [];
  const claimed = new Set();

  // 第一遍：显式提到名称的项，消费紧随其后的第一个数值
  for (let i = 0; i < units.length; i++) {
    const unit = units[i];
    if (unit.type === "component") {
      let valueAt = -1;
      for (let look = i + 1; look < Math.min(i + 3, units.length); look++) {
        if (units[look].type === "number") {
          valueAt = look;
          break;
        }
        if (units[look].type === "component") break;
      }
      if (valueAt < 0) {
        unknown.push(unit.text);
        continue;
      }
      const value = units[valueAt].value;
      if (value < 0 || value > 100) {
        rejected.push({ text: `${unit.text}${value}`, value, reason: "超出 0–100" });
        claimed.add(valueAt);
        continue;
      }
      values[unit.name] = Math.round(value * 100) / 100;
      if (!mentioned.includes(unit.name)) mentioned.push(unit.name);
      claimed.add(valueAt);
    } else if (unit.type === "number") {
      // 已被前一个成绩项消费掉的数字不重复计入
      if (claimed.has(i)) continue;
      if (unit.value < 0 || unit.value > 100) {
        rejected.push({ text: unit.text, value: unit.value, reason: "超出 0–100" });
      } else stray.push({ index: i, value: Math.round(unit.value * 100) / 100 });
    } else if (unit.type === "unknown") {
      unknown.push(unit.text);
    }
  }

  // 第二遍：裸数字按列顺序补进"本次未提到且当前为空"的项。
  // 为什么不直接覆盖已有分数：口述"90 88 76"时无法确认说话人心里是哪一列，
  // 覆盖已有成绩属于不可逆的误操作；补空位是安全默认，剩下的数字在界面上明确提示未使用。
  let cursor = 0;
  for (const component of components) {
    if (cursor >= stray.length) break;
    if (mentioned.includes(component.key)) continue;
    if (values[component.key] !== null && values[component.key] !== undefined) continue;
    values[component.key] = stray[cursor].value;
    mentioned.push(component.key);
    cursor++;
  }
  const unusedNumbers = stray.slice(cursor).map((item) => item.value);
  if (unusedNumbers.length) unknown.push(`未使用 ${unusedNumbers.join("、")}`);

  return {
    values,
    mentioned,
    unknown,
    rejected,
    unmatched: unusedNumbers,
    rest: text.trim(),
  };
}

/** 把解析结果整理成"待确认单元格"列表，便于界面逐格展示。 */
export function toCellUpdates(parsed, components) {
  return components
    .filter((component) => parsed.mentioned.includes(component.key))
    .map((component) => ({
      key: component.key,
      label: component.label,
      value: parsed.values[component.key],
    }));
}

/* --------------------------------------------------- 浏览器语音识别接入 */

/** 是否支持浏览器语音识别（Chrome/Edge 的 webkitSpeechRecognition）。 */
export function speechSupport(scope = globalThis) {
  return Boolean(scope.SpeechRecognition || scope.webkitSpeechRecognition);
}

/**
 * 创建一次语音识别会话。
 *
 * 说明：浏览器语音识别由浏览器厂商提供，**音频可能被发送到厂商的云服务**，
 * 因此调用方必须在界面上明确提示并要求教师逐次确认（见 docs/ocr-voice-design.md 的隐私说明）。
 * 不支持时返回 `{ supported: false }`，界面应降级为文本框输入，解析逻辑完全一致。
 *
 * @param {object} handlers
 * @param {(text:string, isFinal:boolean)=>void} handlers.onResult
 * @param {(error:string)=>void} [handlers.onError]
 * @param {()=>void} [handlers.onEnd]
 * @param {object} [scope] 便于测试注入
 */
export function createVoiceSession(handlers = {}, scope = globalThis) {
  const Recognition = scope.SpeechRecognition || scope.webkitSpeechRecognition;
  if (!Recognition) return { supported: false, start() {}, stop() {}, abort() {} };
  const recognition = new Recognition();
  recognition.lang = "zh-CN";
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.maxAlternatives = 1;

  let stopped = false;
  recognition.onresult = (event) => {
    let interim = "";
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      const text = result[0]?.transcript ?? "";
      if (result.isFinal) handlers.onResult?.(text, true);
      else interim += text;
    }
    if (interim) handlers.onResult?.(interim, false);
  };
  recognition.onerror = (event) => handlers.onError?.(event?.error || "speech-error");
  recognition.onend = () => {
    if (!stopped) handlers.onEnd?.();
  };

  return {
    supported: true,
    start() {
      stopped = false;
      try {
        recognition.start();
      } catch (e) {
        handlers.onError?.(String(e?.message || e));
      }
    },
    stop() {
      stopped = true;
      try {
        recognition.stop();
      } catch {
        /* 已停止 */
      }
    },
    abort() {
      stopped = true;
      try {
        recognition.abort();
      } catch {
        /* 已停止 */
      }
    },
  };
}

/** 成绩项的中文口语别名：用于把"出勤/实验分"等说法映射到权重表里的列名。 */
export const COMPONENT_ALIASES = {
  regular: ["平时", "平时成绩", "平时分", "平时表现"],
  attendance: ["考勤", "出勤", "点名"],
  homework: ["作业", "平时作业"],
  lab: ["实验", "上机", "实验分"],
  midterm: ["期中", "期中考", "期中考试"],
  finalExam: ["期末", "期末考", "期末考试", "大考"],
  makeup: ["补考", "补考成绩"],
};

export function buildVoiceComponents(components) {
  return (components ?? []).map((item) => {
    const [key, label] = Array.isArray(item) ? item : [item?.key, item?.label];
    return { key, label, aliases: COMPONENT_ALIASES[key] ?? [] };
  });
}
