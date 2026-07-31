import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { uploadApi } from '@/api/client'

export interface UploadTask {
  id: string
  name: string
  size: number
  /** 0-100 */
  progress: number
  state: 'hashing' | 'uploading' | 'done' | 'instant' | 'failed'
  error?: string
}

/**
 * 分片上传。
 *
 * <p>整个文件的 sha256 在浏览器里算：服务端靠它做秒传和完整性校验。
 * 用 WebCrypto 的 `crypto.subtle.digest` 而不是引 js-sha256 之类的库——
 * 浏览器原生实现是 C 写的，比 JS 实现快一个数量级，几百 MB 的视频差别很明显。
 *
 * <p><b>注意</b>：`crypto.subtle` 只在安全上下文可用（HTTPS 或 localhost）。
 * 局域网走 http://192.168.x.x 时它是 undefined，所以下面有一个纯 JS 的兜底实现。
 * 这也是 M5 要上自签 TLS 的实际动机之一。
 */
export const useUploadStore = defineStore('upload', () => {
  const tasks = ref<UploadTask[]>([])
  const dragging = ref(false)

  const active = computed(() => tasks.value.filter((t) => t.state === 'hashing' || t.state === 'uploading'))
  const uploading = computed(() => active.value.length > 0)
  const overallProgress = computed(() => {
    if (active.value.length === 0) return 0
    const total = active.value.reduce((sum, t) => sum + t.progress, 0)
    return Math.round(total / active.value.length)
  })

  function update(id: string, patch: Partial<UploadTask>): void {
    tasks.value = tasks.value.map((t) => (t.id === id ? { ...t, ...patch } : t))
  }

  /** 已完成的任务留 5 秒让用户看到结果，之后自动消失 */
  function scheduleCleanup(id: string): void {
    window.setTimeout(() => {
      tasks.value = tasks.value.filter((t) => t.id !== id)
    }, 5000)
  }

  async function uploadFiles(files: File[], onDone?: () => void): Promise<void> {
    const accepted = files.filter((f) => f.size > 0)
    for (const file of accepted) {
      await uploadOne(file)
    }
    onDone?.()
  }

  async function uploadOne(file: File): Promise<void> {
    const id = `${file.name}-${file.size}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    tasks.value = [...tasks.value, {
      id,
      name: file.name,
      size: file.size,
      progress: 0,
      state: 'hashing',
    }]

    try {
      const sha256 = await hashFile(file, (p) => update(id, { progress: Math.round(p * 20) }))

      const init = await uploadApi.init(sha256, file.name, file.size)
      if (init.instant) {
        update(id, { state: 'instant', progress: 100 })
        scheduleCleanup(id)
        return
      }

      const uploadId = init.uploadId!
      const chunkSize = init.chunkSize
      const total = init.chunkTotal
      // 断点续传：服务端已收到的分片直接跳过
      const done = new Set(init.receivedChunks)

      update(id, { state: 'uploading', progress: Math.round((done.size / total) * 80) + 20 })

      for (let index = 0; index < total; index++) {
        if (done.has(index)) continue
        const start = index * chunkSize
        const blob = file.slice(start, Math.min(start + chunkSize, file.size))
        await uploadApi.chunk(uploadId, index, blob)
        done.add(index)
        update(id, { progress: Math.round((done.size / total) * 80) + 20 })
      }

      await uploadApi.complete(uploadId)
      update(id, { state: 'done', progress: 100 })
      scheduleCleanup(id)
    } catch (e) {
      update(id, {
        state: 'failed',
        error: e instanceof Error ? e.message : '上传失败',
      })
    }
  }

  function dismiss(id: string): void {
    tasks.value = tasks.value.filter((t) => t.id !== id)
  }

  return { tasks, dragging, uploading, overallProgress, uploadFiles, dismiss }
})

// ── sha256 ────────────────────────────────────────────────────────────────

const HASH_CHUNK = 8 * 1024 * 1024

async function hashFile(file: File, onProgress: (ratio: number) => void): Promise<string> {
  if (globalThis.crypto?.subtle) {
    // 一次性读整个文件到内存对大视频不可接受，但 subtle.digest 不支持流式，
    // 所以分块读、拼成一个 ArrayBuffer 再算。手机上传 2GB 视频时这里会吃内存，
    // 到了那个量级要换成下面的纯 JS 增量实现。
    if (file.size <= 256 * 1024 * 1024) {
      const buffer = await file.arrayBuffer()
      onProgress(1)
      const digest = await globalThis.crypto.subtle.digest('SHA-256', buffer)
      return toHex(new Uint8Array(digest))
    }
  }
  return incrementalSha256(file, onProgress)
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * 纯 JS 的增量 SHA-256。
 *
 * <p>两种情况需要它：
 * 1. 非安全上下文（局域网 http://）下 `crypto.subtle` 不存在
 * 2. 文件太大，一次性读进内存会把手机浏览器打崩
 */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

class Sha256 {
  private h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  private buffer = new Uint8Array(64)
  private bufferLength = 0
  private byteCount = 0
  private w = new Uint32Array(64)

  update(data: Uint8Array): void {
    this.byteCount += data.length
    let offset = 0
    if (this.bufferLength > 0) {
      const need = Math.min(64 - this.bufferLength, data.length)
      this.buffer.set(data.subarray(0, need), this.bufferLength)
      this.bufferLength += need
      offset = need
      if (this.bufferLength === 64) {
        this.block(this.buffer, 0)
        this.bufferLength = 0
      }
    }
    while (offset + 64 <= data.length) {
      this.block(data, offset)
      offset += 64
    }
    if (offset < data.length) {
      this.buffer.set(data.subarray(offset), 0)
      this.bufferLength = data.length - offset
    }
  }

  digest(): string {
    const bitLength = this.byteCount * 8
    const padded = new Uint8Array(this.bufferLength + 72)
    padded.set(this.buffer.subarray(0, this.bufferLength))
    padded[this.bufferLength] = 0x80
    // 补零到长度 ≡ 56 (mod 64)，最后 8 字节放位长度
    let totalLength = this.bufferLength + 1
    while (totalLength % 64 !== 56) totalLength++
    const view = new DataView(padded.buffer)
    // 位长度用 64 位大端；JS 的 number 精度到 2^53，媒体文件远小于这个量级
    view.setUint32(totalLength, Math.floor(bitLength / 0x100000000), false)
    view.setUint32(totalLength + 4, bitLength >>> 0, false)
    totalLength += 8
    for (let i = 0; i < totalLength; i += 64) this.block(padded, i)

    let out = ''
    for (const value of this.h) out += value.toString(16).padStart(8, '0')
    return out
  }

  private block(data: Uint8Array, offset: number): void {
    const w = this.w
    for (let i = 0; i < 16; i++) {
      w[i] =
        (data[offset + i * 4] << 24) |
        (data[offset + i * 4 + 1] << 16) |
        (data[offset + i * 4 + 2] << 8) |
        data[offset + i * 4 + 3]
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0
    }
    let [a, b, c, d, e, f, g, h] = this.h
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + S1 + ch + K[i] + w[i]) | 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) | 0
      h = g; g = f; f = e
      e = (d + temp1) | 0
      d = c; c = b; b = a
      a = (temp1 + temp2) | 0
    }
    this.h[0] = (this.h[0] + a) | 0
    this.h[1] = (this.h[1] + b) | 0
    this.h[2] = (this.h[2] + c) | 0
    this.h[3] = (this.h[3] + d) | 0
    this.h[4] = (this.h[4] + e) | 0
    this.h[5] = (this.h[5] + f) | 0
    this.h[6] = (this.h[6] + g) | 0
    this.h[7] = (this.h[7] + h) | 0
  }
}

function rotr(value: number, bits: number): number {
  return (value >>> bits) | (value << (32 - bits))
}

async function incrementalSha256(file: File, onProgress: (ratio: number) => void): Promise<string> {
  const hasher = new Sha256()
  let offset = 0
  while (offset < file.size) {
    const slice = file.slice(offset, Math.min(offset + HASH_CHUNK, file.size))
    hasher.update(new Uint8Array(await slice.arrayBuffer()))
    offset += HASH_CHUNK
    onProgress(Math.min(1, offset / file.size))
  }
  return hasher.digest()
}
