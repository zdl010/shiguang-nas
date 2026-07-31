/**
 * 主题切换。取值与 docs/prototype.html 的皮肤名一致。
 *
 * 六套皮肤全部对应 tokens.css 里的变量块，与 docs/prototype.html 一致。
 */
export const THEMES = ['nebula', 'abyss', 'graphite', 'moss', 'oled', 'daylight'] as const
export type Theme = (typeof THEMES)[number]

const STORAGE_KEY = 'shiguang.theme'
const DEFAULT_THEME: Theme = 'nebula'

/** 隐私模式 / 禁用存储时 localStorage 会抛异常，主题偏好丢了不影响使用 */
function safeRead(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function safeWrite(value: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    /* 忽略 */
  }
}

function isTheme(value: unknown): value is Theme {
  return typeof value === 'string' && (THEMES as readonly string[]).includes(value)
}

export function currentTheme(): Theme {
  const saved = safeRead()
  return isTheme(saved) ? saved : DEFAULT_THEME
}

export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme)
  safeWrite(theme)
}

/** 在 app.mount 之前调用，把存过的偏好写回 <html> */
export function initTheme(): void {
  document.documentElement.setAttribute('data-theme', currentTheme())
}
