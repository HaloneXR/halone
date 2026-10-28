<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { SettingKey, Settings } from '@halone/sdk'
import Dropdown from '../shared/Dropdown.vue'
import Icon from '../shared/Icon.vue'
import NoHost from '../shared/NoHost.vue'
import SystemPanel from '../shared/SystemPanel.vue'
import KeyboardPanel from './KeyboardPanel.vue'
import PluginsPanel from './PluginsPanel.vue'
import AboutPanel from './AboutPanel.vue'
import { onPhone, onRoute, useDisplay, useHost, useSettings } from '../shared/host'


type Rule = { type: 'boolean' } | { type: 'number'; min: number; max: number } | { type: 'enum'; values: string[] }

type Item =
  | { kind: 'toggle'; key: SettingKey; label: string; help: string; unavailable?: () => string | null }
  | { kind: 'rate'; key: 'display.refreshRate'; label: string; help: string }
  | { kind: 'slider'; key: SettingKey; label: string; help: string; step: number; format: (v: number) => string }
  | { kind: 'choice'; key: SettingKey; label: string; help: string; labels: Record<string, string>; style: 'segmented' | 'dropdown' }
  | {
      kind: 'action'
      key: string
      label: string
      help: string
      button: string
      run: () => Promise<unknown>
      enabledBy?: SettingKey
      /** Kept on the phone, which otherwise leaves actions out. */
      onPhone?: boolean
    }

interface Group {
  title?: string
  note?: string
  /** Shown above the card while it returns something. */
  warning?: () => string | null
  /** Left out while this is false. */
  shown?: () => boolean
  items: Item[]
}

interface Section {
  id: string
  title: string
  icon: string
  groups: Group[]
  /** A section that is a page of its own rather than settings. */
  panel?: 'system' | 'keyboard' | 'plugins' | 'about'
  /** Only makes sense in the environment, so the phone leaves it out. */
  environmentOnly?: boolean
}

const { halone, status, error } = useHost()
const settings = useSettings(halone)
const display = useDisplay(halone)
const chosenRate = computed(() => display.value?.rates.find((rate) => rate.hz === settings.value?.['display.refreshRate']) ?? null)
const schema = ref<Record<string, Rule>>({})
const message = ref<string | null>(null)
const query = ref('')
const active = ref('windows')
/** Narrow, the list of sections and a section take the whole width in turn, and [opened] says which is showing. */
const width = ref(window.innerWidth)
const narrow = computed(() => onPhone || width.value < NARROW_PX)
const opened = ref(false)
const onResize = () => (width.value = window.innerWidth)
const onPopState = () => (opened.value = false)
window.addEventListener('resize', onResize)
window.addEventListener('popstate', onPopState)
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('popstate', onPopState)
})

/** Narrow, a section is a step in history, so the phone's Back button returns to the list. */
function open(id: string) {
  active.value = id
  query.value = ''
  if (narrow.value && !opened.value) history.pushState({ section: id }, '')
  opened.value = true
}

function close() {
  if (history.state?.section) history.back()
  else opened.value = false
}

onRoute(halone, (route) => {
  if (sections.some((section) => section.id === route)) open(route)
})

fetch('/settings-schema.json')
  .then((response) => response.json())
  .then((value) => (schema.value = value))
  .catch(() => (message.value = 'Could not load the settings schema'))

const NARROW_PX = 720
/** Frames a second below the refresh rate before the settings say so; the meter wobbles by one or two. */
const FRAME_RATE_SLACK = 5
const REFRESH_RATE_FAQ = 'https://halone.app/faq#refresh-rate'
const degrees = (v: number) => `${v}°`

const GLASSES_ACTIONS = {
  disabled: 'Off',
  click: 'Click',
  'context-menu': 'Context menu',
  'middle-click': 'Middle click',
  'scroll-up': 'Scroll up',
  'scroll-down': 'Scroll down',
  'recenter-view': 'Recenter view',
  'toggle-move': 'Pick up or put down a window',
  screenshot: 'Take a screenshot',
}

