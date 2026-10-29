<script setup lang="ts">
defineProps<{ alreadyOn?: boolean }>()
const emit = defineEmits<{ enable: []; keep: [] }>()
</script>

<template>
  <div class="scrim">
    <div class="x-card disclosure" role="dialog" aria-modal="true" aria-labelledby="disclosure-title">
      <h2 id="disclosure-title" class="x-title">Halone uses Android’s accessibility service</h2>
      <p>
        This is optional, and only for physical keyboards. Turning it on lets Alt+Tab, the Meta (Windows) key and PrintScreen work as
        Halone shortcuts instead of Android opening the app switcher, DeX or taking a phone screenshot. Without it, the Alt shortcuts
        in Settings › Keyboard do the same things.
      </p>
      <h3>Why the AccessibilityService API</h3>
      <p>
        Android hands those keys to itself before any app sees them. An accessibility service is the only way an app can receive key
        presses from a physical keyboard first, so Halone uses it for that and nothing else.
      </p>
      <h3>What it accesses</h3>
      <p>
        Key presses from a physical keyboard, and only while the environment is running on the glasses. Halone keeps Alt+Tab, Meta and
        PrintScreen and passes every other key on unchanged. While the environment is stopped, every key goes straight on as usual. It
        does not read the screen, other apps, notifications or touch input.
      </p>
      <h3>What is collected or shared</h3>
      <p>Nothing. Key presses are not recorded, stored or sent anywhere, and never leave the phone.</p>
      <p v-if="alreadyOn" class="x-muted">
        “Halone keyboard shortcuts” is already on in the phone’s accessibility settings. Halone will not use it until you choose to
        enable it here, and you can turn it off there at any time.
      </p>
      <p v-else class="x-muted">
        Choosing to enable opens the phone’s accessibility settings, where you turn on “Halone keyboard shortcuts”. You can turn it off
        there at any time.
      </p>
      <div class="actions">
        <button class="x-button" @click="emit('keep')">Keep alternative shortcuts</button>
        <button class="x-button primary" @click="emit('enable')">Enable system keyboard shortcuts</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scrim {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: grid;
  place-items: center;
  padding: 32px;
  background: rgb(0 0 0 / 0.7);
}
.disclosure {
  display: flex;
  flex-direction: column;
  width: min(1100px, 100%);
  max-height: 100%;
  padding: 36px 40px;
  overflow: auto;
  font-size: var(--font-size-large);
}
.disclosure .x-title {
  margin: 0 0 8px;
}
.disclosure h3 {
  margin: 20px 0 4px;
  font-size: var(--font-size-large);
}
.disclosure p {
  margin: 8px 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 24px;
}
.actions .x-button {
  font-size: var(--font-size-large);
  padding: 18px 32px;
}
</style>
