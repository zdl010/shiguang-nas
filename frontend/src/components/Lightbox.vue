<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { MediaItem } from '@/api/client'
import { formatBytes, formatDateTime, formatDuration, placeholderArt } from '@/utils/format'

const props = defineProps<{ items: MediaItem[]; index: number }>()
const emit = defineEmits<{
  close: []
  navigate: [index: number]
  star: [item: MediaItem]
  remove: [item: MediaItem]
}>()

const current = computed<MediaItem | null>(() => props.items[props.index] ?? null)
const open = computed(() => current.value !== null)

const videoEl = ref<HTMLVideoElement | null>(null)

function go(step: number) {
  const next = props.index + step
  if (next >= 0 && next < props.items.length) emit('navigate', next)
}

function onKey(event: KeyboardEvent) {
  if (!open.value) return
  if (event.key === 'Escape') emit('close')
  else if (event.key === 'ArrowRight') go(1)
  else if (event.key === 'ArrowLeft') go(-1)
}

// 切换条目时暂停上一个视频。不做的话上一个会在后台继续出声。
watch(current, () => {
  videoEl.value?.pause()
})

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// 触摸左右滑动切换
let touchStartX = 0
function onTouchStart(e: TouchEvent) {
  touchStartX = e.changedTouches[0].clientX
}
function onTouchEnd(e: TouchEvent) {
  const delta = e.changedTouches[0].clientX - touchStartX
  if (Math.abs(delta) > 60) go(delta < 0 ? 1 : -1)
}

const kindLabel = computed(() => {
  switch (current.value?.kind) {
    case 'VIDEO': return '视频'
    case 'AUDIO': return '音频'
    default: return '照片'
  }
})

const exif = computed(() => {
  const item = current.value
  if (!item) return []
  const rows: [string, string][] = [
    ['大小', formatBytes(item.size)],
    ['时间', formatDateTime(item.takenAt)],
    ['格式', item.ext.toUpperCase()],
  ]
  if (item.width && item.height) rows.push(['尺寸', `${item.width} × ${item.height}`])
  if (item.durationMs && item.kind !== 'PHOTO') rows.push(['时长', formatDuration(item.durationMs)])
  return rows
})

const backdrop = computed(() =>
  current.value?.thumbUrl ? `url("${current.value.thumbUrl}")` : placeholderArt(current.value?.id ?? 0),
)
</script>

<template>
  <div class="lightbox" :class="{ open }" role="dialog" aria-modal="true" aria-label="媒体预览">
    <template v-if="current">
      <div class="lb-bg" :style="{ backgroundImage: backdrop }" />
      <div class="lb-veil" />
      <div class="lb-inner">
        <div class="lb-view">
          <div class="lb-head">
            <button class="icon-btn round" type="button" aria-label="返回" @click="emit('close')">
              <AppIcon name="back" />
            </button>
            <span class="tag">{{ kindLabel }}</span>
            <button
              class="icon-btn round"
              type="button"
              aria-label="下一个"
              :disabled="index >= items.length - 1"
              @click="go(1)"
            >
              <AppIcon name="next" />
            </button>
          </div>
          <div class="lb-stage" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
            <div class="lb-art">
              <img v-if="current.kind === 'PHOTO'" :src="current.rawUrl" :alt="current.name" />
              <video
                v-else-if="current.kind === 'VIDEO' && current.playable"
                ref="videoEl"
                :src="current.rawUrl"
                controls
                playsinline
                preload="metadata"
              />
              <div
                v-else-if="current.kind === 'AUDIO'"
                class="lb-audio"
                :style="{ backgroundImage: backdrop }"
              />
              <div v-else class="lb-audio" :style="{ backgroundImage: backdrop }" />
            </div>
          </div>
        </div>

        <aside class="lb-side">
          <h4>{{ current.name }}</h4>
          <p class="sub">{{ formatDateTime(current.takenAt) }}</p>

          <audio
            v-if="current.kind === 'AUDIO' && current.playable"
            class="lb-audio-player"
            :src="current.rawUrl"
            controls
            preload="metadata"
          />
          <p v-if="!current.playable" class="unplayable">
            这个格式浏览器放不了（{{ current.ext.toUpperCase() }}），下载后用本地播放器打开。
          </p>

          <div class="exif">
            <div v-for="[label, value] in exif" :key="label">
              <span>{{ label }}</span>
              <b>{{ value }}</b>
            </div>
          </div>

          <div class="lb-acts">
            <button
              type="button"
              :class="{ faved: current.starred }"
              @click="emit('star', current)"
            >
              <AppIcon name="star" />{{ current.starred ? '已标星' : '标星' }}
            </button>
            <a :href="current.downloadUrl" download><AppIcon name="download" />下载</a>
            <button type="button" @click="emit('remove', current)">
              <AppIcon name="trash" />删除
            </button>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.lb-audio-player {
  width: 100%;
  margin-bottom: 16px;
}

.unplayable {
  margin: 0 0 16px;
  padding: 9px 11px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, var(--a3) 40%, transparent);
  background: color-mix(in srgb, var(--a3) 10%, transparent);
  font-size: 12px;
  line-height: 1.6;
}

.exif b {
  font-weight: 500;
  text-align: right;
  word-break: break-all;
}

.icon-btn:disabled {
  opacity: .35;
  cursor: not-allowed;
}
</style>
