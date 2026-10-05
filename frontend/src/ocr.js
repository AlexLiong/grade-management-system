/**
 * 成绩单图片识别（本地 OCR）—— 预处理、版面切分、结构化与容错。
 *
 * 设计要点（对应 docs/ocr-voice-design.md）：
 *
 * 1. **纯浏览器本地执行**：Tesseract.js 与其语言包都从本站 `/ocr/` 提供，
 *    图片经 Canvas 处理，**不上传任何字节**，符合课程"传输加密 + 临时存储即销毁"的约束。
 * 2. **只识别数字，不依赖中文语言包**：成绩单里必须准确的只有学号与分数；
 *    中文表头是噪声，因此语言固定为 `eng`，并用版面几何（列锚点）而不是表头文字来分列。
 * 3. **不用 tesseract 的整页版面分析**：表格框线会让整页 PSM 误判，
 *    这里先用投影法切出行带，再逐行以 PSM 7（单行文本）识别，稳定得多。
 * 4. **多策略择优**：预处理生成若干变体（灰度 / 对比度拉伸 / Otsu 二值 / 轻微旋转），
 *    以"置信度 + 命中名册数"打分，取最好的那个结果。
 * 5. **可解释**：每一行都带 `issues` 与 `confidence`，界面上可逐格修改或整行跳过；
 *    任何单行失败都不会中断整批。
 */

/* ------------------------------------------------------------ 常量与参数 */

/** 参与比对的字符白名单：数字、小数点、负号。 */
const NUMERIC = /^-?\d+(?:\.\d+)?$/;

/** OCR 常见混淆：用于学号模糊纠正（左侧是识别结果，右侧是修正值）。 */
const CONFUSION = {
  O: "0",
  o: "0",
  Q: "0",
  D: "0",
  I: "1",
  l: "1",
  "|": "1",
  "!": "1",
  i: "1",
  Z: "2",
  z: "2",
  B: "8",
  S: "5",
  s: "5",
  G: "6",
  b: "6",
  T: "7",
  A: "4",
  g: "9",
  q: "9",
};

/** 预处理变体：名称用于结果展示与排障；每个变体自带二值化方式与几何变换。 */
export const VARIANTS = [
  { id: "gray", label: "灰度 + 对比度拉伸", binarize: "otsu", rotate: 0, scale: 1 },
  { id: "otsu", label: "Otsu 二值化", binarize: "otsu", rotate: 0, scale: 1 },
  { id: "adaptive", label: "自适应阈值（抗阴影）", binarize: "adaptive", rotate: 0, scale: 1 },
  { id: "gray-up2", label: "灰度 + 2 倍放大", binarize: "otsu", rotate: 0, scale: 2 },
  { id: "adaptive-up2", label: "自适应阈值 + 2 倍放大", binarize: "adaptive", rotate: 0, scale: 2 },
  { id: "gray-rot+1", label: "灰度 + 顺时针 1°", binarize: "otsu", rotate: 1, scale: 1 },
  { id: "gray-rot-1", label: "灰度 + 逆时针 1°", binarize: "otsu", rotate: -1, scale: 1 },
  { id: "gray-rot+2", label: "灰度 + 顺时针 2°", binarize: "otsu", rotate: 2, scale: 1 },
  { id: "gray-rot-2", label: "灰度 + 逆时针 2°", binarize: "otsu", rotate: -2, scale: 1 },
  { id: "otsu-rot+1", label: "Otsu + 顺时针 1°", binarize: "otsu", rotate: 1, scale: 1 },
  { id: "otsu-rot-1", label: "Otsu + 逆时针 1°", binarize: "otsu", rotate: -1, scale: 1 },
];

/** 图像处理与识别的上限，避免超大图拖垮浏览器。 */
const MAX_EDGE = 2600;
const MIN_ROW_HEIGHT = 12;
const MAX_ROWS = 200;

/* --------------------------------------------------------------- 图像处理 */

/** 仅在测试脚本里使用：暴露旋转实现，便于验证"转过去再转回来"的一致性。 */
export const __testRotate = (canvas, degrees) => rotateCanvas(canvas, degrees);

function toCanvas(source, width, height) {
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(width));
  canvas.height = Math.max(1, Math.round(height));
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
  return canvas;
}

/** 读取图像尺寸，按最长边限制等比缩放后返回 Canvas；深色截图会自动反色。 */
export function loadCanvas(source, maxEdge = MAX_EDGE) {
  const width = source.naturalWidth || source.width;
  const height = source.naturalHeight || source.height;
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  const canvas = toCanvas(source, width * scale, height * scale);
  invertIfDark(canvas);
  return canvas;
}

/**
 * 深色背景自动反色。
 *
 * 截图来源不一定是"白纸黑字"：暗色主题的编辑器、终端、深色表格截图是**亮字暗底**，
 * 而整条识别管线（Otsu 的前景/背景假设、行带墨量投影）都建立在"背景亮、文字暗"之上，
 * 不处理就会把背景当成一片墨，行带与列全部错乱（实测暗色截图只能勉强认出 2 行且缺列）。
 *
 * 判据：整体平均灰度 < 110 且暗像素占比 > 55%，两个条件同时满足才反色，
 * 避免把"正常但偏灰的扫描件"误反转。
 *
 * @returns {boolean} 是否做了反色
 */
export function invertIfDark(canvas) {
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const data = image.data;
  let sum = 0;
  let dark = 0;
  const pixels = Math.max(1, data.length / 4);
  for (let i = 0; i < data.length; i += 4) {
    const gray = (data[i] * 299 + data[i + 1] * 587 + data[i + 2] * 114) / 1000;
    sum += gray;
    if (gray < 128) dark++;
  }
  if (sum / pixels >= 110 || dark / pixels <= 0.55) return false;
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 255 - data[i];
    data[i + 1] = 255 - data[i + 1];
    data[i + 2] = 255 - data[i + 2];
  }
  ctx.putImageData(image, 0, 0);
  return true;
}

function toGray(canvas) {
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const { width, height } = canvas;
  const image = ctx.getImageData(0, 0, width, height);
  const data = image.data;
  const gray = new Uint8ClampedArray(width * height);
  for (let i = 0, p = 0; i < data.length; i += 4, p++) {
    gray[p] = (data[i] * 299 + data[i + 1] * 587 + data[i + 2] * 114) / 1000;
  }
  return { gray, width, height };
}

function grayToCanvas(gray, width, height) {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  const image = ctx.createImageData(width, height);
  for (let p = 0, i = 0; p < gray.length; p++, i += 4) {
    const v = gray[p];
    image.data[i] = v;
    image.data[i + 1] = v;
    image.data[i + 2] = v;
    image.data[i + 3] = 255;
  }
  ctx.putImageData(image, 0, 0);
  return canvas;
}

/** 直方图拉伸：把 2%~98% 分位映射到 0~255，消除低对比与整体偏暗。 */
function stretch(gray) {
  const hist = new Uint32Array(256);
  for (const v of gray) hist[v]++;
  const total = gray.length;
  let low = 0;
  let high = 255;
  let acc = 0;
  for (let v = 0; v < 256; v++) {
    acc += hist[v];
    if (acc >= total * 0.02) {
      low = v;
      break;
    }
  }
  acc = 0;
  for (let v = 255; v >= 0; v--) {
    acc += hist[v];
    if (acc >= total * 0.02) {
      high = v;
      break;
    }
  }
  if (high - low < 16) return gray.slice();
  const out = new Uint8ClampedArray(gray.length);
  const scale = 255 / (high - low);
  for (let i = 0; i < gray.length; i++) out[i] = (gray[i] - low) * scale;
  return out;
}

