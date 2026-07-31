import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  mediaApi,
  type MediaCounts,
  type MediaItem,
  type MediaView,
} from '@/api/client'

const PAGE_SIZE = 20

/** 媒体列表、计数与多选状态。 */
export const useMediaStore = defineStore('media', () => {
  const view = ref<MediaView>('all')
  const keyword = ref('')
  const items = ref<MediaItem[]>([])
  const cursor = ref<string | null>(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const error = ref('')
  const counts = ref<MediaCounts | null>(null)

  const selecting = ref(false)
  const selected = ref<Set<number>>(new Set())

  /**
   * 每次切换视图/搜索都自增。异步返回时对不上就丢弃结果——
   * 没有这个的话，快速连点侧边栏会让先发的慢请求把后发的结果覆盖掉。
   */
  let requestId = 0

  const selectedIds = computed(() => Array.from(selected.value))
  const selectedCount = computed(() => selected.value.size)

  async function reload(): Promise<void> {
    const token = ++requestId
    loading.value = true
    error.value = ''
    cursor.value = null
    hasMore.value = true
    try {
      const page = await mediaApi.list({
        view: view.value,
        q: keyword.value || undefined,
        limit: PAGE_SIZE,
      })
      if (token !== requestId) return
      items.value = page.items
      cursor.value = page.nextCursor
      hasMore.value = page.nextCursor !== null
    } catch (e) {
      if (token !== requestId) return
      error.value = e instanceof Error ? e.message : '加载失败'
    } finally {
      if (token === requestId) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    if (loading.value || !hasMore.value || !cursor.value) return
    const token = requestId
    loading.value = true
    try {
      const page = await mediaApi.list({
        view: view.value,
        q: keyword.value || undefined,
        cursor: cursor.value,
        limit: PAGE_SIZE,
      })
      if (token !== requestId) return
      items.value = [...items.value, ...page.items]
      cursor.value = page.nextCursor
      hasMore.value = page.nextCursor !== null
    } catch (e) {
      if (token === requestId) error.value = e instanceof Error ? e.message : '加载失败'
    } finally {
      if (token === requestId) loading.value = false
    }
  }

  async function refreshCounts(): Promise<void> {
    try {
      counts.value = await mediaApi.counts()
    } catch {
      // 角标数字加载失败不该打断主流程
    }
  }

  async function switchView(next: MediaView): Promise<void> {
    if (view.value === next) return
    view.value = next
    clearSelection()
    await reload()
  }

  async function search(text: string): Promise<void> {
    keyword.value = text
    await reload()
  }

  // ── 多选 ────────────────────────────────────────────────────────────

  function toggleSelecting(): void {
    selecting.value = !selecting.value
    if (!selecting.value) selected.value = new Set()
  }

  function toggle(id: number): void {
    // Set 是响应式的，但原地 add/delete 不会触发依赖更新，必须换一个新实例
    const next = new Set(selected.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    selected.value = next
  }

  function clearSelection(): void {
    selecting.value = false
    selected.value = new Set()
  }

  // ── 操作 ────────────────────────────────────────────────────────────

  async function star(ids: number[], starred: boolean): Promise<void> {
    await mediaApi.star(ids, starred)
    const affected = new Set(ids)
    items.value = items.value.map((item) =>
      affected.has(item.id) ? { ...item, starred } : item,
    )
    // 在"已标星"视图里取消标星，条目应当立刻消失
    if (view.value === 'fav' && !starred) {
      items.value = items.value.filter((item) => !affected.has(item.id))
    }
    await refreshCounts()
  }

  async function remove(ids: number[]): Promise<void> {
    await mediaApi.remove(ids)
    dropLocally(ids)
    await refreshCounts()
  }

  async function restore(ids: number[]): Promise<void> {
    await mediaApi.restore(ids)
    dropLocally(ids)
    await refreshCounts()
  }

  async function purge(ids: number[]): Promise<void> {
    await mediaApi.purge(ids)
    dropLocally(ids)
    await refreshCounts()
  }

  /** 从当前列表里移除（服务端已处理完），避免为了一次操作整页重载。 */
  function dropLocally(ids: number[]): void {
    const gone = new Set(ids)
    items.value = items.value.filter((item) => !gone.has(item.id))
    const next = new Set(selected.value)
    ids.forEach((id) => next.delete(id))
    selected.value = next
  }

  /** 上传完成后调用。直接重载而不是插入，才能拿到服务端生成的签名 URL。 */
  async function refreshAfterUpload(): Promise<void> {
    await Promise.all([reload(), refreshCounts()])
  }

  return {
    view,
    keyword,
    items,
    hasMore,
    loading,
    error,
    counts,
    selecting,
    selected,
    selectedIds,
    selectedCount,
    reload,
    loadMore,
    refreshCounts,
    switchView,
    search,
    toggleSelecting,
    toggle,
    clearSelection,
    star,
    remove,
    restore,
    purge,
    refreshAfterUpload,
  }
})
