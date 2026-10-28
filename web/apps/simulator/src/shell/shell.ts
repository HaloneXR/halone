import { reactive } from 'vue'
import {
  HaloneError,
  type AppInfo,
  type Battery,
  type BrowserState,
  type ClipboardFiles,
  type DisplayRate,
  type DisplayState,
  type MenuItem,
  type ResizeAnchor,
  type StereoRegion,
  type SettingKey,
  type Settings,
  type WindowId,
  type WindowInfo,
  type WindowKind,
} from '@halone/sdk'
import { mimeMatches } from '@halone/sdk/files'
import { SimulatorScene } from '@halone/scene'
import { panelDistancePx, pointerAngles, wrapDegrees } from './geometry'
import { declaredLimits, defaultLimits, type SizeLimits } from './limits'
import { validateSetting } from './settings'
import { SimBrowsing } from './browsing'
import { parseManifest, SimWebApps, toAppInfo, type WebApp } from './webapps'
import { originOf, SimPackageApps, toAppInfo as packageAppInfo, type SimPackageApp } from './packages'
import type { CheckedManifest } from '@halone/sdk/package'

export interface SimTab {
  id: number
  title: string
  /** Where the tab is; empty for a new tab waiting to be told. */
  url: string
  history: string[]
  index: number
  /** Bumped to make the page load again. */
  reloads: number
  private: boolean
  /** The page's web app manifest, processed as Gecko does, once found. */
  manifest: Record<string, unknown> | null
}

export interface SimBrowser {
  tabs: SimTab[]
  activeTab: number
  nextTab: number
}

export interface SimWindow {
  id: WindowId
  kind: WindowKind
  appId: string | null
  title: string
  url: string
  widthPx: number
  /** The window's height; in a web window the browser chrome's band and the page share it. */
  heightPx: number
  chromePx: number
  browser: SimBrowser | null
  sizeLimits: SizeLimits
  /** What the page declares, which [sizeLimits] goes back to when an override is cleared. */
  declaredLimits: SizeLimits
  stereoRegion: StereoRegion | null
  /** The file the window was last asked to open. */
  openedFile: string | null
  /** Where `apps.launch` last asked the window to go. */
  route: string | null
  /** An installed web app's window: a page without chrome or the bridge. */
  standalone: boolean
  /** A package app's own origin, the only one its frame gets the bridge on; null for the shell's own apps. */
  origin: string | null
  pinned: boolean
  handPlaced: boolean
  slotDegrees: number
  yawDegrees: number
  pitchDegrees: number
}

export interface SimOverlay {
  id: string
  url: string
  widthPx: number
  heightPx: number
  yawDegrees: number
  pitchDegrees: number
  opacity: number
  shown: boolean
}

export interface ShellOptions {
  apps: AppInfo[]
  appsOrigin: string
  settings: Settings
  persistSettings?: (settings: Settings) => void
  browsing?: SimBrowsing
  webApps?: SimWebApps
  packageApps?: SimPackageApps
  /** Finds a page's processed manifest; the dev server's by default. */
  findManifest?: (url: string) => Promise<Record<string, unknown> | null>
}

/** A context menu over a window or an overlay, at its pixel ([xPx], [yPx]); [shown] once the menu page has drawn it. */
export interface SimMenu {
  menuId: number
  items: MenuItem[]
  sourceId: string
  xPx: number
  yPx: number
  widthPx: number
  heightPx: number
  shown: boolean
}

type ShellEvent =
  | 'windows'
  | 'settings'
  | 'focus'
  | 'launcher'
  | 'battery'
  | 'display'
  | 'browser'
  | 'file'
  | 'closed'
  | 'routed'
  | 'menu'
  | 'apps'

/** What an Air 2 Pro offers. */
const DISPLAY_RATES: DisplayRate[] = [
  { hz: 60, stereo: true, matchesPhone: true },
  { hz: 72, stereo: true, matchesPhone: false },
  { hz: 90, stereo: true, matchesPhone: false },
  { hz: 120, stereo: false, matchesPhone: true },
]

/** The browser page's rows, as on the glasses: the toolbar, then the tab row. */
const TOOLBAR_PX = 76
const TABS_PX = 52

const DOCK = 'dock'
const LAUNCHER = 'launcher'
export const MENU = 'menu'
const OVERLAYS = [DOCK, LAUNCHER, MENU]