const PAD_ACTIONS = {
  disabled: 'Off',
  click: 'Click',
  'context-menu': 'Context menu',
  'middle-click': 'Middle click',
  scroll: 'Drag, or a menu when held still',
  'move-window': 'Move window',
  'recenter-cursor': 'Recenter pointer',
  'recenter-view': 'Recenter view',
}

const glassesSlot = (key: SettingKey, label: string, help: string): Item => ({
  kind: 'choice',
  key,
  label,
  help,
  labels: GLASSES_ACTIONS,
  style: 'dropdown',
})

const padSlot = (key: SettingKey, label: string, help: string): Item => ({
  kind: 'choice',
  key,
  label,
  help,
  labels: PAD_ACTIONS,
  style: 'dropdown',
})

const allSections: Section[] = [
  {
    id: 'windows',
    title: 'Windows',
    icon: 'select_window',
    groups: [
      {
        title: 'Following',
        items: [
          { kind: 'toggle', key: 'follow.enabled', label: 'Windows follow me', help: 'Off keeps every window where it is in the room.' },
          {
            kind: 'slider',
            key: 'follow.marginDegrees',
            label: 'Margin',
            help: 'How far you can look past the edge of your windows before they come after you.',
            step: 1,
            format: degrees,
          },
          {
            kind: 'slider',
            key: 'follow.catchUpSeconds',
            label: 'Catch-up pace',
            help: 'How quickly windows catch up when you turn to look somewhere else. Slower feels calmer.',
            step: 0.05,
            format: (v) => `${v.toFixed(2)} s`,
          },
        ],
      },
      {
        title: 'Layout',
        items: [
          { kind: 'slider', key: 'layout.slotSpacingDegrees', label: 'Window spacing', help: 'Angle between neighbouring windows around you.', step: 1, format: degrees },
          {
            kind: 'choice',
            key: 'layout.collisionPolicy',
            label: 'When a window is moved onto another',
            help: 'Whether windows make room for each other.',
            labels: { 'avoid-all': 'Avoid all', 'avoid-pinned': 'Avoid pinned', overlap: 'Allow overlap' },
            style: 'segmented',
          },
        ],
      },
      {
        title: 'Dock',
        items: [
          {
            kind: 'choice',
            key: 'dock.visibility',
            label: 'Dock',
            help: 'Auto-hide brings it up when you look or point below your windows.',
            labels: { always: 'Always visible', 'auto-hide': 'Auto-hide' },
            style: 'segmented',
          },
        ],
      },
      {
        title: 'Hiding',
        items: [
          {
            kind: 'toggle',
            key: 'windows.autoHide',
            label: 'Hide windows when not pointing at them',
            help: 'Every window fades out once the pointer leaves them all, and comes back when it points where one is.',
          },
          {
            kind: 'slider',
            key: 'windows.autoHideDelaySeconds',
            label: 'Hide after',
            help: 'How long the pointer has to be off your windows before they go.',
            step: 0.1,
            format: (v) => `${v.toFixed(1)} s`,
          },
        ],
      },
      {
        title: 'Window buttons',
        items: [
          {
            kind: 'toggle',
            key: 'windows.shapeButton',
            label: 'Shape button',
            help: 'A button on every title bar that switches the window between landscape, portrait and 4:3.',
          },
          {
            kind: 'toggle',
            key: 'windows.reclaimPhone',
            label: 'Keep the pad after closing an app',
            help: 'Closing an Android app’s window can move the app onto the phone’s screen, in front of the pad. With this on, Halone comes straight back.',
          },
        ],
      },
    ],
  },
  {
    id: 'browser',
    title: 'Browser',
    icon: 'language',
    groups: [
      {
        items: [
          {
            kind: 'choice',
            key: 'browser.searchEngine',
            label: 'Search engine',
            help: 'Used for anything typed in the address field that is not an address.',
            labels: { duckduckgo: 'DuckDuckGo', google: 'Google', bing: 'Bing' },
            style: 'segmented',
          },
          {
            kind: 'choice',
            key: 'browser.userAgent',
            label: 'Ask sites for',
            help: 'Some sites send phones a simpler page. Desktop asks for the full one.',
            labels: { desktop: 'Desktop pages', mobile: 'Mobile pages' },
            style: 'segmented',
          },
          {
            kind: 'toggle',
            key: 'browser.autoHideToolbar',
            label: 'Hide the toolbar while scrolling',
            help: 'Scrolling down hides it; scrolling back up brings it back.',
          },
          {
            kind: 'toggle',
            key: 'browser.adBlocker',
            label: 'Block ads and trackers',
            help: 'uBlock Origin, in every web window and private tab. Turning it on the first time downloads it.',
          },
        ],
      },
      {
        title: 'Browsing data',
        items: [
          {
            kind: 'action',
            key: 'library',
            label: 'History, bookmarks and downloads',
            help: 'Pages you visited, saved and downloaded.',
            button: 'Open',
            run: () => halone.apps.launch('library', 'history'),
          },
          {
            kind: 'action',
            key: 'clear',
            label: 'Clear history and cookies',
            help: 'Choose what to clear: history, cookies and site data, cached files.',
            button: 'Clear…',
            run: () => halone.apps.launch('library', 'clear'),
          },
        ],
      },
    ],
  },
  {
    id: 'display',
    title: 'Display',
    icon: 'brightness_6',
    groups: [
      {
        title: 'Refresh rate and 3D',
        shown: () => (display.value?.rates.length ?? 0) > 0,
        warning: () => {
          const state = display.value
          if (!state?.running || state.refreshHz === null || state.renderFps === null) return null
          if (state.renderFps >= state.refreshHz - FRAME_RATE_SLACK) return null
          return `Halone is drawing about ${Math.round(state.renderFps)} frames a second, fewer than the glasses' ${Math.round(state.refreshHz)} Hz. Some phones limit how fast apps can draw to a second screen.`
        },
        items: [
          {
            kind: 'rate',
            key: 'display.refreshRate',
            label: 'Refresh rate',
            help: 'How often the glasses redraw. Higher is smoother, if this phone keeps up.',
          },
          {
            kind: 'toggle',
            key: 'display.stereo',
            label: 'Enable 3D',
            help: 'One image per eye, so windows and videos have depth. Off shows both eyes the same picture.',
            unavailable: () => (chosenRate.value?.stereo === false ? `The glasses only show 2D at ${chosenRate.value.hz} Hz.` : null),
          },
        ],
        note: 'Changes apply straight away, and open windows stay open. The glasses go dark for a moment while they switch.',
      },
      {
        items: [
          { kind: 'slider', key: 'display.brightness', label: 'Brightness', help: 'Glasses display brightness.', step: 0.05, format: (v) => `${Math.round(v * 100)}%` },
          {
            kind: 'slider',
            key: 'panel.distanceMetres',
            label: 'Window distance',
            help: 'How far away windows feel. Their size in your view stays the same.',
            step: 0.5,
            format: (v) => `${v.toFixed(1)} m`,
          },
        ],
      },
      {
        title: 'Video',
        items: [
          {
            kind: 'slider',
            key: 'media.sphereDistanceMetres',
            label: '180° and 360° distance',
            help: 'How far away a 2D video around you seems. Close to the window distance is easiest to focus on; 3D videos keep their own depth.',
            step: 0.5,
            format: (v) => `${v.toFixed(1)} m`,
          },
        ],
      },
      {
        title: 'Bed mode',
        items: [
          {
            kind: 'toggle',
            key: 'bedMode.enabled',
            label: 'Bed mode',
            help: 'Looking up at the ceiling for a while turns the world so that up becomes in front of you, for using the glasses lying down.',
          },
          {
            kind: 'slider',
            key: 'bedMode.dwellSeconds',
            label: 'Look up for',
            help: 'How long you look up before the countdown starts.',
            step: 1,
            format: (v) => `${v} s`,
          },
          {
            kind: 'slider',
            key: 'bedMode.countdownSeconds',
            label: 'Countdown',
            help: 'How long the countdown runs. Looking back down cancels it.',
            step: 1,
            format: (v) => `${v} s`,
          },
          {
            kind: 'slider',
            key: 'bedMode.forwardDegrees',
            label: 'Straight ahead is',
            help: 'How far above the horizon your windows sit in bed mode. 90° is the ceiling, for lying flat; lower suits a pillow.',
            step: 1,
            format: degrees,
          },
        ],
        note: 'Sitting up for the same time leaves bed mode the same way.',
      },
    ],
  },
  {
    id: 'input',
    title: 'Input',
    icon: 'mouse',
    groups: [
      {
        items: [
          {
            kind: 'choice',
            key: 'input.pointer',
            label: 'Pointer',
            help: 'What moves the cursor: the phone aimed like a remote, the phone screen as a touchpad, or where you look.',
            labels: { phone: 'Phone pointer', touchpad: 'Touchpad', gaze: 'Head gaze' },
            style: 'segmented',
          },
          {
            kind: 'slider',
            key: 'input.touchpadSpeed',
            label: 'Touchpad speed',
            help: 'How far the cursor moves for a swipe on the phone.',
            step: 0.01,
            format: (v) => `${v.toFixed(2)}°`,
          },
          {
            kind: 'slider',
            key: 'input.mouseSpeed',
            label: 'Mouse speed',
            help: 'How far the cursor moves for a movement of a connected mouse or trackpad.',
            step: 0.005,
            format: (v) => `${v.toFixed(3)}°`,
          },
        ],
        note: 'Connecting a mouse or trackpad over Bluetooth switches to it automatically, and a connected keyboard types into the window under the cursor.',
      },
    ],
  },
  {
    id: 'gestures',
    title: 'Gestures',
    icon: 'touch_app',
    groups: [
      {
        title: 'Tapping the glasses',
        items: [
          glassesSlot('gesture.glasses.left.tap', 'Tap on the left', 'A light knock on the left side of the frame.'),
          glassesSlot('gesture.glasses.right.tap', 'Tap on the right', 'A light knock on the right side of the frame.'),
          glassesSlot('gesture.glasses.left.doubleTap', 'Double tap on the left', 'Two quick knocks on the left side.'),
          glassesSlot('gesture.glasses.right.doubleTap', 'Double tap on the right', 'Two quick knocks on the right side.'),
          {
            kind: 'slider',
            key: 'gesture.glasses.scrollPx',
            label: 'Scroll distance',
            help: 'How far one tap scrolls, when a tap is set to scroll.',
            step: 50,
            format: (v) => `${v} px`,
          },
        ],
        note: 'A double tap has to be told apart from a single one, so turning one on for a side makes single taps on that side wait about a third of a second before they act.',
      },
      {
        title: 'Phone pad',
        items: [
          padSlot('gesture.pad.tap1', 'Tap', 'One finger, lifted quickly.'),
          padSlot('gesture.pad.tap2', 'Two-finger tap', 'Two fingers, lifted quickly.'),
          padSlot('gesture.pad.tap3', 'Three-finger tap', 'Three fingers, lifted quickly.'),
          padSlot('gesture.pad.hold1', 'Hold', 'One finger kept down, until it lifts.'),
          padSlot('gesture.pad.hold2', 'Two-finger hold', 'Two fingers kept down, until one lifts.'),
          padSlot('gesture.pad.hold3', 'Three-finger hold', 'Three fingers kept down, until one lifts.'),
        ],
        note: 'Swiping one finger across the pad always scrolls what the pointer is on.',
      },
      {
        title: 'Phone as touchpad',
        items: [
          padSlot('gesture.touchpad.tap1', 'Touchpad tap', 'One finger, lifted quickly.'),
          padSlot('gesture.touchpad.tap2', 'Touchpad two-finger tap', 'Two fingers, lifted quickly.'),
          padSlot('gesture.touchpad.tap3', 'Touchpad three-finger tap', 'Three fingers, lifted quickly.'),
          padSlot('gesture.touchpad.hold1', 'Touchpad hold', 'One finger kept still.'),
          padSlot('gesture.touchpad.hold2', 'Touchpad two-finger hold', 'Two fingers kept still.'),
          padSlot('gesture.touchpad.hold3', 'Touchpad three-finger hold', 'Three fingers kept still.'),
        ],
        note: 'Swiping one finger moves the cursor, two fingers scroll, and a double tap held down drags. A connected trackpad uses these too.',
      },
    ],
  },
  {
    id: 'keyboard',
    title: 'Keyboard',
    icon: 'keyboard',
    groups: [],
    panel: 'keyboard',
  },
  {
    id: 'plugins',
    title: 'Plugins',
    icon: 'extension',
    groups: [],
    panel: 'plugins',
  },
  {
    id: 'developer',
    title: 'Developer',
    icon: 'code',
    groups: [
      {
        items: [
          {
            kind: 'toggle',
            key: 'developer.enabled',
            label: 'Developer mode',
            help: 'Lets the halone command line install apps over adb and run apps from your computer while you build them, with no prompt. Turning it off removes those apps.',
          },
          {
            kind: 'toggle',
            key: 'developer.displayStats',
            label: 'Show display stats',
            help: 'The glasses’ refresh rate and the frames actually drawn, along the bottom of the phone pad.',
          },
        ],
      },
      {
        shown: () => settings.value?.['testMode.enabled'] === true,
        items: [
          {
            kind: 'toggle',
            key: 'testMode.enabled',
            label: 'Test mode',
            help: 'Offers to run the environment on the phone’s own screen, with no glasses. Turning it off hides it again; tapping the glasses on the home screen ten times brings it back.',
          },
        ],
      },
      {
        title: 'Head tracking',
        items: [
          {
            kind: 'toggle',
            key: 'tracking.custom',
            label: 'Custom tracking tuning',
            help: 'Off uses the tuning made for your glasses. On uses the two settings below instead.',
          },
          {
            kind: 'slider',
            key: 'tracking.predictionMs',
            label: 'Prediction',
            help: 'How far ahead of your head the view is drawn. More is less lag; too much wobbles and turns sensor noise into jitter.',
            step: 1,
            format: (v) => `${v} ms`,
          },
          {
            kind: 'slider',
            key: 'tracking.steadinessDegrees',
            label: 'Steadiness',
            help: 'How still the view holds while your head is still. More is stiller; too much feels sticky when you start to move.',
            step: 0.1,
            format: (v) => `${v.toFixed(1)}°`,
          },
          {
            kind: 'choice',
            key: 'tracking.framePacing',
            label: 'Frame pacing',
            help: 'Waiting for each frame to reach the display cuts lag on some glasses and halves the frame rate on others. Matching the glasses draws one frame per glasses refresh, by their own clock.',
            labels: { auto: 'Auto', wait: 'Wait for each frame', free: 'Don’t wait', glasses: 'Match the glasses' },
            style: 'dropdown',
          },
        ],
        note: 'Changes apply straight away, so you can tune them while wearing the glasses.',
      },
    ],
  },
  {
    id: 'privacy',
    title: 'Privacy',
    icon: 'lock',
    groups: [
      {
        items: [
          {
            kind: 'toggle',
            key: 'diagnostics.crashReports',
            label: 'Crash reports',
            help: 'Keeps Halone\'s own log on this phone, and after a crash offers to let you share a report: the app version, phone model, Android version, the error and that log, with web addresses and file names removed. Nothing is sent unless you share it, and turning this off deletes the log.',
          },
          {
            kind: 'action',
            key: 'shareLog',
            label: 'Share log',
            help: 'For when something goes wrong without a crash: shares the same log, scrubbed the same way, from the phone.',
            button: 'Share…',
            run: () => halone.system.shareLog(),
            enabledBy: 'diagnostics.crashReports',
            onPhone: true,
          },
        ],
      },
    ],
  },
  {
    id: 'system',
    title: 'System',
    icon: 'memory',
    groups: [],
    panel: 'system',
    environmentOnly: true,
  },
  {
    id: 'about',
    title: 'About',
    icon: 'info',
    groups: [],
    panel: 'about',
  },
]

