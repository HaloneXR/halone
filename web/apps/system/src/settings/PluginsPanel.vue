<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useHalone, type PluginInfo } from '@halone/sdk'
import Icon from '../shared/Icon.vue'
import PluginSettingsPage from './PluginSettingsPage.vue'

const halone = useHalone()
const plugins = ref<PluginInfo[] | null>(null)
const failure = ref<string | null>(null)
/** The plugin whose settings are open in place of the list. */
const opened = ref<PluginInfo | null>(null)

function show(list: PluginInfo[]) {
  plugins.value = list
  if (list.some((plugin) => plugin.new)) halone.plugins.acknowledge().catch(() => {})
}

halone.plugins.list().then(show, (error: Error) => (failure.value = error.message))
const off = halone.on('plugins.changed', show)
onBeforeUnmount(off)

const STATES: Record<PluginInfo['state'], string> = {
  disabled: 'Off',
  enabled: 'On',
  'certificate-changed': 'Signed by someone else since you turned it on. Turn it on again only if you expected that.',
  incompatible: 'Needs a newer version of Halone',
}

function describe(plugin: PluginInfo) {
  if (plugin.state === 'enabled') return plugin.apps.length ? `Adds ${plugin.apps.join(', ')}` : 'Adds no apps yet'
  return STATES[plugin.state]
}

function setEnabled(plugin: PluginInfo, enabled: boolean) {
  failure.value = null
  halone.plugins.setEnabled(plugin.packageName, enabled).catch((error: Error) => (failure.value = error.message))
}
</script>

<template>
  <PluginSettingsPage v-if="opened" :plugin="opened" @back="opened = null" />
  <div v-else class="plugins">
    <p class="x-muted">
      Plugins are Android apps that add their own apps to Halone, drawn by the plugin itself. Install one on the phone and it appears here,
      off until you turn it on. A plugin runs with the permissions Android gave it, so turn on only those you trust.
    </p>
    <p v-if="failure" class="failure">{{ failure }}</p>
    <p v-if="plugins && plugins.length === 0" class="x-muted empty">No plugins are installed.</p>
    <div v-for="plugin in plugins ?? []" :key="plugin.packageName" class="plugin" :class="plugin.state">
      <span class="text">
        <span class="label">{{ plugin.label }} <span class="x-muted version">{{ plugin.version }}</span></span>
        <span class="help x-muted">{{ describe(plugin) }}</span>
        <span class="help x-muted package">{{ plugin.packageName }}</span>
      </span>
      <button v-if="plugin.hasSettings" class="settings" :aria-label="`${plugin.label} settings`" @click="opened = plugin">
        <Icon name="settings" />
      </button>
      <input
        type="checkbox"
        class="switch"
        :checked="plugin.state === 'enabled'"
        :disabled="plugin.state === 'incompatible'"
        @change="setEnabled(plugin, ($event.target as HTMLInputElement).checked)"
      />
    </div>
  </div>
</template>

<style scoped>
.plugins > p {
  margin: 0 0 16px;
}
.plugin {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 20px;
  margin-bottom: 10px;
  border-radius: 16px;
  background: var(--surface);
}
.text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}
.label {
  font-weight: 600;
}
.version {
  font-weight: 400;
}
.package {
  font-size: 0.85em;
}
.certificate-changed .help:not(.package) {
  color: var(--danger);
}
.failure {
  color: var(--danger);
}
.settings {
  display: flex;
  padding: 8px;
  border: 0;
  border-radius: var(--radius-small);
  background: none;
  color: inherit;
  cursor: pointer;
}
.settings:hover {
  background: var(--surface-hover);
}
.switch {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  accent-color: var(--accent);
}
</style>
