<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import MediaGrid from '@/components/MediaGrid.vue'
import Lightbox from '@/components/Lightbox.vue'
import PlayerBar from '@/components/PlayerBar.vue'
import MobileNavSheet from '@/components/MobileNavSheet.vue'
import SettingsPanel from '@/components/SettingsPanel.vue'
import VideoPlayer from '@/components/VideoPlayer.vue'
import type { MediaItem, MediaView } from '@/api/client'
import { useMediaStore } from '@/stores/media'
import { useSessionStore } from '@/stores/session'
import { useUploadStore } from '@/stores/upload'
import { formatBytes, formatDuration, formatRelative, placeholderArt } from '@/utils/format'
import '@/styles/app.css'

const router = useRouter()
const session = useSessionStore()
const media = useMediaStore()
const upload = useUploadStore()

type Tab = MediaView | 'me'
const tab = ref<Tab>('all')

const NAV: { key: Tab; icon: string; label: string; countKey?: keyof NonNullable<typeof media.counts> }[] = [
  { key: 'all', icon: 'all', label: '全部', countKey: 'all' },
  { key: 'photo', icon: 'photo', label: '照片', countKey: 'photo' },
  { key: 'video', icon: 'video', label: '视频', countKey: 'video' },
  { key: 'audio', icon: 'audio', label: '音频', countKey: 'audio' },
  { key: 'fav', icon: 'star', label: '已标星', countKey: 'fav' },
  { key: 'trash', icon: 'trash', label: '回收站', countKey: 'trash' },
  { key: 'me', icon: 'me', label: '我的' },
]

const TITLES: Record<Tab, string> = {
  all: '全部媒体',
  photo: '照片',
  video: '视频',
  audio: '音频',
  fav: '已标星',
  trash: '回收站',
  me: '我的',
}

// ── 布局 ────────────────────────────────────────────────────────────────
const isMobile = ref(window.innerWidth < 860)
const scrolled = ref(false)
const contentEl = ref<HTMLElement | null>(null)

function onResize() {
  isMobile.value = window.innerWidth < 860
}

function onScroll() {
  const el = contentEl.value
  if (!el) return
  scrolled.value = el.scrollTop > 12
  // 触底继续加载
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 600) {
    void media.loadMore()
  }
}

// ── 上传 ────────────────────────────────────────────────────────────────
const fileInput = ref<HTMLInputElement | null>(null)
/** 拖放事件在子元素间移动时会连续触发 leave/enter，用计数器才不会闪 */
let dragDepth = 0

function pickFiles() {
  fileInput.value?.click()
}

async function onFilesChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  await doUpload(files)
}

async function doUpload(files: File[]) {
  if (files.length === 0) return
  await upload.uploadFiles(files, () => {
    void media.refreshAfterUpload()
  })
  const failed = upload.tasks.filter((t) => t.state === 'failed')
  toast(failed.length ? `${failed.length} 个文件上传失败` : '上传完成')
}

function onDragEnter(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  dragDepth++
  upload.dragging = true
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) upload.dragging = false
}

async function onDrop(event: DragEvent) {
  dragDepth = 0
  upload.dragging = false
  const files = Array.from(event.dataTransfer?.files ?? [])
  await doUpload(files)
}

// ── 搜索 ────────────────────────────────────────────────────────────────
const searchText = ref('')
let searchTimer: number | undefined

watch(searchText, (value) => {
  window.clearTimeout(searchTimer)
  // 防抖：每敲一个字就发一次请求，中文输入法下会打出一串无意义的中间态查询
  searchTimer = window.setTimeout(() => void media.search(value.trim()), 300)
})

// ── 预览 / 播放 ─────────────────────────────────────────────────────────
const lightboxIndex = ref(-1)
const playingTrack = ref<MediaItem | null>(null)
/** 正在全屏播放的视频，null 表示没打开 */
const videoPlaying = ref<MediaItem | null>(null)

const audioQueue = computed(() => media.items.filter((item) => item.kind === 'AUDIO'))

function openItem(item: MediaItem) {
  if (item.kind === 'AUDIO') {
    playingTrack.value = item
    return
  }
  // 视频：全屏只播点中的这一个，不做上下滑动切换。
  // 之前是整条队列的 feed，但那会让"我只想看这一个"变成"一不小心就滑走了"。
  if (item.kind === 'VIDEO') {
    videoPlaying.value = item
    return
  }
  lightboxIndex.value = media.items.findIndex((m) => m.id === item.id)
}

// ── 操作 ────────────────────────────────────────────────────────────────
const toastText = ref('')
let toastTimer: number | undefined

