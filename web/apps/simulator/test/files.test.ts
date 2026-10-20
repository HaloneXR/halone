import { describe, expect, it } from 'vitest'
import type { AppInfo, MediaState } from '@halone/sdk'
import { parseRange } from '../server/files'
import { SimMedia } from '../src/shell/media'
import { Shell } from '../src/shell/shell'
import { DEFAULT_SETTINGS } from '../src/shell/settings'

const app = (id: string, opens?: string[]): AppInfo => ({ id, name: id, description: '', icon: '', entry: `/${id}/`, widthPx: 1280, heightPx: 800, opens })

function shell() {
  return new Shell({
    apps: [app('files'), app('images', ['image/*']), app('video', ['video/*'])],
    appsOrigin: 'http://apps.test',
    settings: { ...DEFAULT_SETTINGS },
  })
}

describe('opening files', () => {
  it('opens a file in the app that declares its type', () => {
    const s = shell()
    const opened: (string | undefined)[] = []
    s.on('file', (id) => opened.push(id))
    const window = s.openFile('/Pictures/a.jpg', 'image/jpeg')
    expect(window.appId).toBe('images')
    expect(window.openedFile).toBe('/Pictures/a.jpg')
    expect(opened).toEqual([window.id])
  })

  it('reuses the running window for the next file', () => {
    const s = shell()
    const first = s.openFile('/Pictures/a.jpg', 'image/jpeg')
    const second = s.openFile('/Pictures/b.png', 'image/png')
    expect(second.id).toBe(first.id)
    expect(second.openedFile).toBe('/Pictures/b.png')
    expect(s.state.windows).toHaveLength(1)
  })

  it('refuses a type nothing opens', () => {
    expect(() => shell().openFile('/Downloads/a.zip', 'application/zip')).toThrow(/no app opens/)
  })

  it('opens in a named app regardless of type', () => {
    expect(shell().openFile('/Downloads/a.bin', null, 'video').appId).toBe('video')
  })
})

describe('parseRange', () => {
  it('reads the ranges a media element asks for', () => {
    expect(parseRange('bytes=0-', 100)).toEqual([0, 99])
    expect(parseRange('bytes=10-19', 100)).toEqual([10, 19])
    expect(parseRange('bytes=90-200', 100)).toEqual([90, 99])
    expect(parseRange('bytes=-10', 100)).toEqual([90, 99])
    expect(parseRange('bytes=100-', 100)).toBe('unsatisfiable')
    expect(parseRange(undefined, 100)).toBeNull()
  })
})

describe('the immersive player', () => {
  const layout = { projection: 'equirect-360', stereo: 'mono' } as const

  it('is owned by the window that opened it', () => {
    const media = new SimMedia()
    media.open('w1', '/Movies/a.mp4', 'http://x/a.mp4', layout, 30)
    expect(media.state.anchorYawDegrees).toBe(30)
    expect(() => media.pause('w2')).toThrow(/another window/)
    media.pause('w1')
    expect(media.state.playing).toBe(false)
  })

  it('tells the old owner when another window takes it over', () => {
    const media = new SimMedia()
    const told: [string, MediaState][] = []
    media.onChange((owner, state) => told.push([owner, state]))
    media.open('w1', '/Movies/a.mp4', 'http://x/a.mp4', layout, 0)
    media.open('w2', '/Movies/b.mp4', 'http://x/b.mp4', layout, 0)
    expect(told.some(([owner, state]) => owner === 'w1' && !state.open)).toBe(true)
    expect(media.state.ownerId).toBe('w2')
  })

  it('closes with the window that owns it', () => {
    const media = new SimMedia()
    media.open('w1', '/Movies/a.mp4', 'http://x/a.mp4', layout, 0)
    media.windowClosed('w2')
    expect(media.state.open).toBe(true)
    media.windowClosed('w1')
    expect(media.state.open).toBe(false)
    expect(() => media.play('w1')).toThrow(/no video/)
  })

  it('clamps a seek to the video', () => {
    const media = new SimMedia()
    media.open('w1', '/Movies/a.mp4', 'http://x/a.mp4', layout, 0)
    media.seek('w1', -5)
    expect(media.state.positionSeconds).toBe(0)
  })
})

describe('a window shaping itself', () => {
  it('resizes within its limits, keeping the edge it asks for', () => {
    const s = shell()
    const window = s.launch('video')
    const top = window.yawDegrees
    expect(s.resizeTo(window.id, 1280, 100, 'bottom')).toEqual({ widthPx: 1280, heightPx: 360 })
    expect(window.yawDegrees).toBe(top)
  })

  it('goes below its floor once its limits are locked, and back when they are cleared', () => {
    const s = shell()
    const window = s.launch('video')
    s.setSizeLimits(window.id, { minWidthPx: 1280, minHeightPx: 180, maxWidthPx: 1280, maxHeightPx: 180 })
    expect(s.resizeTo(window.id, 1280, 180, 'bottom').heightPx).toBe(180)
    s.setSizeLimits(window.id, null)
    expect(s.resizeTo(window.id, 1280, 180).heightPx).toBe(360)
    expect(() => s.setSizeLimits(window.id, { minWidthPx: 900, minHeightPx: 1, maxWidthPx: 800, maxHeightPx: 1 })).toThrow(/minimum/)
  })

  it('takes a stereo region and refuses a mono one', () => {
    const s = shell()
    const window = s.launch('images')
    s.setStereoRegion(window.id, { stereo: 'side-by-side', xPx: 0, yPx: 0, widthPx: 640, heightPx: 360 })
    expect(window.stereoRegion?.widthPx).toBe(640)
    expect(() => s.setStereoRegion(window.id, { stereo: 'mono' as never, xPx: 0, yPx: 0, widthPx: 1, heightPx: 1 })).toThrow()
    s.setStereoRegion(window.id, null)
    expect(window.stereoRegion).toBeNull()
  })
})

describe('auto-hide asked for by a window', () => {
  it('ends when the window closes, and never overrides the setting', () => {
    const s = shell()
    const window = s.launch('video')
    s.setAutoHideRequest(window.id, true)
    expect(s.windowsAutoHide).toBe(true)
    s.close(window.id)
    expect(s.windowsAutoHide).toBe(false)
    s.setSetting('windows.autoHide', true)
    expect(s.windowsAutoHide).toBe(true)
  })
})

describe('display mode', () => {
  it('follows the refresh rate and 3D settings, and stays 2D where the glasses are', () => {
    const s = shell()
    const changes: number[] = []
    s.on('display', () => changes.push(s.displayState().refreshHz ?? 0))
    expect(s.displayState()).toMatchObject({ running: true, stereo: true, refreshHz: 60 })
    s.setSetting('display.refreshRate', 120)
    expect(s.displayState()).toMatchObject({ stereo: false, refreshHz: 120 })
    s.setSetting('display.refreshRate', 90)
    s.setSetting('display.stereo', false)
    expect(s.displayState()).toMatchObject({ stereo: false, refreshHz: 90 })
    expect(changes).toEqual([120, 90, 90])
  })
})