/** The menu overlay's size for its items, as `MenuLayout` (core-shell) works it out. */
export function menuSize(items: MenuItem[]) {
  const longest = Math.max(0, ...items.map((item) => ('separator' in item ? 0 : item.label.length)))
  return {
    widthPx: Math.min(480, Math.max(240, Math.trunc(longest * 10.5 + 56))),
    heightPx: Math.min(560, 16 + items.reduce((sum, item) => sum + ('separator' in item ? 17 : 48), 0)),
  }
}

const WEB_WINDOW_SIZE = { widthPx: 1280, heightPx: 800 }
const PITCH_LIMIT_DEGREES = 80
const CLICK_SLOP_DEGREES = 1

// The scene keeps core-compositor's convention, positive yaw to the left; the
// simulator and the protocol use positive to the right, as Shell.kt does.
const toScene = (yawDegrees: number) => -yawDegrees
const fromScene = (yawDegrees: number) => wrapDegrees(-yawDegrees)

export class Shell {
  readonly state
  readonly scene = new SimulatorScene(panelDistancePx)
  private nextWindow = 1
  /** Windows that have asked for the auto-hide; it lasts as long as they do. */
  private readonly autoHideRequests = new Set<WindowId>()
  private pointer: { dx: number; dy: number } | null = null
  private grabAim = { yawDegrees: 0, pitchDegrees: 0 }
  private grabStart = { yawDegrees: 0, pitchDegrees: 0 }
  private readonly listeners = new Map<ShellEvent, Set<(id?: WindowId) => void>>()
  readonly browsing: SimBrowsing
  readonly webApps: SimWebApps
  readonly packageApps: SimPackageApps
  private lastWebWindow: WindowId | null = null
  private nextMenu = 1
  private menuAnswer: ((itemId: string | null) => void) | null = null
  private clipboardText: string | null = null
  private clipboardFiles: ClipboardFiles | null = null
  private clipboardFilesText: string | null = null

  constructor(private readonly options: ShellOptions) {
    this.browsing = options.browsing ?? new SimBrowsing()
    this.webApps = options.webApps ?? new SimWebApps()
    this.packageApps = options.packageApps ?? new SimPackageApps()
    this.state = reactive({
      head: { yawDegrees: 0, pitchDegrees: 0 },
      anchorYawDegrees: 0,
      returning: false,
      windows: [] as SimWindow[],
      focusedId: null as WindowId | null,
      grabbedId: null as WindowId | null,
      overlays: [] as SimOverlay[],
      launcherOpen: false,
      windowsOpacity: 1,
      battery: null as Battery | null,
      menu: null as SimMenu | null,
      settings: { ...options.settings },
      apps: [] as AppInfo[],
    })
    this.state.apps = this.allApps()
    this.applySettings()
    const appsChanged = () => {
      this.state.apps = this.allApps()
      this.emit('apps')
      for (const id of this.webWindowIds()) this.emit('browser', id)
    }
    this.webApps.onChange(appsChanged)
    this.packageApps.onChange(appsChanged)
  }

  /** The system apps, then the sites installed as apps, then the Halone apps. */
  private allApps(): AppInfo[] {
    return [...this.options.apps, ...this.webApps.apps.map(toAppInfo), ...this.packageApps.apps.map(packageAppInfo)]
  }

  /** Ids no package may take. */
  get reservedIds() {
    return this.options.apps.map((app) => app.id)
  }

  /** What the bridge lets window [id] do: null for the shell's own windows, a package's grant for its. */
  grantOf(id: WindowId) {
    const window = this.state.windows.find((w) => w.id === id)
    return this.packageApps.find(window?.appId)?.granted ?? null
  }

  on(event: ShellEvent, listener: (id?: WindowId) => void) {
    let set = this.listeners.get(event)
    if (!set) this.listeners.set(event, (set = new Set()))
    set.add(listener)
    return () => set.delete(listener)
  }

  private emit(event: ShellEvent, id?: WindowId) {
    for (const listener of this.listeners.get(event) ?? []) listener(id)
  }

  find(id: WindowId): SimWindow {
    const window = this.state.windows.find((w) => w.id === id)
    if (!window) throw new HaloneError('no-such-window', `no such window: ${id}`)
    return window
  }

  info(window: SimWindow): WindowInfo {
    return {
      id: window.id,
      kind: window.kind,
      appId: window.appId,
      title: window.title,
      url: window.url,
      yawDegrees: window.yawDegrees,
      pitchDegrees: window.pitchDegrees,
      widthPx: window.widthPx,
      heightPx: window.heightPx,
      pinned: window.pinned,
      focused: window.id === this.state.focusedId,
    }
  }

  list(): WindowInfo[] {
    return this.state.windows.map((w) => this.info(w))
  }

