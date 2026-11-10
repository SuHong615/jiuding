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
# Deploy the tenancy-center stack into the local k3s cluster (run inside WSL).
#
#   sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh          # full deploy
#   sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh status   # show status
#   sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh delete   # remove namespace
#
# Images are produced by docker (deploy/docker-compose*.yml) and imported into the
# k3s containerd image store, so no registry is required for local verification.
set -eu
KUBECTL="kubectl"
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
HERE=$(cd "$(dirname "$0")" && pwd)
ACTION=${1:-apply}

show_status() {
  $KUBECTL get nodes
  $KUBECTL -n tenancy-center get pods -o wide
  $KUBECTL -n tenancy-center get svc
}

case "$ACTION" in
  status) show_status; exit 0 ;;
  delete) $KUBECTL delete namespace tenancy-center --ignore-not-found; exit 0 ;;
esac

echo "== importing docker images into k3s containerd =="
for img in tenancy/tenant-service:1.0.0 tenancy/approval-service:1.0.0 \
           tenancy/agent-service:1.0.0 tenancy/mcp-service:1.0.0 \
           tenancy/gateway:1.0.0 tenancy/frontend:1.0.0; do
  if /usr/bin/docker image inspect "$img" >/dev/null 2>&1; then
    echo "  import $img"
    /usr/bin/docker save "$img" | /usr/local/bin/k3s ctr images import - >/dev/null
  else
    echo "  WARN image not found in docker: $img (build it first: deploy/docker-compose.yml)"
  fi
done

echo "== applying manifests =="
$KUBECTL apply -f "$HERE/00-namespace.yaml"
sh "$HERE/gen-secrets.sh"
$KUBECTL apply -f "$HERE/02-postgres.yaml"
$KUBECTL wait --for=condition=Available deployment/postgres -n tenancy-center --timeout=180s || true
$KUBECTL apply -f "$HERE/09-redis.yaml"
$KUBECTL wait --for=condition=Available deployment/redis -n tenancy-center --timeout=120s || true
$KUBECTL apply -f "$HERE/03-tenant-service.yaml"
$KUBECTL apply -f "$HERE/04-approval-service.yaml"
$KUBECTL apply -f "$HERE/05-agent-service.yaml"
$KUBECTL apply -f "$HERE/06-mcp-service.yaml"
$KUBECTL apply -f "$HERE/07-gateway.yaml"
$KUBECTL apply -f "$HERE/08-frontend.yaml"

echo "== waiting for rollout =="
for d in tenant-service approval-service agent-service mcp-service gateway frontend redis; do
  $KUBECTL rollout status "deployment/$d" -n tenancy-center --timeout=300s || true
done

echo "== status =="
show_status

echo
echo "Access (inside this WSL distro):"
echo "  gateway  : kubectl -n tenancy-center port-forward --address 0.0.0.0 svc/gateway 18080:8080"
echo "  windows  : sh deploy/k8s/publish.sh start   # -> http://localhost:30080 / :30088"
echo "  frontend : kubectl -n tenancy-center port-forward --address 0.0.0.0 svc/frontend 8088:80"
echo "  or NodePorts: gateway 30080, frontend 30088 (WSL IP: $(hostname -I 2>/dev/null | awk '{print $1}'))"