/** Otsu 阈值：白色背景印黑字时得到"文字=0、背景=255"的二值图。 */
function otsu(gray) {
  const hist = new Uint32Array(256);
  for (const v of gray) hist[v]++;
  const total = gray.length;
  let sum = 0;
  for (let v = 0; v < 256; v++) sum += v * hist[v];
  let sumB = 0;
  let wB = 0;
  let best = 0;
  let threshold = 128;
  for (let v = 0; v < 256; v++) {
    wB += hist[v];
    if (wB === 0) continue;
    const wF = total - wB;
    if (wF === 0) break;
    sumB += v * hist[v];
    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;
    const between = wB * wF * (mB - mF) * (mB - mF);
    if (between > best) {
      best = between;
      threshold = v;
    }
  }
  const out = new Uint8ClampedArray(gray.length);
  for (let i = 0; i < gray.length; i++) out[i] = gray[i] > threshold ? 255 : 0;
  return { binary: out, threshold };
}

/**
 * 自适应均值阈值：每个像素与"自身周围 radius 内的均值 − offset"比较。
 *
 * 为什么需要它：Otsu 是**全局**阈值，遇到拍照阴影（左暗右亮）时会把暗侧整片判成文字，
 * 行带切分随之失效——这正是"阴影 + 噪点"用例在全局阈值下识别不出来的原因。
 * 积分图实现，复杂度 O(W·H)，在千级像素的图上是毫秒级。
 */
function adaptiveThreshold(gray, width, height, options = {}) {
  const radius = options.radius ?? Math.max(8, Math.round(Math.min(width, height) / 12));
  const offset = options.offset ?? 10;
  const integral = new Float64Array((width + 1) * (height + 1));
  for (let y = 0; y < height; y++) {
    let rowSum = 0;
    for (let x = 0; x < width; x++) {
      rowSum += gray[y * width + x];
      integral[(y + 1) * (width + 1) + (x + 1)] = integral[y * (width + 1) + (x + 1)] + rowSum;
    }
  }
  const out = new Uint8ClampedArray(gray.length);
  for (let y = 0; y < height; y++) {
    const y0 = Math.max(0, y - radius);
    const y1 = Math.min(height - 1, y + radius);
    for (let x = 0; x < width; x++) {
      const x0 = Math.max(0, x - radius);
      const x1 = Math.min(width - 1, x + radius);
      const area = (x1 - x0 + 1) * (y1 - y0 + 1);
      const sum =
        integral[(y1 + 1) * (width + 1) + (x1 + 1)] -
        integral[y0 * (width + 1) + (x1 + 1)] -
        integral[(y1 + 1) * (width + 1) + x0] +
        integral[y0 * (width + 1) + x0];
      const mean = sum / area;
      out[y * width + x] = gray[y * width + x] > mean - offset ? 255 : 0;
    }
  }
  return out;
}

/**
 * 旋转画布（**双线性插值**，白底）。
 *
 * 为什么不用最近邻：倾斜校正要"转过去再转回来"两次重采样，最近邻会让笔画出现锯齿与断点，
 * 实测 1–2° 的旋转就足以把「87」读成「g§7」。双线性插值让笔画保持连续，
 * 代价是每像素 4 次采样，对千级像素的图仍是毫秒级。
 */
function rotateCanvas(canvas, degrees) {
  const rad = (degrees * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  const width = Math.ceil(Math.abs(canvas.width * cos) + Math.abs(canvas.height * sin));
  const height = Math.ceil(Math.abs(canvas.width * sin) + Math.abs(canvas.height * cos));
  const out = document.createElement("canvas");
  out.width = width;
  out.height = height;
  const outCtx = out.getContext("2d", { willReadFrequently: true });
  outCtx.fillStyle = "#fff";
  outCtx.fillRect(0, 0, width, height);

  const src = canvas.getContext("2d", { willReadFrequently: true }).getImageData(0, 0, canvas.width, canvas.height);
  const srcData = src.data;
  const dst = outCtx.getImageData(0, 0, width, height);
  const dstData = dst.data;
  const cx = canvas.width / 2;
  const cy = canvas.height / 2;
  const ox = width / 2;
  const oy = height / 2;

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const dx = x - ox;
      const dy = y - oy;
      const sx = cos * dx + sin * dy + cx;
      const sy = -sin * dx + cos * dy + cy;
      if (sx < 0 || sy < 0 || sx > canvas.width - 1 || sy > canvas.height - 1) continue;
      const x0 = Math.floor(sx);
      const y0 = Math.floor(sy);
      const x1 = Math.min(canvas.width - 1, x0 + 1);
      const y1 = Math.min(canvas.height - 1, y0 + 1);
      const wx = sx - x0;
      const wy = sy - y0;
      const i00 = (y0 * canvas.width + x0) * 4;
      const i10 = (y0 * canvas.width + x1) * 4;
      const i01 = (y1 * canvas.width + x0) * 4;
      const i11 = (y1 * canvas.width + x1) * 4;
      const offset = (y * width + x) * 4;
      for (let channel = 0; channel < 3; channel++) {
        const top = srcData[i00 + channel] * (1 - wx) + srcData[i10 + channel] * wx;
        const bottom = srcData[i01 + channel] * (1 - wx) + srcData[i11 + channel] * wx;
        dstData[offset + channel] = top * (1 - wy) + bottom * wy;
      }
      dstData[offset + 3] = 255;
    }
  }
  outCtx.putImageData(dst, 0, 0);
  return out;
}

/**
 * 用投影法估计倾斜角。
 *
 * 原理：文字按行排布时，**行方向**的墨量投影会出现"一行有字、一行空白"的清晰交替。
 * 评分用"归一化行投影的平方和"：sum(rowInk²) / totalInk²。
 * 归一化很关键——不除掉总墨量的话，旋转带来的重采样噪声（笔画被打散成孤立点）
 * 反而会让评分变大，估计结果就会偏向被破坏得最厉害的角度。
 *
 * 为了速度，先在缩略图上估计（投影指标与分辨率无关）。
 *
 * @returns {number} 估计角度（度）；返回正值表示图顺时针倾斜，校正时应反向旋转。
 */
export function estimateSkew(canvas, options = {}) {
  const candidates =
    options.candidates ?? [-4, -3, -2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2, 3, 4];
  const scale = Math.min(1, 600 / Math.max(1, canvas.width));
  const probe = scale < 1 ? toCanvas(canvas, canvas.width * scale, canvas.height * scale) : canvas;
  const scores = [];
  for (const angle of candidates) {
    const rotated = angle === 0 ? probe : rotateCanvas(probe, angle);
    const { gray, width, height } = toGray(rotated);
    const { binary } = otsu(stretch(gray));
    const y0 = Math.floor(height * 0.08);
    const y1 = Math.ceil(height * 0.92);
    let total = 0;
    let squares = 0;
    for (let y = y0; y < y1; y++) {
      let ink = 0;
      const base = y * width;
      for (let x = 0; x < width; x++) if (binary[base + x] === 0) ink++;
      total += ink;
      squares += ink * ink;
    }
    if (!total) continue;
    scores.push({ angle, score: squares / (total * total) });
  }
  if (!scores.length) return 0;
  scores.sort((a, b) => b.score - a.score);
  return scores[0].angle;
}