  openOverlays() {
    const dock = this.state.apps.find((a) => a.id === DOCK)
    const launcher = this.state.apps.find((a) => a.id === LAUNCHER)
    if (!dock || !launcher) return
    this.scene.openOverlays(dock.id, dock.widthPx, dock.heightPx, launcher.id, launcher.widthPx, launcher.heightPx)
    this.state.overlays = [dock, launcher].map((app) => ({
      id: app.id,
      url: new URL(app.entry, this.options.appsOrigin).href,
      widthPx: app.widthPx,
      heightPx: app.heightPx,
      yawDegrees: 0,
      pitchDegrees: 0,
      opacity: 0,
      shown: false,
    }))
    this.sync()
  }

  setLauncherOpen(open: boolean) {
    this.scene.launcherRequested = open
    this.sync()
  }

  setBattery(battery: Battery) {
    this.state.battery = battery
    this.emit('battery')
  }

  launch(appId: string, route?: string): SimWindow {
    if (OVERLAYS.includes(appId)) throw new HaloneError('no-such-app', `${appId} is part of the shell, not an app to launch`)
    const webApp = this.webApps.find(appId)
    if (webApp) return this.launchWebApp(webApp, route)
    const packageApp = this.packageApps.find(appId)
    if (packageApp) return this.launchPackageApp(packageApp, route)
    const app = this.state.apps.find((a) => a.id === appId)
    if (!app) throw new HaloneError('no-such-app', `no such app: ${appId}`)
    const existing = this.state.windows.find((w) => w.appId === appId)
    if (existing) {
      if (route) {
        existing.route = route
        this.emit('routed', existing.id)
      }
      this.focus(existing.id)
      return existing
    }
    const window = this.open({
      kind: 'app',
      appId,
      title: app.name,
      url: new URL(app.entry, this.options.appsOrigin).href,
      widthPx: app.widthPx,
      heightPx: app.heightPx,
    })
    window.route = route ?? null
    const defaults = window.sizeLimits
    fetch(window.url)
      .then((response) => response.text())
      .then((html) => {
        window.declaredLimits = declaredLimits(html, defaults)
        window.sizeLimits = window.declaredLimits
      })
      .catch(() => {})
    return window
  }

  /** Opens a web app's window, or focuses it; [url], inside the app, is where it goes instead of its start. */
  private launchWebApp(app: WebApp, url?: string): SimWindow {
    const target = url?.startsWith(app.scope) ? url : undefined
    const existing = this.state.windows.find((w) => w.appId === app.id)
    if (existing) {
      if (target) existing.url = target
      this.focus(existing.id)
      return existing
    }
    const window = this.open({ kind: 'app', appId: app.id, title: app.name, url: target ?? app.startUrl, ...WEB_WINDOW_SIZE })
    window.standalone = true
    window.sizeLimits = { minWidthPx: 400, minHeightPx: 600, maxWidthPx: 3200, maxHeightPx: 2000 }
    window.declaredLimits = window.sizeLimits
    return window
  }

  private launchPackageApp(app: SimPackageApp, route?: string): SimWindow {
    const existing = this.state.windows.find((w) => w.appId === app.manifest.id)
    if (existing) {
      if (route) {
        existing.route = route
        this.emit('routed', existing.id)
      }
      this.focus(existing.id)
      return existing
    }
    const { width, height } = app.manifest.window
    const window = this.open({ kind: 'app', appId: app.manifest.id, title: app.manifest.name, url: app.entryUrl, widthPx: width, heightPx: height })
    window.origin = originOf(app.entryUrl)
    window.route = route ?? null
    return window
  }

  /** A developer's app, loaded from their server; only while developer mode is on. */
  installDevApp(manifest: CheckedManifest, entryUrl: string): SimWindow {
    if (!this.state.settings['developer.enabled']) throw new HaloneError('developer-mode-off', 'turn on developer mode in Settings first')
    for (const window of this.state.windows.filter((w) => w.appId === manifest.id)) this.close(window.id)
    this.packageApps.install(manifest, entryUrl, true)
    return this.launch(manifest.id)
  }

  /** The active page of web window [id] as an app that can be installed. */
  installableApp(id: WindowId) {
    return parseManifest(this.activeTab(this.browserOf(id)).manifest)
  }

  installWebApp(id: WindowId): AppInfo {
    const manifest = this.installableApp(id)
    if (!manifest) throw new HaloneError('not-installable', 'this page cannot be installed as an app')
    return toAppInfo(this.webApps.install(manifest))
  }

