<script setup lang="ts">
import { ref } from 'vue'
import { useHalone, type PluginInfo, type PluginSettingsPage, type PluginSettingValue } from '@halone/sdk'
import Dropdown from '../shared/Dropdown.vue'
import Icon from '../shared/Icon.vue'

const props = defineProps<{ plugin: PluginInfo }>()
const emit = defineEmits<{ back: [] }>()

const halone = useHalone()
const page = ref<PluginSettingsPage | null>(null)
const failure = ref<string | null>(null)

halone.plugins.settings(props.plugin.packageName).then(
  (loaded) => (page.value = loaded),
  (error: Error) => (failure.value = error.message),
)

async function set(key: string, value: PluginSettingValue) {
  failure.value = null
  try {
    await halone.plugins.setSetting(props.plugin.packageName, key, value)
    if (page.value) page.value.values[key] = value
  } catch (error) {
    failure.value = error instanceof Error ? error.message : String(error)
  }
}

function format(value: PluginSettingValue, unit: string) {
  const number = Number(value)
  return `${Number.isInteger(number) ? number : number.toFixed(1)}${unit ? ` ${unit}` : ''}`
}
</script>

<template>
  <div class="plugin-settings">
    <button class="back" @click="emit('back')"><Icon name="arrow_back" />{{ plugin.label }}</button>
    <p v-if="failure" class="failure">{{ failure }}</p>
    <div v-if="page" class="x-card group">
      <div v-for="item in page.items" :key="item.key" class="item" :class="item.kind">
        <template v-if="item.kind === 'toggle'">
          <span class="text">
            <span class="label">{{ item.label }}</span>
            <span v-if="item.help" class="help x-muted">{{ item.help }}</span>
          </span>
          <input
            type="checkbox"
            class="switch"
            :checked="page.values[item.key] as boolean"
            @change="set(item.key, ($event.target as HTMLInputElement).checked)"
          />
        </template>

        <template v-else-if="item.kind === 'slider'">
          <span class="row">
            <span class="label">{{ item.label }}</span>
            <span class="value">{{ format(page.values[item.key]!, item.unit) }}</span>
          </span>
          <span v-if="item.help" class="help x-muted">{{ item.help }}</span>
          <input
            type="range"
            :min="item.min"
            :max="item.max"
            :step="item.step"
            :value="page.values[item.key]"
            @change="set(item.key, Number(($event.target as HTMLInputElement).value))"
          />
        </template>

        <template v-else>
          <span class="text">
            <span class="label">{{ item.label }}</span>
            <span v-if="item.help" class="help x-muted">{{ item.help }}</span>
          </span>
          <Dropdown :model-value="page.values[item.key] as string" :options="item.options" @update:model-value="set(item.key, $event)" />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.back {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 0 18px -8px;
  padding: 8px;
  border: 0;
  border-radius: var(--radius-small);
  background: none;
  color: inherit;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
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
.item.choice {
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
.help {
  font-size: var(--font-size-small);
}
.switch {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  accent-color: var(--accent);
}
input[type='range'] {
  width: 100%;
  height: 36px;
  accent-color: var(--accent);
}
.failure {
  color: var(--danger);
}
</style>
