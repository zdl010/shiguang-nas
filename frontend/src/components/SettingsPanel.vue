<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import QrCode from '@/components/QrCode.vue'
import SettingsCard from '@/components/SettingsCard.vue'
import {
  accountApi,
  adminApi,
  type DeviceSession,
  type ManagedUser,
  type SiteSettings,
  type StorageInfo,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'
import { useMediaStore } from '@/stores/media'
import { THEMES, applyTheme, currentTheme, type Theme } from '@/theme'
import { formatBytes, formatRelative } from '@/utils/format'

const emit = defineEmits<{
  toast: [message: string]
  display: [value: { density: string; showNames: boolean }]
}>()

const session = useSessionStore()
const media = useMediaStore()

const isAdmin = computed(() => session.user?.role === 'ADMIN')
const lanUrl = computed(() => session.system?.lanUrls?.[0] ?? window.location.origin)

// ── 主题 ────────────────────────────────────────────────────────────────
const theme = ref<Theme>(currentTheme())
/** 色板与名称取自 docs/prototype.html 的 THEMES 常量 */
const THEME_META: Record<Theme, { name: string; colors: string[]; bg: string }> = {
  nebula: { name: '星云', colors: ['#FF4D8D', '#46E3D5', '#FFC24B'], bg: 'linear-gradient(140deg,#2A1B45,#0D0918)' },
  abyss: { name: '深海', colors: ['#3CC7F0', '#5BE6B0', '#FFD166'], bg: 'linear-gradient(140deg,#0A2130,#04121B)' },
  graphite: { name: '石墨', colors: ['#FF7A45', '#8ED9C6', '#E5C05C'], bg: 'linear-gradient(140deg,#1A1A1C,#101011)' },
  moss: { name: '苔藓', colors: ['#E8734C', '#7BD48A', '#E3C56A'], bg: 'linear-gradient(140deg,#132019,#0B1410)' },
  oled: { name: '纯黑', colors: ['#FF375F', '#00E5A0', '#FFB400'], bg: 'linear-gradient(140deg,#0A0A0A,#000000)' },
  daylight: { name: '日光', colors: ['#DC2E6B', '#0C9E96', '#B07E00'], bg: 'linear-gradient(140deg,#FFFFFF,#E7E3F0)' },
}

function pickTheme(next: Theme) {
  theme.value = next
  applyTheme(next)
}

// ── 资料 ────────────────────────────────────────────────────────────────
const displayName = ref(session.user?.displayName ?? '')
const savingName = ref(false)

async function saveName() {
  savingName.value = true
  try {
    const result = await accountApi.updateProfile(displayName.value)
    if (session.user) session.user = { ...session.user, displayName: result.displayName }
    emit('toast', '显示名已更新')
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '保存失败')
  } finally {
    savingName.value = false
  }
}

// ── 改密码 ──────────────────────────────────────────────────────────────
const oldPassword = ref('')
const newPassword = ref('')
const changingPassword = ref(false)

async function changePassword() {
  changingPassword.value = true
  try {
    const result = await accountApi.changePassword(oldPassword.value, newPassword.value)
    oldPassword.value = ''
    newPassword.value = ''
    emit('toast', `密码已修改，同时下线了 ${result.revokedSessions} 台其他设备`)
    await loadSessions()
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '修改失败')
  } finally {
    changingPassword.value = false
  }
}

// ── 设备 ────────────────────────────────────────────────────────────────
const sessions = ref<DeviceSession[]>([])

async function loadSessions() {
  try {
    sessions.value = await accountApi.sessions()
  } catch {
    /* 忽略 */
  }
}

async function revoke(id: string) {
  try {
    await accountApi.revokeSession(id)
    await loadSessions()
    emit('toast', '该设备已下线')
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '操作失败')
  }
}