/* ------------------------------------------------------------- 行带切分 */

/**
 * 按水平投影切出行带（每一行文字一条）。
 *
 * 阈值不能一刀切：定高了，轻微倾斜会让相邻行在行间重叠、整张表被切成一条巨带；
 * 定低了，"阴影+噪点"里的背景噪点会被当成文字、行带被切碎。
 * 因此这里用一组阈值各切一次，选"切出的文字行总数最接近整图文字行规模"的那个结果。
 *
 * @param {Uint8ClampedArray} binary 二值图（0=文字）
 * @param {number} width
 * @param {number} height
 * @returns {Array<{top:number, bottom:number, ink:number}>}
 */
export function segmentBands(binary, width, height, options = {}) {
  const minHeight = options.minHeight ?? MIN_ROW_HEIGHT;
  const maxRows = options.maxRows ?? MAX_ROWS;
  const rows = new Float64Array(height);
  for (let y = 0; y < height; y++) {
    let ink = 0;
    const base = y * width;
    for (let x = 0; x < width; x++) if (binary[base + x] === 0) ink++;
    rows[y] = ink;
  }
  const maxInk = Math.max(...rows, 1);
  const sorted = [...rows].sort((a, b) => a - b);
  const p90 = sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.9))] || maxInk;
  const totalInk = rows.reduce((total, value) => total + value, 0);
  const minBandInk = Math.max(8, totalInk * 0.004);
  const ratios = options.ratios ?? [0.06, 0.03, 0.12, 0.015];
  // "整图大概有多少行文字"：表格行距通常远大于字号，用图高估计会造成大量碎带，
  // 因此以"墨量达到最大值 25% 的行"数量作为粗略规模（有字的行占比很高）
  const strongRows = rows.reduce((total, value) => total + (value >= maxInk * 0.25 ? 1 : 0), 0);
  const expectedLines = Math.max(1, Math.round(strongRows / 3));

  const splitWith = (threshold) => {
    const bands = [];
    let start = -1;
    for (let y = 0; y < height; y++) {
      const active = rows[y] >= threshold;
      if (active && start < 0) start = y;
      if (!active && start >= 0) {
        bands.push({ top: start, bottom: y - 1, ink: sum(rows, start, y - 1) });
        start = -1;
      }
    }
    if (start >= 0) bands.push({ top: start, bottom: height - 1, ink: sum(rows, start, height - 1) });
    const merged = [];
    for (const band of bands) {
      const bandHeight = band.bottom - band.top + 1;
      const last = merged[merged.length - 1];
      if (last && (bandHeight < minHeight || band.top - last.bottom <= 2)) {
        last.bottom = band.bottom;
        last.ink += band.ink;
      } else merged.push({ ...band });
    }
    return merged
      .filter((band) => band.bottom - band.top + 1 >= minHeight && band.ink >= minBandInk)
      .slice(0, maxRows)
      .map((band) => ({
        top: Math.max(0, band.top - 4),
        bottom: Math.min(height - 1, band.bottom + 4),
        ink: band.ink,
      }));
  };

  let best = null;
  let bestScore = -Infinity;
  for (const ratio of ratios) {
    const threshold = Math.max(1, Math.min(maxInk * ratio, p90 * ratio));
    const candidate = splitWith(threshold);
    if (!candidate.length) continue;
    // 评分：行数落在 [expectedLines/3, expectedLines*2] 之内最好，越接近越优，其次行数多的优先
    const lines = candidate.length;
    const low = Math.max(1, Math.floor(expectedLines / 3));
    const high = Math.max(low + 1, expectedLines * 2);
    const inRange = lines >= low && lines <= high;
    const score = (inRange ? 1000 : 0) - Math.abs(lines - expectedLines) + lines * 0.5;
    if (score > bestScore) {
      bestScore = score;
      best = candidate;
    }
  }
  return best ?? splitWith(Math.max(1, maxInk * 0.06));
}

function sum(values, from, to) {
  let total = 0;
  for (let i = from; i <= to; i++) total += values[i];
  return total;
}

/* --------------------------------------------------------------- 识别 */

