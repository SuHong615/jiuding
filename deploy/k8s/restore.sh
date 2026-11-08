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
# Restore PostgreSQL databases from a backup produced by deploy/k8s/backup.sh.
# DESTRUCTIVE: pg_restore runs with --clean --if-exists, so existing objects are
# dropped before being recreated. --yes is mandatory.
#
# Usage (inside WSL):
#   sh deploy/k8s/restore.sh --from deploy/k8s/backup/20261108-190000 --yes
#   sh deploy/k8s/restore.sh --from <dir> --db tenant_db,mcp_db --yes
set -eu
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
export PATH=/usr/local/bin:$PATH

HERE=$(cd "$(dirname "$0")" && pwd)
NAMESPACE=${NAMESPACE:-tenancy-center}
SECRET=${SECRET:-tenancy-secrets}
FROM=""
DBSEL="all"
CONFIRM=0
DBS="tenant_db approval_db agent_db mcp_db"

usage() {
  sed -n '2,30p' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --from)
      [ $# -ge 2 ] || { echo "--from requires a backup directory" >&2; exit 2; }
      FROM=$2
      shift 2
      ;;
    --db)
      [ $# -ge 2 ] || { echo "--db requires all|tenant_db,..." >&2; exit 2; }
      DBSEL=$2
      shift 2
      ;;
    --yes)
      CONFIRM=1
      shift
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

if [ "$CONFIRM" -ne 1 ]; then
  echo "REFUSED: restore overwrites data (pg_restore --clean --if-exists)." >&2
  echo "Re-run with --yes after checking the target backup directory and cluster." >&2
  exit 2
fi

[ -n "$FROM" ] || { echo "--from <backupDir> is required" >&2; usage; exit 2; }
FROM=$(cd "$FROM" 2>/dev/null && pwd) || { echo "backup directory not found" >&2; exit 2; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found" >&2; exit 1; }
kubectl -n "$NAMESPACE" get deployment/postgres >/dev/null 2>&1 \
  || { echo "deployment/postgres not found in namespace $NAMESPACE" >&2; exit 1; }

secret_value() {
  kubectl -n "$NAMESPACE" get secret "$SECRET" -o jsonpath="{.data.$1}" \
    | base64 -d 2>/dev/null || true
}
PGUSER=$(secret_value POSTGRES_USER)
[ -n "$PGUSER" ] || { echo "cannot read POSTGRES_USER from secret $SECRET" >&2; exit 1; }

# Resolve the database selection.
if [ "$DBSEL" = "all" ]; then
  SELECTED="$DBS"
else
  SELECTED=$(echo "$DBSEL" | tr ',' ' ')
  for db in $SELECTED; do
    found=0
    for known in $DBS; do
      [ "$db" = "$known" ] && found=1
    done
    [ "$found" -eq 1 ] || { echo "unknown database: $db (allowed: $DBS)" >&2; exit 2; }
  done
fi

echo "== restore $SELECTED from $FROM =="
MANIFEST="$FROM/manifest.txt"

for db in $SELECTED; do
  DUMP="$FROM/$db.dump"
  [ -f "$DUMP" ] || { echo "missing dump file: $DUMP" >&2; exit 1; }

  # Integrity check against manifest.txt when the checksum is present.
  if [ -f "$MANIFEST" ]; then
    EXPECTED=$(grep "^db=$db " "$MANIFEST" | sed -n 's/.*sha256=\([0-9a-fA-F]*\).*/\1/p' || true)
    if [ -n "$EXPECTED" ]; then
      ACTUAL=$(sha256sum "$DUMP" | awk '{print $1}')
      if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "sha256 mismatch for $db: expected $EXPECTED, got $ACTUAL" >&2
        exit 1
      fi
      echo "   sha256 ok: $db"
    fi
  fi

  echo "== pg_restore $db (--clean --if-exists) =="
  kubectl -n "$NAMESPACE" exec -i deployment/postgres -- \
    pg_restore -U "$PGUSER" -d "$db" --clean --if-exists --no-owner < "$DUMP"
  echo "   restored: $db"
done

echo "== restore OK: $SELECTED =="