// ── 邀请码与站点设置（仅管理员）──────────────────────────────────────────
const siteSettings = ref<SiteSettings | null>(null)
const users = ref<ManagedUser[]>([])
const storageInfo = ref<StorageInfo | null>(null)

async function loadAdmin() {
  if (!isAdmin.value) return
  try {
    ;[siteSettings.value, users.value, storageInfo.value] = await Promise.all([
      adminApi.settings(),
      adminApi.listUsers(),
      adminApi.storage(),
    ])
  } catch {
    /* 忽略 */
  }
}

// ── 用户管理 ────────────────────────────────────────────────────────────
const newUser = ref({ username: '', displayName: '', password: '' })
const creatingUser = ref(false)
/** 正在重置密码的用户 id，null 表示没有面板展开 */
const resettingId = ref<number | null>(null)
const resetPassword = ref('')

async function createUser() {
  creatingUser.value = true
  try {
    await adminApi.createUser(
      newUser.value.username,
      newUser.value.displayName,
      newUser.value.password,
    )
    newUser.value = { username: '', displayName: '', password: '' }
    await loadAdmin()
    emit('toast', '用户已创建')
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '创建失败')
  } finally {
    creatingUser.value = false
  }
}

async function doResetPassword(id: number) {
  try {
    await adminApi.resetPassword(id, resetPassword.value)
    resettingId.value = null
    resetPassword.value = ''
    emit('toast', '密码已重置，请把新密码告诉本人')
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '重置失败')
  }
}

async function toggleUserActive(user: ManagedUser) {
  try {
    await adminApi.setUserActive(user.id, !user.active)
    await loadAdmin()
    emit('toast', user.active ? '已停用' : '已启用')
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '操作失败')
  }
}

// ── 存储根目录 ──────────────────────────────────────────────────────────
const editingRoot = ref(false)
const newRoot = ref('')

function startEditRoot() {
  newRoot.value = storageInfo.value?.path ?? ''
  editingRoot.value = true
}

async function saveRoot() {
  const path = newRoot.value.trim()
  if (!path) {
    emit('toast', '根目录不能为空')
    return
  }
  try {
    const result = await adminApi.changeStorage(path)
    editingRoot.value = false
    await loadAdmin()
    emit('toast', result.message)
  } catch (e) {
    emit('toast', e instanceof Error ? e.message : '保存失败')
  }
}

const diskPercent = computed(() => {
  const info = storageInfo.value
  if (!info || !info.diskTotal) return 0
  return Math.round((info.diskUsed / info.diskTotal) * 100)
})

// ── 网格密度 / 显示文件名 ────────────────────────────────────────────────
const density = ref<'compact' | 'standard' | 'loose'>(
  (localStorage.getItem('shiguang.density') as 'compact' | 'standard' | 'loose') ?? 'standard',
)
const showNames = ref(localStorage.getItem('shiguang.shownames') === '1')

function setDensity(value: 'compact' | 'standard' | 'loose') {
  density.value = value
  localStorage.setItem('shiguang.density', value)
  emit('display', { density: density.value, showNames: showNames.value })
}

function toggleNames() {
  showNames.value = !showNames.value
  localStorage.setItem('shiguang.shownames', showNames.value ? '1' : '0')
  emit('display', { density: density.value, showNames: showNames.value })
}

// ── 工具 ────────────────────────────────────────────────────────────────
async function copy(text: string) {
  try {
    // clipboard API 只在安全上下文可用；局域网 http 下要退回老办法
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    emit('toast', '已复制')
  } catch {
    emit('toast', '复制失败，请手动选中')
  }
}

const storage = computed(() => {
  const c = media.counts
  if (!c) return null
  const total = Math.max(1, c.usedBytes)
  return {
    used: c.usedBytes,
    photo: (c.photoBytes / total) * 100,
    video: (c.videoBytes / total) * 100,
    audio: (c.audioBytes / total) * 100,
  }
})

onMounted(() => {
  void loadSessions()
  void loadAdmin()
})
</script>

