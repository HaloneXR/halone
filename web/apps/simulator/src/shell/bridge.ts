import { reactive } from 'vue'
import {
  PROTOCOL_VERSION,
  HaloneError,
  isVideo,
  type ConnectMessage,
  type EventName,
  type Events,
  type MediaLayout,
  type WindowId,
  type WireMessage,
} from '@halone/sdk'
import { HostEndpoint, type Handlers, type TrafficDirection } from '@halone/sdk/host'
import { checkAllowed, hears } from '@halone/sdk/permissions'
import { DISPLAY_HEIGHT_PX, DISPLAY_WIDTH_PX, VERTICAL_FOV_DEGREES } from './geometry'
import type { SimFiles } from './files'
import type { SimMedia } from './media'
import { SimSockets } from './sockets'
import { MENU, type Shell } from './shell'

export interface TrafficEntry {
  seq: number
  at: number
  windowId: WindowId
  direction: TrafficDirection
  message: WireMessage
}

const TRAFFIC_LIMIT = 300

interface Attachment {
  frame: HTMLIFrameElement
  /** The only origin the frame's page gets the port from, or says hello from. */
  origin: string
  endpoint: HostEndpoint | null
  focused: boolean
}

export class Bridge {
  readonly traffic = reactive<TrafficEntry[]>([])
  private seq = 0
  private readonly attachments = new Map<WindowId, Attachment>()
  private readonly handlers: Handlers
  private readonly sockets = new SimSockets()

  constructor(
    private readonly shell: Shell,
    private readonly appsOrigin: string,
    private readonly files: SimFiles,
    private readonly media: SimMedia,
  ) {
    this.handlers = this.createHandlers()
    shell.on('windows', () => this.broadcast('windows.changed', shell.list()))
    shell.on('settings', () => this.broadcast('settings.changed', { ...shell.state.settings }))
    shell.on('focus', () => this.notifyFocus())
    shell.on('launcher', () => this.broadcast('launcher.changed', { open: shell.state.launcherOpen }))
    shell.on('browser', (id) => id && this.attachments.get(id)?.endpoint?.emit('browser.changed', shell.browserState(id)))
    shell.on('battery', () => shell.state.battery && this.broadcast('system.batteryChanged', { ...shell.state.battery }))
    shell.on('display', () => this.broadcast('display.changed', shell.displayState()))
    shell.on('file', (id) => {
      const path = id && shell.find(id).openedFile
      if (path) this.attachments.get(id)?.endpoint?.emit('window.fileOpened', { path })
    })
    shell.on('closed', (id) => {
      if (!id) return
      media.windowClosed(id)
      this.sockets.windowClosed(id)
    })
    this.sockets.onEvent = (owner, name, data) => {
      const endpoint = this.attachments.get(owner)?.endpoint
      if (name === 'socket.data') endpoint?.emit(name, { socketId: data.socketId, data: data.data ?? '' })
      else endpoint?.emit(name, { socketId: data.socketId, error: data.error ?? null })
    }
    shell.on('routed', (id) => {
      const route = id && shell.find(id).route
      if (route) this.attachments.get(id)?.endpoint?.emit('window.routed', { route })
    })
    shell.on('apps', () => this.broadcast('apps.changed', null))
    shell.on('menu', () => {
      const menu = shell.state.menu
      this.attachments.get(MENU)?.endpoint?.emit('menu.changed', menu ? { menuId: menu.menuId, items: menu.items, dialog: null } : null)
    })
    shell.browsing.onChange((what) => {
      if (what === 'history') this.broadcast('history.changed', null)
      else this.broadcast('bookmarks.changed', [...shell.browsing.bookmarks])
      for (const id of shell.webWindowIds()) this.attachments.get(id)?.endpoint?.emit('browser.changed', shell.browserState(id))
    })
    media.onChange((ownerId, state) => this.attachments.get(ownerId)?.endpoint?.emit('media.changed', state))
    window.addEventListener('message', (event) => this.onHello(event))
  }

  attach(windowId: WindowId, frame: HTMLIFrameElement, origin = this.appsOrigin) {
    this.detach(windowId)
    this.attachments.set(windowId, { frame, origin, endpoint: null, focused: false })
  }

  detach(windowId: WindowId) {
    this.attachments.get(windowId)?.endpoint?.close()
    this.attachments.delete(windowId)
  }

