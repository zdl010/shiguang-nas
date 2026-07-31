<script setup lang="ts">
import { onMounted } from 'vue'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()

onMounted(() => {
  void session.bootstrap()
})
</script>

<template>
  <RouterView v-if="session.ready" />
  <div v-else class="boot">
    <span class="boot-dot" aria-hidden="true"></span>
    <p>正在连接拾光…</p>
  </div>
</template>

<style scoped>
.boot {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 14px;
  height: 100vh;
  color: var(--muted);
  font-size: 13px;
}

.boot-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--a2);
  box-shadow: 0 0 14px var(--a2);
  animation: pulse 1.2s ease-in-out infinite;
}

.boot p {
  margin: 0;
}

@keyframes pulse {
  0%, 100% { opacity: .35; transform: scale(.85); }
  50% { opacity: 1; transform: scale(1.15); }
}

@media (prefers-reduced-motion: reduce) {
  .boot-dot { animation: none; }
}
</style>