function normalize(text) {
  return String(text || "")
    .replace(/[，,、;；|]/g, " ")
    .replace(/[（）()[\]{}]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** 把一行文本切成 token。 */
function tokenize(text) {
  return normalize(text)
    .split(" ")
    .map((t) => t.replace(/[^\dA-Za-z.\-]/g, ""))
    .filter((t) => t.length > 0);
}

/** 把 token 里的数字抽取为候选分数；`85.5` 保留一位小数。 */
function asNumber(token) {
  if (!NUMERIC.test(token)) return null;
  const value = Number(token);
  if (!Number.isFinite(value)) return null;
  return Math.round(value * 100) / 100;
}

/**
 * 是否可能是分数：整数位不超过 3 位（成绩单上不会出现 8 位学号被当成分数）。
 * 学号单独由 {@link idCandidates} 识别，不参与分列。
 */
const SCORE_SHAPE = /^\d{1,3}(?:\.\d+)?$/;

function asScore(token) {
  if (!SCORE_SHAPE.test(token)) return null;
  return asNumber(token);
}

/** 置信度：取该行所有词置信度的加权平均（墨量少的词权重低）。 */
function lineConfidence(words) {
  if (!words?.length) return 0;
  let weight = 0;
  let total = 0;
  for (const word of words) {
    const w = Math.max(1, (word.bbox?.x1 ?? 1) - (word.bbox?.x0 ?? 0));
    weight += w;
    total += w * (word.confidence ?? 0);
  }
  return weight ? total / weight : 0;
}

function flattenWords(data) {
  const words = [];
  for (const block of data.blocks || [])
    for (const paragraph of block.paragraphs || [])
      for (const line of paragraph.lines || [])
        for (const word of line.words || [])
          if (word.text?.trim() && word.bbox)
            words.push({
              text: word.text,
              confidence: word.confidence ?? 0,
              x0: word.bbox.x0,
              x1: word.bbox.x1,
              y0: word.bbox.y0,
              y1: word.bbox.y1,
            });
  if (!words.length && data.text) {
    // 兜底：极少数情况下 blocks 为空，退化为整段文本
    const text = normalize(data.text);
    if (text) words.push({ text, confidence: 0, x0: 0, x1: 1, y0: 0, y1: 1 });
  }
  return words;
}

/**
 * 识别一张图：多策略预处理 → 行带切分 → 逐行 PSM 7 → 汇总为候选行。
 *
 * 这是"低层"入口：返回**全部**候选变体，由调用方决定怎么选。
 * 只需要"给我最好的结果"时用 {@link recognizeBest}。
 *
 * @param {HTMLCanvasElement|HTMLImageElement} source
 * @param {(info:{stage:string,progress:number})=>void} [onProgress]
 * @param {{tesseract?:object, maxVariants?:number, goodEnough?:number,
 *          skew?:number, offsets?:number[]}} [options]
 * @returns {Promise<{variants:Array, width:number, height:number, skew:number}>}
 */
export async function recognizeSheet(source, onProgress = () => {}, options = {}) {
  const canvas = source?.tagName === "CANVAS" ? source : loadCanvas(source);
  const skew = options.skew ?? estimateSkew(canvas);
  onProgress({ stage: "skew", progress: 1 });
  const variantList = buildVariants(canvas, skew, options.offsets ?? [0]).slice(
    0,
    options.maxVariants ?? VARIANTS.length,
  );
  const { variants } = await recognizeVariants(canvas, variantList, onProgress, {
    tesseract: options.tesseract,
    goodEnough: options.goodEnough ?? 55,
  });
  onProgress({ stage: "done", progress: 1 });
  return { variants, width: canvas.width, height: canvas.height, skew };
}

/**
 * 识别一张图，并直接返回**选优后的结果**（App 与测试共用同一段代码）。
 *
 * 两轮策略：
 *   1. 第一轮围绕倾斜估计值生成变体；
 *   2. 只要结果还"不够确定"（可填入行数 < goodEnough，或还有格子对不上），
 *      就继续用更粗的旋转角扩展搜索——倾斜估计偏 1° 时后面的轮次能救回来。
 *   3. 结果已经完整命中（可填入行数达到 goodEnough）时立刻停，清晰图因此只需 1 秒左右。
 *
 * @param {HTMLCanvasElement|HTMLImageElement} source
 * @param {(info:{stage:string,progress:number})=>void} [onProgress]
 * @param {{tesseract?:object, goodEnough?:number, roster?:Array, components?:Array,
 *          maxPasses?:number, maxVariants?:number, skew?:number}} [options]
 *        roster/components 传入后即可用于选优（命中名册的行越多越好）。
 * @returns {Promise<{best:object|null, variants:Array, skew:number, passes:number, width:number, height:number}>}
 */
/**
 * 识别一张图，并直接返回**选优后的结果**（App 与测试共用同一段代码）。
 *
 * 流程（清晰图只跑第一轮，约 1 秒）：
 *   1. 用投影法估一个倾斜角，围绕它跑一组预处理变体（二值化方式 / 放大 / 微调旋转）；
 *   2. **自校正**：把识别到的文字行做一次线性拟合，量出真实倾斜；
 *      若与估计值差 > 0.4°，用实测角度重跑一轮——投影法偶尔会差 1°，
 *      而 1° 就足以让最后一列串行；
 *   3. 若结果仍不完整：先按 scaleSteps（默认 2/3/1.5 倍）放大重试（小字号截图），
 *      再按 ±1°/±2° 微调旋转；最终取"可填入行数 → 已填格子数 → 置信度"最优者。
 *
 * @param {HTMLCanvasElement|HTMLImageElement} source
 * @param {(info:{stage:string,progress:number})=>void} [onProgress]
 * @param {{tesseract?:object, goodEnough?:number, roster?:Array, components?:Array,
 *          maxVariants?:number, variantQuality?:number, skew?:number,
 *          scaleSteps?:number[], allowDigitCorrection?:boolean}} [options]
 * @returns {Promise<{best:object|null, variants:Array, skew:number, coarseSkew:number,
 *                    corrected:boolean, passes:number, width:number, height:number}>}
 */
export async function recognizeBest(source, onProgress = () => {}, options = {}) {
  const canvas = source?.tagName === "CANVAS" ? source : loadCanvas(source);
  const roster = options.roster ?? [];
  const components = options.components ?? [];
  const goodEnough = options.goodEnough ?? 1;
  const coarseSkew = options.skew ?? estimateSkew(canvas);
  const maxVariants = options.maxVariants ?? 4;
  const variantQualityThreshold = options.variantQuality ?? 55;
  const passes = [];

  /**
   * 跑一轮候选。
   *
   * 关键一：**做了纠偏时把"未纠偏原图"放在本轮最前面**。
   * 旋转要重采样、笔画会变糊；倾斜不大或估计偏 1° 时原图往往更好读
   * （实测 `skew-2deg`/`skew-4deg` 的最优候选都是 `gray-raw`）。放在末尾可能被提前结束跳过。
   *
   * 关键二：`scale` 用于**小字号截图**（Excel/网页截图常见 11–14px 字）。
   * 这类图原始像素太少，直接识别会把 `80` 读成 `8`；放大 2~3 倍后同一张图能完整读出。
   */
  const runPass = async (skew, offset, scale = 1) => {
    const list = buildVariants(canvas, skew, [offset], {
      includeRaw: true,
      rawFirst: offset === 0,
      scale,
    });
    const { variants } = await recognizeVariants(canvas, list.slice(0, maxVariants), onProgress, {
      tesseract: options.tesseract,
      goodEnough: variantQualityThreshold,
    });
    passes.push(
      ...variants.map((variant) => ({
        variant,
        score: scoreVariant(variant, components, roster, { allowDigitCorrection: options.allowDigitCorrection }),
      })),
    );
    return rankPasses(passes)[0] ?? null;
  };

  let skew = coarseSkew;
  let corrected = false;
  let top = await runPass(skew, 0);
  if (top) {
    const measured = estimateTiltFromRows(top.variant.rows);
    if (measured !== null && Math.abs(measured - skew) > 0.4) {
      skew = measured;
      corrected = true;
      passes.length = 0;
      top = await runPass(skew, 0);
    }
  }

  const complete = (entry) =>
    entry.score.usable >= goodEnough &&
    entry.score.filled >= entry.score.rows * Math.max(1, components.length);

  // 尺度回退：小字号截图放大后往往一次就完整了
  const scaleSteps = options.scaleSteps ?? [2, 3, 1.5];
  if (!top || !complete(top)) {
    for (const scale of scaleSteps) {
      if (scale <= 1) continue;
      const entry = await runPass(skew, 0, scale);
      if (!entry) continue;
      top = entry;
      if (complete(entry)) break;
    }
  }

  // 角度微调：仍然不完整时按 ±1°/±2° 试
  if (!top || !complete(top)) {
    for (const offset of [1, -1, 2, -2]) {
      const entry = await runPass(skew, offset);
      if (!entry) continue;
      top = entry;
      if (complete(entry)) break;
    }
  }

  const best = rankPasses(passes)[0] ?? null;
  return {
    best: best
      ? { ...best.variant, usable: best.score.usable, meanConfidence: best.score.mean, score: best.score }
      : null,
    variants: passes.map((entry) => entry.variant),
    skew,
    coarseSkew,
    corrected,
    passes: passes.length,
    width: canvas.width,
    height: canvas.height,
  };
}

/** 排序：可填入行数 → 已填格子数 → 均值置信度。 */
function rankPasses(passes) {
  return [...passes].sort(
    (a, b) => b.score.usable - a.score.usable || b.score.filled - a.score.filled || b.score.mean - a.score.mean,
  );
}

/** 变体打分：命中名册的行数为主，平均置信度为辅。 */
export function scoreVariant(variant, components, roster, options = {}) {
  const preview = buildPreview(variant.rows ?? [], components, roster, undefined, options);
  const rows = preview.previewRows;
  const mean = rows.length ? rows.reduce((total, row) => total + row.confidence, 0) / rows.length : 0;
  return {
    usable: preview.summary.usable,
    rows: rows.length,
    filled: rows.reduce((total, row) => total + row.cells.filter((c) => c.value !== null).length, 0),
    mean: Math.round(mean * 10) / 10,
    columns: preview.columns.length,
  };
}

/**
 * 从"行带内部的文字行"估计**残余倾斜角**（度）。
 *
 * 优先用 tesseract 给出的 **baseline**：它是识别到的文字基线（起点/终点坐标），
 * 取它的斜率几乎就是这一行文字的倾斜；比用外接框中心拟合准得多
 * （外接框还包含上下延伸的笔画，会让斜率被稀释）。
 * 没有 baseline 时退化为对所有行的外接框中心做最小二乘拟合。
 *
 * @returns {number|null} 倾斜角度；样本不足时返回 null
 */
export function estimateTiltFromRows(rows = []) {
  // 1) 优先：baseline 斜率
  const baselineAngles = [];
  for (const row of rows) {
    for (const line of row?.lines ?? []) {
      const b = line.baseline;
      if (!b) continue;
      const dx = (b.x1 ?? 0) - (b.x0 ?? 0);
      const dy = (b.y1 ?? 0) - (b.y0 ?? 0);
      if (Math.abs(dx) < 60) continue; // 太短的基线容易受噪声影响
      const angle = (Math.atan2(dy, dx) * 180) / Math.PI;
      if (Math.abs(angle) <= 12) baselineAngles.push(angle);
    }
  }
  if (baselineAngles.length >= 2) {
    const mean = baselineAngles.reduce((t, v) => t + v, 0) / baselineAngles.length;
    return Math.round(mean * 100) / 100;
  }

  // 2) 兜底：外接框中心的最小二乘拟合
  const points = [];
  for (const row of rows) {
    for (const line of row?.lines ?? []) points.push({ x: line.x, y: line.y });
  }
  if (points.length < 6) return null;
  const meanX = points.reduce((t, p) => t + p.x, 0) / points.length;
  const meanY = points.reduce((t, p) => t + p.y, 0) / points.length;
  let covariance = 0;
  let variance = 0;
  for (const point of points) {
    covariance += (point.x - meanX) * (point.y - meanY);
    variance += (point.x - meanX) ** 2;
  }
  if (variance < 1) return null;
  const angle = (Math.atan(covariance / variance) * 180) / Math.PI;
  return Math.abs(angle) <= 12 ? Math.round(angle * 100) / 100 : null;
}

/** 按给定的旋转偏移集合生成候选预处理画布。 */
function buildVariants(canvas, skew, offsets = [0], options = {}) {
  const includeRaw = options.includeRaw ?? true;
  // 先按需放大（小字号截图），再做倾斜校正，避免"先旋转再放大"两次重采样
  const scale = options.scale && options.scale > 1 ? options.scale : 1;
  const source = scale > 1 ? toCanvas(canvas, canvas.width * scale, canvas.height * scale) : canvas;
  const base = Math.abs(skew) > 0.25 ? rotateCanvas(source, -skew) : source;
  const list = [];
  const push = (spec, source, rotate, idSuffix = "", labelSuffix = "") => {
    list.push({
      id: `${spec.id}${idSuffix}`,
      label: `${spec.label}${labelSuffix}`,
      binarize: spec.binarize,
      angle: rotate,
      canvas: rotate !== 0 ? rotateCanvas(source, rotate) : spec.scale === 2 ? toCanvas(source, source.width * 2, source.height * 2) : source,
    });
  };

  const corrected = base !== source;
  const candidates = [];
  if (includeRaw && corrected && options.rawFirst) candidates.push("raw");
  candidates.push("corrected");
  if (includeRaw && corrected && !options.rawFirst) candidates.push("raw");
  const rawSpec = { ...VARIANTS[0], id: "gray-raw", label: "灰度 + 对比度拉伸（不纠偏）" };
  for (const offset of offsets) {
    for (const which of candidates) {
      if (which === "raw") push(rawSpec, source, 0);
      else {
        const rotate = offset;
        push({ ...VARIANTS[0], id: "gray", label: "灰度 + 对比度拉伸" }, base, rotate);
        push({ ...VARIANTS[1], id: "otsu", label: "Otsu 二值化" }, base, rotate);
        push(
          { id: "adaptive", label: "自适应阈值（抗阴影）", binarize: "adaptive", scale: 1 },
          base,
          rotate,
        );
        push({ id: "gray-up2", label: "灰度 + 2 倍放大", binarize: "otsu", scale: 2 }, base, rotate);
      }
    }
  }
  return list;
}

/** 依次识别给定候选；命中足够好时可提前结束。 */
async function recognizeVariants(canvas, variantList, onProgress, options = {}) {
  const tesseract = options.tesseract ?? (await import("tesseract.js"));
  const goodEnough = options.goodEnough ?? 1;
  const variants = [];
  let worker;
  try {
    worker = await tesseract.createWorker("eng", 1, {
      workerPath: "/ocr/worker.min.js",
      corePath: "/ocr/core",
      langPath: "/ocr/lang",
      logger: (m) => {
        if (m.status === "recognizing text") onProgress({ stage: "recognize", progress: m.progress ?? 0 });
      },
    });
    await worker.setParameters({ tessedit_pageseg_mode: "7" });
    for (let i = 0; i < variantList.length; i++) {
      const variant = variantList[i];
      onProgress({ stage: "variant", progress: i / variantList.length });
      const rows = await recognizeRows(worker, variant, onProgress, i, variantList.length);
      const quality = variantQuality(rows);
      variants.push({ id: variant.id, label: variant.label, angle: variant.angle, rows, quality });
      if (quality >= 55) break;
    }
  } finally {
    if (worker) await worker.terminate();
  }
  return { variants };
}

/** 单个变体：二值化 → 行带切分 → 逐行 PSM 7 识别。 */
async function recognizeRows(worker, variant, onProgress, variantIndex, variantCount) {
  const { gray, width, height } = toGray(variant.canvas);
  const stretched = stretch(gray);
  const binary =
    variant.binarize === "adaptive" ? adaptiveThreshold(stretched, width, height) : otsu(stretched).binary;
  const bands = segmentBands(binary, width, height);
  const rows = [];
  for (let b = 0; b < bands.length; b++) {
    const band = bands[b];
    onProgress({
      stage: "recognize",
      progress: (variantIndex + b / Math.max(1, bands.length)) / variantCount,
    });
    const slice = cropBand(variant.canvas, band);
    const { data } = await worker.recognize(slice, {}, { blocks: true, text: true });
    const words = flattenWords(data);
    rows.push({
      band,
      words,
      lines: flattenLines(data),
      text: normalize(data.text),
      confidence: lineConfidence(words),
    });
  }
  return rows;
}

/** 取出 tesseract 的 text line 级信息（含 baseline），用于残余倾斜估计。 */
function flattenLines(data) {
  const lines = [];
  for (const block of data.blocks || [])
    for (const paragraph of block.paragraphs || [])
      for (const line of paragraph.lines || []) {
        const bbox = line.bbox;
        if (!bbox) continue;
        lines.push({
          x: (bbox.x0 + bbox.x1) / 2,
          y: (bbox.y0 + bbox.y1) / 2,
          baseline: line.baseline ?? null,
        });
      }
  return lines;
}

/** 变体质量：行数越多、平均置信度越高越好。 */
function variantQuality(rows) {
  if (!rows.length) return 0;
  const mean = rows.reduce((acc, r) => acc + r.confidence, 0) / rows.length;
  const coverage = Math.min(1, rows.length / 8);
  return mean * (0.6 + 0.4 * coverage);
}

function cropBand(canvas, band) {
  const height = band.bottom - band.top + 1;
  const out = document.createElement("canvas");
  out.width = canvas.width;
  out.height = height;
  const ctx = out.getContext("2d", { willReadFrequently: true });
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, out.width, out.height);
  ctx.drawImage(canvas, 0, band.top, canvas.width, height, 0, 0, out.width, out.height);
  return out;
}

/* ------------------------------------------------------------ 结构化 */

/** 兼容两种词结构：{bbox:{x0,x1,y0,y1}}（tesseract）与 {x0,x1,y0,y1}（扁平）。 */
function box(word) {
  const source = word?.bbox ?? word ?? {};
  return {
    x0: Number(source.x0 ?? 0),
    x1: Number(source.x1 ?? 0),
    y0: Number(source.y0 ?? 0),
    y1: Number(source.y1 ?? 0),
  };
}

/** 把一行词拼成更接近表格的 token 序列（按 x 排序，一行的词可能被 OCR 拆开或粘连）。 */
export function rowTokens(words) {
  const sorted = [...words].sort((a, b) => box(a).x0 - box(b).x0);
  const tokens = [];
  for (const word of sorted) {
    const text = normalize(word.text);
    if (!text) continue;
    const bounds = box(word);
    const pieces = tokenize(text);
    pieces.forEach((piece, index) => {
      // 粘连 token 按等分估算各段的 x 区间，保证列锚点比对仍有几何依据
      const width = Math.max(1, bounds.x1 - bounds.x0);
      const step = width / Math.max(1, pieces.length);
      tokens.push({
        text: piece,
        number: asNumber(piece),
        numeric: asScore(piece),
        confidence: word.confidence ?? 0,
        x0: bounds.x0 + step * index,
        x1: bounds.x0 + step * (index + 1),
        y0: bounds.y0,
        y1: bounds.y1,
      });
    });
  }
  return tokens;
}

/** 常见混淆替换，用于学号候选归一。 */
export function fixConfusion(text) {
  let out = "";
  for (const ch of String(text || "")) out += CONFUSION[ch] ?? ch;
  return out.replace(/[^0-9A-Za-z]/g, "");
}

/**
 * 编辑距离（完整计算，短串场景下开销可忽略）。
 *
 * 注意：**不要**做"某一行最小值已超过 limit 就提前返回"的剪枝——
 * 第一行（i=1）的最小值天然是 0 或 1，按那个剪枝会把 20231539 与 20241530
 * 这种"首字符就不同、但整体只差 1"的对照直接算成超过上限，
 * 从而漏掉本该被识别为歧义的候选。
 */
export function editDistance(a, b, limit = 3) {
  if (a === b) return 0;
  const m = a.length;
  const n = b.length;
  if (Math.abs(m - n) > limit) return limit + 1;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  for (let i = 1; i <= m; i++) {
    const current = [i];
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current.push(Math.min(prev[j] + 1, current[j - 1] + 1, prev[j - 1] + cost));
    }
    prev = current;
  }
  const distance = prev[n];
  return distance > limit ? limit + 1 : distance;
}

