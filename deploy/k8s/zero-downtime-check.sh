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
# Zero-downtime check: continuously hit the gateway NodePort while rolling the
# gateway and tenant-service deployments, then report the failure count.
#
# Preconditions: prod profile applied (>=2 replicas), NodePort 30080 reachable
# from inside the WSL distro (k3s iptables).
#
# Usage: sh deploy/k8s/zero-downtime-check.sh [seconds]
set -u
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
export PATH=/usr/local/bin:$PATH
DURATION=${1:-60}
BASE=http://127.0.0.1:30080
LOG=/tmp/zero-downtime.log
: > "$LOG"

probe() {
  wget -q -O /dev/null --timeout=3 "$BASE/actuator/health" || echo "FAIL health $(date +%H:%M:%S.%N)" >> "$LOG"
  wget -q -O /dev/null --timeout=3 --header="X-API-Key: ${MCP_API_KEY:-dev-mcp-key}" "$BASE/mcp/info" || echo "FAIL mcp-info $(date +%H:%M:%S.%N)" >> "$LOG"
}

echo "== warmup =="
probe
sleep 1

echo "== background probes for ${DURATION}s =="
end=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$end" ]; do
  probe
  sleep 0.2
done &
PROBE_PID=$!

sleep 2
echo "== rolling restart gateway + tenant-service =="
kubectl -n tenancy-center rollout restart deployment/gateway deployment/tenant-service
kubectl -n tenancy-center rollout status deployment/gateway --timeout=240s || true
kubectl -n tenancy-center rollout status deployment/tenant-service --timeout=240s || true

wait "$PROBE_PID" 2>/dev/null || true
FAILS=$(grep -c FAIL "$LOG" || true)
TOTAL=$(grep -c . "$LOG" || true)
echo "== result: failures=$FAILS (log $LOG) =="
[ "$FAILS" -eq 0 ] && echo "ZERO_DOWNTIME_OK" || { echo "ZERO_DOWNTIME_FAILED"; tail -n 20 "$LOG"; exit 1; }
