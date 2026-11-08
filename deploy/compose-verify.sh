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
# docker compose full-stack runtime verification helper (run inside WSL).
#
#   1) scale the k3s workloads to zero to free memory
#   2) rebuild the six runtime images (with curl for healthchecks)
#   3) docker compose up --no-build with the local port override
#   4) wait for containers, print status
set -u
ROOT=/mnt/d/deepseekHarness/tenancy-center
export PATH=/usr/local/bin:$PATH
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

echo "== scale k8s to zero =="
kubectl -n tenancy-center scale deployment --all --replicas=0
sleep 8
kubectl -n tenancy-center get pods

cd "$ROOT"
echo "== rebuild runtime images (curl included) =="
build() {
  svc=$1; jar=$2
  [ -f "$jar" ] || { echo "SKIP $svc"; return 0; }
  docker build -q -f deploy/k8s/runtime.Dockerfile --build-arg JAR_PATH="$jar" -t "tenancy/$svc:1.0.0" . >/dev/null
  echo "built tenancy/$svc:1.0.0"
}
build tenant-service   tenant-service/target/tenant-service-0.9.0-SNAPSHOT.jar
build approval-service approval-service/target/approval-service-0.9.0-SNAPSHOT.jar
build agent-service    agent-service/target/agent-service-0.9.0-SNAPSHOT.jar
build mcp-service      mcp-service/target/mcp-service-0.9.0-SNAPSHOT.jar
build gateway          gateway/target/gateway-0.9.0-SNAPSHOT.jar
docker build -q -f deploy/k8s/frontend-runtime.Dockerfile -t tenancy/frontend:1.0.0 . >/dev/null && echo "built tenancy/frontend:1.0.0"

echo "== compose up (no build) =="
cd "$ROOT/deploy"
[ -f .env ] || cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --no-build 2>&1 | tail -n 20

echo "== wait for health =="
for i in $(seq 1 60); do
  healthy=$(docker ps --filter label=com.docker.compose.project=tenancy-center --format '{{.Status}}' | grep -c '(healthy)' || true)
  total=$(docker ps --filter label=com.docker.compose.project=tenancy-center --format '{{.Names}}' | wc -l)
  echo "attempt $i: $healthy/$total healthy"
  [ "$healthy" -ge 7 ] && break
  sleep 5
done
docker ps --filter label=com.docker.compose.project=tenancy-center --format '{{.Names}} | {{.Status}} | {{.Ports}}'
echo "== memory =="
free -m
