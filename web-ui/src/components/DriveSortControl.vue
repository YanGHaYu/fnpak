<!-- 目录工具栏的紧凑排序控件：维度 + 方向，移动端随 header 换行，不占列表行宽。 -->
<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        class="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-md border border-line bg-surface px-2.5 text-xs font-medium text-ink transition-colors hover:bg-surface2 focus-visible:outline-none md:min-h-8 md:px-2 md:text-[13px]"
        :aria-label="`排序：${dimensionLabel} ${directionLabel}`"
        :aria-expanded="String(open)"
      >
        <AppIcon name="sort" :size="15" />
        <span>{{ dimensionLabel }}</span>
        <AppIcon :name="direction === 'asc' ? 'arrowUp' : 'arrowDown'" :size="13" />
      </button>
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent
        side="bottom"
        align="end"
        :side-offset="4"
        class="z-50 w-52 rounded-lg border border-line bg-surface p-1 shadow-pop focus-visible:outline-none"
      >
        <p class="px-2.5 py-1 text-xs text-muted">排序方式</p>
        <button
          v-for="option in DRIVE_SORT_OPTIONS"
          :key="option.key"
          type="button"
          role="menuitemradio"
          :aria-checked="String(option.key === dimension)"
          class="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors"
          :class="option.key === dimension ? 'bg-brand-soft font-medium text-brand' : 'text-ink hover:bg-surface2'"
          @click="chooseDimension(option.key)"
        >
          <AppIcon v-if="option.key === dimension" name="check" :size="15" />
          <span v-else class="h-[15px] w-[15px] shrink-0" />
          <span class="truncate">{{ option.label }}</span>
        </button>
        <div class="my-1 border-t border-line" />
        <div class="flex gap-1 p-1">
          <button
            v-for="option in DRIVE_SORT_DIRECTIONS"
            :key="option.key"
            type="button"
            role="menuitemradio"
            :aria-checked="String(option.key === direction)"
            class="min-h-9 flex-1 rounded-md text-xs font-medium transition-colors"
            :class="option.key === direction ? 'bg-brand-strong text-white' : 'text-muted hover:bg-surface2 hover:text-ink'"
            @click="chooseDirection(option.key)"
          >
            {{ option.label }}
          </button>
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<script setup>
import { computed, ref } from 'vue'
import { PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui'
import AppIcon from './ui/AppIcon.vue'
import { DRIVE_SORT_DIRECTIONS, DRIVE_SORT_OPTIONS } from '../driveSort'

const props = defineProps({
  dimension: { type: String, default: 'time' },
  direction: { type: String, default: 'desc' },
})
const emit = defineEmits(['update:dimension', 'update:direction'])
const open = ref(false)

const dimensionLabel = computed(
  () => DRIVE_SORT_OPTIONS.find((option) => option.key === props.dimension)?.label || '时间',
)
const directionLabel = computed(() => (props.direction === 'asc' ? '正序' : '倒序'))

function chooseDimension(key) {
  open.value = false
  emit('update:dimension', key)
}

function chooseDirection(key) {
  open.value = false
  emit('update:direction', key)
}
</script>
