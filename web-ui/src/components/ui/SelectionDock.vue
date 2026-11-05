<!-- 批量操作条：移动端固定在底部 Tab 上沿（含安全区），桌面浮在内容区底部；始终占位，不遮最后一行。 -->
<template>
  <div class="selection-dock">
    <div class="card flex flex-col gap-2 p-3 shadow-pop md:flex-row md:items-center md:gap-4">
      <div class="min-w-0 flex-1 text-sm">
        <slot name="info" />
      </div>
      <div class="flex flex-wrap items-center gap-2 md:justify-end">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

<script setup></script>