/** 归一：只去掉分隔符，不做混淆替换。 */
function compact(text) {
  return String(text || "").replace(/[^0-9A-Za-z]/g, "");
}

/**
 * 统计 cleaned → candidate 中"能被已知混淆解释"的差异个数。
 * 数量越多说明这次纠错越有依据（例如 `2O23153O` → `20231530` 有 2 处 O→0）。
 */
function confusionHits(cleaned, candidate) {
  if (cleaned.length !== candidate.length) return 0;
  let hits = 0;
  for (let i = 0; i < cleaned.length; i++) {
    if (cleaned[i] === candidate[i]) continue;
    if (CONFUSION[cleaned[i]] === candidate[i]) hits++;
  }
  return hits;
}

/**
 * 学号纠错：在名册里找最合适的候选。
 *
 * 判定顺序（这个顺序很关键，写反会把"同前缀的另一个学号"认成本人）：
 *   1. **归一化后的精确匹配**（原样命中，或混淆纠正后命中），直接返回；
 *   2. 编辑距离 ≤ maxDistance 的候选，距离相同优先"混淆能解释的差异更多"的那个；
 *   3. 都不满足则返回 null，界面标成"未匹配"交人工核对。
 *
 * 1 必须在 2 之前：`20231530` 与 `20241530` 的编辑距离是 1，
 * 若先做模糊匹配，笔画不清的 `20231530` 会被就近认成另一个学生——
 * 这比"标成未匹配让人工核对"危险得多。
 *
 * @param {string} raw OCR 出来的学号候选
 * @param {string[]} candidates 名册里的学号
 * @param {{maxDistance?:number}} [options]
 * @returns {{username:string|null, distance:number, corrected:boolean, fixed:string}|null}
 */