  uninstall(appId: string) {
    if (!this.webApps.find(appId) && !this.packageApps.find(appId)) {
      const known = this.options.apps.some((app) => app.id === appId)
      throw new HaloneError(known ? 'not-removable' : 'no-such-app', known ? `${appId} is part of the system` : `no such app: ${appId}`)
    }
    for (const window of this.state.windows.filter((w) => w.appId === appId)) this.close(window.id)
    const pinned = this.state.settings['dock.pinnedApps']
    if (pinned.includes(appId)) this.setSetting('dock.pinnedApps', pinned.filter((id) => id !== appId))
    if (this.webApps.find(appId)) this.webApps.remove(appId)
    else this.packageApps.remove(appId)
  }

  /** Looks for the manifest of the page now in [tab], for as long as it stays there. */
  private findManifest(window: SimWindow, tab: SimTab) {
    tab.manifest = null
    const url = tab.url
    if (!url) return
    const find = this.options.findManifest ?? ((page: string) => fetch(`/__webapp/manifest?url=${encodeURIComponent(page)}`).then((r) => r.json()))
    find(url)
      .then((manifest) => {
        if (tab.url !== url || !this.state.windows.includes(window)) return
        tab.manifest = manifest
        this.emit('browser', window.id)
      })
      .catch(() => {})
  }

  /** Opens [path] in [appId], or in the first app that declares its type. */
  openFile(path: string, mimeType: string | null, appId?: string): SimWindow {
    const app = appId
      ? this.state.apps.find((a) => a.id === appId)
      : this.state.apps.find((a) => a.opens?.some((pattern) => mimeMatches(pattern, mimeType)))
    if (!app) {
      throw appId
        ? new HaloneError('no-such-app', `no such app: ${appId}`)
        : new HaloneError('no-handler', `no app opens ${mimeType ?? 'this kind of file'}`)
    }
    const window = this.launch(app.id)
    window.openedFile = path
    this.emit('file', window.id)
    return window
  }

  openUrl(url: string, isPrivate = false): SimWindow {
    const parsed = webUrl(url)
    const window = this.open({
      kind: 'web',
      appId: null,
      title: parsed.host,
      url: parsed.href,
      widthPx: WEB_WINDOW_SIZE.widthPx,
      heightPx: WEB_WINDOW_SIZE.heightPx + TOOLBAR_PX,
    })
    window.browser = { tabs: [this.tab(1, parsed.href, isPrivate)], activeTab: 1, nextTab: 2 }
    if (!isPrivate) this.browsing.visited(parsed.href, parsed.host)
    window.sizeLimits = {
      minWidthPx: 400,
      minHeightPx: 600,
      maxWidthPx: Math.floor(window.widthPx * 2.5),
      maxHeightPx: Math.floor(window.heightPx * 2.5),
    }
    window.declaredLimits = window.sizeLimits
    this.updateChrome(window)
    this.findManifest(window, window.browser.tabs[0]!)
    return window
  }

  /** Opens [url] in a new tab of the web window used last, or in a new web window if none is open. */
  openInBrowser(url: string, isPrivate = false): SimWindow {
    webUrl(url)
    const window =
      this.state.windows.find((w) => w.id === this.lastWebWindow) ?? [...this.state.windows].reverse().find((w) => w.kind === 'web')
    if (!window) return this.openUrl(url, isPrivate)
    this.newTab(window.id, url, isPrivate)
    this.focus(window.id)
    return window
  }

  browserState(id: WindowId): BrowserState {
    const browser = this.browserOf(id)
    const active = this.activeTab(browser)
    return {
      tabs: browser.tabs.map((tab) => ({ id: tab.id, title: tab.title, url: tab.url, loading: false, private: tab.private })),
      activeTab: browser.activeTab,
      canGoBack: active.index > 0,
      canGoForward: active.index < active.history.length - 1,
      loading: false,
      bookmarked: !!this.browsing.findBookmark(active.url),
      activeDownloads: 0,
      prompt: null,
      webApp: (() => {
        const manifest = parseManifest(active.manifest)
        return manifest ? { appId: manifest.appId, name: manifest.name, installed: !!this.webApps.find(manifest.appId) } : null
      })(),
    }
  }

  toggleBookmark(id: WindowId) {
    const tab = this.activeTab(this.browserOf(id))
    const existing = this.browsing.findBookmark(tab.url)
    if (existing) this.browsing.removeBookmark(existing.id)
    else this.browsing.addBookmark(tab.url, tab.title)
  }

