/**
 * QR 码编码器（字节模式，纠错级别 M，版本 1-14）。
 *
 * <p>项目为此砍掉了 ZXing（一个只为生成几个二维码就要背上的 Java 依赖），
 * 改在前端画。这里只实现真正用到的那条路径：
 *   · 字节模式（URL 和 otpauth 链接都是 ASCII/UTF-8）
 *   · 纠错级别 M（15% 冗余，手机扫码的通行选择）
 *   · 版本 1-14（最长 362 字节，够放局域网地址和 otpauth URI）
 *
 * 不实现数字/字母数字模式和更高版本：那些只会让码更小一点点，
 * 而每多一条分支就多一处可能出错的地方，且这里没有单元测试之外的验证手段。
 *
 * 实现依据 ISO/IEC 18004，已用独立解码器 jsQR 做过往返验证
 * （含 otpauth URI 和中文内容，版本 1-13 全部可正确解出）。
 *
 * 注意：不要拿 Python 的 segno 库做逐位对拍。它的 write_padding_bits 写的是
 * `8 - (length % 8)`，比特流已经字节对齐时会凭空多补一整个字节，
 * 因此它的矩阵和按标准实现的结果必然不同（两者都能扫，只是填充区不一样）。
 */

/** 纠错级别 M 下，各版本的 (每块纠错码字数, 组1块数, 组1数据码字, 组2块数, 组2数据码字) */
const EC_TABLE: Record<number, [number, number, number, number, number]> = {
  1: [10, 1, 16, 0, 0],
  2: [16, 1, 28, 0, 0],
  3: [26, 1, 44, 0, 0],
  4: [18, 2, 32, 0, 0],
  5: [24, 2, 43, 0, 0],
  6: [16, 4, 27, 0, 0],
  7: [18, 4, 31, 0, 0],
  8: [22, 2, 38, 2, 39],
  9: [22, 3, 36, 2, 37],
  10: [26, 4, 43, 1, 44],
  11: [30, 1, 50, 4, 51],
  12: [22, 6, 36, 2, 37],
  13: [22, 8, 37, 1, 38],
  14: [24, 4, 40, 5, 41],
}

/** 各版本的对齐图案中心坐标 */
const ALIGN_POS: Record<number, number[]> = {
  1: [],
  2: [6, 18],
  3: [6, 22],
  4: [6, 26],
  5: [6, 30],
  6: [6, 34],
  7: [6, 22, 38],
  8: [6, 24, 42],
  9: [6, 26, 46],
  10: [6, 28, 50],
  11: [6, 30, 54],
  12: [6, 32, 58],
  13: [6, 34, 62],
  14: [6, 26, 46, 66],
}

// ── GF(256) ──────────────────────────────────────────────────────────────
// 本原多项式 0x11D，生成元 α=2。Reed-Solomon 纠错的算术都在这个域里做。

const EXP = new Uint8Array(512)
const LOG = new Uint8Array(256)
;(() => {
  let x = 1
  for (let i = 0; i < 255; i++) {
    EXP[i] = x
    LOG[x] = i
    x <<= 1
    if (x & 0x100) x ^= 0x11d
  }
  // 后半段是前半段的复制，让乘法里的下标相加不用取模
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255]
})()

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0
  return EXP[LOG[a] + LOG[b]]
}

/** 生成多项式 (x-α⁰)(x-α¹)…(x-α^(n-1)) 的系数 */
function rsGenerator(degree: number): Uint8Array {
  let poly = new Uint8Array([1])
  for (let i = 0; i < degree; i++) {
    const next = new Uint8Array(poly.length + 1)
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j]
      next[j + 1] ^= gfMul(poly[j], EXP[i])
    }
    poly = next
  }
  return poly
}

/** 多项式除法取余，余数就是纠错码字 */
function rsEncode(data: Uint8Array, ecLength: number): Uint8Array {
  const generator = rsGenerator(ecLength)
  const result = new Uint8Array(data.length + ecLength)
  result.set(data)
  for (let i = 0; i < data.length; i++) {
    const factor = result[i]
    if (factor === 0) continue
    for (let j = 0; j < generator.length; j++) {
      result[i + j] ^= gfMul(generator[j], factor)
    }
  }
  return result.slice(data.length)
}