export function correctId(raw, candidates, options = {}) {
  const maxDistance = options.maxDistance ?? 1;
  const cleaned = compact(raw);
  if (!cleaned) return null;
  const fixed = fixConfusion(cleaned);
  const list = (candidates || []).filter(Boolean);

  // 1) 精确匹配（原样命中，或混淆纠正后命中）
  const exact = list.find((candidate) => candidate === cleaned);
  if (exact) return { username: exact, distance: 0, corrected: false, fixed };
  const exactFixed = list.find((candidate) => candidate === fixed);
  if (exactFixed)
    return {
      username: exactFixed,
      distance: 0,
      corrected: cleaned !== fixed,
      fixed,
      confusionHits: confusionHits(cleaned, exactFixed),
    };

  // 2) 模糊匹配：距离优先，其次"混淆能解释的差异更多"
  const matched = [];
  for (const candidate of list) {
    const distance = editDistance(cleaned, candidate, maxDistance);
    if (distance > maxDistance) continue;
    matched.push({ candidate, distance, hits: confusionHits(cleaned, candidate) });
  }
  if (!matched.length) return null;
  matched.sort((a, b) => a.distance - b.distance || b.hits - a.hits);
  const best = matched[0];

  // 安全规则：默认**只接受"能被已知 OCR 混淆解释"的差异**（O→0、l→1、B→8、S→5…）。
  // 其它一位之差的数字（9→0、5→4…）在成绩单上无法区分"OCR 看错"与"看错学号"，
  // 默认不自动填人，交给教师在预览界面里人工确认；
  // 若名册里确实存在"仅一位数字不同"的同前缀学号（真实教务系统的学号常常如此），
  // 可传 allowDigitCorrection: true 打开——此时仍会标注"请核对"，但不再拦死。
  if (best.distance > 0 && best.hits === 0) {
    const digitOnly =
      cleaned.length === best.candidate.length &&
      [...cleaned].filter((ch, i) => ch !== best.candidate[i]).every((ch) => /\d/.test(ch));
    if (!options.allowDigitCorrection || !digitOnly)
      return {
        username: null,
        distance: best.distance,
        corrected: false,
        fixed,
        confusionHits: 0,
        unverified: true,
        candidate: best.candidate,
      };
  }

  const tied = matched.filter((entry) => entry.distance === best.distance && entry.hits === best.hits);
  if (tied.length > 1)
    return {
      username: best.candidate,
      distance: best.distance,
      corrected: true,
      fixed,
      confusionHits: best.hits,
      ambiguous: true,
      alternatives: tied.map((entry) => entry.candidate),
    };
  return {
    username: best.candidate,
    distance: best.distance,
    corrected: true,
    fixed,
    confusionHits: best.hits,
  };
}