  /** Web windows whose chrome shows something that just changed, such as whether their page is bookmarked. */
  webWindowIds(): WindowId[] {
    return this.state.windows.filter((w) => w.kind === 'web').map((w) => w.id)
  }

  /**
   * Shows a context menu over [sourceId], replacing any menu already up, and
   * answers with the item chosen or null. Shown once the menu page says it
   * has drawn it, as on the glasses.
   */
  showMenu(sourceId: string, items: MenuItem[], xPx: number, yPx: number): Promise<string | null> {
    if (sourceId !== DOCK && sourceId !== LAUNCHER) this.find(sourceId)
    if (!Array.isArray(items) || !items.some((item) => !('separator' in item))) {
      throw new HaloneError('invalid-params', 'a menu needs at least one item')
    }
    if (!Number.isFinite(xPx) || !Number.isFinite(yPx)) throw new HaloneError('invalid-params', 'a menu needs a position')
    this.dismissMenu()
    this.state.menu = { menuId: this.nextMenu++, items, sourceId, xPx, yPx, ...menuSize(items), shown: false }
    this.emit('menu')
    return new Promise((resolve) => (this.menuAnswer = resolve))
  }

  menuRendered(menuId: number) {
    if (this.state.menu?.menuId === menuId) this.state.menu.shown = true
  }

  chooseMenu(menuId: number, itemId: string | null) {
    const menu = this.state.menu
    if (!menu || menu.menuId !== menuId) return
    if (itemId !== null && !menu.items.some((item) => !('separator' in item) && item.id === itemId && !item.disabled)) {
      throw new HaloneError('invalid-params', `no such item: ${itemId}`)
    }
    const answer = this.menuAnswer
    this.state.menu = null
    this.menuAnswer = null
    this.emit('menu')
    answer?.(itemId)
  }

  dismissMenu() {
    if (this.state.menu) this.chooseMenu(this.state.menu.menuId, null)
  }

  readClipboard() {
    const text = this.clipboardText
    return { text, files: this.clipboardFiles && text === this.clipboardFilesText ? { ...this.clipboardFiles } : null }
  }

  writeClipboardText(text: string) {
    this.clipboardFiles = null
    this.clipboardText = text
    navigator.clipboard?.writeText(text).catch(() => {})
  }

  writeClipboardFiles(files: ClipboardFiles) {
    if (!Array.isArray(files?.paths)) throw new HaloneError('invalid-params', 'paths must be a list')
    const text = files.paths.join('\n')
    this.clipboardText = text
    this.clipboardFiles = files.paths.length ? { paths: [...files.paths], cut: !!files.cut } : null
    this.clipboardFilesText = text
  }

  filesMoved(paths: string[]) {
    if (this.clipboardFiles?.cut && this.clipboardFiles.paths.some((path) => paths.includes(path))) this.clipboardFiles = null
  }

  navigate(id: WindowId, url: string) {
    const tab = this.activeTab(this.browserOf(id))
    tab.history.splice(tab.index + 1, Infinity, url)
    tab.index = tab.history.length - 1
    this.show(id, tab, url)
  }

  goBack(id: WindowId) {
    const tab = this.activeTab(this.browserOf(id))
    if (tab.index > 0) this.show(id, tab, tab.history[--tab.index]!)
  }

  goForward(id: WindowId) {
    const tab = this.activeTab(this.browserOf(id))
    if (tab.index < tab.history.length - 1) this.show(id, tab, tab.history[++tab.index]!)
  }

  reload(id: WindowId) {
    this.activeTab(this.browserOf(id)).reloads++
    this.emit('browser', id)
  }

  newTab(id: WindowId, url?: string, isPrivate = false) {
    const browser = this.browserOf(id)
    const tab = this.tab(browser.nextTab++, url ?? '', isPrivate)
    if (url && !isPrivate) this.browsing.visited(url, tab.title)
    browser.tabs.push(tab)
    browser.activeTab = tab.id
    this.findManifest(this.find(id), tab)
    this.browserChanged(this.find(id))
  }

  selectTab(id: WindowId, tabId: number) {
    const browser = this.browserOf(id)
    if (!browser.tabs.some((tab) => tab.id === tabId)) return
    browser.activeTab = tabId
    this.browserChanged(this.find(id))
  }

  closeTab(id: WindowId, tabId: number) {
    const browser = this.browserOf(id)
    if (browser.tabs.length <= 1) return this.close(id)
    const index = browser.tabs.findIndex((tab) => tab.id === tabId)
    if (index < 0) return
    browser.tabs.splice(index, 1)
    if (browser.activeTab === tabId) browser.activeTab = browser.tabs[Math.min(index, browser.tabs.length - 1)]!.id
    this.browserChanged(this.find(id))
  }