  clearTraffic() {
    this.traffic.splice(0)
  }

  private connect(windowId: WindowId) {
    const attachment = this.attachments.get(windowId)
    const target = attachment?.frame.contentWindow
    if (!attachment || !target) return
    attachment.endpoint?.close()
    const channel = new MessageChannel()
    const granted = this.shell.grantOf(windowId)
    attachment.endpoint = new HostEndpoint(
      channel.port1,
      windowId,
      this.handlers,
      (id, direction, message) => this.record(id, direction, message),
      granted
        ? {
            check: (method, params) => checkAllowed(granted, method, windowId, params),
            hears: (name) => hears(granted, name),
          }
        : undefined,
    )
    attachment.focused = false
    const hello: ConnectMessage = { halone: 'connect', protocolVersion: PROTOCOL_VERSION, windowId }
    target.postMessage(JSON.stringify(hello), attachment.origin, [channel.port2])
    this.notifyFocus()
  }

  private onHello(event: MessageEvent) {
    if ((event.data as { halone?: string })?.halone !== 'hello') return
    for (const [windowId, attachment] of this.attachments) {
      if (attachment.frame.contentWindow === event.source && event.origin === attachment.origin) this.connect(windowId)
    }
  }

  private broadcast<E extends EventName>(name: E, data: Events[E]) {
    for (const { endpoint } of this.attachments.values()) endpoint?.emit(name, data)
  }

  private notifyFocus() {
    for (const [windowId, attachment] of this.attachments) {
      const focused = this.shell.state.focusedId === windowId
      if (!attachment.endpoint || attachment.focused === focused) continue
      attachment.focused = focused
      attachment.endpoint.emit('window.focusChanged', { focused })
    }
  }

  private record(windowId: WindowId, direction: TrafficDirection, message: WireMessage) {
    this.traffic.push({ seq: this.seq++, at: performance.now(), windowId, direction, message })
    if (this.traffic.length > TRAFFIC_LIMIT) this.traffic.splice(0, this.traffic.length - TRAFFIC_LIMIT)
  }

