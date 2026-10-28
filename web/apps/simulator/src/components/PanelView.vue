<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSimulator } from '../injection'
import { angularHalfWidthDegrees, panelDistancePx, wrapDegrees } from '../shell/geometry'
import type { SimWindow } from '../shell/shell'

const props = defineProps<{ window: SimWindow; camera: string; blend: boolean; screenToPanel: number }>()
const emit = defineEmits<{ grabMove: [dx: number, dy: number] }>()

const { shell, bridge, appsOrigin } = useSimulator()
const frame = ref<HTMLIFrameElement>()
const chromeFrame = ref<HTMLIFrameElement>()
const chromeApp = shell.state.apps.find((app) => app.id === 'browser')
const chromeSrc = chromeApp ? new URL(chromeApp.entry, appsOrigin).href : undefined
const BLANK = 'data:text/html,<body style="background:%23000">'
const activeTab = computed(() => props.window.browser?.tabs.find((tab) => tab.id === props.window.browser?.activeTab))
const src = ref<string>()

const focused = computed(() => shell.state.focusedId === props.window.id)
const bar = computed(() => {
  const [left, top, width, height] = shell.scene.titleBar(props.window.widthPx, props.window.heightPx)
  return { left: `${left}px`, top: `${top}px`, width: `${width}px`, height: `${height}px` }
})
const grabbed = computed(() => shell.state.grabbedId === props.window.id)
const hideBar = computed(() => {
  const hiding = shell.state.settings['windows.autoHideTitleBars']
  return hiding === 'always' || (hiding === 'unfocused' && !focused.value)
})

const BEHIND_DEGREES = 85

type CornerName = 'TOP_LEFT' | 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTTOM_RIGHT'
const CORNERS: { name: CornerName; sx: number; sy: number }[] = [
  { name: 'TOP_LEFT', sx: -1, sy: 1 },
  { name: 'TOP_RIGHT', sx: 1, sy: 1 },
  { name: 'BOTTOM_LEFT', sx: -1, sy: -1 },
  { name: 'BOTTOM_RIGHT', sx: 1, sy: -1 },
]
/** As ResizeHandles in core-scene: the press reaches this far out of the corner, and this far in. */
const HANDLE_OUT = 64
const HANDLE_IN = 40

const HANDLE_SIZE = HANDLE_OUT + HANDLE_IN

const handleStyle = (corner: { sx: number; sy: number }) => ({
  left: `${(corner.sx < 0 ? 0 : props.window.widthPx) - HANDLE_OUT}px`,
  top: `${(corner.sy > 0 ? 0 : props.window.heightPx) - HANDLE_OUT}px`,
  width: `${HANDLE_SIZE}px`,
  height: `${HANDLE_SIZE}px`,
  transformOrigin: `${HANDLE_OUT}px ${HANDLE_OUT}px`,
  transform: `scale(${-corner.sx}, ${corner.sy})`,
  // Only the part outside the window, so the page's own corner stays clickable.
  clipPath: `polygon(0 0, ${HANDLE_SIZE}px 0, ${HANDLE_SIZE}px ${HANDLE_OUT}px, ${HANDLE_OUT}px ${HANDLE_OUT}px, ${HANDLE_OUT}px ${HANDLE_SIZE}px, 0 ${HANDLE_SIZE}px)`,
})

const resizing = ref<{ corner: (typeof CORNERS)[number]; widthPx: number; heightPx: number } | null>(null)

function onHandleDown(corner: (typeof CORNERS)[number], event: PointerEvent) {
  if (event.button !== 0) return
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  resizing.value = { corner, widthPx: props.window.widthPx, heightPx: props.window.heightPx }
}

function onHandleMove(event: PointerEvent) {
  const drag = resizing.value
  if (!drag) return
  const limits = props.window.sizeLimits
  const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi)
  drag.widthPx = clamp(drag.widthPx + event.movementX * drag.corner.sx * props.screenToPanel, limits.minWidthPx, limits.maxWidthPx)
  drag.heightPx = clamp(drag.heightPx - event.movementY * drag.corner.sy * props.screenToPanel, limits.minHeightPx, limits.maxHeightPx)
}

function onHandleUp() {
  const drag = resizing.value
  if (!drag) return
  resizing.value = null
  shell.resizeWindow(props.window.id, drag.widthPx, drag.heightPx, drag.corner.name)
}

