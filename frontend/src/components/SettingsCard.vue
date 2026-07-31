<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * 设置页的一张卡片。
 *
 * <p>手机上默认折叠，点标题展开。设置页有九个板块，全展开时在 320px 宽的屏幕上
 * 有近三千像素高——要滑六屏才能看到最后一项，找任何东西都得靠翻。
 * 折叠之后整页只剩九行，一屏就能看全，需要哪个点哪个。
 *
 * <p>桌面宽屏不折叠：那里本来就一屏放得下大半，多一次点击反而是负担。
 */
const props = withDefaults(defineProps<{ icon: string; title: string; open?: boolean }>(), {
  open: false,
})

const isNarrow = ref(window.innerWidth < 860)
const expanded = ref(props.open)

function onResize() {
  isNarrow.value = window.innerWidth < 860
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

/** 宽屏一律展开，窄屏看自己的状态 */
const shown = computed(() => !isNarrow.value || expanded.value)
</script>

<template>
  <section class="card" :class="{ collapsible: isNarrow, open: shown }">
    <button
      v-if="isNarrow"
      class="head"
      type="button"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <AppIcon :name="icon" />
      <span class="t">{{ title }}</span>
      <svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
    </button>
    <h3 v-else><AppIcon :name="icon" />{{ title }}</h3>

    <div v-show="shown" class="body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.card.collapsible {
  padding: 0;
  overflow: hidden;
}

.head {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 14px;
  border: none;
  background: none;
  color: inherit;
  font-size: 13.5px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.head :deep(svg:first-child) {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: var(--a2);
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.head .t {
  flex: 1;
}

.chev {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: var(--muted);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform .22s;
}

.card.collapsible.open .chev {
  transform: rotate(90deg);
}

.card.collapsible .body {
  padding: 0 14px 14px;
}
</style>
