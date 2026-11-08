/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发环境：/api 与 /mcp 均代理到网关，网关再路由到 tenant/approval/agent/mcp 各服务。
// 端口说明：本机默认的 8080/5173 已被其它本地应用占用，因此本地验证使用
// 网关 18080 + 前端 dev 15173；Docker 部署仍按文档使用 8080/8088。
// 可用环境变量覆盖：TENANCY_GATEWAY_URL / TENANCY_DEV_PORT。
const gatewayUrl = process.env.TENANCY_GATEWAY_URL || 'http://localhost:18080'
const devPort = Number(process.env.TENANCY_DEV_PORT || 15173)

export default defineConfig({
  plugins: [react()],
  server: {
    port: devPort,
    strictPort: true,
    proxy: {
      '/api': {
        target: gatewayUrl,
        changeOrigin: true,
      },
      '/mcp': {
        target: gatewayUrl,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
})