/** On the phone, without what only works in the environment: windows it would open there, and its diagnostics. Sharing the log works from either. */
const sections = onPhone
  ? allSections
      .filter((section) => !section.environmentOnly)
      .map((section) => ({
        ...section,
        groups: section.groups
          .map((group) => ({ ...group, items: group.items.filter((item) => item.kind !== 'action' || item.onPhone) }))
          .filter((group) => group.items.length > 0),
      }))
  : allSections

function rule(key: SettingKey): Rule | undefined {
  return schema.value[key]
}

function options(item: Extract<Item, { kind: 'choice' }>) {
  const r = rule(item.key)
  const values = r?.type === 'enum' ? r.values : Object.keys(item.labels)
  return values.map((value) => ({ value, label: item.labels[value] ?? value }))
}

function rateOptions() {
  return (display.value?.rates ?? []).map((rate) => ({
    value: String(rate.hz),
    label: `${rate.hz} Hz (${[rate.stereo ? 'supports 3D' : '2D only', ...(rate.matchesPhone ? [] : ['may stutter'])].join(', ')})`,
  }))
}

function learnMore() {
  halone.browser.open(REFRESH_RATE_FAQ).catch(() => (message.value = `See ${REFRESH_RATE_FAQ}`))
}

function shown(groups: Group[]) {
  return groups.filter((group) => !group.shown || group.shown())
}

