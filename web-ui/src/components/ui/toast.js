import { reactive } from 'vue'

export const toastState = reactive({ items: [] })
let seq = 0

export function toast(message, type = 'info', duration = 3000) {
  const id = ++seq
  toastState.items.push({ id, message, type })
  setTimeout(() => {
    const index = toastState.items.findIndex((item) => item.id === id)
    if (index >= 0) toastState.items.splice(index, 1)
  }, duration)
  return id
}

toast.success = (message) => toast(message, 'success')
toast.warning = (message) => toast(message, 'warning')
toast.error = (message) => toast(message, 'error')
