#!/bin/sh
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
# Publish tenancy-center k8s NodePorts to the Windows host.
#
# Why: raw listeners created inside the WSL2 distro (kubectl port-forward, NodePort
# iptables redirects) are NOT forwarded to the Windows host automatically. Only
# docker-published ports (docker -p) are. This helper runs tiny socat containers that
# publish the k8s NodePorts on the docker bridge, so http://localhost:<port> works
# from Windows exactly like a normal docker compose stack.
#
#   sh publish.sh start    # create/refresh publisher containers
#   sh publish.sh stop     # remove them
#   sh publish.sh status
#
# Port mapping (host -> NodePort):
#   30080 -> gateway :8080     (API + /mcp)
#   30081 -> tenant-service    :8081
#   30082 -> approval-service  :8082
#   30083 -> agent-service     :8083
#   30084 -> mcp-service       :8084
#   30088 -> frontend nginx    :80
set -eu
GW=$(docker network inspect bridge --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}')
PORTS="30080 30081 30082 30083 30084 30088"

start() {
  echo "docker bridge gateway: $GW"
  for p in $PORTS; do
    name="tenancy-pub-$p"
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --name "$name" --restart unless-stopped \
      -p "${p}:${p}" \
      alpine/socat "TCP-LISTEN:${p},fork,reuseaddr" "TCP:${GW}:${p}" >/dev/null
    echo "published :$p -> ${GW}:$p ($name)"
  done
  sleep 2
  docker ps --filter name=tenancy-pub --format '{{.Names}} {{.Ports}} {{.Status}}'
}

stop() {
  for p in $PORTS; do docker rm -f "tenancy-pub-$p" >/dev/null 2>&1 || true; done
  echo "publishers removed"
}

status() {
  docker ps -a --filter name=tenancy-pub --format '{{.Names}} {{.Status}} {{.Ports}}'
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: $0 start|stop|status"; exit 2 ;;
esac
