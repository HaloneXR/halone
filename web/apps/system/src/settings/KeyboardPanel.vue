<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useHalone } from '@halone/sdk'
import { useSettings, useSystemInfo } from '../shared/host'

interface Shortcut {
  keys: string
  label: string
  browser?: boolean
  fallback?: boolean
}

const halone = useHalone()
const info = useSystemInfo(halone)
const settings = useSettings(halone)
const capture = ref<boolean | null>(null)
const desktop = computed(() => info.value?.platform === 'desktop')
/** Raw keyboard mode, where a desktop has it: every key is Halone's while its window is focused. */
const raw = computed(() => desktop.value && info.value?.os === 'linux')

function setRaw(on: boolean) {
  halone.settings.set('input.rawKeyboard', on).catch(() => {})
}
const shortcuts = ref<Shortcut[]>([])

fetch('/shortcuts.json')
  .then((response) => response.json())
  .then((list: Shortcut[]) => (shortcuts.value = list), () => {})

const check = () => halone.input.keyCapture().then(({ enabled }) => (capture.value = enabled), () => (capture.value = null))
check()
// Turned on in the phone's settings, so look again now and then rather than wait to be told.
const poll = setInterval(check, 2000)
onBeforeUnmount(() => clearInterval(poll))

const pretty = (keys: string) => keys.replace(/ArrowLeft/g, '←').replace(/ArrowRight/g, '→').replace(/\+/g, ' + ')
const shell = computed(() => shortcuts.value.filter((s) => !s.browser && !!s.fallback === !capture.value))
const browser = computed(() => shortcuts.value.filter((s) => s.browser))
</script>

<template>
  <div class="keyboard">
    <div v-if="raw" class="capture" :class="{ on: capture }">
      <div>
        <strong>{{ capture ? 'Halone has the keyboard' : 'The desktop keeps some keys' }}</strong>
        <p class="x-muted">
          {{
            capture
              ? 'Every key comes to Halone while its window is focused, Alt+Tab and the Meta key included. The launcher’s button gives them back to the desktop.'
              : 'The desktop keeps Alt+Tab and the Meta key for itself; until Halone has the keyboard the Alt shortcuts below stand in. Raw keyboard gives Halone every key while its window is focused, and asks for your password once.'
          }}
        </p>
      </div>
      <label class="raw">
        Raw keyboard
        <input
          type="checkbox"
          class="switch"
          :checked="settings?.['input.rawKeyboard'] ?? false"
          @change="setRaw(($event.target as HTMLInputElement).checked)"
        />
      </label>
    </div>
    <div v-else-if="!desktop" class="capture" :class="{ on: capture }">
      <div>
        <strong>{{ capture ? 'Key capture is on' : 'Key capture is off' }}</strong>
        <p class="x-muted">
          {{
            capture
              ? 'Alt+Tab, the Meta key and PrintScreen are Halone’s while the environment runs.'
              : 'Android keeps Alt+Tab, the Meta key and PrintScreen for itself. Turn on “Halone keyboard shortcuts” in the phone’s accessibility settings to use them here; until then the Alt shortcuts below stand in.'
          }}
        </p>
      </div>
      <button v-if="capture === false" class="x-button primary" @click="halone.input.openKeyCaptureSettings().catch(() => {})">
        Open on the phone
      </button>
    </div>

    <h3>Everywhere</h3>
    <dl>
      <template v-for="s in shell" :key="s.keys">
        <dt><kbd>{{ pretty(s.keys) }}</kbd></dt>
        <dd>{{ s.label }}</dd>
      </template>
    </dl>
    <h3>In a web window</h3>
    <dl>
      <template v-for="s in browser" :key="s.keys">
        <dt><kbd>{{ pretty(s.keys) }}</kbd></dt>
        <dd>{{ s.label }}</dd>
      </template>
    </dl>
    <p class="x-muted">In any app, Ctrl+C, Ctrl+X and Ctrl+V copy, cut and paste; in a terminal, add Shift.</p>
  </div>
</template>

<style scoped>
.capture {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 20px;
  border-radius: 16px;
  background: var(--surface);
}
.capture p {
  margin: 6px 0 0;
}
.raw {
  display: flex;
  align-items: center;
  gap: 12px;
  white-space: nowrap;
}
h3 {
  margin: 24px 0 8px;
}
dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 8px 24px;
  margin: 0;
}
dt,
dd {
  margin: 0;
}
kbd {
  font: inherit;
  padding: 2px 10px;
  border-radius: 8px;
  background: var(--surface-hover);
}
</style>