<template>
  <div class="setwrap">
    <!-- 局域网地址 + 二维码 -->
    <SettingsCard icon="link" title="在手机上打开" open>
      <p class="hint">让家人用手机扫这个码，或者直接在浏览器里输地址。必须连同一个 Wi-Fi。</p>
      <div class="lanrow">
        <QrCode :text="lanUrl" :size="152" />
        <div class="lanmeta">
          <div class="rootrow">
            <span class="p">{{ lanUrl }}</span>
            <span class="badge online">在线</span>
          </div>
          <button class="copybtn" type="button" @click="copy(lanUrl)">复制地址</button>
          <p class="hint" style="margin-top: 12px">
            Windows 首次启动会弹防火墙提示，必须勾选「专用网络」，否则手机连不上。
          </p>
        </div>
      </div>
    </SettingsCard>

    <!-- 存储 -->
    <SettingsCard v-if="storage" icon="disk" title="存储">
      <div class="rootbox">
        <div class="rootrow">
          <span class="p">{{ formatBytes(storage.used) }}</span>
          <span class="badge">已用</span>
        </div>
        <div class="cap">
          <div class="bar">
            <i :style="{ width: `${storage.photo}%`, background: 'var(--a1)' }" />
            <i :style="{ width: `${storage.video}%`, background: 'var(--a2)' }" />
            <i :style="{ width: `${storage.audio}%`, background: 'var(--a3)' }" />
          </div>
        </div>
        <div class="legend">
          <span><b style="background: var(--a1)" />照片 {{ formatBytes(media.counts?.photoBytes ?? 0) }}</span>
          <span><b style="background: var(--a2)" />视频 {{ formatBytes(media.counts?.videoBytes ?? 0) }}</span>
          <span><b style="background: var(--a3)" />音频 {{ formatBytes(media.counts?.audioBytes ?? 0) }}</span>
        </div>
      </div>
    </SettingsCard>

    <!-- 外观 -->
    <SettingsCard icon="palette" title="外观">
      <p class="hint">主题保存在这台设备上，不影响其他人。</p>
      <div class="skins">
        <button
          v-for="key in THEMES"
          :key="key"
          class="skin"
          type="button"
          role="radio"
          :aria-checked="theme === key"
          @click="pickTheme(key)"
        >
          <span class="swatch" :style="{ background: THEME_META[key].bg, color: THEME_META[key].colors[0] }">
            <i v-for="c in THEME_META[key].colors" :key="c" :style="{ background: c }" />
          </span>
          <span class="nm">
            {{ THEME_META[key].name }}
            <em>{{ key }}</em>
            <svg class="tick" viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></svg>
          </span>
        </button>
      </div>
      <div class="kv" style="margin-top: 16px">
        <span class="k">网格密度<small>一行显示多少张缩略图</small></span>
        <span class="v">
          <span class="seg">
            <button
              v-for="[key, label] in [['compact', '紧凑'], ['standard', '标准'], ['loose', '宽松']] as const"
              :key="key"
              type="button"
              :aria-selected="density === key"
              @click="setDensity(key)"
            >{{ label }}</button>
          </span>
        </span>
      </div>
      <div class="kv">
        <span class="k">在缩略图上显示文件名<small>方便对照磁盘里的原文件</small></span>
        <span class="v">
          <button class="switch" type="button" role="switch" :aria-checked="showNames" @click="toggleNames" />
        </span>
      </div>
    </SettingsCard>

    <!-- 存储根目录（仅管理员）-->
    <SettingsCard v-if="isAdmin && storageInfo" icon="disk" title="存储根目录">
      <p class="hint">
        只需要指定一个根目录。上传进来的文件由程序按内容哈希自动归档到子目录，不需要手动建文件夹。
      </p>
      <div class="rootbox">
        <div class="rootrow">
          <span class="p">{{ storageInfo.path }}</span>
          <span class="badge" :class="{ online: storageInfo.writable }">
            {{ storageInfo.writable ? '可写' : '不可写' }}
          </span>
          <button class="copybtn" type="button" @click="startEditRoot">更改</button>
        </div>
        <div class="cap">
          <div class="bar">
            <i
              :style="{
                width: diskPercent + '%',
                background: diskPercent > 90 ? 'var(--a1)' : 'linear-gradient(90deg,var(--a2),var(--a3))',
              }"
            />
          </div>
          <span class="txt">
            {{ formatBytes(storageInfo.diskUsed) }} / {{ formatBytes(storageInfo.diskTotal) }}
            · 剩余 {{ formatBytes(storageInfo.diskFree) }}
          </span>
        </div>
        <p v-if="storageInfo.restartPending" class="pending-root">
          已改为 <b>{{ storageInfo.configuredPath }}</b>，<b>重启后生效</b>。
          上面显示的是本进程当前仍在使用的目录。
        </p>
        <div v-if="editingRoot" class="editrow">
          <input v-model="newRoot" placeholder="/mnt/media  或  D:\Media" />
          <button type="button" @click="saveRoot">保存</button>
          <button class="ghost" type="button" @click="editingRoot = false">取消</button>
        </div>
        <p v-if="editingRoot" class="hint" style="margin: 10px 0 0">
          改完要重启才生效，而且<b>旧目录里的文件不会自动搬过去</b>——
          几百 GB 的跨盘复制中途断电就毁了，交给你自己用文件管理器搬更稳妥。
        </p>
        <div class="tree"><b>{{ storageInfo.path }}</b>