// ── BCH ──────────────────────────────────────────────────────────────────
// 格式信息和版本信息的校验位用代码算而不是查表：
// 手抄那两张表极易出错，而出错的表现是"某些掩码下扫不出来"，非常难查。

function bch(value: number, generator: number, generatorBits: number): number {
  let result = value << (generatorBits - 1)
  const total = bitLength(generator)
  while (bitLength(result) >= total) {
    result ^= generator << (bitLength(result) - total)
  }
  return result
}

function bitLength(value: number): number {
  let n = 0
  while (value !== 0) {
    n++
    value >>>= 1
  }
  return n
}

/** 格式信息 15 bit：5 位数据（2 位纠错级别 + 3 位掩码）+ 10 位 BCH，再异或掩码常量 */
function formatBits(mask: number): number {
  const ecBits = 0b00 // 级别 M
  const data = (ecBits << 3) | mask
  return ((data << 10) | bch(data, 0b10100110111, 11)) ^ 0b101010000010010
}

/** 版本信息 18 bit，仅版本 ≥ 7 需要 */
function versionBits(version: number): number {
  return (version << 12) | bch(version, 0b1111100100101, 13)
}

// ── 编码 ─────────────────────────────────────────────────────────────────

function chooseVersion(byteLength: number): number {
  for (let version = 1; version <= 14; version++) {
    const [ecPerBlock, g1, g1Data, g2, g2Data] = EC_TABLE[version]
    void ecPerBlock
    const capacity = g1 * g1Data + g2 * g2Data
    // 4 位模式指示符 + 长度字段（版本 ≥10 时是 16 位，否则 8 位）
    const headerBits = 4 + (version >= 10 ? 16 : 8)
    if (Math.floor((capacity * 8 - headerBits) / 8) >= byteLength) {
      return version
    }
  }
  throw new Error('内容过长，无法编码为二维码')
}

function buildCodewords(data: Uint8Array, version: number): Uint8Array {
  const [ecPerBlock, g1, g1Data, g2, g2Data] = EC_TABLE[version]
  const totalData = g1 * g1Data + g2 * g2Data

  // 比特流：模式(0100) + 长度 + 数据 + 终止符
  const bits: number[] = []
  const push = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1)
  }
  push(0b0100, 4)
  push(data.length, version >= 10 ? 16 : 8)
  for (const byte of data) push(byte, 8)

  const capacityBits = totalData * 8
  push(0, Math.min(4, capacityBits - bits.length))
  while (bits.length % 8 !== 0) bits.push(0)

  const codewords = new Uint8Array(totalData)
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0
    for (let j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j]
    codewords[i / 8] = byte
  }
  // 填充字节在标准里就是这两个值交替
  const PAD = [0xec, 0x11]
  for (let i = bits.length / 8, k = 0; i < totalData; i++, k++) {
    codewords[i] = PAD[k % 2]
  }

  // 分块并各自算纠错码字
  const dataBlocks: Uint8Array[] = []
  const ecBlocks: Uint8Array[] = []
  let offset = 0
  for (let i = 0; i < g1 + g2; i++) {
    const size = i < g1 ? g1Data : g2Data
    const block = codewords.slice(offset, offset + size)
    offset += size
    dataBlocks.push(block)
    ecBlocks.push(rsEncode(block, ecPerBlock))
  }

  // 交错：先按列取遍所有数据块，再按列取遍所有纠错块。
  // 这样做的意义是让一片连续的污损分散到多个块上，每块都还在纠错能力之内。
  const result: number[] = []
  const maxData = Math.max(g1Data, g2Data)
  for (let i = 0; i < maxData; i++) {
    for (const block of dataBlocks) {
      if (i < block.length) result.push(block[i])
    }
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (const block of ecBlocks) result.push(block[i])
  }
  return new Uint8Array(result)
}

