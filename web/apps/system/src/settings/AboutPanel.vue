<script setup lang="ts">
import { ref } from 'vue'
import { useHalone, type SystemInfo } from '@halone/sdk'
import Icon from '../shared/Icon.vue'
import { onPhone } from '../shared/host'
import { BUNDLED, RESEARCH, type Acknowledgement } from './acknowledgements'

const halone = useHalone()
const info = ref<SystemInfo | null>(null)
const license = ref<{ title: string; text: string } | null>(null)
const failure = ref<string | null>(null)

halone.system.info().then((value) => (info.value = value), () => {})

function openLink(url: string) {
  failure.value = null
  if (onPhone) window.open(url, '_blank')
  else halone.browser.open(url).catch((error: Error) => (failure.value = error.message))
}

async function showLicense(item: Acknowledgement) {
  failure.value = null
  try {
    const response = await fetch(`/licenses/${item.licenseFile}`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    license.value = { title: `${item.name} · ${item.license}`, text: await response.text() }
  } catch (error) {
    failure.value = `Could not open the licence: ${error instanceof Error ? error.message : String(error)}`
  }
}

const CREDITS = [
  { name: 'TheBITLINK', what: 'Design and development' },
  { name: 'BJDB331', what: 'RayNeo GT Series driver development and other functionality' },
  { name: 'Rlkirkland', what: 'XREAL One Series driver testing' },
  { name: '방고장', what: 'XREAL One Series driver testing' },
]

const LINKS = [
  { label: 'Website', url: 'https://halone.app/', icon: 'language' },
  { label: 'Documentation', url: 'https://halone.dev/', icon: 'description' },
  { label: 'Source code', url: 'https://github.com/HaloneXR', icon: 'code' },
]
</script>

<template>
  <div class="about">
    <section class="hero">
      <img class="logo" src="/logo.png" alt="" />
      <div>
        <h2 class="name">Halone</h2>
        <p class="x-muted">Version {{ info?.version ?? '…' }}</p>
        <p class="x-muted">A web-based XR environment for XREAL glasses, driven from an Android phone.</p>
      </div>
    </section>

    <div class="links">
      <button v-for="link in LINKS" :key="link.url" class="x-button small" @click="openLink(link.url)">
        <Icon :name="link.icon" />{{ link.label }}
      </button>
    </div>
    <p v-if="failure" class="failure">{{ failure }}</p>

    <h3 class="x-section-title">Credits</h3>
    <div class="x-card list">
      <div v-for="credit in CREDITS" :key="credit.name" class="entry">
        <span class="text">
          <span class="label">{{ credit.name }}</span>
          <span class="help x-muted">{{ credit.what }}</span>
        </span>
      </div>
    </div>

    <h3 class="x-section-title">Open source software</h3>
    <div class="x-card list">
      <div v-for="item in BUNDLED" :key="item.name" class="entry">
        <button class="text link" @click="openLink(item.url)">
          <span class="label">{{ item.name }}<Icon name="open_in_new" class="out" /></span>
          <span class="help x-muted">{{ item.what }}</span>
        </button>
        <button v-if="item.licenseFile" class="x-button small" @click="showLicense(item)">{{ item.license }}</button>
        <span v-else class="x-muted">{{ item.license }}</span>
      </div>
    </div>

    <h3 class="x-section-title">Research</h3>
    <p class="note x-muted">Halone’s glasses driver was written from what these projects documented about the XREAL protocol.</p>
    <div class="x-card list">
      <div v-for="item in RESEARCH" :key="item.name" class="entry">
        <button class="text link" @click="openLink(item.url)">
          <span class="label">{{ item.name }}<Icon name="open_in_new" class="out" /></span>
          <span class="help x-muted">{{ item.what }}</span>
        </button>
        <span class="x-muted">{{ item.license }}</span>
      </div>
    </div>

    <div v-if="license" class="sheet" @click.self="license = null">
      <div class="x-card dialog" role="dialog" :aria-label="license.title">
        <header>
          <h3>{{ license.title }}</h3>
          <button class="x-icon-button small" aria-label="Close" @click="license = null"><Icon name="close" /></button>
        </header>
        <pre>{{ license.text }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  gap: 28px;
  margin-bottom: 20px;
}

.hero p {
  margin: 4px 0 0;
}

.logo {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
  border-radius: 22%;
}

.name {
  margin: 0;
  font-size: var(--font-size-title);
  font-weight: 600;
}

.links {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 28px;
}

.failure {
  color: var(--danger);
  font-size: var(--font-size-small);
}

h3.x-section-title {
  margin-top: 26px;
}

.list {
  padding: 4px 24px;
}

.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}

.entry:last-child {
  border-bottom: 0;
}

.text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.link {
  flex: 1;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
}

.link:hover .label {
  color: var(--accent-strong);
}

.out {
  margin-left: 8px;
  font-size: 0.9em;
  color: var(--text-muted);
  vertical-align: -0.1em;
}

.help,
.note {
  font-size: var(--font-size-small);
}

.note {
  margin: 0 4px 12px;
}

.sheet {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: grid;
  place-items: center;
  padding: 32px;
  background: rgb(0 0 0 / 0.7);
}

.dialog {
  display: flex;
  flex-direction: column;
  width: min(900px, 100%);
  max-height: 100%;
  padding: 0;
  overflow: hidden;
}

.dialog header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px 16px 24px;
  border-bottom: 1px solid var(--border);
}

.dialog h3 {
  margin: 0;
  font-size: var(--font-size);
  font-weight: 600;
}

pre {
  margin: 0;
  padding: 20px 24px;
  overflow: auto;
  font-size: var(--font-size-small);
  white-space: pre-wrap;
  user-select: text;
}

.x-phone .hero {
  gap: 18px;
}

.x-phone .logo {
  width: 72px;
  height: 72px;
}

.x-phone .list {
  padding: 2px 16px;
}

.x-phone .sheet {
  padding: 12px;
}
</style>