function range(key: SettingKey) {
  const r = rule(key)
  return r?.type === 'number' ? r : { min: 0, max: 1 }
}

async function set<K extends SettingKey>(key: K, value: Settings[K]) {
  try {
    message.value = null
    await halone.settings.set(key, value)
  } catch (caught) {
    message.value = caught instanceof Error ? caught.message : String(caught)
  }
}

const needle = computed(() => query.value.trim().toLowerCase())

const visible = computed<{ section: Section; groups: Group[] }[]>(() => {
  if (!needle.value) {
    const section = sections.find((s) => s.id === active.value) ?? sections[0]!
    return [{ section, groups: shown(section.groups) }]
  }
  return sections
    .map((section) => ({
      section,
      groups: shown(section.groups)
        .map((group) => ({
          ...group,
          items: group.items.filter((item) =>
            [section.title, group.title ?? '', item.label, item.help].join(' ').toLowerCase().includes(needle.value),
          ),
        }))
        .filter((group) => group.items.length > 0),
    }))
    .filter((entry) => entry.groups.length > 0 || (entry.section.panel && entry.section.title.toLowerCase().includes(needle.value)))
})
</script>

<template>
  <NoHost :status="status" :error="error" />
  <div v-if="status === 'ready' && settings" class="settings" :class="{ narrow, opened: narrow && opened && !needle, searching: narrow && needle }">
    <nav>
      <h1 class="x-title">Settings</h1>
      <label class="search">
        <Icon name="search" />
        <input v-model="query" class="x-input" placeholder="Search" spellcheck="false" autocomplete="off" />
      </label>
      <template v-if="!narrow || !needle">
        <button
          v-for="section in sections"
          :key="section.id"
          class="nav-item"
          :class="{ active: !narrow && !needle && active === section.id }"
          @click="open(section.id)"
        >
          <Icon class="icon" :name="section.icon" />
          <span class="nav-label">{{ section.title }}</span>
          <Icon v-if="narrow" class="chevron" name="chevron_right" />
        </button>
      </template>
    </nav>

    <main>
      <button v-if="narrow && !needle" class="back" @click="close">
        <Icon name="arrow_back" />{{ visible[0]?.section.title }}
      </button>
      <p v-if="message" class="message">{{ message }}</p>
      <p v-if="needle && visible.length === 0" class="x-muted">Nothing matches “{{ query }}”.</p>
      <template v-for="entry in visible" :key="entry.section.id">
        <h2 v-if="needle" class="section-title"><Icon :name="entry.section.icon" />{{ entry.section.title }}</h2>
        <SystemPanel v-if="entry.section.panel === 'system'" />
        <KeyboardPanel v-else-if="entry.section.panel === 'keyboard'" />
        <PluginsPanel v-else-if="entry.section.panel === 'plugins'" />
        <AboutPanel v-else-if="entry.section.panel === 'about'" />
        <template v-for="(group, index) in entry.groups" :key="index">
          <h3 v-if="group.title" class="x-section-title">{{ group.title }}</h3>
          <p v-if="group.warning?.()" class="warning">
            {{ group.warning() }} <a href="#" @click.prevent="learnMore">Learn more</a>
          </p>
          <div class="x-card group">
            <div v-for="item in group.items" :key="item.key" class="item" :class="item.kind">
              <template v-if="item.kind === 'toggle'">
                <span class="text">
                  <span class="label">{{ item.label }}</span>
                  <span class="help x-muted">{{ item.unavailable?.() ?? item.help }}</span>
                </span>
                <input
                  type="checkbox"
                  class="switch"
                  :disabled="!!item.unavailable?.()"
                  :checked="!item.unavailable?.() && (settings[item.key] as boolean)"
                  @change="set(item.key, ($event.target as HTMLInputElement).checked as never)"
                />
              </template>

              <template v-else-if="item.kind === 'rate'">
                <span class="text">
                  <span class="label">{{ item.label }}</span>
                  <span class="help x-muted">{{ item.help }}</span>
                  <span v-if="chosenRate?.matchesPhone === false" class="help warning">
                    This phone's screen can't refresh in step with {{ chosenRate.hz }} Hz, so motion may stutter.
                    <a href="#" @click.prevent="learnMore">Learn more</a>
                  </span>
                </span>
                <Dropdown
                  :model-value="String(settings[item.key])"
                  :options="rateOptions()"
                  @update:model-value="set(item.key, Number($event))"
                />
              </template>

              <template v-else-if="item.kind === 'action'">
                <span class="text">
                  <span class="label">{{ item.label }}</span>
                  <span class="help x-muted">{{ item.help }}</span>
                </span>
                <button class="x-button" :disabled="item.enabledBy !== undefined && !settings[item.enabledBy]" @click="item.run().catch((caught) => (message = String(caught)))">{{ item.button }}</button>
              </template>

              <template v-else-if="item.kind === 'slider'">
                <span class="row">
                  <span class="label">{{ item.label }}</span>
                  <span class="value">{{ item.format(settings[item.key] as number) }}</span>
                </span>
                <span class="help x-muted">{{ item.help }}</span>
                <input
                  type="range"
                  :min="range(item.key).min"
                  :max="range(item.key).max"
                  :step="item.step"
                  :value="settings[item.key]"
                  @change="set(item.key, Number(($event.target as HTMLInputElement).value) as never)"
                />
              </template>

              <template v-else>
                <span class="text">
                  <span class="label">{{ item.label }}</span>
                  <span class="help x-muted">{{ item.help }}</span>
                </span>
                <div v-if="item.style === 'segmented'" class="segmented">
                  <button
                    v-for="option in options(item)"
                    :key="option.value"
                    class="x-button small"
                    :class="{ primary: settings[item.key] === option.value }"
                    @click="set(item.key, option.value as never)"
                  >
                    {{ option.label }}
                  </button>
                </div>
                <Dropdown
                  v-else
                  :model-value="settings[item.key] as string"
                  :options="options(item)"
                  @update:model-value="set(item.key, $event as never)"
                />
              </template>
            </div>
          </div>
          <p v-if="group.note" class="note x-muted">{{ group.note }}</p>
        </template>
      </template>
    </main>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  height: 100%;
}