// ── 矩阵 ─────────────────────────────────────────────────────────────────

type Grid = { size: number; modules: Int8Array; reserved: Uint8Array }

function makeGrid(version: number): Grid {
  const size = version * 4 + 17
  return { size, modules: new Int8Array(size * size), reserved: new Uint8Array(size * size) }
}

const at = (g: Grid, x: number, y: number) => y * g.size + x

function setFunction(g: Grid, x: number, y: number, dark: boolean) {
  g.modules[at(g, x, y)] = dark ? 1 : 0
  g.reserved[at(g, x, y)] = 1
}

function placeFinder(g: Grid, cx: number, cy: number) {
  for (let dy = -1; dy <= 7; dy++) {
    for (let dx = -1; dx <= 7; dx++) {
      const x = cx + dx
      const y = cy + dy
      if (x < 0 || y < 0 || x >= g.size || y >= g.size) continue
      const inner = Math.max(Math.abs(dx - 3), Math.abs(dy - 3))
      setFunction(g, x, y, inner !== 2 && inner <= 3)
    }
  }
}

function placePatterns(g: Grid, version: number) {
  placeFinder(g, 0, 0)
  placeFinder(g, g.size - 7, 0)
  placeFinder(g, 0, g.size - 7)

  // 定时图案
  for (let i = 8; i < g.size - 8; i++) {
    setFunction(g, i, 6, i % 2 === 0)
    setFunction(g, 6, i, i % 2 === 0)
  }

  // 对齐图案，跳过与定位图案重叠的三个角
  const positions = ALIGN_POS[version]
  for (const cy of positions) {
    for (const cx of positions) {
      const nearFinder =
        (cx === 6 && cy === 6) ||
        (cx === 6 && cy === g.size - 7) ||
        (cx === g.size - 7 && cy === 6)
      if (nearFinder) continue
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          setFunction(g, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
        }
      }
    }
  }

  // 预留格式信息区（内容稍后写）
  for (let i = 0; i < 9; i++) {
    if (!g.reserved[at(g, i, 8)]) setFunction(g, i, 8, false)
    if (!g.reserved[at(g, 8, i)]) setFunction(g, 8, i, false)
  }
  for (let i = 0; i < 8; i++) {
    setFunction(g, g.size - 1 - i, 8, false)
    setFunction(g, 8, g.size - 1 - i, false)
  }

  // 固定的深色模块。必须放在上面那个预留循环**之后**：
  // 循环里 i=7 那一次正好落在 (8, size-8)，先设会被它清成浅色。
  setFunction(g, 8, g.size - 8, true)

  if (version >= 7) {
    const bits = versionBits(version)
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1
      const a = Math.floor(i / 3)
      const b = (i % 3) + g.size - 11
      setFunction(g, a, b, dark)
      setFunction(g, b, a, dark)
    }
  }
}

/** 按标准的蛇形顺序把数据位填进非功能模块 */
function placeData(g: Grid, codewords: Uint8Array) {
  let bitIndex = 0
  let upward = true
  for (let right = g.size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5 // 第 6 列是定时图案，整列跳过
    for (let step = 0; step < g.size; step++) {
      const y = upward ? g.size - 1 - step : step
      for (let c = 0; c < 2; c++) {
        const x = right - c
        if (g.reserved[at(g, x, y)]) continue
        let dark = false
        if (bitIndex < codewords.length * 8) {
          dark = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) === 1
        }
        g.modules[at(g, x, y)] = dark ? 1 : 0
        bitIndex++
      }
    }
    upward = !upward
  }
}

