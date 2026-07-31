import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { writeFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

/**
 * emptyOutDir 会连 .gitkeep 一起删掉，而这个文件是 static/ 目录能存在于
 * 仓库里的唯一原因（构建产物本身不入库）。构建完补回去。
 */
function keepStaticDir(outDir: string): Plugin {
  return {
    name: 'shiguang-keep-static-dir',
    apply: 'build',
    closeBundle() {
      writeFileSync(fileURLToPath(new URL(`${outDir}/.gitkeep`, import.meta.url)), '')
    },
  }
}

export default defineConfig({
  plugins: [vue(), keepStaticDir('../src/main/resources/static')],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    // 直接构建进后端静态资源目录，最终随 jar 一起分发，不需要单独部署前端
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    // 生产包不带 sourcemap：它会把完整源码暴露给任何能打开页面的人
    sourcemap: false,
  },
  server: {
    port: 5173,
    // 开发时把 API 代理到后端，避免跨域，也让 Cookie 能正常工作
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
})