.settings.narrow {
  display: block;
}

.narrow nav {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  border-right: 0;
}

.narrow main {
  display: none;
  height: 100%;
}

.narrow.opened nav {
  display: none;
}

.narrow.opened main,
.narrow.searching main {
  display: block;
  height: auto;
}

.narrow.searching nav {
  height: auto;
}

.narrow.opened main {
  height: 100%;
}

.narrow .nav-item {
  min-height: 56px;
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
}

.chevron {
  margin-left: auto;
  color: var(--text-muted);
}

.nav-label {
  flex: 1;
}

.back {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 0 18px -8px;
  padding: 8px;
  border: 0;
  border-radius: var(--radius-small);
  background: none;
  font-size: var(--font-size-large);
  font-weight: 500;
  cursor: pointer;
}

.search {
  position: relative;
  display: flex;
  margin-bottom: 14px;
  color: var(--text-muted);
}

.search .x-icon {
  position: absolute;
  top: 50%;
  left: 16px;
  transform: translateY(-50%);
  pointer-events: none;
}

.search .x-input {
  flex: 1;
  min-width: 0;
  padding-left: 52px;
  color: var(--text);
}

.x-phone nav {
  padding: 20px 16px;
}

.x-phone main {
  padding: 16px 16px 48px;
}

.x-phone .group {
  padding: 2px 16px;
}