function toast(message: string) {
  toastText.value = message
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => (toastText.value = ''), 2600)
}

async function runOnSelection(action: 'star' | 'delete' | 'restore' | 'purge') {
  const ids = media.selectedIds
  if (ids.length === 0) return
  try {
    switch (action) {
      case 'star': await media.star(ids, true); toast(`已标星 ${ids.length} 项`); break
      case 'delete': await media.remove(ids); toast(`已移入回收站`); break
      case 'restore': await media.restore(ids); toast('已恢复'); break
      case 'purge': await media.purge(ids); toast('已彻底删除'); break
    }
  } catch (e) {
    toast(e instanceof Error ? e.message : '操作失败')
  }
  media.clearSelection()
}

async function toggleStar(item: MediaItem) {
  try {
    await media.star([item.id], !item.starred)
  } catch (e) {
    toast(e instanceof Error ? e.message : '操作失败')
  }
}

async function removeOne(item: MediaItem) {
  try {
    await media.remove([item.id])
    lightboxIndex.value = -1
    videoPlaying.value = null
    toast('已移入回收站')
  } catch (e) {
    toast(e instanceof Error ? e.message : '删除失败')
  }
}

async function purgeAll() {
  const ids = media.items.map((item) => item.id)
  if (ids.length === 0) return
  if (!window.confirm(`彻底删除回收站里的 ${ids.length} 项？此操作无法撤销。`)) return
  try {
    await media.purge(ids)
    toast('回收站已清空')
  } catch (e) {
    toast(e instanceof Error ? e.message : '清空失败')
  }
}

async function signOut() {
  await session.logout()
  await router.replace({ name: 'login' })
}

/** 背景图在 script 里拼：模板属性里写 \" 不是合法转义。 */
function art(item: MediaItem): string {
  return item.thumbUrl ? `url("${item.thumbUrl}")` : placeholderArt(item.id)
}

/**
 * 底部标签栏只放最常用的四个入口，其余走「更多」面板。
 * 手机上一行塞不下七个目的地，硬塞会让每个按钮小到点不准。
 */
const TABBAR_KEYS: Tab[] = ['all', 'photo', 'video']
const navSheetOpen = ref(false)

/** 当前视图不在标签栏里时，「更多」要高亮，否则用户不知道自己在哪 */
const moreActive = computed(() => !TABBAR_KEYS.includes(tab.value))

async function switchTab(next: Tab) {
  navSheetOpen.value = false
  tab.value = next
  lightboxIndex.value = -1
  videoPlaying.value = null
  if (next !== 'me') {
    await media.switchView(next)
  }
}

