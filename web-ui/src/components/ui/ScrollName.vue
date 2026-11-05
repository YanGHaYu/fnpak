<!-- 行名称区：单行显示，移动端在自身容器内横向滚动（滚动条隐藏），桌面保持省略号。
     移动端横向拖动超过阈值时不触发内部按钮的点击，轻点仍可打开/进入。 -->
<template>
  <div
    ref="box"
    class="name-scroll"
    @touchstart.passive="onTouchStart"
    @touchmove.passive="onTouchMove"
    @touchcancel="dragged = false"
  >
    <div class="name-scroll-content" @click.capture="onClickCapture">
      <slot />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  /** 可访问名称，仅用于调试/语义提示 */
  label: { type: String, default: '' },
})

const box = ref(null)
let startX = 0
let startY = 0
let dragged = false

function onTouchStart(event) {
  const touch = event.touches[0]
  if (!touch) return
  startX = touch.clientX
  startY = touch.clientY
  dragged = false
}

function onTouchMove(event) {
  const touch = event.touches[0]
  if (!touch) return
  const dx = Math.abs(touch.clientX - startX)
  const dy = Math.abs(touch.clientY - startY)
  if (dx > 8 && dx > dy) dragged = true
}

function onClickCapture(event) {
  if (!dragged) return
  // 拖动结束浏览器仍可能补发 click：拦掉它，避免误触进入目录/展开任务
  event.preventDefault()
  event.stopPropagation()
  dragged = false
}
</script>