const previewStyle = computed(() => {
  const drag = resizing.value
  if (!drag) return {}
  return {
    left: `${drag.corner.sx < 0 ? props.window.widthPx - drag.widthPx : 0}px`,
    top: `${drag.corner.sy > 0 ? props.window.heightPx - drag.heightPx : 0}px`,
    width: `${drag.widthPx}px`,
    height: `${drag.heightPx}px`,
  }
})
const GRIP = [
  [2, 2],
  [9, 2],
  [2, 9],
  [9, 9],
  [2, 16],
  [9, 16],
]

const inFront = computed(
  () =>
    Math.abs(wrapDegrees(props.window.yawDegrees - shell.state.head.yawDegrees)) - angularHalfWidthDegrees(props.window.widthPx) <
    BEHIND_DEGREES,
)

const transform = computed(
  () =>
    `${props.camera} rotateY(${-props.window.yawDegrees}deg) rotateX(${-props.window.pitchDegrees}deg) translateZ(${-panelDistancePx}px) translate(-50%, -50%)`,
)

onMounted(() => {
  if (props.window.kind === 'app' && !props.window.standalone) bridge.attach(props.window.id, frame.value!, props.window.origin ?? undefined)
  else if (chromeFrame.value) bridge.attach(props.window.id, chromeFrame.value)
  src.value = props.window.url
})
onBeforeUnmount(() => bridge.detach(props.window.id))
watch(
  () => props.window.url,
  (url) => {
    if (props.window.standalone) src.value = url
  },
)

function onGrabDown(event: PointerEvent) {
  if (event.button !== 0) return
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  shell.beginGrab(props.window.id)
}

function onGrabMove(event: PointerEvent) {
  if (grabbed.value) emit('grabMove', event.movementX, event.movementY)
}

function onGrabUp() {
  if (grabbed.value) shell.endGrab()
}
</script>

<template>
  <div
    class="panel"
    :class="{ focused, pinned: window.pinned, grabbed, blend, 'hide-bar': hideBar }"
    :style="{ transform, zIndex: grabbed ? 3 : focused ? 2 : 1, visibility: inFront ? 'visible' : 'hidden', opacity: shell.state.windowsOpacity }"
    :data-window-id="window.id"
  >
    <div class="window-frame">
      <div v-if="window.kind === 'web' && chromeSrc" class="chrome" :style="{ height: `${window.chromePx}px` }">
        <iframe ref="chromeFrame" :src="chromeSrc" title="Browser" :width="window.widthPx" height="128" />
      </div>
      <iframe
        v-if="window.kind === 'web'"
        :key="`${activeTab?.id}:${activeTab?.reloads}`"
        :src="activeTab?.url || BLANK"
        :title="window.title"
        :width="window.widthPx"
        :height="window.heightPx - window.chromePx"
        allow="autoplay; fullscreen; clipboard-read; clipboard-write"
      />
      <iframe
        v-else
        ref="frame"
        :src="src"
        :title="window.title"
        :width="window.widthPx"
        :height="window.heightPx"
        allow="autoplay; fullscreen; clipboard-read; clipboard-write"
      />
      <div
        v-if="window.stereoRegion"
        class="stereo-region"
        :style="{
          left: `${window.stereoRegion.xPx}px`,
          top: `${window.stereoRegion.yPx}px`,
          width: `${window.stereoRegion.widthPx}px`,
          height: `${window.stereoRegion.heightPx}px`,
        }"
      >
        <span>{{ window.stereoRegion.stereo === 'side-by-side' ? 'Side by side' : 'Top and bottom' }}: each eye sees its half on the glasses</span>
      </div>
    </div>
    <div
      v-for="corner in CORNERS"
      v-show="!resizing"
      :key="corner.name"
      class="handle"
      :style="handleStyle(corner)"
      @pointerdown.stop="onHandleDown(corner, $event)"
      @pointermove="onHandleMove"
      @pointerup="onHandleUp"
      @pointercancel="onHandleUp"
    >
      <svg viewBox="0 0 104 104" width="104" height="104" aria-hidden="true">
        <path d="M 50 116 L 50 88 A 38 38 0 0 1 88 50 L 116 50" />
      </svg>
    </div>
    <div v-if="resizing" class="resize-preview" :style="previewStyle" />
    <div
      class="bar"
      :style="bar"
      @pointerdown="onGrabDown"
      @pointermove="onGrabMove"
      @pointerup="onGrabUp"
      @pointercancel="onGrabUp"
    >
      <svg class="grip" viewBox="0 0 11 18" width="11" height="18" aria-hidden="true">
        <circle v-for="(dot, i) in GRIP" :key="i" :cx="dot[0]" :cy="dot[1]" r="2" />
      </svg>
      <span class="title">{{ window.title }}</span>
      <span v-if="window.kind === 'web'" class="tag">web</span>
      <span v-else-if="window.standalone" class="tag">web app</span>
      <button
        class="bar-button"
        :class="{ active: window.pinned }"
        :title="window.pinned ? 'Unpin: follow me' : 'Pin to this place'"
        @pointerdown.stop
        @click="shell.setPinned(window.id, !window.pinned)"
      >
        <svg viewBox="0 0 40 40" width="40" height="40" aria-hidden="true">
          <circle cx="20" cy="16" r="5" :class="{ filled: window.pinned }" />
          <line x1="20" y1="21" x2="20" y2="30" />
        </svg>
      </button>
      <button class="bar-button" title="Close" @pointerdown.stop @click="shell.close(window.id)">
        <svg viewBox="0 0 40 40" width="40" height="40" aria-hidden="true">
          <path d="M14 14 L26 26 M26 14 L14 26" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.panel {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
}

