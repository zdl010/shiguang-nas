<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { MediaItem } from '@/api/client'
import { formatDuration, placeholderArt } from '@/utils/format'

const props = defineProps<{ track: MediaItem | null; queue: MediaItem[] }>()
const emit = defineEmits<{ close: []; change: [item: MediaItem] }>()

const audio = ref<HTMLAudioElement | null>(null)
const playing = ref(false)
const currentMs = ref(0)
const totalMs = ref(0)
const volume = ref(0.8)

watch(
  () => props.track,
  (track) => {
    if (!track) {
      audio.value?.pause()
      playing.value = false
      return
    }
    // 换歌时重建 Audio 元素而不是改 src：改 src 在部分浏览器上会残留上一首的
    // 缓冲和 currentTime，表现为"切歌后进度条乱跳"
    audio.value?.pause()
    const el = new Audio(track.rawUrl)
    el.volume = volume.value
    el.addEventListener('timeupdate', () => {
      currentMs.value = el.currentTime * 1000
    })
    el.addEventListener('loadedmetadata', () => {
      totalMs.value = Number.isFinite(el.duration) ? el.duration * 1000 : (track.durationMs ?? 0)
    })
    el.addEventListener('ended', playNext)
    el.addEventListener('play', () => (playing.value = true))
    el.addEventListener('pause', () => (playing.value = false))
    audio.value = el
    currentMs.value = 0
    totalMs.value = track.durationMs ?? 0
    void el.play().catch(() => {
      // 自动播放被拦时保持暂停状态，等用户点播放
    })
  },
)

onBeforeUnmount(() => audio.value?.pause())

function toggle() {
  const el = audio.value
  if (!el) return
  if (el.paused) void el.play().catch(() => {})
  else el.pause()
}

function step(delta: number) {
  if (!props.track) return
  const index = props.queue.findIndex((item) => item.id === props.track!.id)
  const next = props.queue[index + delta]
  if (next) emit('change', next)
}

function playNext() {
  step(1)
}

function seek(event: MouseEvent) {
  const el = audio.value
  if (!el || !totalMs.value) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
  el.currentTime = (totalMs.value * ratio) / 1000
}

function setVolume(event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
  volume.value = ratio
  if (audio.value) audio.value.volume = ratio
}

function close() {
  audio.value?.pause()
  emit('close')
}

/** 背景图在 script 里拼。模板属性是双引号包裹的，里面写 \" 不是合法转义。 */
const cover = computed(() =>
  props.track?.thumbUrl ? `url("${props.track.thumbUrl}")` : placeholderArt(props.track?.id ?? 0),
)
</script>

<template>
  <div class="player">
    <div class="cov" :style="{ backgroundImage: cover }" />
    <div class="now">
      <b>{{ track?.name ?? '—' }}</b>
      <span>{{ track ? track.ext.toUpperCase() : '—' }}</span>
    </div>
    <div class="ctrls">
      <button type="button" aria-label="上一首" @click="step(-1)"><AppIcon name="prev" /></button>
      <button class="main-btn" type="button" :aria-label="playing ? '暂停' : '播放'" @click="toggle">
        <AppIcon :name="playing ? 'pause' : 'play'" />
      </button>
      <button type="button" aria-label="下一首" @click="step(1)"><AppIcon name="nextTrack" /></button>
    </div>
    <div class="pbar">
      <span class="t">{{ formatDuration(currentMs) }}</span>
      <div class="trackline" @click="seek">
        <i :style="{ width: totalMs ? `${(currentMs / totalMs) * 100}%` : '0%' }" />
      </div>
      <span class="t">{{ formatDuration(totalMs) }}</span>
    </div>
    <div class="vol">
      <AppIcon name="volume" />
      <div class="trackline" @click="setVolume"><i :style="{ width: `${volume * 100}%` }" /></div>
    </div>
    <button class="icon-btn round" type="button" aria-label="关闭播放器" @click="close">
      <AppIcon name="close" />
    </button>
  </div>
</template>
