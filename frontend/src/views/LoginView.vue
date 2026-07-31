<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '@/components/AuthShell.vue'
import { ApiError } from '@/api/client'
import { useSessionStore } from '@/stores/session'
import '@/styles/form.css'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref('')

const justCreated = computed(() => route.query.created === '1')
const lanUrls = computed(() => session.system?.lanUrls ?? [])
/** 还没改过初始密码时，把账号密码直接写在页面上——控制台日志双击启动的人看不到 */
const showInitialHint = computed(() => session.system?.needsInitialPassword === true)

/** 一键填好，省得手打错（前面就有人把 admin 打成了 admin1） */
function fillInitial() {
  username.value = 'admin'
  password.value = 'admin'
}

async function submit() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await session.login(username.value.trim(), password.value)
    // 无论成败都不要把密码留在内存里
    password.value = ''

    // 还在用初始密码就先去改密，其余情况按 next 走
    if (session.mustChangePassword) {
      await router.replace({ name: 'change-password' })
      return
    }

    // next 只接受站内绝对路径。放开的话 //evil.com 这类值会变成开放重定向，
    // 把带登录态的用户送到钓鱼站。
    const next = route.query.next
    const target = typeof next === 'string' && /^\/(?!\/)/.test(next) ? next : '/'
    await router.replace(target)
  } catch (e) {
    // 限流时保留已填的用户名密码：这时输入通常是对的，只是要等一会儿，
    // 清掉让人以为是自己打错了，反而会再试几次把锁定时间翻倍
    if (e instanceof ApiError && e.status === 429) {
      error.value = e.message
    } else {
      password.value = ''
      error.value = e instanceof Error ? e.message : '登录失败'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell eyebrow="Sign in" title="欢迎回到拾光" subtitle="用你的账号登录，查看这台机器里的照片、视频和录音。">
    <div v-if="showInitialHint" class="notice initial">
      <b>首次使用</b>
      <p>
        用户名 <code>admin</code>，密码 <code>admin</code>。登录后会要求你立刻改成一个复杂密码。
      </p>
      <button type="button" @click="fillInitial">自动填入</button>
    </div>
    <p v-if="justCreated && !error" class="notice">账号已创建，用它登录即可。</p>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <form novalidate @submit.prevent="submit">
      <label class="field">
        <span>用户名</span>
        <input
          v-model="username"
          type="text"
          autocomplete="username"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          :disabled="submitting"
          required
        />
      </label>

      <label class="field">
        <span>密码</span>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          :disabled="submitting"
          required
        />
      </label>

      <button class="submit" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>

    <p class="hint tip">连续登录失败会被临时锁定（按 IP 和用户名分别计数），稍等片刻再试即可。</p>

    <template #footer>
      <p v-if="lanUrls.length">
        <span class="dot" aria-hidden="true"></span>
        局域网地址
      </p>
      <p v-for="url in lanUrls" :key="url" class="url">{{ url }}</p>
    </template>
  </AuthShell>
</template>

<style scoped>
.initial {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.initial b {
  font-weight: 600;
}

.initial p {
  margin: 0;
}

.initial code {
  font-family: var(--mono);
  font-size: .95em;
  padding: 1px 5px;
  border-radius: 5px;
  background: var(--raise2);
}

.initial button {
  align-self: stretch;
  padding: 8px;
  border: 1px solid var(--line2);
  border-radius: 10px;
  background: var(--raise);
  color: inherit;
  font-size: 12.5px;
  cursor: pointer;
}

.initial button:hover {
  background: var(--raise2);
}

.tip {
  margin-top: 12px;
  text-align: center;
}

.dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--a2);
  box-shadow: 0 0 9px var(--a2);
}

.url {
  margin: 2px 0 0;
  color: var(--text);
  word-break: break-all;
}
</style>
