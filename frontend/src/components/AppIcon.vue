<script setup lang="ts">
/**
 * 内联 SVG 图标集，路径取自 docs/prototype.html。
 *
 * 不引图标库：整个应用只用到十几个图标，一个图标库要多背几百 KB
 * 和一条外部依赖，而这里总共不到 40 行路径数据。
 *
 * 下面用了 v-html。这里是安全的，因为渲染的内容只可能来自本文件里的
 * PATHS 常量表——**永远不要**把它改成接受外部传入的 SVG 字符串。
 */
const props = defineProps<{ name: string }>()

const PATHS: Record<string, string> = {
  all: '<rect x="3" y="3" width="18" height="18" rx="4"/><circle cx="9" cy="9.5" r="1.7"/><path d="M21 16l-5-5-9 9"/>',
  photo: '<path d="M4 7h3l1.6-2h6.8L17 7h3a1 1 0 011 1v10a2 2 0 01-2 2H5a2 2 0 01-2-2V8a1 1 0 011-1z"/><circle cx="12" cy="13" r="3.6"/>',
  video: '<rect x="2.5" y="5" width="14" height="14" rx="3"/><path d="M16.5 10.5l5-3v9l-5-3z"/>',
  audio: '<path d="M4 12h2l2-6 3 13 3-9 2 4h4"/>',
  star: '<path d="M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L4.2 9.7l5.4-.8z"/>',
  trash: '<path d="M4 7h16"/><path d="M9 7V5h6v2"/><path d="M6 7l1 13h10l1-13"/>',
  me: '<circle cx="12" cy="8.5" r="3.6"/><path d="M4.5 20a7.5 7.5 0 0115 0"/>',
  upload: '<path d="M12 19V6"/><path d="M6 12l6-6 6 6"/>',
  uploadBox: '<path d="M12 17V4"/><path d="M6 10l6-6 6 6"/><path d="M4 20h16"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.6-3.6"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  next: '<path d="M9 5l7 7-7 7"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  download: '<path d="M12 4v12"/><path d="M8 12l4 4 4-4"/><path d="M5 20h14"/>',
  shield: '<path d="M12 3l7 3v6c0 4.4-3 8.2-7 9-4-.8-7-4.6-7-9V6z"/><path d="M9 12l2 2 4-4"/>',
  device: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
  key: '<circle cx="8" cy="12" r="4"/><path d="M12 12h9"/><path d="M17 12v3"/><path d="M20 12v2"/>',
  palette: '<path d="M12 3a9 9 0 100 18c1 0 1.5-.8 1.5-1.5S12.8 18 12.8 17c0-1 .8-1.5 1.7-1.5H16a5 5 0 005-5c0-4.1-4-7.5-9-7.5z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10.5" cy="7.5" r="1"/><circle cx="14.5" cy="7.5" r="1"/>',
  disk: '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  link: '<path d="M10 13a5 5 0 007.5.5l3-3a5 5 0 00-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 00-7.5-.5l-3 3a5 5 0 007 7l1.7-1.7"/>',
  logout: '<path d="M14 5H6a1 1 0 00-1 1v12a1 1 0 001 1h8"/><path d="M17 15l4-3-4-3"/><path d="M21 12H10"/>',
  play: '<path d="M8 5v14l11-7z"/>',
  pause: '<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/>',
  prev: '<path d="M7 6h2v12H7zM19 6v12l-9-6z"/>',
  nextTrack: '<path d="M15 6h2v12h-2zM5 6l9 6-9 6z"/>',
  volume: '<path d="M11 5L6.5 9H3v6h3.5L11 19z"/><path d="M15.5 9.5a3.5 3.5 0 010 5"/><path d="M18 7a7 7 0 010 10"/>',
  more: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
  restore: '<path d="M4 12a8 8 0 108-8 8 8 0 00-5.7 2.3L4 8.5"/><path d="M4 4v5h5"/>',
}

const filled = new Set(['play', 'pause', 'prev', 'nextTrack'])
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    aria-hidden="true"
    :style="filled.has(props.name) ? { fill: 'currentColor', stroke: 'none' } : undefined"
    v-html="PATHS[props.name] ?? ''"
  />
</template>