/** 从一行 token 里挑学号候选：长度接近的 token，以及长度接近的**纯数字** token。 */
export function idCandidates(tokens, idLength = 8) {
  const out = [];
  const lengthOk = (text) => text.length >= idLength - 1 && text.length <= idLength + 1;
  const push = (value) => {
    if (value && lengthOk(value) && !out.includes(value)) out.push(value);
  };
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    const compact = token.text.replace(/[^0-9A-Za-z]/g, "");
    push(compact);
    // 相邻两个 token 拼起来正好是学号长度（OCR 可能把学号切开）
    const next = tokens[i + 1];
    if (next) push(compact + next.text.replace(/[^0-9A-Za-z]/g, ""));
  }
  // 纯数字 token 仍要单独试一遍：识别成数字说明它"整体像学号"，
  // 即便长度判定略超，也值得拿去做混淆纠正（例如被读成 9 位）。
  for (const token of tokens) {
    const compact = token.text.replace(/[^0-9A-Za-z]/g, "");
    if (/^\d+$/.test(compact) && compact.length >= idLength && compact.length <= idLength + 2)
      push(compact);
  }
  return out;
}

/**
 * 依据列锚点把一行 token 分配到各列。
 *
 * 规则：为每个列锚点挑选位置最接近且未被占用的数值 token；超出容差的列留空。
 * 这样"缺值"不会被后面的值前移填补，避免串列。
 *
 * 三处刻意的保守设计（宁可留空并标"缺少 N 项分数"，也不要静默错位）：
 *   1. 锚点比较用 token 的**右边缘**：成绩单上的分数是右对齐的，右边缘对个位数/两位数都稳定；
 *   2. 容差取 0.35×列间距（下限 36px），太大时相邻列的残片会被吸过来；
 *   3. 单位数 token 用更小的容差——OCR 把一个两位数拆开、只剩个位落在前一列附近时，
 *      它会被当成"前一列的低分"写进成绩，属于最危险的静默错误。
 */
export function assignColumns(tokens, anchors) {
  const cells = anchors.map(() => null);
  if (!anchors.length) return cells;
  const values = tokens.filter((t) => t.numeric !== null);
  if (!values.length) return cells;
  const gap = anchors.length > 1 ? medianGap(anchors) : 240;
  const tolerance = Math.max(36, gap * 0.35);
  const singleDigitTolerance = Math.max(18, gap * 0.12);
  const used = new Set();
  anchors.forEach((anchor, columnIndex) => {
    let bestIndex = -1;
    let bestDelta = Infinity;
    values.forEach((token, index) => {
      if (used.has(index)) return;
      const delta = Math.abs(rightEdge(token) - anchor);
      if (delta < bestDelta) {
        bestDelta = delta;
        bestIndex = index;
      }
    });
    if (bestIndex < 0) return;
    const token = values[bestIndex];
    const limit = singleDigit(token) ? singleDigitTolerance : tolerance;
    if (bestDelta <= limit) {
      used.add(bestIndex);
      cells[columnIndex] = token;
    }
  });
  return cells;
}

/** token 的右边缘（用于右对齐的分数列比对）。 */
function rightEdge(token) {
  return Number.isFinite(token?.x1) ? token.x1 : (token?.x0 ?? 0) + 20;
}

/** 是否单位数 token（可能是被切开的两位数残片）。 */
function singleDigit(token) {
  return String(token?.text ?? "").replace(/[^\d]/g, "").length === 1;
}

function medianGap(anchors) {
  const gaps = [];
  for (let i = 1; i < anchors.length; i++) gaps.push(anchors[i] - anchors[i - 1]);
  if (!gaps.length) return 240;
  gaps.sort((a, b) => a - b);
  return gaps[Math.floor(gaps.length / 2)];
}

/**
 * 从已识别的行里推导列锚点：取 token 数最多的若干行，按列位置取数值 token **右边缘**的中位数。
 *
 * 用右边缘而不是左边缘，是因为成绩单的分数列通常右对齐：
 * 「85」与「100」的左边缘能差一个字宽，右边缘却基本重合。
 *
 * @param {Array<{words:Array}>|Array<Array>} rows 行（含 words）或已经切好的 token 数组
 * @param {number} columnCount 需要推导的列数
 * @returns {number[]|null}
 */
export function inferAnchors(rows, columnCount) {
  const samples = rows
    .map((row) =>
      Array.isArray(row)
        ? row.filter((t) => t.numeric !== null && t.numeric !== undefined)
        : rowTokens(row?.words ?? []).filter((t) => t.numeric !== null),
    )
    .filter((values) => values.length >= Math.min(columnCount, 2))
    .sort((a, b) => b.length - a.length)
    .slice(0, 12);
  if (!samples.length) return null;
  const columns = [];
  for (let index = 0; index < columnCount; index++) {
    const values = samples
      .map((sample) => sample[index])
      .filter(Boolean)
      .map((token) => rightEdge(token))
      .sort((a, b) => a - b);
    if (values.length >= Math.max(1, Math.floor(samples.length * 0.5)))
      columns.push(values[Math.floor(values.length / 2)]);
  }
  if (columns.length < columnCount) {
    // 样本不足：用已推得的列间距外推
    const gap = columns.length > 1 ? medianGap(columns) : 240;
    while (columns.length < columnCount)
      columns.push((columns[columns.length - 1] ?? 0) + gap);
  }
  return columns.slice(-columnCount);
}

/**
 * 把识别结果整理成结构化行（便捷入口：自动检测列并按顺序映射到成绩项）。
 *
 * 需要教师确认列映射时，请改用 {@link detectColumns} + {@link mapColumns}。
 *
 * @param {object} input
 * @param {Array} input.rows 识别出的行（含 words/text/confidence）
 * @param {Array<{key:string,label:string}>} input.components 参与录入的成绩项（顺序即列顺序）
 * @param {Array<{id:string,username:string,name:string}>} input.roster 名册
 * @param {number} [input.idLength] 学号长度
 * @param {number} [input.minConfidence] 低于该值的单元格标记为需要人工确认
 */
export function structureRows(input) {
  const { rows = [], components = [] } = input;
  const detected = input.detected ?? detectColumns(rows);
  return mapColumns({ ...input, ...detected });
}

/**
 * 端到端便捷入口：识别候选行 → 检测列 → 映射成绩项 → 返回预览行与检测信息。
 * App.vue 与测试脚本都走这一条路径，保证"界面看到的"与"测出来的"是同一段代码。
 *
 * @param {Array} rows 识别出的行带（含 words/text/confidence）
 * @param {Array<{key:string,label:string}>} components 成绩项
 * @param {Array} roster 名册
 * @param {number[]} [columnMap] 列映射；缺省按顺序
 */