  private tab(id: number, url: string, isPrivate = false): SimTab {
    return { id, title: url ? new URL(url).host : '', url, history: url ? [url] : [], index: url ? 0 : -1, reloads: 0, private: isPrivate, manifest: null }
  }

  private browserOf(id: WindowId): SimBrowser {
    const browser = this.find(id).browser
    if (!browser) throw new HaloneError('not-a-browser', `window ${id} is not a web window`)
    return browser
  }

  private activeTab(browser: SimBrowser): SimTab {
    return browser.tabs.find((tab) => tab.id === browser.activeTab)!
  }

  private show(id: WindowId, tab: SimTab, url: string) {
    tab.url = url
    try {
      tab.title = new URL(url).host
    } catch {
      tab.title = url
    }
    if (!tab.private) this.browsing.visited(url, tab.title)
    const window = this.find(id)
    this.findManifest(window, tab)
    this.browserChanged(window)
  }

  private browserChanged(window: SimWindow) {
    const tab = this.activeTab(window.browser!)
    window.url = tab.url
    window.title = tab.title || 'New tab'
    this.updateChrome(window)
    this.emit('windows')
    this.emit('browser', window.id)
  }

  /** The chrome's band follows the tabs, and the page takes what is left. */
  private updateChrome(window: SimWindow) {
    window.chromePx = window.browser!.tabs.length > 1 ? TOOLBAR_PX + TABS_PX : TOOLBAR_PX
  }

  private open(spec: Pick<SimWindow, 'kind' | 'appId' | 'title' | 'url' | 'widthPx' | 'heightPx'>): SimWindow {
    const id = `w${this.nextWindow++}`
    const chromePx = spec.kind === 'web' ? TOOLBAR_PX : 0
    this.scene.open(id, spec.widthPx, spec.heightPx)
    this.scene.launcherRequested = false
    this.state.windows.push({
      ...spec,
      id,
      pinned: false,
      handPlaced: false,
      slotDegrees: 0,
      yawDegrees: 0,
      pitchDegrees: 0,
      chromePx,
      browser: null,
      sizeLimits: defaultLimits(spec.widthPx, spec.heightPx),
      declaredLimits: defaultLimits(spec.widthPx, spec.heightPx),
      stereoRegion: null,
      openedFile: null,
      route: null,
      standalone: false,
      origin: null,
    })
    this.sync()
    this.emit('windows')
    return this.find(id)
  }

  close(id: WindowId) {
    this.find(id)
    if (this.state.menu?.sourceId === id) this.dismissMenu()
    this.scene.close(id)
    this.state.windows.splice(
      this.state.windows.findIndex((w) => w.id === id),
      1,
    )
    this.autoHideRequests.delete(id)
    this.applySettings()
    this.sync()
    this.emit('windows')
    this.emit('closed', id)
  }

  setAutoHideRequest(id: WindowId, enabled: boolean) {
    this.find(id)
    if (enabled) this.autoHideRequests.add(id)
    else this.autoHideRequests.delete(id)
    this.applySettings()
  }

  get windowsAutoHide() {
    return this.state.settings['windows.autoHide'] || this.autoHideRequests.size > 0
  }

  /** Focuses a window and brings the arrangement round to it, as a click in the dock does. */
  focus(id: WindowId) {
    this.find(id)
    this.scene.focusAndCentre(id, toScene(this.state.head.yawDegrees))
    this.scene.launcherRequested = false
    this.sync()
  }

  setPointer(dx: number, dy: number) {
    this.pointer = { dx, dy }
    this.followPointer()
    this.sync()
  }

  clearPointer() {
    this.pointer = null
  }

  setPinned(id: WindowId, pinned: boolean) {
    this.find(id)
    this.scene.setPinned(id, pinned)
    this.sync()
    this.emit('windows')
  }

  /** Resizes a window from a dragged corner, keeping the opposite corner where it was. */
  resizeWindow(id: WindowId, widthPx: number, heightPx: number, corner: 'TOP_LEFT' | 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTTOM_RIGHT') {
    const window = this.find(id)
    const limits = window.sizeLimits
    const width = Math.round(Math.min(Math.max(widthPx, limits.minWidthPx), limits.maxWidthPx))
    const height = Math.round(Math.min(Math.max(heightPx, limits.minHeightPx), limits.maxHeightPx))
    if (width === window.widthPx && height === window.heightPx) return
    window.widthPx = width
    window.heightPx = height
    this.scene.resize(id, width, height, corner)
    this.sync()
    this.emit('windows')
  }

