# Copyright 2026 suhong
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author suhong
# syntax=docker/dockerfile:1
# frontend - Vite build then served by nginx; /api and /mcp are reverse-proxied to gateway.
# Build context is the repository root, so both frontend/ sources and deploy/ compose in.
FROM node:20-alpine AS build
WORKDIR /app
# npm registry override: official registry is throttled/blocked on some networks.
ARG NPM_REGISTRY=https://registry.npmmirror.com
# Remove a host node_modules (Windows/macOS binaries) if the context contains one,
# then install inside the image. npm ci is preferred, install is the lockfile-less fallback.
COPY frontend/ ./
RUN --mount=type=cache,target=/root/.npm rm -rf node_modules dist \
 && npm config set registry "$NPM_REGISTRY" \
 && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund) \
 && npm run build

FROM nginx:alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
