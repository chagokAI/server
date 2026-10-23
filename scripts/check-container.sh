#!/usr/bin/env bash
set -Eeuo pipefail

image=${1:?Usage: bash scripts/check-container.sh IMAGE}
name="chagok-server-check-${RANDOM}-$$"
work_dir=$(mktemp -d)
cleanup() {
  local status=$?
  trap - EXIT
  if [ "$status" -ne 0 ]; then docker logs "$name-app" >&2 || true; fi
  # Only remove the containers and anonymous volumes created by this check.
  docker rm -fv "$name-app" "$name-kafka" "$name-db" >/dev/null 2>&1 || true
  docker network rm "$name" >/dev/null 2>&1 || true
  rm -rf -- "$work_dir"
  exit "$status"
}
trap cleanup EXIT

docker network create "$name" >/dev/null
docker run --detach --name "$name-db" --network "$name" --network-alias postgres \
  --env POSTGRES_DB=chagok_ci --env POSTGRES_USER=postgres --env POSTGRES_PASSWORD=ci-password \
  pgvector/pgvector:0.8.7-pg17 >/dev/null
docker run --detach --name "$name-kafka" --network "$name" --network-alias kafka \
  --env CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  --env KAFKA_NODE_ID=1 --env KAFKA_PROCESS_ROLES=broker,controller \
  --env KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  --env KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  --env KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  --env KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  --env KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  --env KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  --env KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  --env KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  apache/kafka:4.3.1 >/dev/null

deadline=$((SECONDS + 120))
until docker exec "$name-db" pg_isready -U postgres -d chagok_ci >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || { echo 'Test PostgreSQL did not start.' >&2; exit 1; }
  sleep 2
done
until docker exec "$name-kafka" /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || { echo 'Test Kafka did not start.' >&2; exit 1; }
  sleep 2
done

docker run --detach --name "$name-app" --network "$name" --publish 127.0.0.1::8080 \
  --env DB_URL=jdbc:postgresql://postgres:5432/chagok_ci \
  --env DB_USERNAME=postgres --env DB_PASSWORD=ci-password \
  --env KAFKA_BOOTSTRAP_SERVERS=kafka:9092 --env DDL_AUTO=update \
  --env JOB_SCHEDULING_ENABLED=false --env JOB_WORKER_ENABLED=false \
  --env BOOTSTRAP_ENABLED=false --env AUTH_ENABLED=true \
  --env INTERNAL_API_TOKEN=ci-internal-token "$image" >/dev/null
deadline=$((SECONDS + 180))
while [ "$(docker inspect --format '{{.State.Health.Status}}' "$name-app")" != healthy ]; do
  [ "$(docker inspect --format '{{.State.Running}}' "$name-app")" = true ] || { echo 'Backend exited during startup.' >&2; exit 1; }
  [ "$SECONDS" -lt "$deadline" ] || { echo 'Backend did not become healthy.' >&2; exit 1; }
  sleep 3
done

port=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$name-app")
base="http://127.0.0.1:$port"
curl --fail --silent --show-error --max-time 10 "$base/actuator/health" |
  python3 -c 'import json, sys; sys.exit(0 if json.load(sys.stdin)["status"] == "UP" else 1)'
curl --fail --silent --show-error --max-time 10 "$base/api/v1/auth/csrf" |
  python3 -c 'import json, sys; d=json.load(sys.stdin)["data"]; sys.exit(0 if d.get("token") and d.get("headerName") else 1)'
status=$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' "$base/api/v1/auth/me")
[ "$status" = 401 ] || { echo "Expected unauthenticated /auth/me to return 401, got $status" >&2; exit 1; }
echo 'Backend boot, PostgreSQL, Kafka, health and authentication checks passed.'