  private createHandlers(): Handlers {
    const { shell, files, media } = this
    return {
      'system.info': () => ({
        platform: 'simulator',
        version: '0.1.0',
        protocolVersion: PROTOCOL_VERSION,
        device: {
          name: 'Simulated XREAL Air 2 Pro',
          verticalFovDegrees: VERTICAL_FOV_DEGREES,
          displayWidthPx: DISPLAY_WIDTH_PX,
          displayHeightPx: DISPLAY_HEIGHT_PX,
        },
      }),
      'system.ping': ({ sentAt }) => ({ sentAt, receivedAt: performance.now() }),
      'system.battery': () => {
        if (!shell.state.battery) throw new HaloneError('unavailable', 'battery level is not known')
        return { ...shell.state.battery }
      },
      'system.shareLog': () => {
        throw new HaloneError('unavailable', 'the simulator keeps no log')
      },
      'display.state': () => shell.displayState(),
      'display.restart': () => undefined,
      'screen.capture': () => {
        throw new HaloneError('unavailable', 'the simulator cannot capture what the glasses show')
      },
      'screen.recording': () => ({ recording: false, startedAt: null, audio: false }),
      'screen.startRecording': () => {
        throw new HaloneError('unavailable', 'the simulator cannot record what the glasses show')
      },
      'screen.stopRecording': () => undefined,
      'browser.state': (_, { windowId }) => shell.browserState(windowId),
      'browser.navigate': ({ url }, { windowId }) => shell.navigate(windowId, url),
      'browser.back': (_, { windowId }) => shell.goBack(windowId),
      'browser.forward': (_, { windowId }) => shell.goForward(windowId),
      'browser.reload': (_, { windowId }) => shell.reload(windowId),
      'browser.stop': () => undefined,
      'browser.newTab': ({ url, private: isPrivate }, { windowId }) => shell.newTab(windowId, url, !!isPrivate),
      'browser.selectTab': ({ tabId }, { windowId }) => shell.selectTab(windowId, tabId),
      'browser.closeTab': ({ tabId }, { windowId }) => shell.closeTab(windowId, tabId),
      'browser.answerPrompt': ({ id }) => {
        throw new HaloneError('no-such-prompt', `no such prompt: ${id}`)
      },
      'browser.toggleBookmark': (_, { windowId }) => shell.toggleBookmark(windowId),
      'browser.installApp': (_, { windowId }) => shell.installWebApp(windowId),
      'browser.open': ({ url, private: isPrivate }) => shell.info(shell.openInBrowser(url, !!isPrivate)),
      'browser.clearData': ({ history }) => {
        if (history) shell.browsing.clearHistory()
      },
      'history.list': (params) => shell.browsing.listHistory(params?.query, params?.limit),
      'history.remove': ({ url }) => shell.browsing.removeHistory(url),
      'bookmarks.list': () => [...shell.browsing.bookmarks],
      'bookmarks.add': ({ url, title, iconUrl }) => shell.browsing.addBookmark(url, title, iconUrl ?? null),
      'bookmarks.update': ({ id, title, url }) => shell.browsing.updateBookmark(id, { title, url }),
      'bookmarks.remove': ({ id }) => shell.browsing.removeBookmark(id),
      'downloads.list': () => [...shell.browsing.downloads],
      'downloads.cancel': ({ id }) => {
        throw new HaloneError('no-such-download', `no such download: ${id}`)
      },
      'downloads.remove': ({ id }) => {
        throw new HaloneError('no-such-download', `no such download: ${id}`)
      },
      'downloads.clear': () => undefined,
      'menu.show': async ({ items, xPx, yPx }, { windowId }) => ({ itemId: await shell.showMenu(windowId, items, xPx, yPx) }),
      'menu.current': () => {
        const menu = shell.state.menu
        return menu ? { menuId: menu.menuId, items: menu.items, dialog: null } : null
      },
      'menu.rendered': ({ menuId }) => shell.menuRendered(menuId),
      'menu.choose': ({ menuId, itemId }) => shell.chooseMenu(menuId, itemId ?? null),
      'clipboard.read': () => shell.readClipboard(),
      'clipboard.writeText': ({ text }) => {
        if (typeof text !== 'string') throw new HaloneError('invalid-params', 'text must be a string')
        shell.writeClipboardText(text)
      },
      'clipboard.writeFiles': (files) => shell.writeClipboardFiles(files),
      'input.keyCapture': () => ({ enabled: false }),
      'input.openKeyCaptureSettings': () => {
        throw new HaloneError('unavailable', 'key capture is an Android accessibility service; the simulator has no use for it')
      },
      'launcher.get': () => ({ open: shell.state.launcherOpen }),
      'launcher.setOpen': ({ open }) => shell.setLauncherOpen(open),
      'apps.list': (params) =>
        shell.state.apps.filter((app) => params?.includeHidden || !app.hidden).map((app) => ({ ...app })),
      'apps.launch': ({ appId, route }) => shell.info(shell.launch(appId, route)),
      'apps.uninstall': ({ appId }) => shell.uninstall(appId),
      'plugins.list': () => [],
      'status.list': () => [],
      'status.click': ({ id }) => {
        throw new HaloneError('no-such-item', `status items come from plugins; the simulator has none: ${id}`)
      },
      'plugins.setEnabled': ({ packageName }) => {
        throw new HaloneError('no-such-plugin', `plugins are Android apps; the simulator has none: ${packageName}`)
      },
      'plugins.settings': ({ packageName }) => {
        throw new HaloneError('no-such-plugin', `plugins are Android apps; the simulator has none: ${packageName}`)
      },
      'plugins.setSetting': ({ packageName }) => {
        throw new HaloneError('no-such-plugin', `plugins are Android apps; the simulator has none: ${packageName}`)
      },
      'apps.open': async ({ path, appId }) => {
        const entry = await files.stat(path)
        if (entry.kind !== 'file') throw new HaloneError('not-a-file', `not a file: ${path}`)
        return shell.info(shell.openFile(entry.path, entry.mimeType, appId))
      },
      'sockets.connect': ({ host, port, timeoutMs, tls }, { windowId }) => this.sockets.connect(windowId, host, port, timeoutMs, !!tls),
      'sockets.write': ({ socketId, data }, { windowId }) => this.sockets.write(windowId, socketId, data),
      'sockets.ack': ({ socketId }, { windowId }) => this.sockets.ack(windowId, socketId),
      'sockets.close': ({ socketId }, { windowId }) => this.sockets.close(windowId, socketId),
      'files.access': () => ({ granted: true }),
      'files.requestAccess': () => undefined,
      'files.roots': () => files.roots(),
      'files.list': ({ path }) => files.list(path),
      'files.stat': ({ path }) => files.stat(path),
      'files.copy': ({ paths, to }) => files.copy(paths, to),
      'files.move': ({ paths, to }) => {
        shell.filesMoved(paths)
        return files.move(paths, to)
      },
      'files.rename': ({ path, name }) => files.rename(path, name),
      'files.delete': ({ paths }) => files.delete(paths),
      'files.createDirectory': ({ parent, name }) => files.createDirectory(parent, name),
      'media.open': async ({ path, layout, startSeconds }, { windowId }) => {
        const entry = await files.stat(path)
        if (entry.kind !== 'file' || !entry.url || !isVideo(entry)) throw new HaloneError('not-a-video', `not a video: ${path}`)
        return media.open(windowId, entry.path, entry.url, checkLayout(layout), shell.state.head.yawDegrees, startSeconds ?? 0)
      },
      'media.state': () => media.snapshot(),
      'media.play': (_, { windowId }) => media.play(windowId),
      'media.pause': (_, { windowId }) => media.pause(windowId),
      'media.seek': ({ seconds }, { windowId }) => media.seek(windowId, seconds),
      'media.setLayout': ({ layout }, { windowId }) => media.setLayout(windowId, checkLayout(layout)),
      'media.recenter': (_, { windowId }) => media.recenter(windowId, shell.state.head.yawDegrees),
      'media.setRotatable': ({ rotatable }, { windowId }) => {
        if (typeof rotatable !== 'boolean') throw new HaloneError('invalid-params', 'rotatable must be a boolean')
        media.setRotatable(windowId, rotatable)
      },
      'media.close': (_, { windowId }) => media.closeFor(windowId),
      'windows.list': () => shell.list(),
      'windows.self': (_, { windowId }) => shell.info(shell.find(windowId)),
      'windows.openUrl': ({ url }) => shell.info(shell.openUrl(url)),
      'windows.focus': ({ id }) => shell.focus(id),
      'windows.close': ({ id }, { windowId }) => shell.close(id ?? windowId),
      'windows.setPinned': ({ id, pinned }, { windowId }) => shell.setPinned(id ?? windowId, pinned),
      'windows.setTitle': ({ title }, { windowId }) => shell.setTitle(windowId, title),
      'windows.openedFile': (_, { windowId }) => ({ path: shell.find(windowId).openedFile }),
      'windows.route': (_, { windowId }) => ({ route: shell.find(windowId).route }),
      'windows.setStereoRegion': ({ region }, { windowId }) => shell.setStereoRegion(windowId, region),
      'windows.resize': ({ widthPx, heightPx, anchor }, { windowId }) => {
        if (!Number.isFinite(widthPx) || !Number.isFinite(heightPx)) throw new HaloneError('invalid-params', 'a size must be numbers')
        if (anchor !== undefined && anchor !== 'top' && anchor !== 'bottom') throw new HaloneError('invalid-params', 'anchor must be top or bottom')
        return shell.resizeTo(windowId, widthPx, heightPx, anchor)
      },
      'windows.setSizeLimits': ({ limits }, { windowId }) => shell.setSizeLimits(windowId, limits),
      'windows.setAutoHide': ({ enabled }, { windowId }) => {
        if (typeof enabled !== 'boolean') throw new HaloneError('invalid-params', 'enabled must be a boolean')
        shell.setAutoHideRequest(windowId, enabled)
      },
      'scene.recenter': () => shell.recenter(),
      'scene.headPose': () => ({ ...shell.state.head }),
      'settings.get': () => ({ ...shell.state.settings }),
      'settings.set': ({ key, value }) => shell.setSetting(key, value),
    }
  }
}

const PROJECTIONS = ['flat', 'equirect-180', 'equirect-360']
const STEREO_LAYOUTS = ['mono', 'side-by-side', 'top-bottom']

function checkLayout(layout: MediaLayout | undefined): MediaLayout {
  if (!layout || !PROJECTIONS.includes(layout.projection) || !STEREO_LAYOUTS.includes(layout.stereo)) {
    throw new HaloneError('invalid-params', `layout must be one of ${PROJECTIONS.join(', ')} with one of ${STEREO_LAYOUTS.join(', ')}`)
  }
  return { projection: layout.projection, stereo: layout.stereo }
}
