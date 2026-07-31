<script setup lang="ts">
import { computed } from 'vue'
import { qrMatrix } from '@/utils/qr'

const props = withDefaults(defineProps<{ text: string; size?: number }>(), { size: 168 })

/**
 * 用 SVG 而不是 canvas：SVG 在任何 DPI 下都是清晰的，
 * 而 canvas 要处理 devicePixelRatio，手机上很容易画糊。
 */
const svg = computed(() => {
  try {
    const matrix = qrMatrix(props.text)
    const n = matrix.length
    const quiet = 2 // 静区，扫码必需
    const total = n + quiet * 2
    const paths: string[] = []
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (matrix[y][x]) paths.push(`M${x + quiet} ${y + quiet}h1v1h-1z`)
      }
    }
    return { total, path: paths.join('') }
  } catch {
    return null
  }
})
</script>

<template>
  <svg
    v-if="svg"
    class="qr"
    :width="size"
    :height="size"
    :viewBox="`0 0 ${svg.total} ${svg.total}`"
    shape-rendering="crispEdges"
    role="img"
    aria-label="二维码"
  >
    <rect :width="svg.total" :height="svg.total" fill="#fff" />
    <path :d="svg.path" fill="#000" />
  </svg>
  <p v-else class="qr-fail">内容过长，无法生成二维码</p>
</template>

<style scoped>
.qr {
  border-radius: 10px;
  display: block;
}

.qr-fail {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
}
</style>
