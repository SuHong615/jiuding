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
# Start kubectl port-forwards for the tenancy-center namespace (run inside WSL).
# Binds 0.0.0.0 so the Windows host can reach the cluster through WSL2 localhost
# forwarding (IPv6-only listeners are not forwarded).
#
#   sh portforward.sh start   # start all forwards, pid files in /tmp/tenancy-pf
#   sh portforward.sh stop
#   sh portforward.sh status
set -eu
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
DIR=/tmp/tenancy-pf
mkdir -p "$DIR"

# name:localPort:service:remotePort
FORWARDS="gateway:18080:gateway:8080 tenant:8081:tenant-service:8081 approval:8082:approval-service:8082 agent:8083:agent-service:8083 mcp:8084:mcp-service:8084 frontend:8088:frontend:80"

start() {
  for spec in $FORWARDS; do
    name=$(echo "$spec" | cut -d: -f1)
    lport=$(echo "$spec" | cut -d: -f2)
    svc=$(echo "$spec" | cut -d: -f3)
    rport=$(echo "$spec" | cut -d: -f4)
    pidfile="$DIR/$name.pid"
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "already running $name (pid $(cat "$pidfile"))"
      continue
    fi
    nohup kubectl -n tenancy-center port-forward --address 0.0.0.0 "svc/$svc" "$lport:$rport" \
      >"$DIR/$name.log" 2>&1 &
    echo $! > "$pidfile"
    echo "started $name :$lport -> svc/$svc:$rport (pid $!)"
  done
  sleep 3
  for spec in $FORWARDS; do
    name=$(echo "$spec" | cut -d: -f1)
    lport=$(echo "$spec" | cut -d: -f2)
    if netstat -ltn 2>/dev/null | grep -q ":$lport "; then echo "  $name :$lport LISTEN"; else echo "  $name :$lport NOT-LISTENING (see $DIR/$name.log)"; fi
  done
}

stop() {
  for spec in $FORWARDS; do
    name=$(echo "$spec" | cut -d: -f1)
    pidfile="$DIR/$name.pid"
    if [ -f "$pidfile" ]; then
      kill "$(cat "$pidfile")" 2>/dev/null || true
      rm -f "$pidfile"
      echo "stopped $name"
    fi
  done
}

status() {
  for spec in $FORWARDS; do
    name=$(echo "$spec" | cut -d: -f1)
    lport=$(echo "$spec" | cut -d: -f2)
    pidfile="$DIR/$name.pid"
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "$name :$lport RUNNING pid=$(cat "$pidfile")"
    else
      echo "$name :$lport STOPPED"
    fi
  done
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: $0 start|stop|status"; exit 2 ;;
esac
