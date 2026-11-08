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
#
# Backup the four PostgreSQL databases from the k3s postgres Deployment using
# pg_dump -Fc. Output layout:
#
#   <out>/YYYYmmdd-HHMMSS/tenant_db.dump
#   <out>/YYYYmmdd-HHMMSS/approval_db.dump
#   <out>/YYYYmmdd-HHMMSS/agent_db.dump
#   <out>/YYYYmmdd-HHMMSS/mcp_db.dump
#   <out>/YYYYmmdd-HHMMSS/manifest.txt   (time, image, size + sha256 per db)
#
# Usage (inside WSL, where kubectl can reach the k3s API):
#   sh deploy/k8s/backup.sh                  # ./backup/YYYYmmdd-HHMMSS, keep 7
#   sh deploy/k8s/backup.sh --out /backups --keep 14
set -eu
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
export PATH=/usr/local/bin:$PATH

HERE=$(cd "$(dirname "$0")" && pwd)
NAMESPACE=${NAMESPACE:-tenancy-center}
SECRET=${SECRET:-tenancy-secrets}
OUT="$HERE/backup"
KEEP=${KEEP:-7}
DBS="tenant_db approval_db agent_db mcp_db"

usage() {
  sed -n '2,30p' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)
      [ $# -ge 2 ] || { echo "--out requires a directory" >&2; exit 2; }
      OUT=$2
      shift 2
      ;;
    --keep)
      [ $# -ge 2 ] || { echo "--keep requires a number" >&2; exit 2; }
      KEEP=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum not found" >&2; exit 1; }
kubectl -n "$NAMESPACE" get deployment/postgres >/dev/null 2>&1 \
  || { echo "deployment/postgres not found in namespace $NAMESPACE" >&2; exit 1; }

secret_value() {
  kubectl -n "$NAMESPACE" get secret "$SECRET" -o jsonpath="{.data.$1}" \
    | base64 -d 2>/dev/null || true
}

PGUSER=$(secret_value POSTGRES_USER)
[ -n "$PGUSER" ] || { echo "cannot read POSTGRES_USER from secret $SECRET" >&2; exit 1; }
IMAGE=$(kubectl -n "$NAMESPACE" get deployment/postgres \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || echo unknown)
PGVERSION=$(kubectl -n "$NAMESPACE" exec deployment/postgres -- postgres --version 2>/dev/null \
  | head -n 1 || echo unknown)

STAMP=$(date +%Y%m%d-%H%M%S)
TARGET="$OUT/$STAMP"
mkdir -p "$TARGET"
MANIFEST="$TARGET/manifest.txt"
{
  echo "# jiuding PostgreSQL backup manifest"
  echo "time=$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)"
  echo "namespace=$NAMESPACE"
  echo "postgres_image=$IMAGE"
  echo "postgres_version=$PGVERSION"
  echo "host=$(hostname 2>/dev/null || echo unknown)"
  echo "layout=pg_dump -Fc; restore with pg_restore --clean --if-exists"
} > "$MANIFEST"

echo "== backing up databases of namespace $NAMESPACE =="
echo "== target: $TARGET =="
FAILED=0
for db in $DBS; do
  echo "== pg_dump $db =="
  if kubectl -n "$NAMESPACE" exec deployment/postgres -- \
        pg_dump -U "$PGUSER" -d "$db" -Fc > "$TARGET/$db.dump"; then
    SIZE=$(wc -c < "$TARGET/$db.dump" | tr -d ' ')
    SHA=$(sha256sum "$TARGET/$db.dump" | awk '{print $1}')
    echo "db=$db file=$db.dump size=$SIZE sha256=$SHA" >> "$MANIFEST"
    echo "   ok: $db ($SIZE bytes) sha256=$SHA"
  else
    FAILED=1
    echo "db=$db file=$db.dump error=pg_dump_failed" >> "$MANIFEST"
    rm -f "$TARGET/$db.dump"
    echo "   ERROR: pg_dump failed for $db" >&2
  fi
done

if [ "$FAILED" -ne 0 ]; then
  echo "backup FAILED (partial output kept for diagnosis): $TARGET" >&2
  exit 1
fi

echo "== backup OK =="
cat "$MANIFEST"

# Retention: keep the most recent N timestamp directories (lexicographic == chronological).
case "$KEEP" in
  ''|*[!0-9]*) echo "invalid --keep: $KEEP" >&2; exit 2 ;;
esac
if [ "$KEEP" -gt 0 ]; then
  COUNT=0
  for dir in $(ls -1d "$OUT"/*/ 2>/dev/null | sort -r); do
    COUNT=$((COUNT + 1))
    if [ "$COUNT" -gt "$KEEP" ]; then
      echo "== pruning old backup: $dir =="
      rm -rf "$dir"
    fi
  done
fi
echo "== done: $TARGET =="
