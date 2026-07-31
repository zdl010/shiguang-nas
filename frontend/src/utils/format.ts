/** 展示用的格式化函数。 */

export function formatBytes(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / 1024 ** i
  return `${value >= 100 || i === 0 ? Math.round(value) : value.toFixed(1)} ${units[i]}`
}

export function formatDuration(ms: number | null | undefined): string {
  if (!ms || ms < 0) return '0:00'
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

export function formatRelative(timestamp: number): string {
  const diff = Date.now() - timestamp
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  if (diff < 30 * DAY) return `${Math.floor(diff / DAY)} 天前`
  return formatDate(timestamp)
}

export function formatDate(timestamp: number): string {
  const d = new Date(timestamp)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function formatDateTime(timestamp: number): string {
  const d = new Date(timestamp)
  return `${formatDate(timestamp)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 分组标题：今天 / 昨天 / 本周 / 具体月份。
 * 用本地时区计算——照片的"哪一天"是拍摄者所在时区的概念。
 */
export function groupLabel(timestamp: number): string {
  const date = new Date(timestamp)
  const today = new Date()
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()

  if (timestamp >= startOfToday) return '今天'
  if (timestamp >= startOfToday - DAY) return '昨天'
  if (timestamp >= startOfToday - 7 * DAY) return '本周'
  if (date.getFullYear() === today.getFullYear()) return `${date.getMonth() + 1} 月`
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月`
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/**
 * 没有缩略图时的占位渐变。
 *
 * <p>按 id 取色而不是随机：同一个文件每次进来颜色都一样，
 * 列表刷新时不会整片闪成另一套配色。
 */
const PLACEHOLDERS = [
  'radial-gradient(120% 90% at 18% 8%,#FFB05C,transparent 58%),radial-gradient(100% 80% at 82% 22%,#FF4D8D,transparent 55%),linear-gradient(#4B1E63,#150B25)',
  'radial-gradient(110% 80% at 30% 10%,#DFF3FF,transparent 60%),radial-gradient(90% 70% at 80% 70%,#6FA8E8,transparent 60%),linear-gradient(#2A3B6B,#101A33)',
  'radial-gradient(100% 80% at 25% 15%,#7BD48A,transparent 60%),radial-gradient(90% 70% at 78% 75%,#E3C56A,transparent 60%),linear-gradient(#1E5A40,#0B1410)',
  'radial-gradient(110% 85% at 20% 12%,#46E3D5,transparent 58%),radial-gradient(95% 75% at 82% 70%,#7A5CFF,transparent 60%),linear-gradient(#26205C,#0D0918)',
  'radial-gradient(120% 90% at 15% 10%,#FF7A45,transparent 55%),radial-gradient(90% 70% at 85% 80%,#FFC24B,transparent 60%),linear-gradient(#5A2A18,#160B08)',
]

export function placeholderArt(id: number): string {
  return PLACEHOLDERS[Math.abs(id) % PLACEHOLDERS.length]
}