const MASKS: ((x: number, y: number) => boolean)[] = [
  (x, y) => (x + y) % 2 === 0,
  (_x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
]

function applyMaskAndFormat(g: Grid, mask: number): Grid {
  const out: Grid = {
    size: g.size,
    modules: Int8Array.from(g.modules),
    reserved: g.reserved,
  }
  for (let y = 0; y < g.size; y++) {
    for (let x = 0; x < g.size; x++) {
      if (g.reserved[at(g, x, y)]) continue
      if (MASKS[mask](x, y)) out.modules[at(g, x, y)] ^= 1
    }
  }

  const bits = formatBits(mask)
  for (let i = 0; i < 15; i++) {
    const dark = ((bits >>> i) & 1) === 1 ? 1 : 0
    // 左上角那份
    if (i < 6) out.modules[at(g, 8, i)] = dark
    else if (i < 8) out.modules[at(g, 8, i + 1)] = dark
    else if (i === 8) out.modules[at(g, 7, 8)] = dark
    else out.modules[at(g, 14 - i, 8)] = dark
    // 分散在右上和左下的第二份
    if (i < 8) out.modules[at(g, g.size - 1 - i, 8)] = dark
    else out.modules[at(g, 8, g.size - 15 + i)] = dark
  }
  return out
}

/** 掩码惩罚分，取最低分的那个掩码——目的是避免出现干扰识别的图案 */
function penalty(g: Grid): number {
  const size = g.size
  const get = (x: number, y: number) => g.modules[y * size + x] === 1
  let score = 0

  // 规则 1：同色连续 5 个以上
  for (let i = 0; i < size; i++) {
    for (const horizontal of [true, false]) {
      let run = 1
      for (let j = 1; j < size; j++) {
        const cur = horizontal ? get(j, i) : get(i, j)
        const prev = horizontal ? get(j - 1, i) : get(i, j - 1)
        if (cur === prev) {
          run++
        } else {
          if (run >= 5) score += run - 2
          run = 1
        }
      }
      if (run >= 5) score += run - 2
    }
  }

  // 规则 2：2x2 同色块
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const v = get(x, y)
      if (v === get(x + 1, y) && v === get(x, y + 1) && v === get(x + 1, y + 1)) score += 3
    }
  }

  // 规则 3：形似定位图案的 1:1:3:1:1 序列
  const PATTERN = [true, false, true, true, true, false, true]
  const hasPattern = (cells: boolean[], start: number) =>
    PATTERN.every((v, k) => cells[start + k] === v)
  for (let i = 0; i < size; i++) {
    const row: boolean[] = []
    const col: boolean[] = []
    for (let j = 0; j < size; j++) {
      row.push(get(j, i))
      col.push(get(i, j))
    }
    for (const line of [row, col]) {
      for (let j = 0; j + 7 <= size; j++) {
        if (!hasPattern(line, j)) continue
        const before = line.slice(Math.max(0, j - 4), j)
        const after = line.slice(j + 7, j + 11)
        if (before.length === 0 || before.every((v) => !v)) score += 40
        else if (after.length === 0 || after.every((v) => !v)) score += 40
      }
    }
  }

  // 规则 4：深色比例偏离 50%
  let dark = 0
  for (let i = 0; i < size * size; i++) if (g.modules[i] === 1) dark++
  const ratio = (dark * 100) / (size * size)
  score += Math.floor(Math.abs(ratio - 50) / 5) * 10
  return score
}

/**
 * 生成二维码矩阵。
 *
 * @returns 二维布尔数组，true 表示深色模块
 */
export function qrMatrix(text: string): boolean[][] {
  const data = new TextEncoder().encode(text)
  const version = chooseVersion(data.length)
  const codewords = buildCodewords(data, version)

  const base = makeGrid(version)
  placePatterns(base, version)
  placeData(base, codewords)

  let best: Grid | null = null
  let bestScore = Infinity
  for (let mask = 0; mask < 8; mask++) {
    const candidate = applyMaskAndFormat(base, mask)
    const score = penalty(candidate)
    if (score < bestScore) {
      bestScore = score
      best = candidate
    }
  }

  const grid = best!
  const rows: boolean[][] = []
  for (let y = 0; y < grid.size; y++) {
    const row: boolean[] = []
    for (let x = 0; x < grid.size; x++) row.push(grid.modules[at(grid, x, y)] === 1)
    rows.push(row)
  }
  return rows
}
