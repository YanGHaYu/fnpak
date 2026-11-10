<!-- 轻量内联图标：不依赖任何图标库，路径全部内置，离线可用。 -->
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    class="shrink-0"
  >
    <path v-for="(d, index) in shape" :key="index" :d="d" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 },
})

const ICONS = {
  play: ['M8 5l11 7-11 7z'],
  pause: ['M9 5v14', 'M15 5v14'],
  resume: ['M7 5l11 7-11 7z'],
  download: ['M12 3v12', 'M7 11l5 5 5-5', 'M5 21h14'],
  folder: ['M3 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'],
  file: ['M6 2h7l5 5v15H6z', 'M13 2v5h5'],
  rename: ['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'],
  move: ['M12 3v18', 'M3 12h18', 'M7 8l-4 4 4 4', 'M17 8l4 4-4 4'],
  copy: ['M9 9h11v11H9z', 'M5 15H4V4h11v1'],
  delete: ['M3 6h18', 'M8 6V4h8v2', 'M6 6l1 14h10l1-14'],
  retry: ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v5h-5'],
  cancel: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M9 9l6 6', 'M15 9l-6 6'],
  restore: ['M3 12a9 9 0 1 0 3-6.7', 'M3 3v5h5'],
  more: ['M6 12h.01', 'M12 12h.01', 'M18 12h.01'],
  refresh: ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v5h-5'],
  settings: ['M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.4-2.3 1a7 7 0 0 0-1.7-1L14.5 3h-4l-.4 2.1a7 7 0 0 0-1.7 1l-2.3-1-2 3.4L6 11a7 7 0 0 0 0 2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 1.7 1l.4 2.1h4l.4-2.1a7 7 0 0 0 1.7-1l2.3 1 2-3.4L19 13a7 7 0 0 0 .1-1z'],
  sun: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M12 2v2', 'M12 20v2', 'M2 12h2', 'M20 12h2', 'M5 5l1.5 1.5', 'M17.5 17.5L19 19', 'M19 5l-1.5 1.5', 'M6.5 17.5L5 19'],
  moon: ['M21 13a9 9 0 1 1-10-10 7 7 0 0 0 10 10z'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  trash: ['M3 6h18', 'M8 6V4h8v2', 'M6 6l1 14h10l1-14', 'M10 11v5', 'M14 11v5'],
  home: ['M4 11l8-7 8 7', 'M6 10v10h12v-10'],
  chevronDown: ['M6 9l6 6 6-6'],
  chevronRight: ['M9 6l6 6-6 6'],
  eye: ['M2 12c2.6-4.2 6-6.5 10-6.5s7.4 2.3 10 6.5c-2.6 4.2-6 6.5-10 6.5S4.6 16.2 2 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  sort: ['M4 7h12', 'M4 12h8', 'M4 17h4', 'M17 7v10', 'M14 14l3 3 3-3'],
  arrowUp: ['M12 19V6', 'M6 12l6-6 6 6'],
  arrowDown: ['M12 5v13', 'M6 12l6 6 6-6'],
  archive: ['M3 4h18v5H3z', 'M5 9v11h14V9', 'M10 13h4'],
  check: ['M4 12l5 5 11-11'],
  x: ['M6 6l12 12', 'M18 6L6 18'],
  upload: ['M12 21V9', 'M7 13l5-5 5 5', 'M5 3h14'],
  link: ['M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1', 'M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1'],
  server: ['M4 5h16v6H4z', 'M4 13h16v6H4z', 'M8 8h.01', 'M8 16h.01'],
  key: ['M14 7a4 4 0 1 1-3 6l-7 7v-3h3v-3h3l1-1'],
  gauge: ['M4 18a8 8 0 1 1 16 0', 'M12 14l3-4'],
  logs: ['M5 3h14v18H5z', 'M8 8h8', 'M8 12h8', 'M8 16h5'],
  grid: ['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z'],
  list: ['M8 6h12', 'M8 12h12', 'M8 18h12', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5', 'M12 7.5h.01'],
}

const shape = computed(() => ICONS[props.name] || ICONS.more)
</script>
