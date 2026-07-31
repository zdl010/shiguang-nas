<script setup lang="ts">
/**
 * 初始化页与登录页共用的外壳：极光背景 + 毛玻璃卡片。
 * 视觉语言沿用 docs/prototype.html 的 .app::before 极光层。
 */
defineProps<{
  eyebrow: string
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="shell">
    <div class="aurora" aria-hidden="true"></div>
    <main class="card">
      <header class="head">
        <div class="logo">
          <span class="mark">拾</span>
          <div>
            <h1>拾光</h1>
            <p>SHIGUANG NAS</p>
          </div>
        </div>
        <p class="eyebrow">{{ eyebrow }}</p>
        <h2>{{ title }}</h2>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      </header>

      <slot />

      <footer v-if="$slots.footer" class="foot">
        <slot name="footer" />
      </footer>
    </main>
  </div>
</template>

<style scoped>
.shell {
  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px 18px calc(24px + env(safe-area-inset-bottom));
  background: var(--bg);
  overflow: hidden;
}

.aurora {
  position: absolute;
  inset: -25% -10% auto -10%;
  height: 72%;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(34% 44% at 20% 30%, var(--aur1), transparent 70%),
    radial-gradient(36% 42% at 76% 16%, var(--aur2), transparent 70%),
    radial-gradient(32% 38% at 52% 62%, var(--aur3), transparent 70%);
  filter: blur(30px);
  animation: drift 24s ease-in-out infinite alternate;
}

@keyframes drift {
  from { transform: translate3d(-4%, -2%, 0) scale(1); }
  to { transform: translate3d(6%, 4%, 0) scale(1.14); }
}

@media (prefers-reduced-motion: reduce) {
  .aurora { animation: none; }
}

.card {
  position: relative;
  z-index: 1;
  width: min(100%, 420px);
  padding: 28px 26px 24px;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: var(--glass);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: 0 30px 70px -30px var(--shadow);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
}

.mark {
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: linear-gradient(140deg, var(--a1), var(--a3));
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 0 8px 20px -8px var(--a1);
}

.logo h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.2;
}

.logo p {
  margin: 1px 0 0;
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: .12em;
  color: var(--muted);
}

.eyebrow {
  margin: 0 0 6px;
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: .24em;
  text-transform: uppercase;
  color: var(--a2);
}

.head h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -.01em;
}

.subtitle {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--muted);
}

.head {
  margin-bottom: 22px;
}

.foot {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  font-family: var(--mono);
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--muted);
}
</style>
