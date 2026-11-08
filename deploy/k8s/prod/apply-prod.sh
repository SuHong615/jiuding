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
# Apply (or dry-run validate) the production profile on top of the base manifests:
#   * 2 replicas for every stateless deployment
#   * PDB minAvailable=1, HPA for gateway/tenant
#   * disable the in-process rating scheduler (CronJob owns it)
#   * CronJobs: rating-refresh (03:00 local) + maintenance-purge (04:00 UTC)
#
# Usage (inside WSL):
#   sh deploy/k8s/prod/apply-prod.sh              # apply for real
#   sh deploy/k8s/prod/apply-prod.sh --dry-run    # kubectl --dry-run=client validation only
set -eu
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
export PATH=/usr/local/bin:$PATH
HERE=$(cd "$(dirname "$0")" && pwd)
BASE=$(cd "$HERE/.." && pwd)

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

APPLY="kubectl apply"
if [ "$DRY_RUN" -eq 1 ]; then
  APPLY="kubectl apply --dry-run=client"
  echo "== DRY RUN: no cluster state will be changed =="
fi

echo "== secrets (random, generated once) =="
if [ "$DRY_RUN" -eq 1 ]; then
  echo "  (dry-run: skip gen-secrets.sh)"
else
  sh "$BASE/gen-secrets.sh"
fi

echo "== base manifests (with mcp_db init + MCP_DB_* env) =="
for f in 00-namespace 02-postgres 03-tenant-service 04-approval-service \
         05-agent-service 06-mcp-service 07-gateway 08-frontend; do
  $APPLY -f "$BASE/$f.yaml" >/dev/null
done

echo "== replicas=2 (stateless services) =="
SCALE_DRY=""
ENV_DRY=""
if [ "$DRY_RUN" -eq 1 ]; then
  SCALE_DRY="--dry-run=client"
  ENV_DRY="--dry-run=client"
fi
# shellcheck disable=SC2086
kubectl -n tenancy-center scale deployment \
  tenant-service approval-service agent-service mcp-service gateway frontend \
  --replicas=2 $SCALE_DRY

echo "== rating scheduler owned by CronJob =="
# shellcheck disable=SC2086
kubectl -n tenancy-center set env deployment/tenant-service \
  TENANCY_RATING_AUTO_REFRESH_ENABLED=false $ENV_DRY

echo "== PDB / HPA / CronJobs =="
$APPLY -f "$HERE/pdb.yaml" >/dev/null
$APPLY -f "$HERE/hpa.yaml" >/dev/null
$APPLY -f "$HERE/rating-refresh-cronjob.yaml" >/dev/null
$APPLY -f "$HERE/maintenance-purge-cronjob.yaml" >/dev/null

if [ "$DRY_RUN" -eq 1 ]; then
  echo "== dry-run OK (base + prod manifests validated; rollout skipped) =="
  exit 0
fi

echo "== wait for rollout =="
for d in tenant-service approval-service agent-service mcp-service gateway frontend; do
  kubectl -n tenancy-center rollout status "deployment/$d" --timeout=300s || true
done

echo "== status =="
kubectl -n tenancy-center get deploy
kubectl -n tenancy-center get pdb
kubectl -n tenancy-center get hpa
kubectl -n tenancy-center get cronjob