.panel.blend {
  mix-blend-mode: screen;
}

iframe {
  display: block;
  border: 0;
  background: #000;
}

.window-frame {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  outline: 3px solid rgba(255, 184, 51, 0.3);
  outline-offset: 4px;
  transition: outline-color 150ms;
}

.stereo-region {
  position: absolute;
  border: 2px dashed rgba(92, 200, 255, 0.8);
  pointer-events: none;
}

.stereo-region span {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 4px 10px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.7);
  color: #9adcff;
  font: 16px system-ui, sans-serif;
}

.chrome {
  overflow: hidden;
}

.panel.focused .window-frame {
  outline-color: rgb(255, 184, 51);
}

.panel.pinned .window-frame {
  outline-color: rgba(64, 204, 255, 0.3);
}

.panel.pinned.focused .window-frame {
  outline-color: rgb(64, 204, 255);
}

.panel.grabbed .window-frame {
  outline-color: #fff;
}

.handle {
  position: absolute;
  z-index: 2;
  cursor: nwse-resize;
  opacity: 0;
  transition: opacity 120ms;
}

.panel:hover .handle {
  opacity: 0.55;
}

.panel .handle:hover {
  opacity: 1;
}

.handle svg {
  overflow: visible;
  fill: none;
  stroke: #fff;
  stroke-width: 5;
  stroke-linecap: round;
}

.resize-preview {
  position: absolute;
  z-index: 3;
  box-sizing: border-box;
  border: 4px solid rgba(255, 255, 255, 0.9);
  border-radius: 24px;
  pointer-events: none;
}

.bar {
  position: absolute;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 10px 0 20px;
  border-radius: 28px;
  background: rgba(22, 28, 38, 0.92);
  color: var(--text-muted);
  font-size: 20px;
  cursor: grab;
  opacity: 0.55;
  transition: opacity 150ms;
}

.panel.focused .bar,
.panel.grabbed .bar {
  opacity: 1;
}

.panel.hide-bar:not(.grabbed, :hover) .bar {
  opacity: 0;
  transition: opacity 600ms;
}

.panel.grabbed .bar {
  cursor: grabbing;
}

.grip {
  flex: none;
  fill: var(--text-muted);
}

.title {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--text);
}

.tag {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 15px;
}

.bar-button {
  flex: none;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
  opacity: 0.7;
}

.bar-button + .bar-button {
  margin-left: -8px;
}

.bar-button:hover,
.bar-button.active {
  opacity: 1;
  background: var(--surface-hover);
}

.bar-button svg {
  display: block;
  fill: none;
  stroke: var(--text);
  stroke-width: 2;
  stroke-linecap: round;
}

.bar-button svg .filled {
  fill: var(--text);
}
</style>