.x-phone .item.toggle,
.x-phone .item.action,
.x-phone .item.choice {
  flex-wrap: wrap;
  gap: 12px;
}

.x-phone .segmented {
  flex-wrap: wrap;
}

nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 280px;
  flex-shrink: 0;
  padding: 36px 20px;
  overflow-y: auto;
  border-right: 1px solid var(--border);
}

.x-title {
  margin: 0 12px 20px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 60px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-small);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.nav-item:hover {
  background: var(--surface-hover);
}

.nav-item.active {
  border-color: var(--accent);
  background: var(--surface-raised);
  color: var(--accent-strong);
}

.icon {
  font-size: 28px;
  color: var(--text-muted);
}

.nav-item.active .icon {
  color: var(--accent-strong);
}

main {
  flex: 1;
  min-width: 0;
  padding: 36px 40px 80px;
  overflow-y: auto;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0 4px;
  font-size: var(--font-size-large);
  font-weight: 500;
}

h3.x-section-title {
  margin-top: 26px;
}

h3.x-section-title:first-child {
  margin-top: 0;
}

.message {
  color: var(--danger);
  font-size: var(--font-size-small);
}

.warning {
  color: var(--danger);
  font-size: var(--font-size-small);
}

p.warning {
  margin: 0 4px 12px;
}

.warning a {
  color: inherit;
}

.group {
  padding: 4px 24px;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}

.item:last-child {
  border-bottom: 0;
}

.item.toggle,
.item.action,
.item.choice,
.item.rate {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.value {
  color: var(--accent-strong);
  font-variant-numeric: tabular-nums;
}

.help,
.note {
  font-size: var(--font-size-small);
}

.note {
  margin: 12px 4px 0;
}

input[type='range'] {
  width: 100%;
  height: 36px;
  accent-color: var(--accent);
}

.switch {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  accent-color: var(--accent);
}

.segmented {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
}
</style>