  /** Resizes from the window's own request, keeping its top or bottom edge where it is. */
  resizeTo(id: WindowId, widthPx: number, heightPx: number, anchor: ResizeAnchor = 'top') {
    this.resizeWindow(id, widthPx, heightPx, anchor === 'top' ? 'BOTTOM_RIGHT' : 'TOP_RIGHT')
    const window = this.find(id)
    return { widthPx: window.widthPx, heightPx: window.heightPx }
  }

  setSizeLimits(id: WindowId, limits: SizeLimits | null) {
    const window = this.find(id)
    window.sizeLimits = limits ? checkLimits(limits) : window.declaredLimits
  }

  setStereoRegion(id: WindowId, region: StereoRegion | null) {
    this.find(id).stereoRegion = region ? checkRegion(region) : null
  }

  setTitle(id: WindowId, title: string) {
    this.find(id).title = title.slice(0, 120)
    this.emit('windows')
  }

  setHead(yawDegrees: number, pitchDegrees: number) {
    this.state.head.yawDegrees = wrapDegrees(yawDegrees)
    this.state.head.pitchDegrees = Math.max(-PITCH_LIMIT_DEGREES, Math.min(PITCH_LIMIT_DEGREES, pitchDegrees))
  }

  recenter() {
    this.state.head.yawDegrees = 0
    this.scene.recentre()
    this.sync()
  }

  setSetting(key: string, value: unknown) {
    validateSetting(key, value)
    ;(this.state.settings as Record<SettingKey, unknown>)[key] = value
    this.options.persistSettings?.({ ...this.state.settings })
    if (key === 'developer.enabled' && !value) {
      const dev = this.packageApps.apps.filter((app) => app.dev).map((app) => app.manifest.id)
      for (const window of this.state.windows.filter((w) => w.appId && dev.includes(w.appId))) this.close(window.id)
      this.packageApps.removeDev()
    }
    this.applySettings()
    this.emit('settings')
    if (key === 'display.refreshRate' || key === 'display.stereo') this.emit('display')
  }

  /** The simulated glasses run whatever the settings ask for, falling back as the device does. */
  displayState(): DisplayState {
    const settings = this.state.settings
    const rate = DISPLAY_RATES.find((r) => r.hz === settings['display.refreshRate']) ?? DISPLAY_RATES[0]
    return {
      running: true,
      stereo: rate.stereo && settings['display.stereo'],
      refreshHz: rate.hz,
      renderFps: rate.hz,
      rates: DISPLAY_RATES.map((r) => ({ ...r })),
      restartNeeded: false,
    }
  }

  private applySettings() {
    const settings = this.state.settings
    this.scene.setFollowsHead(settings['follow.enabled'])
    this.scene.setFollowMarginDegrees(settings['follow.marginDegrees'])
    this.scene.setCatchUpSeconds(settings['follow.catchUpSeconds'])
    this.scene.setCollisionPolicy(settings['layout.collisionPolicy'])
    this.scene.setSlotSpacingDegrees(settings['layout.slotSpacingDegrees'])
    this.scene.setDockAutoHide(settings['dock.visibility'] === 'auto-hide')
    this.scene.setWindowsAutoHide(this.windowsAutoHide, settings['windows.autoHideDelaySeconds'])
  }

  beginGrab(id: WindowId) {
    const window = this.find(id)
    this.grabAim = { yawDegrees: toScene(window.yawDegrees), pitchDegrees: window.pitchDegrees }
    this.grabStart = { ...this.grabAim }
    this.scene.beginGrab(id, this.grabAim.yawDegrees, this.grabAim.pitchDegrees)
    this.sync()
  }

  moveGrab(deltaYawDegrees: number, deltaPitchDegrees: number) {
    if (!this.state.grabbedId) return
    this.grabAim.yawDegrees = wrapDegrees(this.grabAim.yawDegrees + toScene(deltaYawDegrees))
    this.grabAim.pitchDegrees += deltaPitchDegrees
    this.scene.updateGrab(this.grabAim.yawDegrees, this.grabAim.pitchDegrees)
    this.sync()
  }

  endGrab() {
    if (!this.state.grabbedId) return
    const travel = Math.hypot(
      wrapDegrees(this.grabAim.yawDegrees - this.grabStart.yawDegrees),
      this.grabAim.pitchDegrees - this.grabStart.pitchDegrees,
    )
    if (travel < CLICK_SLOP_DEGREES) this.scene.cancelGrab()
    else this.scene.endGrab(toScene(this.state.head.yawDegrees))
    this.sync()
    this.emit('windows')
  }

