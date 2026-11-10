<!-- 增量渲染列表：先渲染 step 项，滚动到哨兵或点「加载更多」再追加，320+ 项也不会一次性挂载。 -->
<template>
  <div>
    <slot v-for="row in shown" :key="row.id" :row="row" />
    <div v-if="hasMore" ref="sentinel" class="col-span-full flex justify-center py-3">
      <AppButton size="sm" variant="ghost" @click="more">
        加载更多（还有 {{ items.length - shown.length }} 项）
      </AppButton>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import AppButton from './AppButton.vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  step: { type: Number, default: 40 },
})
const limit = ref(props.step)
const sentinel = ref(null)
let observer = null

const shown = computed(() => props.items.slice(0, limit.value))
const hasMore = computed(() => limit.value < props.items.length)

watch(
  () => props.items,
  () => {
    limit.value = props.step
  },
)

function more() {
  limit.value += props.step
}

// 首次挂载时列表通常为空（哨兵不存在），需要等哨兵出现后再建立观察者。
function ensureObserver() {
  if (observer || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) more()
    },
    { rootMargin: '200px' },
  )
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined' || !sentinel.value) return
  ensureObserver()
  observer?.observe(sentinel.value)
})

watch(sentinel, (element, previous) => {
  if (!element) {
    if (previous) observer?.unobserve(previous)
    return
  }
  ensureObserver()
  if (previous) observer?.unobserve(previous)
  observer?.observe(element)
})

onUnmounted(() => observer?.disconnect())
</script>
