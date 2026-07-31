<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { MediaItem } from '@/api/client'
import { formatDuration, groupLabel, placeholderArt } from '@/utils/format'

const props = defineProps<{
  items: MediaItem[]
  selecting: boolean
  selected: Set<number>
}>()

const emit = defineEmits<{
  open: [item: MediaItem]
  toggle: [id: number]
}>()

/** 按"今天/昨天/本周/月份"分组，和原型一致。 */
const groups = computed(() => {
  const buckets: { label: string; items: MediaItem[] }[] = []
  for (const item of props.items) {
    const label = groupLabel(item.takenAt)
    const last = buckets[buckets.length - 1]
    if (last && last.label === label) last.items.push(item)
    else buckets.push({ label, items: [item] })
  }
  return buckets
})

function art(item: MediaItem): string {
  return item.thumbUrl ? `url("${item.thumbUrl}")` : placeholderArt(item.id)
}

function activate(item: MediaItem) {
  if (props.selecting) emit('toggle', item.id)
  else emit('open', item)
}
</script>

<template>
  <div v-for="group in groups" :key="group.label" class="group">
    <div class="group-head">
      <h3>{{ group.label }}</h3>
      <span class="meta">{{ group.items.length }} 项</span>
    </div>
    <div class="grid">
      <button
        v-for="(item, index) in group.items"
        :key="item.id"
        class="tile"
        :class="{ 'is-video': item.kind === 'VIDEO' }"
        :aria-pressed="selected.has(item.id)"
        :style="{ animationDelay: `${Math.min(index, 12) * 18}ms` }"
        type="button"
        @click="activate(item)"
      >
        <span class="art" :style="{ backgroundImage: art(item) }" />
        <span class="vgrad" />
        <span v-if="item.thumbState === 'PENDING'" class="pending">处理中…</span>
        <span class="fname">{{ item.name }}</span>
        <span v-if="item.durationMs && item.kind !== 'PHOTO'" class="dur">
          <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
          {{ formatDuration(item.durationMs) }}
        </span>
        <svg v-if="item.starred" class="fav" viewBox="0 0 24 24">
          <path d="M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L4.2 9.7l5.4-.8z" />
        </svg>
        <span class="check"><AppIcon name="check" /></span>
      </button>
    </div>
  </div>
</template>
