import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const routes: RouteRecordRaw[] = [
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/ChangePasswordView.vue'),
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    name: 'library',
    component: () => import('@/views/LibraryView.vue'),
  },
  // 兜底：未知路径回首页，避免直接白屏
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 路由守卫。
 *
 * 它只负责"少让用户看到无意义的页面"，**不承担安全职责** ——
 * 绕过守卫直接访问 /（比如改 JS）依然拿不到任何数据，
 * 因为每个 /api/** 请求都由服务端独立鉴权。
 */
router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.ready) {
    await session.bootstrap()
  }

  // 还在用初始密码时，除了改密页哪儿都不让去。
  // 这只是体验层的引导——真正的拦截在后端 MustChangePasswordFilter。
  if (session.user && session.mustChangePassword) {
    return to.name === 'change-password' ? true : { name: 'change-password' }
  }
  if (to.name === 'change-password' && !session.mustChangePassword) {
    return session.user ? { name: 'library' } : { name: 'login' }
  }

  if (!to.meta.public && !session.user) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { next: to.fullPath } }
  }

  if (to.name === 'login' && session.user) {
    return { name: 'library' }
  }

  return true
})
