<!-- 增量渲染列表：先渲染 step 项，滚动到哨兵或点「加载更多」再追加，320+ 项也不会一次性挂载。 -->
<template>
  <div>
    <slot v-for="row in shown" :key="row.id" :row="row" />
    <div v-if="hasMore" ref="sentinel" class="flex justify-center py-3">
      <AppButton size="sm" variant="ghost" @click="more">
        加载更多（还有 {{ items.length - shown.length }} 项）
      </AppButton>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

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

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined' || !sentinel.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) more()
    },
    { rootMargin: '200px' },
  )
  observer.observe(sentinel.value)
})

watch(sentinel, (element, previous) => {
  if (!observer) return
  if (previous) observer.unobserve(previous)
  if (element) observer.observe(element)
})

onUnmounted(() => observer?.disconnect())
</script>
