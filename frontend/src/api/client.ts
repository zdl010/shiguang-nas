/**
 * 后端 API 客户端。
 *
 * 两条安全约定，改动时务必保留：
 * 1. 所有请求带 `credentials: 'same-origin'` —— 会话靠 HttpOnly Cookie 承载，
 *    前端 JS 读不到它（这正是防 XSS 窃取会话的关键），必须让浏览器自动携带。
 * 2. 所有写操作带 `X-XSRF-TOKEN` 头 —— 服务端开了 CSRF 校验，缺这个头一律 403。
 */

export interface ApiErrorBody {
  error: string
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

/** 从 Cookie 里取 CSRF token。该 Cookie 刻意不是 HttpOnly，就是给前端读的。 */
function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function handle<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}

  if (!response.ok) {
    const body = payload as ApiErrorBody
    throw new ApiError(response.status, body.error ?? '请求失败')
  }
  return payload as T
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET' && method !== 'HEAD') {
    headers['X-XSRF-TOKEN'] = csrfToken()
  }

  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return handle<T>(response)
}

/** 上传分片走 multipart，不能设 Content-Type（要让浏览器自己带 boundary）。 */
async function postForm<T>(path: string, form: FormData): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
    credentials: 'same-origin',
    body: form,
  })
  return handle<T>(response)
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  postForm,
}

// ── 类型 ──────────────────────────────────────────────────────────────────

export interface SystemInfo {
  name: string
  version: string
  lanUrls: string[]
  tls: boolean
  /** 管理员还在用初始密码。登录页据此把初始账号密码显示出来。 */
  needsInitialPassword: boolean
}

export interface CurrentUser {
  id: number
  username: string
  displayName: string
  role: string
  /** 还在用初始密码。为 true 时后端会拒掉除改密外的所有接口。 */
  mustChangePassword: boolean
}

export type MediaKind = 'PHOTO' | 'VIDEO' | 'AUDIO'
export type ThumbState = 'PENDING' | 'READY' | 'FAILED' | 'NONE'

export interface MediaItem {
  id: number
  kind: MediaKind
  mime: string
  ext: string
  name: string
  size: number
  width: number | null
  height: number | null
  durationMs: number | null
  takenAt: number
  createdAt: number
  starred: boolean
  playable: boolean
  thumbState: ThumbState
  deletedAt: number | null
  rawUrl: string
  thumbUrl: string | null
  downloadUrl: string
}

export interface MediaPage {
  items: MediaItem[]
  nextCursor: string | null
}

export interface MediaCounts {
  all: number
  photo: number
  video: number
  audio: number
  fav: number
  trash: number
  usedBytes: number
  photoBytes: number
  videoBytes: number
  audioBytes: number
  chunkSize: number
}

export interface DeviceSession {
  id: string
  current: boolean
  ip: string | null
  device: string
  createdAt: number
  lastSeenAt: number
}

export interface ManagedUser {
  id: number
  username: string
  displayName: string
  role: string
  active: boolean
  createdAt: number
  lastLoginAt: number | null
}

export interface StorageInfo {
  path: string
  /** 配置里写的路径。与 path 不同时说明改过但还没重启。 */
  configuredPath: string
  restartPending: boolean
  writable: boolean
  diskTotal: number
  diskFree: number
  diskUsed: number
  mediaDir: string
  thumbDir: string
  tempDir: string
  dbDir: string
  logDir: string
  configDir: string
  counts: Record<string, number>
}

export interface SiteSettings {
  trashRetentionDays: number
}

// ── 接口 ──────────────────────────────────────────────────────────────────

export const systemApi = {
  info: () => api.get<SystemInfo>('/api/system/info'),
}

export const authApi = {
  login: (username: string, password: string) =>
    api.post<CurrentUser>('/api/auth/login', { username, password }),
  logout: () => api.post<{ ok: boolean }>('/api/auth/logout'),
  me: () => api.get<CurrentUser>('/api/auth/me'),
}

export const accountApi = {
  updateProfile: (displayName: string) =>
    api.post<{ ok: boolean; displayName: string }>('/api/account/profile', { displayName }),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<{ ok: boolean; revokedSessions: number }>('/api/account/password', {
      currentPassword,
      newPassword,
    }),
  sessions: () => api.get<DeviceSession[]>('/api/account/sessions'),
  revokeSession: (id: string) => api.del<{ ok: boolean }>(`/api/account/sessions/${id}`),
}

export const adminApi = {
  settings: () => api.get<SiteSettings>('/api/admin/settings'),
  updateSettings: (body: Partial<{ trashRetentionDays: number }>) =>
    api.post<SiteSettings>('/api/admin/settings', body),

  listUsers: () => api.get<ManagedUser[]>('/api/admin/users'),
  createUser: (username: string, displayName: string, password: string) =>
    api.post<ManagedUser>('/api/admin/users', { username, displayName, password }),
  resetPassword: (id: number, newPassword: string) =>
    api.post<{ ok: boolean }>(`/api/admin/users/${id}/password`, { newPassword }),
  setUserActive: (id: number, active: boolean) =>
    api.post<{ ok: boolean; active: boolean }>(`/api/admin/users/${id}/status`, { active }),

  storage: () => api.get<StorageInfo>('/api/admin/storage'),
  changeStorage: (path: string) =>
    api.post<{ ok: boolean; path: string; restartRequired: boolean; message: string }>(
      '/api/admin/storage',
      { path },
    ),
}

export type MediaView = 'all' | 'photo' | 'video' | 'audio' | 'fav' | 'trash'

export const mediaApi = {
  list: (params: { view?: MediaView; q?: string; cursor?: string | null; limit?: number }) => {
    const search = new URLSearchParams()
    if (params.view) search.set('view', params.view)
    if (params.q) search.set('q', params.q)
    if (params.cursor) search.set('cursor', params.cursor)
    if (params.limit) search.set('limit', String(params.limit))
    return api.get<MediaPage>(`/api/media?${search.toString()}`)
  },
  counts: () => api.get<MediaCounts>('/api/media/counts'),
  star: (ids: number[], starred: boolean) =>
    api.post<{ affected: number }>('/api/media/star', { ids, starred }),
  remove: (ids: number[]) => api.post<{ affected: number }>('/api/media/delete', { ids }),
  restore: (ids: number[]) => api.post<{ affected: number }>('/api/media/restore', { ids }),
  purge: (ids: number[]) => api.post<{ affected: number }>('/api/media/purge', { ids }),
}

export interface UploadInit {
  instant: boolean
  mediaId: number | null
  uploadId: string | null
  chunkSize: number
  chunkTotal: number
  receivedChunks: number[]
}

export const uploadApi = {
  init: (sha256: string, name: string, size: number) =>
    api.post<UploadInit>('/api/upload/init', { sha256, name, size }),
  chunk: (uploadId: string, index: number, blob: Blob) => {
    const form = new FormData()
    form.append('file', blob)
    return api.postForm<{ ok: boolean }>(
      `/api/upload/chunk?uploadId=${encodeURIComponent(uploadId)}&index=${index}`,
      form,
    )
  },
  complete: (uploadId: string) =>
    api.post<{ ok: boolean; mediaId: number }>('/api/upload/complete', { uploadId }),
  abort: (uploadId: string) => api.post<{ ok: boolean }>('/api/upload/abort', { uploadId }),
}