export function buildPreview(rows, components, roster, columnMap, options = {}) {
  const detected = detectColumns(rows);
  const mapped = mapColumns({
    rows: detected.rows,
    tokens: detected.tokens,
    components,
    roster,
    columnMap: columnMap ?? defaultColumnMap(detected.columns.length, components.length),
    allowDigitCorrection: options.allowDigitCorrection,
  });
  return { ...detected, previewRows: mapped, summary: summarize({ rows: mapped }, components) };
}

/** 判断一行是否"可填入"：匹配到名册且至少有一个合法分数。 */
export function isRowUsable(row) {
  return Boolean(row?.matched) && (row?.cells ?? []).some((cell) => cell.value !== null);
}

/**
 * 从候选行里抽出"检测到的分值列"。
 *
 * 不假设列数等于成绩项数：真实成绩单可能多一列（总分/补考/排名），
 * 因此先把所有数值列都找出来，再由 {@link mapColumns} 把列映射到成绩项，
 * 映射关系由教师在预览界面上确认。
 *
 * 只保留"看起来是数据行"的行带：表头、页脚、纯文字说明没有分数形态的 token，
 * 交给 {@link mapColumns} 只会得到"未匹配"的噪声行，因此在入口就剔除。
 *
 * @param {Array<{words:Array}>} rows
 * @returns {{columns:number[], tokens:(object[]|null)[][], rows:Array}}
 */
export function detectColumns(rows) {
  const dataRows = (rows ?? []).filter((row) =>
    rowTokens(row?.words ?? []).some((token) => token.numeric !== null),
  );
  const source = dataRows.length ? dataRows : (rows ?? []);
  const anchors = inferAnchors(source, maxNumericColumns(source)) ?? [];
  const columns = anchors.length ? anchors : fallbackAnchors(source);
  const tokens = source.map((row) => {
    const values = rowTokens(row?.words ?? []).filter((t) => t.numeric !== null);
    return columns.length ? assignColumns(values, columns) : [];
  });
  return { columns, tokens, rows: source };
}

function maxNumericColumns(rows) {
  let max = 0;
  for (const row of rows) {
    const count = rowTokens(row?.words ?? []).filter((t) => t.numeric !== null).length;
    if (count > max) max = count;
  }
  return Math.max(1, max);
}

function fallbackAnchors(rows) {
  const sample = rows
    .map((row) => rowTokens(row?.words ?? []).filter((t) => t.numeric !== null))
    .sort((a, b) => b.length - a.length)[0];
  return (sample ?? []).map((token) => rightEdge(token));
}

/**
 * 把检测到的列映射成成绩项，生成预览行。
 *
 * @param {object} input
 * @param {Array} input.rows 识别出的行（含 words/text/confidence）
 * @param {number[]} input.columns 检测到的列锚点
 * @param {Array<Array>} input.tokens 每行每列的 token（由 {@link detectColumns} 给出）
 * @param {Array<{key:string,label:string}>} input.components 成绩项（按最终列顺序）
 * @param {Array<{mapping:(number|null)}>} [input.columnMap] 第 i 个成绩项对应检测列的下标；缺省按顺序
 * @param {Array<{id:string,username:string,name:string}>} input.roster 名册
 * @returns {Array} 预览行
 */
export function mapColumns(input) {
  const { rows = [], tokens = [], components = [], roster = [] } = input;
  const idLength = input.idLength ?? 8;
  const minConfidence = input.minConfidence ?? 85;
  const usernames = roster.map((r) => r.username).filter(Boolean);
  const mapping =
    input.columnMap ??
    components.map((_, index) => (index < (tokens[0]?.length ?? 0) ? index : null));

  return rows.map((row, rowIndex) => {
    const rowTokensList = rowTokens(row?.words ?? []);
    const issues = [];
    // 逐个学号候选尝试匹配：候选中既有精确命中，也有"混淆可解释"的纠正；
    // 无法确认（unverified）或并列（ambiguous）的结果会保留下来用于提示，但不会写入学生。
    let match = null;
    for (const candidate of idCandidates(rowTokensList, idLength)) {
      const corrected = correctId(candidate, usernames, {
        maxDistance: 1,
        allowDigitCorrection: input.allowDigitCorrection === true,
      });
      if (!corrected) continue;
      if (corrected.username) {
        match = corrected;
        break;
      }
      if (!match) match = corrected;
    }
    const student = match && match.username ? roster.find((r) => r.username === match.username) : null;
    if (!match) issues.push("未匹配到名册中的学号");
    else if (match.unverified) issues.push("学号与名册有差异但无法确认，请人工核对");
    else if (match.ambiguous) issues.push("学号有多个相近候选，请人工确认");
    else if (match.corrected) issues.push("学号经自动纠正，请核对");

    const cells = components.map((component, index) => {
      const columnIndex = mapping[index];
      const token =
        columnIndex === null || columnIndex === undefined ? null : (tokens[rowIndex] ?? [])[columnIndex] ?? null;
      const numeric = token ? token.numeric : null;
      const outOfRange = numeric !== null && (numeric < 0 || numeric > 100);
      if (outOfRange) issues.push(`${component.label} 分数超出 0–100`);
      return {
        key: component.key,
        label: component.label,
        column: columnIndex ?? null,
        value: outOfRange ? null : numeric,
        raw: token ? token.text : "",
        confidence: token ? token.confidence : 0,
        needsCheck: Boolean(token) && token.confidence < minConfidence,
      };
    });

    const numericCount = cells.filter((c) => c.value !== null).length;
    if (numericCount === 0) issues.push("未识别到分数");
    else if (numericCount < components.length)
      issues.push(`缺少 ${components.length - numericCount} 项分数`);

    return {
      type: "row",
      text: row.text ?? "",
      confidence: Math.round((row.confidence ?? 0) * 10) / 10,
      username: match ? match.username : "",
      name: student ? student.name : "",
      studentId: student ? student.id : "",
      matched: Boolean(student),
      corrected: Boolean(match?.corrected),
      cells,
      issues: [...new Set(issues)],
    };
  });
}

/**
 * 依据列数给出默认映射：列数与成绩项数一致时按顺序一一对应；
 * 多出来的列（总分、排名等）排在最后，默认不映射。
 */
export function defaultColumnMap(columnCount, componentCount) {
  return Array.from({ length: componentCount }, (_, index) => (index < columnCount ? index : null));
}

/** 汇总统计，供界面顶部展示。 */
export function summarize(structure, components) {
  const rows = structure?.rows ?? [];
  const usable = rows.filter(isRowUsable);
  let cells = 0;
  let filled = 0;
  let suspicious = 0;
  for (const row of rows)
    for (const cell of row.cells) {
      cells++;
      if (cell.value !== null) filled++;
      if (cell.needsCheck) suspicious++;
    }
  return {
    rows: rows.length,
    usable: usable.length,
    skipped: rows.length - usable.length,
    cells,
    filled,
    suspicious,
    columns: components.length,
    coverage: rows.length ? cells / rows.length / Math.max(1, components.length) : 0,
  };
}
