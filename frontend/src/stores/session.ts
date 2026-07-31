import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError, authApi, systemApi, type CurrentUser, type SystemInfo } from '@/api/client'

/**
 * 全局会话状态。
 *
 * 这里存的 `user` 只是服务端已认证事实的一份缓存，**不是权限来源**。
 * 真正的授权判定永远在服务端 —— 前端把 user 改成 admin 也拿不到任何数据。
 */
export const useSessionStore = defineStore('session', () => {
  const system = ref<SystemInfo | null>(null)
  const user = ref<CurrentUser | null>(null)
  /** bootstrap 是否至少成功跑过一次，路由守卫据此决定要不要等待 */
  const ready = ref(false)
  const bootError = ref('')

  let inflight: Promise<void> | null = null

  /**
   * 拉取系统信息与当前登录态。并发调用会复用同一个 Promise，
   * 避免刷新页面时路由守卫和组件各发一轮请求。
   */
  function bootstrap(): Promise<void> {
    if (inflight) {
      return inflight
    }
    inflight = (async () => {
      try {
        system.value = await systemApi.info()
        bootError.value = ''
      } catch (error) {
        bootError.value = error instanceof Error ? error.message : '无法连接到服务端'
      }

      try {
        user.value = await authApi.me()
      } catch (error) {
        // 401 是未登录的正常态，不是故障，不要往 bootError 里写
        if (!(error instanceof ApiError && error.status === 401)) {
          bootError.value = error instanceof Error ? error.message : '无法确认登录状态'
        }
        user.value = null
      }

      ready.value = true
    })().finally(() => {
      inflight = null
    })
    return inflight
  }

  async function login(username: string, password: string): Promise<void> {
    user.value = await authApi.login(username, password)
    system.value = await systemApi.info()
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      // 即使服务端返回失败也要清掉本地状态，不能让界面停在"已登录"的假象上
      user.value = null
      // 顺手刷新系统信息：needsInitialPassword 是开机时拉的，
      // 用户在这次会话里改过密码的话它已经过期了——不刷新的话，
      // 退出后登录页会继续显示"首次使用 admin/admin"，误导得很厉害
      try {
        system.value = await systemApi.info()
      } catch {
        /* 刷新失败不影响登出本身 */
      }
    }
  }

  async function refreshSystem(): Promise<void> {
    system.value = await systemApi.info()
  }

  /** 改完密码后必须重新拉一次，否则 mustChangePassword 还是旧值，会被守卫弹回改密页 */
  async function refreshUser(): Promise<void> {
    user.value = await authApi.me()
  }

  /** 还在用初始密码。为 true 时除了改密页哪儿都去不了。 */
  const mustChangePassword = computed(() => user.value?.mustChangePassword === true)

  return {
    system, user, ready, bootError, mustChangePassword,
    bootstrap, login, logout, refreshSystem, refreshUser,
  }
})