├── <em>{{ storageInfo.mediaDir }}</em>/        原文件 {{ storageInfo.counts.all ?? 0 }} 项 · 按内容哈希分目录
├── <em>{{ storageInfo.thumbDir }}</em>/        缩略图与封面帧缓存
├── <em>{{ storageInfo.tempDir }}</em>/         上传分片的临时区
├── <em>{{ storageInfo.dbDir }}</em>/           数据库 shiguang.db
└── <em>{{ storageInfo.logDir }}</em>/          运行日志

配置和密钥另存于 {{ storageInfo.configDir }}</div>
      </div>
    </SettingsCard>

    <!-- 账号 -->
    <SettingsCard icon="me" title="账号">
      <div class="kv">
        <span class="k">用户名<small>创建后不可修改</small></span>
        <span class="v">{{ session.user?.username }}</span>
      </div>
      <div class="kv">
        <span class="k">角色</span>
        <span class="v">{{ isAdmin ? '管理员' : '普通用户' }}</span>
      </div>
      <div class="editrow">
        <input v-model="displayName" placeholder="显示名" maxlength="32" />
        <button type="button" :disabled="savingName" @click="saveName">保存</button>
      </div>
    </SettingsCard>

    <!-- 密码 -->
    <SettingsCard icon="key" title="修改密码">
      <p class="hint">改完会自动把其他设备全部下线。密码没有找回渠道，请记牢。</p>
      <div class="editrow">
        <input v-model="oldPassword" type="password" placeholder="当前密码" autocomplete="current-password" />
      </div>
      <div class="editrow">
        <input v-model="newPassword" type="password" placeholder="新密码（至少 10 位）" autocomplete="new-password" />
        <button type="button" :disabled="changingPassword" @click="changePassword">修改</button>
      </div>
    </SettingsCard>

    <!-- 已登录设备 -->
    <SettingsCard icon="device" title="已登录设备">
      <p class="hint">看到不认识的设备就把它踢下线，然后改密码。</p>
      <div v-for="s in sessions" :key="s.id" class="kv">
        <span class="k">
          {{ s.device }}<small>{{ s.ip }} · 最后活跃 {{ formatRelative(s.lastSeenAt) }}</small>
        </span>
        <span class="v">
          <span v-if="s.current" class="badge online">当前设备</span>
          <button v-else class="copybtn danger" type="button" @click="revoke(s.id)">下线</button>
        </span>
      </div>
      <p v-if="sessions.length === 0" class="hint">暂无记录</p>
    </SettingsCard>

    <!-- 管理员 -->
    <template v-if="isAdmin">
      <SettingsCard icon="me" title="用户管理">
        <p class="hint">
          家人的账号由你在这里建，建完直接就能登录，新建的一律是普通用户——
          <b>admin 是这台机器上唯一的管理员</b>。重置密码后请把新密码亲口告诉本人，系统不会替你发送。
          停用是可逆的；刻意没有「删除用户」，因为删了之后他名下的媒体文件会变成
          谁也访问不到、谁也清理不掉的孤儿。
        </p>

        <div v-for="user in users" :key="user.id" class="userrow">
          <span class="av">{{ (user.displayName || user.username).slice(0, 1) }}</span>
          <span class="who">
            <b>{{ user.displayName }}</b>
            <small>
              {{ user.username }} · {{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}
              · {{ user.lastLoginAt ? '最后登录 ' + formatRelative(user.lastLoginAt) : '从未登录' }}
            </small>
          </span>
          <span class="acts">
            <span v-if="!user.active" class="badge">已停用</span>
            <button class="copybtn" type="button" @click="resettingId = resettingId === user.id ? null : user.id">
              重置密码
            </button>
            <button
              v-if="user.role !== 'ADMIN'"
              class="copybtn"
              :class="{ danger: user.active }"
              type="button"
              @click="toggleUserActive(user)"
            >
              {{ user.active ? '停用' : '启用' }}
            </button>
            <span v-else class="badge">唯一管理员</span>
          </span>
          <div v-if="resettingId === user.id" class="editrow" style="width: 100%">
            <input
              v-model="resetPassword"
              type="password"
              placeholder="新密码（至少 10 位，含两类字符）"
              autocomplete="new-password"
            />
            <button type="button" @click="doResetPassword(user.id)">确认重置</button>
          </div>
        </div>

        <div class="editrow" style="margin-top: 18px; border-top: 1px solid var(--line); padding-top: 18px">
          <input v-model="newUser.username" placeholder="用户名（3-20 位字母数字）" autocomplete="off" />
          <input v-model="newUser.displayName" placeholder="显示名" autocomplete="off" />
        </div>
        <div class="editrow">
          <input
            v-model="newUser.password"
            type="password"
            placeholder="初始密码（至少 10 位）"
            autocomplete="new-password"
          />
          <button type="button" :disabled="creatingUser" @click="createUser">新增用户</button>
        </div>
      </SettingsCard>

      <SettingsCard v-if="siteSettings" icon="trash" title="回收站">
        <div class="kv">
          <span class="k">保留天数<small>超过这个天数会自动彻底删除，无法恢复</small></span>
          <span class="v">{{ siteSettings.trashRetentionDays }} 天</span>
        </div>
        <div class="kv">
          <span class="k">单文件大小<small>没有上限，能传多大取决于这台机器还剩多少空间</small></span>
          <span class="v">{{ storageInfo ? '剩余 ' + formatBytes(storageInfo.diskFree) : '不限' }}</span>
        </div>
      </SettingsCard>
    </template>
  </div>
</template>

<style scoped>
.pending-root {
  margin: 12px 0 0;
  padding: 9px 12px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, var(--a3) 40%, transparent);
  background: color-mix(in srgb, var(--a3) 10%, transparent);
  font-size: 12px;
  line-height: 1.65;
}

.pending-root b {
  font-weight: 600;
  word-break: break-all;
}

.lanrow {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  align-items: flex-start;
}

.lanmeta {
  flex: 1;
  min-width: 200px;
}

.kv .k small {
  font-family: var(--mono);
}
</style>
