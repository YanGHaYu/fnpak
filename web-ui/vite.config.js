import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 产物要能离线单机打开：不打 CDN、不做外部化，依赖全部进 bundle。
// Tailwind 在构建期静态产出 CSS，运行时不需要任何网络资源。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    chunkSizeWarningLimit: 1200,
    // 老 NAS 上的浏览器内核可能偏旧，目标放到 es2019
    target: 'es2019',
  },
})
