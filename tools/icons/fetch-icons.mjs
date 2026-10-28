#!/usr/bin/env node
// Fetches the icons the shell ships: full-colour app and file-type icons from
// Papirus (GPL-3.0), and a Material Symbols Rounded font (Apache-2.0) cut down
// to the symbols listed below. Run it again after changing either list:
//
//   node tools/icons/fetch-icons.mjs

import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join, posix } from 'node:path'

const root = join(import.meta.dirname, '..', '..')
const PAPIRUS_TAG = '20260801'
const PAPIRUS_BASE = `https://raw.githubusercontent.com/PapirusDevelopmentTeam/papirus-icon-theme/${PAPIRUS_TAG}/Papirus/64x64`

const PAPIRUS = {
  'apps/settings': 'apps/preferences-system',
  'apps/files': 'apps/system-file-manager',
  'apps/browser': 'apps/web-browser',
  'apps/images': 'apps/image-viewer',
  'apps/video': 'apps/video-player',
  'apps/library': 'places/user-bookmarks',
  'apps/system': 'apps/utilities-system-monitor',
  'apps/launcher': 'apps/preferences-desktop',
  'apps/web': 'apps/web-browser',
  'files/folder': 'places/folder',
  'files/image': 'mimetypes/image-x-generic',
  'files/video': 'mimetypes/video-x-generic',
  'files/audio': 'mimetypes/audio-x-generic',
  'files/text': 'mimetypes/text-x-generic',
  'files/pdf': 'mimetypes/application-pdf',
  'files/archive': 'mimetypes/package-x-generic',
  'files/apk': 'mimetypes/application-vnd.android.package-archive',
  'files/html': 'mimetypes/text-html',
  'files/unknown': 'mimetypes/unknown',
}

const SYMBOLS = [
  'add', 'apps', 'arrow_back', 'arrow_upward', 'bookmarks', 'brightness_6', 'camera', 'center_focus_strong', 'check',
  'chevron_left', 'chevron_right', 'close', 'code', 'description', 'download', 'expand_less', 'expand_more',
  'extension', 'eye_tracking', 'eyeglasses', 'forward_10', 'gesture', 'grid_view', 'image', 'info', 'keep', 'keep_off',
  'keyboard', 'language', 'memory', 'more_vert', 'mouse', 'movie', 'music_note', 'my_location', 'open_in_new',
  'pause', 'photo_camera', 'play_arrow', 'radio_button_checked', 'refresh', 'remove', 'replay_10', 'search',
  'select_window', 'settings', 'smartphone', 'star', 'stop', 'stop_circle', 'swap_horiz', 'touch_app',
  'trackpad_input', 'usb', 'view_list', 'visibility', 'web_asset', 'zoom_in', 'zoom_out', 'all_out', 'moving',
  'link_off', 'folder', 'lock', 'arrow_forward', 'videocam', 'screen_rotation_alt', 'ads_click', 'picture_in_picture',
]

async function fetchText(url) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`)
  return response.text()
}

async function fetchBytes(url, headers = {}) {
  const response = await fetch(url, { headers })
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`)
  return Buffer.from(await response.arrayBuffer())
}

/** Papirus links most names to another file; raw GitHub serves a link as the path it points to. */
async function papirus(path, hops = 0) {
  const text = await fetchText(`${PAPIRUS_BASE}/${path}.svg`)
  if (text.trimStart().startsWith('<')) return text
  if (hops > 4) throw new Error(`${path}: too many links`)
  const target = posix.normalize(posix.join(posix.dirname(path), text.trim())).replace(/\.svg$/, '')
  return papirus(target, hops + 1)
}

async function write(path, data) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, data)
  console.log(`wrote ${path.slice(root.length + 1)} (${data.length} bytes)`)
}

const iconsDir = join(root, 'web', 'apps', 'system', 'public', 'icons')
for (const [name, source] of Object.entries(PAPIRUS)) {
  await write(join(iconsDir, `${name}.svg`), await papirus(source))
}
await write(join(iconsDir, 'LICENSE'), `The icons in this folder are not covered by Halone's MIT License.

They come from the Papirus icon theme, tag ${PAPIRUS_TAG}, and are licensed
under the GNU General Public License, version 3: see ../licenses/GPL-3.0.txt.
Their source is at
https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/tree/${PAPIRUS_TAG}/Papirus/64x64
and tools/icons/fetch-icons.mjs fetches them again.
`)

const names = [...new Set(SYMBOLS)].sort().join(',')
const family = 'Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0..1,0'
const css = (await fetchText(`https://fonts.googleapis.com/css2?family=${family}&icon_names=${names}`))
const ttf = css.match(/src: url\((.+?)\) format\('truetype'\)/)?.[1]
if (!ttf) throw new Error(`no font in:\n${css}`)
const font = await fetchBytes(ttf)
await write(join(root, 'web', 'packages', 'ui', 'src', 'symbols.ttf'), font)
await write(join(root, 'android', 'app', 'src', 'main', 'res', 'font', 'symbols.ttf'), font)