  tick(dt: number) {
    this.scene.setHeadPitchDegrees(this.state.head.pitchDegrees)
    this.scene.advance(toScene(this.state.head.yawDegrees), dt)
    this.followPointer()
    this.sync()
  }

  describe() {
    return this.scene.describe(toScene(this.state.head.yawDegrees))
  }

  private followPointer() {
    const head = this.state.head
    const aim =
      this.state.settings['input.pointer'] === 'gaze'
        ? head
        : this.pointer && pointerAngles(head.yawDegrees, head.pitchDegrees, this.pointer.dx, this.pointer.dy)
    if (aim) this.scene.hitTest(toScene(aim.yawDegrees), aim.pitchDegrees)
  }

  private sync() {
    const panels = this.scene.panels()
    const byId = new Map(this.state.windows.map((w) => [w.id, w]))
    panels.forEach((panel, index) => {
      const window = byId.get(panel.id)
      if (!window) return
      window.yawDegrees = fromScene(panel.yawDegrees)
      window.pitchDegrees = panel.pitchDegrees
      window.slotDegrees = fromScene(panel.slotDegrees)
      window.pinned = panel.pinned
      window.handPlaced = panel.placedByHand
      if (this.state.windows[index] !== window) {
        this.state.windows.splice(this.state.windows.indexOf(window), 1)
        this.state.windows.splice(index, 0, window)
      }
    })
    for (const overlay of this.scene.overlays()) {
      const state = this.state.overlays.find((o) => o.id === overlay.id)
      if (!state) continue
      state.yawDegrees = fromScene(overlay.yawDegrees)
      state.pitchDegrees = overlay.pitchDegrees
      state.opacity = overlay.opacity
      state.shown = overlay.shown
    }
    if (this.scene.launcherShown !== this.state.launcherOpen) {
      this.state.launcherOpen = this.scene.launcherShown
      this.emit('launcher')
    }
    this.state.windowsOpacity = this.scene.windowsOpacity
    this.state.anchorYawDegrees = fromScene(this.scene.anchorYawDegrees)
    this.state.returning = this.scene.returning
    this.state.grabbedId = this.scene.grabbedId ?? null
    const focused = this.scene.focusedId ?? null
    if (focused !== this.state.focusedId) {
      this.state.focusedId = focused
      if (focused && this.state.windows.find((w) => w.id === focused)?.kind === 'web') this.lastWebWindow = focused
      this.emit('focus')
      this.emit('windows')
    }
  }
}

const MAX_WINDOW_PX = 4096

function webUrl(url: string): URL {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    throw new HaloneError('invalid-url', `not a URL: ${url}`)
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new HaloneError('invalid-url', `only http and https can be opened: ${url}`)
  }
  return parsed
}

function checkLimits(limits: SizeLimits): SizeLimits {
  const values = [limits?.minWidthPx, limits?.minHeightPx, limits?.maxWidthPx, limits?.maxHeightPx]
  if (!values.every((v) => typeof v === 'number' && Number.isFinite(v) && v >= 1 && v <= MAX_WINDOW_PX)) {
    throw new HaloneError('invalid-params', `limits must be whole pixels between 1 and ${MAX_WINDOW_PX}`)
  }
  if (limits.minWidthPx > limits.maxWidthPx || limits.minHeightPx > limits.maxHeightPx) {
    throw new HaloneError('invalid-params', 'a minimum is above its maximum')
  }
  return {
    minWidthPx: Math.round(limits.minWidthPx),
    minHeightPx: Math.round(limits.minHeightPx),
    maxWidthPx: Math.round(limits.maxWidthPx),
    maxHeightPx: Math.round(limits.maxHeightPx),
  }
}

function checkRegion(region: StereoRegion): StereoRegion {
  const sizes = [region?.xPx, region?.yPx, region?.widthPx, region?.heightPx]
  if (!['side-by-side', 'top-bottom'].includes(region?.stereo) || !sizes.every((v) => typeof v === 'number' && Number.isFinite(v))) {
    throw new HaloneError('invalid-params', 'region must be side-by-side or top-bottom, with a position and size in pixels')
  }
  if (region.widthPx <= 0 || region.heightPx <= 0) throw new HaloneError('invalid-params', 'region must have a size')
  return { stereo: region.stereo, xPx: region.xPx, yPx: region.yPx, widthPx: region.widthPx, heightPx: region.heightPx }
}
