import { reactive } from 'vue'

export const confirmState = reactive({
  open: false,
  title: '',
  message: '',
  confirmText: '确定',
  cancelText: '取消',
  danger: false,
})

let resolver = null

export function confirmDialog(options = {}) {
  Object.assign(confirmState, {
    open: true,
    title: options.title || '请确认',
    message: options.message || '',
    confirmText: options.confirmText || '确定',
    cancelText: options.cancelText || '取消',
    danger: options.danger === true,
  })
  return new Promise((resolve) => {
    resolver = resolve
  })
}

export function resolveConfirm(value) {
  confirmState.open = false
  const done = resolver
  resolver = null
  if (done) done(value)
}
