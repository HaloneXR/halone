import { onBeforeUnmount, reactive, ref, type Ref } from 'vue'
import { useHalone, type AppInfo, type RecordingState, type Settings, type SystemInfo, type WindowInfo, type HaloneClient } from '@halone/sdk'
import { installTextMenu } from './menu'

export type HostStatus = 'connecting' | 'ready' | 'no-host'

/** Shown on the phone's own screen rather than in a window on the glasses; the phone opens the page with `?phone`. */
export const onPhone = new URLSearchParams(location.search).has('phone')
if (onPhone) document.documentElement.classList.add('x-phone')

export function useHost() {
  const halone = useHalone()
  const status = ref<HostStatus>('connecting')
  const error = ref<string | null>(null)
  halone.ready.then(
    () => {
      status.value = 'ready'
      installTextMenu(halone)
    },
    (caught: Error) => {
      status.value = 'no-host'
      error.value = caught.message
    },
  )
  return { halone, status, error }
}

function subscribe<T>(halone: HaloneClient, load: () => Promise<T>, event: 'windows.changed' | 'settings.changed', target: Ref<T>) {
  load().then((value) => (target.value = value), () => {})
  const off = halone.on(event, (value) => (target.value = value as T))
  onBeforeUnmount(off)
}

export function useWindows(halone: HaloneClient) {
  const windows = ref<WindowInfo[]>([])
  subscribe(halone, () => halone.windows.list(), 'windows.changed', windows)
  return windows
}

export function useSettings(halone: HaloneClient) {
  const settings = ref<Settings | null>(null)
  subscribe(halone, () => halone.settings.get(), 'settings.changed', settings)
  return settings
}

/** What Halone runs on: the platform, and on a desktop the operating system. */
export function useSystemInfo(halone: HaloneClient) {
  const info = ref<SystemInfo | null>(null)
  halone.system.info().then((value) => (info.value = value), () => {})
  return info
}

export function useFocus(halone: HaloneClient) {
  const state = reactive({ focused: false })
  const off = halone.on('window.focusChanged', ({ focused }) => (state.focused = focused))
  onBeforeUnmount(off)
  return state
}

/** The file this window was asked to open, now and whenever it is asked again. */
export function useOpenedFile(halone: HaloneClient) {
  const path = ref<string | null>(null)
  halone.windows.openedFile().then((value) => (path.value ??= value.path), () => {})
  const off = halone.on('window.fileOpened', (value) => (path.value = value.path))
  onBeforeUnmount(off)
  return path
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** Whether the screen is being recorded, kept current. */
export function useRecording(halone: HaloneClient) {
  const state = ref<RecordingState>({ recording: false, startedAt: null, audio: false })
  halone.screen.recording().then((value) => (state.value = value), () => {})
  const off = halone.on('screen.recordingChanged', (value) => (state.value = value))
  onBeforeUnmount(off)
  return state
}

/** Hears where `apps.launch` asks this window to go: once on load, and on every later ask, repeats included. */
export function onRoute(halone: HaloneClient, go: (route: string) => void) {
  let heard = false
  halone.windows.route().then((value) => !heard && value.route && go(value.route), () => {})
  const off = halone.on('window.routed', (value) => {
    heard = true
    go(value.route)
  })
  onBeforeUnmount(off)
}

/** The apps to offer, kept current as sites are installed and removed; [includeHidden] as for `apps.list`. */
export function useApps(halone: HaloneClient, includeHidden = false) {
  const apps = ref<AppInfo[]>([])
  const load = () => halone.apps.list(includeHidden).then((list) => (apps.value = list), () => {})
  load()
  const off = halone.on('apps.changed', load)
  onBeforeUnmount(off)
  return apps
}