// ── 生命周期 ────────────────────────────────────────────────────────────
onMounted(async () => {
  window.addEventListener('resize', onResize)
  await Promise.all([media.reload(), media.refreshCounts()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.clearTimeout(searchTimer)
  window.clearTimeout(toastTimer)
})

// 显示偏好存在本机 localStorage：它是"这台设备怎么看"，不是账号属性，
// 家里每个人可以各设各的
const density = ref(localStorage.getItem('shiguang.density') ?? 'standard')
const showNames = ref(localStorage.getItem('shiguang.shownames') === '1')

function onDisplayChange(value: { density: string; showNames: boolean }) {
  density.value = value.density
  showNames.value = value.showNames
}

const shellClass = computed(() => ({
  'is-mobile': isMobile.value,
  scrolled: scrolled.value,
  selecting: media.selecting,
  uploading: upload.uploading,
  dragging: upload.dragging,
  playing: playingTrack.value !== null,
  shownames: showNames.value,
}))

const eyebrow = computed(() => {
  if (upload.uploading) return `正在上传 ${upload.overallProgress}%`
  const latest = media.items[0]
  return latest ? `最近上传 · ${formatRelative(latest.createdAt)}` : '还没有内容'
})
</script>

<template>
  <div
    class="app"
    :class="shellClass"
    :data-density="density"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 侧边栏 -->
    <aside class="side">
      <div class="logo">
        <span class="mark">拾</span>
        <div><h1>拾光NAS</h1><p>LAN ONLY</p></div>
      </div>
      <button class="upbtn" type="button" @click="pickFiles">
        <AppIcon name="upload" />上传文件
      </button>
      <nav class="nav">
        <span class="nav-label">媒体</span>
        <button
          v-for="entry in NAV.slice(0, 4)"
          :key="entry.key"
          type="button"
          :aria-current="tab === entry.key ? 'page' : undefined"
          @click="switchTab(entry.key)"
        >
          <AppIcon :name="entry.icon" />{{ entry.label }}
          <span class="count">{{ entry.countKey ? media.counts?.[entry.countKey] ?? '' : '' }}</span>
        </button>
        <span class="nav-label">其他</span>
        <button
          v-for="entry in NAV.slice(4)"
          :key="entry.key"
          type="button"
          :aria-current="tab === entry.key ? 'page' : undefined"
          @click="switchTab(entry.key)"
        >
          <AppIcon :name="entry.icon" />{{ entry.label }}
          <span class="count">{{ entry.countKey ? media.counts?.[entry.countKey] ?? '' : '' }}</span>
        </button>
      </nav>
      <div class="side-foot">
        <p class="lanpill"><span class="dot" />{{ session.user?.displayName }}</p>
        <p class="lanpill" style="margin-top: 2px">{{ session.system?.lanUrls?.[0] ?? '' }}</p>
        <button class="copybtn" style="margin-top: 10px; width: 100%" type="button" @click="signOut">
          退出登录
        </button>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="titlewrap">
          <div class="eyebrow"><span class="dot" />{{ eyebrow }}</div>
          <h2>{{ TITLES[tab] }}</h2>
        </div>
        <div class="spacer" />
        <label v-if="tab !== 'me'" class="searchbox">
          <AppIcon name="search" />
          <input v-model="searchText" placeholder="搜索文件名…" />
        </label>
        <button
          v-if="tab !== 'me'"
          class="icon-btn"
          :class="{ on: media.selecting }"
          type="button"
          title="多选"
          :aria-pressed="media.selecting"
          @click="media.toggleSelecting()"
        >
          <AppIcon name="check" />
        </button>
        <div class="upline" :style="{ width: `${upload.overallProgress}%` }" />
      </header>

      <div ref="contentEl" class="content" @scroll.passive="onScroll">
        <!-- 我的 -->
        <SettingsPanel v-if="tab === 'me'" @toast="toast" @display="onDisplayChange" />

        <!-- 回收站 -->
        <template v-else-if="tab === 'trash'">
          <div class="trashbar">
            <p class="note">
              删除的内容会在这里保留一段时间，之后自动彻底删除。彻底删除后无法恢复。
            </p>
            <button type="button" @click="media.reload()">刷新</button>
            <button class="danger" type="button" @click="purgeAll">清空回收站</button>
          </div>
          <div class="trashlist">
            <div v-for="item in media.items" :key="item.id" class="trashrow">
              <span
                class="th"
                :style="{ backgroundImage: art(item) }"
              />
              <div class="m">
                <b>{{ item.name }}</b>
                <span>{{ formatBytes(item.size) }} · 删除于 {{ formatRelative(item.deletedAt ?? item.createdAt) }}</span>
              </div>
              <div class="acts">
                <button type="button" @click="media.restore([item.id]).then(() => toast('已恢复'))">
                  恢复
                </button>
                <button class="danger" type="button" @click="media.purge([item.id]).then(() => toast('已彻底删除'))">
                  彻底删除
                </button>
              </div>
            </div>
          </div>
          <div v-if="media.items.length === 0" class="empty">
            <b>回收站是空的</b>
            删掉的东西会先放到这里，给你后悔的机会。
          </div>
        </template>

        <!-- 音频：列表 -->
        <div v-else-if="tab === 'audio'" class="tracks">
          <div
            v-for="(item, index) in media.items"
            :key="item.id"
            class="track-row"
            :class="{ picked: media.selected.has(item.id) }"
            :data-playing="playingTrack?.id === item.id"
          >
            <!-- 整行是一个按钮：多选模式下点行就是勾选，平时才是播放。
                 和网格里的 tile 保持同一种手感。 -->
            <button
              class="hit"
              type="button"
              :aria-pressed="media.selecting ? media.selected.has(item.id) : undefined"
              @click="media.selecting ? media.toggle(item.id) : openItem(item)"
            >
              <span class="idx">
                <span v-if="media.selecting" class="check" :class="{ on: media.selected.has(item.id) }">
                  <AppIcon name="check" />
                </span>
                <span v-else-if="playingTrack?.id === item.id" class="eq"><i /><i /><i /></span>
                <template v-else>{{ index + 1 }}</template>
              </span>
              <span
                class="cov"
                :style="{ backgroundImage: art(item) }"
              />
              <span class="info">
                <b>{{ item.name }}</b>
                <span>{{ formatBytes(item.size) }}</span>
              </span>
              <svg v-if="item.starred" class="fav" viewBox="0 0 24 24">
                <path d="M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L4.2 9.7l5.4-.8z" />
              </svg>
              <span class="fmt" :class="{ lossless: ['flac', 'wav', 'alac'].includes(item.ext) }">
                {{ item.ext }}
              </span>
              <span class="tm">{{ formatDuration(item.durationMs) }}</span>
            </button>
            <!-- 音频没有灯箱那样的详情页，下载入口只能放在行里 -->
            <a
              v-if="!media.selecting"
              class="dl"
              :href="item.downloadUrl"
              download
              :aria-label="`下载 ${item.name}`"
              @click.stop
            >
              <AppIcon name="download" />
            </a>
          </div>
          <div v-if="media.items.length === 0" class="empty">
            <b>还没有音频</b>
            上传一些录音或音乐吧。
            <br /><button class="go" type="button" @click="pickFiles">上传文件</button>
          </div>
        </div>

        <!-- 其余：网格 -->
        <template v-else>
          <MediaGrid
            :items="media.items"
            :selecting="media.selecting"
            :selected="media.selected"
            @open="openItem"
            @toggle="media.toggle"
          />
          <div v-if="media.items.length === 0 && !media.loading" class="empty">
            <b>{{ media.keyword ? '没有找到匹配的内容' : '这里还是空的' }}</b>
            {{ media.keyword ? '换个关键词试试。' : '把手机里的照片和视频传上来，家里人就都能看了。' }}
            <br v-if="!media.keyword" />
            <button v-if="!media.keyword" class="go" type="button" @click="pickFiles">上传文件</button>
          </div>
          <div v-if="media.loading" class="empty">加载中…</div>
        </template>
      </div>
    </div>

    <!-- 移动端标签栏。第五格是「更多」，装下剩余全部入口 -->
    <nav class="tabbar">
      <button type="button" :aria-current="tab === 'all' ? 'page' : undefined" @click="switchTab('all')">
        <AppIcon name="all" />全部
      </button>
      <button type="button" :aria-current="tab === 'photo' ? 'page' : undefined" @click="switchTab('photo')">
        <AppIcon name="photo" />照片
      </button>
      <button class="up" type="button" aria-label="上传" @click="pickFiles"><AppIcon name="upload" /></button>
      <button type="button" :aria-current="tab === 'video' ? 'page' : undefined" @click="switchTab('video')">
        <AppIcon name="video" />视频
      </button>
      <button
        type="button"
        :aria-current="moreActive ? 'page' : undefined"
        :aria-expanded="navSheetOpen"
        @click="navSheetOpen = !navSheetOpen"
      >
        <AppIcon name="more" />更多
      </button>
    </nav>

    <MobileNavSheet
      :open="navSheetOpen"
      :current="tab"
      :counts="media.counts"
      :display-name="session.user?.displayName ?? ''"
      :lan-url="session.system?.lanUrls?.[0] ?? ''"
      @close="navSheetOpen = false"
      @select="switchTab($event as Tab)"
      @sign-out="signOut"
    />

    <!-- 多选条 -->
    <div class="selbar">
      <span class="cnt">已选 <b>{{ media.selectedCount }}</b> 项</span>
      <div class="selacts">
        <template v-if="tab === 'trash'">
          <button type="button" @click="runOnSelection('restore')">恢复</button>
          <button class="danger" type="button" @click="runOnSelection('purge')">彻底删除</button>
        </template>
        <template v-else>
          <button type="button" @click="runOnSelection('star')">标星</button>
          <button class="danger" type="button" @click="runOnSelection('delete')">删除</button>
        </template>
      </div>
    </div>

    <!-- 播放器 -->
    <PlayerBar
      :track="playingTrack"
      :queue="audioQueue"
      @close="playingTrack = null"
      @change="playingTrack = $event"
    />

    <!-- 全屏播放单个视频。从网格点进来 -->
    <VideoPlayer
      v-if="videoPlaying"
      :item="videoPlaying"
      @close="videoPlaying = null"
      @star="toggleStar"
      @remove="removeOne"
    />

    <!-- 预览 -->
    <Lightbox
      :items="media.items"
      :index="lightboxIndex"
      @close="lightboxIndex = -1"
      @navigate="lightboxIndex = $event"
      @star="toggleStar"
      @remove="removeOne"
    />

    <!-- 拖放 -->
    <div class="dropzone">
      <div class="dropinner">
        <AppIcon name="uploadBox" />
        <b>松手就开始上传</b>
        <p>照片 · 视频 · 音频</p>
      </div>
    </div>

    <div class="toast" :class="{ show: toastText }">{{ toastText }}</div>

    <input
      ref="fileInput"
      type="file"
      multiple
      accept="image/*,video/*,audio/*"
      hidden
      @change="onFilesChosen"
    />
  </div>
</template>


