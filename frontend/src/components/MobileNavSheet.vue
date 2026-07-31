<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * 手机版的完整导航面板。
 *
 * <p>为什么需要它：底部标签栏只有 5 个位置，其中一个还要留给上传按钮，
 * 剩下 4 个装不下 7 个目的地（全部/照片/视频/音频/已标星/回收站/我的）。
 * 硬塞会让每个按钮小到点不准，砍掉几个又会让「音频」「回收站」在手机上
 * 彻底无法访问——那正是这个面板要解决的问题。
 *
 * <p>内容与桌面侧边栏 1:1 对应，包括分组标题和角标数字，
 * 这样用户在两种设备上看到的是同一套结构。
 */
defineProps<{
  open: boolean
  current: string
  counts: Record<string, number> | null
  displayName: string
  lanUrl: string
}>()

const emit = defineEmits<{
  close: []
  select: [key: string]
  signOut: []
}>()

const GROUPS: { label: string; items: { key: string; icon: string; label: string; countKey?: string }[] }[] = [
  {
    label: '媒体',
    items: [
      { key: 'all', icon: 'all', label: '全部', countKey: 'all' },
      { key: 'photo', icon: 'photo', label: '照片', countKey: 'photo' },
      { key: 'video', icon: 'video', label: '视频', countKey: 'video' },
      { key: 'audio', icon: 'audio', label: '音频', countKey: 'audio' },
    ],
  },
  {
    label: '其他',
    items: [
      { key: 'fav', icon: 'star', label: '已标星', countKey: 'fav' },
      { key: 'trash', icon: 'trash', label: '回收站', countKey: 'trash' },
      { key: 'me', icon: 'me', label: '我的' },
    ],
  },
]
</script>

<template>
  <div class="sheet-root" :class="{ open }" role="dialog" aria-modal="true" aria-label="导航">
    <!-- 点遮罩关闭。没有它，面板打开后用户只能去找那个小小的关闭按钮 -->
    <div class="scrim" @click="emit('close')" />

    <nav class="sheet">
      <div class="handle" aria-hidden="true" />

      <div class="sheet-scroll">
        <template v-for="group in GROUPS" :key="group.label">
          <span class="nav-label">{{ group.label }}</span>
          <button
            v-for="item in group.items"
            :key="item.key"
            type="button"
            class="row"
            :aria-current="current === item.key ? 'page' : undefined"
            @click="emit('select', item.key)"
          >
            <AppIcon :name="item.icon" />
            <span class="txt">{{ item.label }}</span>
            <span class="count">{{ item.countKey ? counts?.[item.countKey] ?? '' : '' }}</span>
          </button>
        </template>

        <div class="foot">
          <p class="lanpill"><span class="dot" />{{ displayName }}</p>
          <p class="lanpill">{{ lanUrl }}</p>
          <button class="signout" type="button" @click="emit('signOut')">退出登录</button>
        </div>
      </div>
    </nav>
  </div>
</template>

<style scoped>
.sheet-root {
  position: absolute;
  inset: 0;
  z-index: 60;
  pointer-events: none;
}

.sheet-root.open {
  pointer-events: auto;
}

.scrim {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--bg) 70%, transparent);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  opacity: 0;
  transition: opacity .28s;
}

.sheet-root.open .scrim {
  opacity: 1;
}

.sheet {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 10px;
  /* 最多占屏幕八成高，内容多了自己滚，不会把标签栏顶出屏幕 */
  max-height: 80%;
  display: flex;
  flex-direction: column;
  padding: 8px 10px calc(12px + env(safe-area-inset-bottom));
  border-radius: 24px;
  border: 1px solid var(--line2);
  background: var(--glass);
  backdrop-filter: blur(24px) saturate(1.5);
  -webkit-backdrop-filter: blur(24px) saturate(1.5);
  box-shadow: 0 24px 60px -18px var(--shadow);
  /* 余量给足：位移量只要略小于「自身高度 + bottom 偏移」就会露出一条边 */
  transform: translateY(calc(100% + 80px));
  transition: transform .32s cubic-bezier(.2, .9, .3, 1);
}

.sheet-root.open .sheet {
  transform: none;
}

.handle {
  width: 38px;
  height: 4px;
  border-radius: 99px;
  background: var(--line2);
  margin: 4px auto 8px;
  flex: none;
}

/* 面板自身可滚：内容比可用高度多时（比如角标很长）不至于被裁掉 */
.sheet-scroll {
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  min-height: 0;
}

.row {
  display: flex;
  align-items: center;
  gap: 13px;
  width: 100%;
  padding: 13px 12px;
  border: none;
  border-radius: 13px;
  background: none;
  color: var(--muted);
  cursor: pointer;
  font-size: 15px;
  text-align: left;
  transition: background .18s, color .18s;
}

.row:active {
  background: var(--raise);
}

.row[aria-current='page'] {
  background: var(--raise2);
  color: var(--text);
  font-weight: 600;
}

.row :deep(svg) {
  width: 20px;
  height: 20px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.row[aria-current='page'] :deep(svg) {
  stroke: var(--a2);
}

.txt {
  flex: 1;
}

.count {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--muted);
}

.nav-label {
  display: block;
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: .24em;
  color: var(--muted);
  text-transform: uppercase;
  padding: 12px 12px 6px;
}

.foot {
  margin-top: 10px;
  padding: 12px 12px 0;
  border-top: 1px solid var(--line);
}

.signout {
  width: 100%;
  margin-top: 10px;
  padding: 11px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--raise);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
}
</style>
