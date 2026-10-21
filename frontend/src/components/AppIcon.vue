<script setup>
defineProps({ name: { type: String, default: 'chart' }, size: { type: [Number, String], default: 20 } })
const paths = {
  overview: 'M3 10 12 3l9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
  chart: 'M4 20V10m6 10V4m6 16v-7m5 7H2',
  backtest: 'M3 3v18h18M6 15l5-5 4 3 6-8M17 5h4v4',
  compression: 'M4 6c0-4 16-4 16 0s-16 4-16 0Zm0 0v6c0 4 16 4 16 0V6M4 12v6c0 4 16 4 16 0v-6',
  agent: 'M8 5h8a4 4 0 0 1 4 4v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9a4 4 0 0 1 4-4ZM12 2v3M1 10v5m22-5v5M8 11h.01M16 11h.01M9 16h6',
  cloud: 'M7 18H6a4 4 0 1 1 .8-7.9A6 6 0 0 1 18 8a5 5 0 0 1 0 10h-1M9 16l3-3 3 3m-3-3v8',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  chevron: 'm9 5 7 7-7 7',
  clock: 'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  file: 'M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8m-8 4h6',
  trend: 'm3 17 6-6 4 4 8-10m-6 0h6v6',
  pie: 'M11 2a10 10 0 1 0 11 11H11ZM15 2v7h7a10 10 0 0 0-7-7Z',
  money: 'M12 3v18m5-14c-2-4-10-3-10 1 0 5 10 2 10 7 0 4-8 5-11 1',
  calendar: 'M5 5h14a2 2 0 0 1 2 2v13H3V7a2 2 0 0 1 2-2ZM7 2v6m10-6v6M3 11h18',
  refresh: 'M20 7a9 9 0 1 0 1 9M20 2v6h-6',
  play: 'm8 4 12 8-12 8Z',
  download: 'M12 3v12m-5-5 5 5 5-5M4 15v6h16v-6',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  close: 'm6 6 12 12M6 18 18 6',
  info: 'M12 11v6m0-10h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  send: 'm22 2-7 20-4-9-9-4 20-7ZM11 13 22 2',
  search: 'M17 17l5 5M19 10a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  check: 'm5 12 4 4L19 6',
  menu: 'M3 6h18M3 12h18M3 18h18',
  help: 'M9 8a3 3 0 0 1 6 0c0 3-3 2-3 6m0 3h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9ZM10 21h4'
}
</script>

<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.chart" /></svg></template>
