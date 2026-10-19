<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { AppInfo, Bookmark } from '@halone/sdk'
import AppIcon from '../shared/AppIcon.vue'
import AppTile from './AppTile.vue'
import Icon from '../shared/Icon.vue'
import NoHost from '../shared/NoHost.vue'
import { toUrl } from '../shared/address'
import { displayUrl } from '../shared/address'
import { useApps, useHost, useRecording, useSettings, useSystemInfo, useWindows } from '../shared/host'
import { showMenu } from '../shared/menu'

const { halone, status, error } = useHost()
const info = useSystemInfo(halone)
const windows = useWindows(halone)
const settings = useSettings(halone)
const recording = useRecording(halone)
const apps = useApps(halone)
const selfId = ref<string | null>(null)
const address = ref('')
const message = ref<string | null>(null)
const now = ref(new Date())

const tab = ref<'apps' | 'windows' | 'bookmarks'>('apps')
const bookmarks = ref<Bookmark[]>([])
const brokenIcons = ref(new Set<string>())

halone.bookmarks.list().then((list) => (bookmarks.value = list), () => {})
const offBookmarks = halone.on('bookmarks.changed', (list) => (bookmarks.value = list))
halone.connection.then((connection) => (selfId.value = connection.windowId), () => {})

const clock = setInterval(() => (now.value = new Date()), 1000)
const field = ref<HTMLInputElement>()

// Typing anywhere in the launcher goes to the address field, and Escape puts
// the launcher away: it sits over everything, so keys are meant for it.
function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    halone.launcher.setOpen(false).catch(() => {})
    return
  }
  const typing = event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey
  if (!typing || document.activeElement === field.value || !field.value) return
  event.preventDefault()
  field.value.focus()
  address.value += event.key
}
document.addEventListener('keydown', onKey)
onBeforeUnmount(() => {
  offBookmarks()
  clearInterval(clock)
  document.removeEventListener('keydown', onKey)
})

