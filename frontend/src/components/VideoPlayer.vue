<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { MediaItem } from '@/api/client'
import { formatBytes, formatDateTime, formatDuration } from '@/utils/format'

/**
 * 全屏播放单个视频。
 *
 * <p>刻意<b>不做上下滑动切换</b>：早先是整条队列的 feed，但那让"我只想看这一个"
 * 变成了"手一滑就到下一个"。要挑视频回网格挑，这里只负责把选中的那个放好。
 */
const props = defineProps<{ item: MediaItem }>()
const emit = defineEmits<{ close: []; star: [item: MediaItem]; remove: [item: MediaItem] }>()

const videoEl = ref<HTMLVideoElement | null>(null)

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  // 尝试自动播放。带声音的自动播放会被浏览器拒绝，这里不静音——
  // 用户是主动点进来的，播不了就让他自己点一下 play，比默默静音更好懂。
  void videoEl.value?.play().catch(() => {})
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  // 关闭时必须暂停：不停的话元素被移除后音频可能还在后台响
  videoEl.value?.pause()
})
</script>

<template>
  <div class="overlay" role="dialog" aria-modal="true" :aria-label="item.name">
    <div class="topbar">
      <button class="icon-btn round" type="button" aria-label="返回" @click="emit('close')">
        <AppIcon name="back" />
      </button>
      <span class="title">{{ item.name }}</span>
      <div class="acts">
        <button
          class="icon-btn round"
          :class="{ on: item.starred }"
          type="button"
          :aria-pressed="item.starred"
          aria-label="标星"
          @click="emit('star', item)"
        >
          <AppIcon name="star" />
        </button>
        <a class="icon-btn round" :href="item.downloadUrl" download aria-label="下载">
          <AppIcon name="download" />
        </a>
        <button class="icon-btn round" type="button" aria-label="删除" @click="emit('remove', item)">
          <AppIcon name="trash" />
        </button>
      </div>
    </div>

    <div class="stage">
      <video
        v-if="item.playable"
        ref="videoEl"
        :src="item.rawUrl"
        :poster="item.thumbUrl ?? undefined"
        controls
        playsinline
        preload="metadata"
      />
      <div v-else class="unplayable">
        <p>这个格式浏览器放不了（{{ item.ext.toUpperCase() }}）。</p>
        <a class="dl" :href="item.downloadUrl" download>下载后用本地播放器打开</a>
      </div>
    </div>

    <div class="meta">
      <span>{{ formatDateTime(item.takenAt) }}</span>
      <span v-if="item.width && item.height">{{ item.width }} × {{ item.height }}</span>
      <span v-if="item.durationMs">{{ formatDuration(item.durationMs) }}</span>
      <span>{{ formatBytes(item.size) }}</span>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: absolute;
  inset: 0;
  z-index: 90;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg);
}

/* 名字不能叫 .bar：全局那个 .bar 是上传进度条（height:7px; overflow:hidden），
   会把这一行压成 22px 再把按钮裁掉。组件里用通用名要小心撞车。 */
.topbar {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: calc(12px + env(safe-area-inset-top)) 14px 10px;
}

.title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.acts {
  display: flex;
  gap: 6px;
  flex: none;
}

.topbar .acts a.icon-btn {
  text-decoration: none;
  color: var(--text);
}

.icon-btn.on {
  color: var(--a3);
  border-color: color-mix(in srgb, var(--a3) 45%, transparent);
}

.stage {
  flex: 1;
  min-height: 0;
  display: grid;
  place-items: center;
  padding: 0 12px;
}

.stage video {
  max-width: 100%;
  max-height: 100%;
  border-radius: 14px;
  background: #000;
}

.unplayable {
  text-align: center;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.8;
}

.unplayable .dl {
  display: inline-block;
  margin-top: 10px;
  padding: 9px 16px;
  border-radius: 11px;
  background: var(--text);
  color: var(--bg);
  font-weight: 600;
  text-decoration: none;
}

.meta {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  justify-content: center;
  padding: 12px 16px calc(16px + env(safe-area-inset-bottom));
  font-family: var(--mono);
  font-size: 11px;
  color: var(--muted);
}
</style>
