<!-- 批量操作条：移动端固定在底部 Tab 上沿（含安全区），桌面浮在内容区底部。
     selection-dock-space 是流内占位：fixed 定位不占滚动高度，没有它滚动到底时最后一行会被盖住。 -->
<template>
  <div class="selection-dock">
    <div class="card flex flex-col gap-2 p-3 shadow-pop md:flex-row md:items-center md:gap-4">
      <div class="min-w-0 flex-1 whitespace-nowrap text-sm md:whitespace-normal">
        <slot name="info" />
      </div>
      <div class="flex flex-wrap items-center gap-2 md:justify-end">
        <slot name="actions" />
      </div>
    </div>
  </div>
  <div class="selection-dock-space md:hidden" aria-hidden="true" />
</template>

<script setup></script>