const time = computed(() => now.value.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))
const date = computed(() => now.value.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' }))
const launchable = computed(() => apps.value.filter((app) => app.id !== 'launcher'))
const others = computed(() => windows.value.filter((window) => window.id !== selfId.value))
const appFor = (appId: string | null) => apps.value.find((app) => app.id === appId)

async function run(action: () => Promise<unknown>) {
  try {
    message.value = null
    await action()
  } catch (caught) {
    message.value = caught instanceof Error ? caught.message : String(caught)
  }
}

function openAddress() {
  if (!address.value.trim()) return
  const url = toUrl(address.value, settings.value?.['browser.searchEngine'])
  run(async () => {
    await halone.windows.openUrl(url)
    address.value = ''
  })
}

const pinnedApps = computed(() => settings.value?.['dock.pinnedApps'] ?? [])

function togglePinned(appId: string) {
  const current = pinnedApps.value
  const next = current.includes(appId) ? current.filter((id) => id !== appId) : [...current, appId]
  run(() => halone.settings.set('dock.pinnedApps', next))
}

/** Long enough for the launcher to fade out, so the picture is of what is behind it. */
const OUT_OF_THE_WAY_MS = 400

function screenshot() {
  run(async () => {
    await halone.launcher.setOpen(false)
    await halone.screen.capture(OUT_OF_THE_WAY_MS)
  })
}

function toggleRecording() {
  run(async () => {
    if (recording.value.recording) return halone.screen.stopRecording()
    await halone.launcher.setOpen(false)
    await halone.screen.startRecording(OUT_OF_THE_WAY_MS)
  })
}

const iconOf = (bookmark: Bookmark) => (bookmark.iconUrl && !brokenIcons.value.has(bookmark.iconUrl) ? bookmark.iconUrl : null)

function markBroken(url: string) {
  brokenIcons.value = new Set(brokenIcons.value).add(url)
}

const openBookmark = (bookmark: Bookmark, isPrivate = false) => run(() => halone.browser.open(bookmark.url, isPrivate))

function bookmarkMenu(event: MouseEvent, bookmark: Bookmark) {
  showMenu(
    halone,
    [
      { id: 'open', label: 'Open', run: () => openBookmark(bookmark) },
      { id: 'open-private', label: 'Open in private tab', run: () => openBookmark(bookmark, true) },
      { id: 'copy', label: 'Copy address', run: () => halone.clipboard.writeText(bookmark.url) },
      'separator',
      { id: 'manage', label: 'Manage bookmarks', run: () => run(() => halone.apps.launch('library', 'bookmarks')) },
      { id: 'remove', label: 'Delete bookmark', danger: true, run: () => run(() => halone.bookmarks.remove(bookmark.id)) },
    ],
    event.clientX,
    event.clientY,
  ).catch(() => {})
}

function appMenu(event: MouseEvent, app: AppInfo) {
  const pinned = pinnedApps.value.includes(app.id)
  showMenu(
    halone,
    [
      { id: 'open', label: 'Open', run: () => run(() => halone.apps.launch(app.id)) },
      { id: 'pin', label: pinned ? 'Remove from the dock' : 'Keep in the dock', run: () => togglePinned(app.id) },
      ...(app.kind === 'web' ? [{ id: 'browser', label: 'Open in browser', run: () => run(() => halone.browser.open(app.entry)) }] : []),
      ...(app.kind === 'web' || app.kind === 'package'
        ? [
            'separator' as const,
            { id: 'uninstall', label: 'Uninstall', danger: true, run: () => run(() => halone.apps.uninstall(app.id)) },
          ]
        : []),
    ],
    event.clientX,
    event.clientY,
  ).catch(() => {})
}

function toggleFollow() {
  if (settings.value) run(() => halone.settings.set('follow.enabled', !settings.value!['follow.enabled']))
}
</script>

<template>
  <NoHost :status="status" :error="error" />
  <main v-if="status === 'ready'" class="launcher">
    <header>
      <div>
        <div class="time">{{ time }}</div>
        <div class="x-muted">{{ date }}</div>
      </div>
      <div class="quick">
        <button class="x-button" @click="run(() => halone.scene.recenter())"><Icon name="center_focus_strong" />Recenter</button>
        <button class="x-button" @click="screenshot"><Icon name="photo_camera" />Screenshot</button>
        <button class="x-button" :class="{ danger: recording.recording, primary: recording.recording }" @click="toggleRecording">
          <Icon :name="recording.recording ? 'stop_circle' : 'radio_button_checked'" />
          {{ recording.recording ? 'Stop recording' : 'Record' }}
        </button>
        <button v-if="settings" class="x-button" :class="{ primary: settings['follow.enabled'] }" @click="toggleFollow">
          <Icon :name="settings['follow.enabled'] ? 'moving' : 'keep'" />
          {{ settings['follow.enabled'] ? 'Following' : 'World-locked' }}
        </button>
        <button v-if="info?.platform === 'desktop'" class="x-button" @click="run(() => halone.input.releaseToDesktop())">
          <Icon name="swap_horiz" />Back to desktop
        </button>
      </div>
    </header>

    <form class="address" @submit.prevent="openAddress">
      <Icon class="address-icon" name="search" />
      <input ref="field" v-model="address" class="x-input" placeholder="Search or enter a web address" autocomplete="off" spellcheck="false" />
      <button class="x-button primary" type="submit">Open</button>
    </form>
    <p v-if="message" class="message">{{ message }}</p>

    <section>
      <div class="tabs" role="tablist">
        <button role="tab" :aria-selected="tab === 'apps'" :class="{ active: tab === 'apps' }" @click="tab = 'apps'">
          <Icon name="apps" />Apps
        </button>
        <button role="tab" :aria-selected="tab === 'windows'" :class="{ active: tab === 'windows' }" @click="tab = 'windows'">
          <Icon name="select_window" />Windows<span v-if="others.length" class="count">{{ others.length }}</span>
        </button>
        <button role="tab" :aria-selected="tab === 'bookmarks'" :class="{ active: tab === 'bookmarks' }" @click="tab = 'bookmarks'">
          <Icon name="bookmarks" />Bookmarks
        </button>
      </div>

      <div v-if="tab === 'bookmarks'" class="grid">
        <p v-if="!bookmarks.length" class="x-muted">No bookmarks yet. The star in a web window’s address field adds one.</p>
        <button
          v-for="bookmark in bookmarks"
          :key="bookmark.id"
          class="tile bookmark"
          @click="openBookmark(bookmark)"
          @contextmenu.prevent="bookmarkMenu($event, bookmark)"
        >
          <img v-if="iconOf(bookmark)" class="favicon" :src="iconOf(bookmark)!" alt="" @error="markBroken(bookmark.iconUrl!)" />
          <span v-else class="favicon letter">{{ bookmark.title.slice(0, 1).toUpperCase() }}</span>
          <span class="name">{{ bookmark.title }}</span>
          <span class="description x-muted">{{ displayUrl(bookmark.url) }}</span>
        </button>
      </div>

      <div v-else-if="tab === 'windows'" class="windows">
        <p v-if="others.length === 0" class="x-muted">Nothing else is open.</p>
        <div v-for="window in others" :key="window.id" class="window" :class="{ focused: window.focused }">
          <AppIcon class="window-icon" :app="appFor(window.appId)" />
          <span class="window-title">
            {{ window.title }}
            <span class="x-muted meta">{{ window.pinned ? 'World-locked' : 'Following' }}</span>
          </span>
          <button class="x-button small" @click="run(() => halone.windows.focus(window.id))"><Icon name="visibility" />Show</button>
          <button class="x-button small" @click="run(() => halone.windows.setPinned(!window.pinned, window.id))">
            <Icon :name="window.pinned ? 'moving' : 'keep'" />{{ window.pinned ? 'Follow me' : 'Lock here' }}
          </button>
          <button class="x-button small danger" @click="run(() => halone.windows.close(window.id))"><Icon name="close" />Close</button>
        </div>
      </div>

      <div v-else class="grid apps">
        <AppTile
          v-for="app in launchable"
          :key="app.id"
          :app="app"
          :pinned="pinnedApps.includes(app.id)"
          @open="run(() => halone.apps.launch(app.id))"
          @menu="appMenu($event, app)"
          @toggle-pin="togglePinned(app.id)"
        />
      </div>
    </section>
  </main>
</template>

<style scoped>
.launcher {
  display: flex;
  flex-direction: column;
  gap: 28px;
  height: 100%;
  padding: 40px 48px;
  overflow-y: auto;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.time {
  font-size: 64px;
  font-weight: 300;
  line-height: 1;
  margin-bottom: 6px;
}

.quick {
  display: flex;
  gap: 12px;
}

.address {
  position: relative;
  display: flex;
  gap: 12px;
}

.address-icon {
  position: absolute;
  top: 50%;
  left: 26px;
  transform: translateY(-50%);
  color: var(--text-muted);
  pointer-events: none;
}

.address .x-input {
  flex: 1;
  font-size: var(--font-size-large);
  min-height: 72px;
  border-radius: 36px;
  padding: 0 32px 0 72px;
}

.address .x-button {
  min-height: 72px;
  border-radius: 36px;
  padding: 0 36px;
}

.message {
  margin: -12px 0 0;
  color: var(--danger);
  font-size: var(--font-size-small);
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.tabs button {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 22px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted);
  font-size: var(--font-size-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  cursor: pointer;
}

.tabs button.active {
  background: var(--surface-raised);
  color: var(--text);
}

.count {
  min-width: 26px;
  padding: 0 8px;
  border-radius: 13px;
  background: var(--accent);
  color: #000;
  font-size: 15px;
  letter-spacing: 0;
  line-height: 26px;
  text-align: center;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}

.grid.apps {
  grid-template-columns: repeat(auto-fill, minmax(220px, 260px));
}

.tile {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  text-align: left;
  cursor: pointer;
  transition: border-color 120ms, background 120ms;
}

.tile:hover {
  border-color: var(--accent);
  background: var(--surface-raised);
}

.bookmark .name,
.bookmark .description {
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.name {
  font-size: var(--font-size-large);
  font-weight: 500;
}

.description {
  font-size: var(--font-size-small);
}

.favicon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  object-fit: contain;
}

.favicon.letter {
  display: grid;
  place-items: center;
  background: var(--surface-raised);
  font-size: 26px;
  font-weight: 600;
}

.window {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-small);
  background: var(--surface);
}

.window.focused {
  border-color: var(--accent);
}

.window-icon {
  font-size: 40px;
}

.window-title {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.meta {
  margin-left: 10px;
  font-size: var(--font-size-small);
}
</style>
