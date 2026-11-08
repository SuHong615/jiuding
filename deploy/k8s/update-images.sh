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
# Rebuild the six runtime images from the current jars/dist, import them into the
# k3s containerd store and roll the deployments. Use after a backend/frontend build.
#
#   sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/update-images.sh
#
# Prerequisite: `mvn -B -DskipTests install` and (for frontend) `npm run build`.
set -eu
ROOT=/mnt/d/deepseekHarness/tenancy-center
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
export PATH=/usr/local/bin:$PATH
cd "$ROOT"

build() {
  svc=$1; jar=$2
  if [ ! -f "$jar" ]; then echo "SKIP $svc (missing $jar)"; return 0; fi
  docker build -q -f deploy/k8s/runtime.Dockerfile --build-arg JAR_PATH="$jar" -t "tenancy/$svc:1.0.0" . >/dev/null
  docker save "tenancy/$svc:1.0.0" | k3s ctr images import - >/dev/null 2>&1
  echo "image updated: tenancy/$svc:1.0.0"
}

build tenant-service   tenant-service/target/tenant-service-0.9.0-SNAPSHOT.jar
build approval-service approval-service/target/approval-service-0.9.0-SNAPSHOT.jar
build agent-service    agent-service/target/agent-service-0.9.0-SNAPSHOT.jar
build mcp-service      mcp-service/target/mcp-service-0.9.0-SNAPSHOT.jar
build gateway          gateway/target/gateway-0.9.0-SNAPSHOT.jar

if [ -d frontend/dist ]; then
  docker build -q -f deploy/k8s/frontend-runtime.Dockerfile -t tenancy/frontend:1.0.0 . >/dev/null
  docker save tenancy/frontend:1.0.0 | k3s ctr images import - >/dev/null 2>&1
  echo "image updated: tenancy/frontend:1.0.0"
else
  echo "SKIP frontend (frontend/dist missing)"
fi

echo "== rolling restart =="
for d in tenant-service approval-service agent-service mcp-service gateway frontend; do
  kubectl -n tenancy-center rollout restart "deployment/$d"
done
for d in tenant-service approval-service agent-service mcp-service gateway frontend; do
  kubectl -n tenancy-center rollout status "deployment/$d" --timeout=240s
done
kubectl -n tenancy-center get pods
