#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

die() { echo "ERROR: $*" >&2; exit 1; }
mode=${1:-}
image=${2:-}
config=${3:?Set the absolute server configuration path as the third argument}
requested_live_feeder=${4:-}
requested_live_allow_remote=${5:-}
requested_live_reset=${6:-}
[[ "$mode" == deploy || "$mode" == rollback ]] || die 'Use: deploy IMAGE CONFIG | rollback "" CONFIG'
for value in "$requested_live_feeder" "$requested_live_allow_remote" "$requested_live_reset"; do
  [[ -z "$value" || "$value" = true || "$value" = false ]] || die 'Live settings must be true or false'
done
[ -f "$config" ] || die "Deployment config not found: $config"

# This file is maintained by the server administrator and can define Bash arrays.
source "$config"
: "${COMPOSE_PROJECT_DIRECTORY:?Set COMPOSE_PROJECT_DIRECTORY}"
: "${COMPOSE_PROJECT_NAME:?Set COMPOSE_PROJECT_NAME}"
: "${DEPLOY_STATE_DIR:?Set DEPLOY_STATE_DIR}"
: "${SMOKE_BASE_URL:?Set SMOKE_BASE_URL}"
image_repository=${IMAGE_REPOSITORY:-ghcr.io/chagokai/server}
health_timeout=${HEALTH_TIMEOUT:-180}
http_attempts=${HTTP_ATTEMPTS:-6}
http_retry_delay=${HTTP_RETRY_DELAY:-2}
[[ "$health_timeout" =~ ^[1-9][0-9]*$ ]] || die 'Invalid HEALTH_TIMEOUT'
[[ "$http_attempts" =~ ^[1-9][0-9]*$ ]] || die 'Invalid HTTP_ATTEMPTS'
[[ "$http_retry_delay" =~ ^[0-9]+$ ]] || die 'Invalid HTTP_RETRY_DELAY'
[[ "$COMPOSE_PROJECT_DIRECTORY" = /* && "$DEPLOY_STATE_DIR" = /* ]] || die 'Use absolute server paths'
[ -d "$COMPOSE_PROJECT_DIRECTORY" ] || die 'Compose project directory does not exist'
[ "${#COMPOSE_FILES[@]}" -gt 0 ] || die 'Set COMPOSE_FILES to a Bash array of absolute Compose paths'
for command in docker curl python3; do command -v "$command" >/dev/null || die "Missing $command"; done

mkdir -p "$DEPLOY_STATE_DIR"
lock_file=${DEPLOY_LOCK_FILE:-$DEPLOY_STATE_DIR/deploy.lock}
mkdir -p "$(dirname -- "$lock_file")"
exec 9> "$lock_file"
# Lock the inherited file description. Bash keeps fd 9 open for the entire run.
# Python's standard-library flock works on both macOS and Linux without a CLI package.
python3 -c 'import fcntl, sys
try:
    fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(1)
' || die 'Another backend deployment is already running'

compose=(docker compose --project-directory "$COMPOSE_PROJECT_DIRECTORY" --project-name "$COMPOSE_PROJECT_NAME")
if [ -n "${COMPOSE_ENV_FILE:-}" ]; then
  [ -f "$COMPOSE_ENV_FILE" ] || die 'Compose environment file does not exist'
  compose+=(--env-file "$COMPOSE_ENV_FILE")
fi
for file in "${COMPOSE_FILES[@]}"; do
  [[ "$file" = /* && -f "$file" ]] || die "Compose file must exist at an absolute path: $file"
  compose+=(-f "$file")
done
override="$DEPLOY_STATE_DIR/backend.image.yaml"
active_compose=("${compose[@]}")
if [ -f "$override" ]; then active_compose+=(-f "$override"); fi
"${active_compose[@]}" config --quiet
container=$("${active_compose[@]}" ps --status running -q backend)
[[ -n "$container" && "$container" != *$'\n'* ]] || die 'Expected exactly one running backend; bootstrap the stack first'
old_image_id=$(docker inspect --format '{{.Image}}' "$container")
[[ "$old_image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || die 'Cannot determine current backend image'
rollback_image="chagok-server-rollback:${old_image_id#sha256:}"
# Restored images keep the schema mode their container actually ran with. The app defaults to update.
old_environment=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container")
old_ddl_auto=$(printf '%s\n' "$old_environment" | sed -n 's/^DDL_AUTO=//p')
old_ddl_auto=${old_ddl_auto:-update}
old_live_feeder=$(printf '%s\n' "$old_environment" | sed -n 's/^LIVE_FEEDER_ENABLED=//p')
old_live_allow_remote=$(printf '%s\n' "$old_environment" | sed -n 's/^LIVE_ALLOW_REMOTE_DATABASE=//p')
old_live_reset=$(printf '%s\n' "$old_environment" | sed -n 's/^LIVE_RESET_ENABLED=//p')
old_live_feeder=${old_live_feeder:-false}
old_live_allow_remote=${old_live_allow_remote:-false}
old_live_reset=${old_live_reset:-false}
next_live_feeder=${requested_live_feeder:-$old_live_feeder}
next_live_allow_remote=${requested_live_allow_remote:-$old_live_allow_remote}
next_live_reset=${requested_live_reset:-$old_live_reset}
# New releases must match the current schema; Hibernate does not change it.
next_ddl_auto=validate

if [ "$mode" = rollback ]; then
  [ -f "$DEPLOY_STATE_DIR/previous-image" ] || die 'No previous successful deployment is recorded'
  image=$(cat "$DEPLOY_STATE_DIR/previous-image")
  [[ "$image" =~ ^chagok-server-rollback:[a-f0-9]{64}$ ]] || die 'Invalid recorded rollback image'
  if [ -f "$DEPLOY_STATE_DIR/previous-ddl-auto" ]; then next_ddl_auto=$(cat "$DEPLOY_STATE_DIR/previous-ddl-auto"); fi
  # Older deployment records predate live settings and use the application's disabled defaults.
  next_live_feeder=false
  next_live_allow_remote=false
  next_live_reset=false
  if [ -f "$DEPLOY_STATE_DIR/previous-live-env" ]; then
    next_live_feeder=$(sed -n 's/^LIVE_FEEDER_ENABLED=//p' "$DEPLOY_STATE_DIR/previous-live-env")
    next_live_allow_remote=$(sed -n 's/^LIVE_ALLOW_REMOTE_DATABASE=//p' "$DEPLOY_STATE_DIR/previous-live-env")
    next_live_reset=$(sed -n 's/^LIVE_RESET_ENABLED=//p' "$DEPLOY_STATE_DIR/previous-live-env")
    # 초기화 변수가 도입되기 전 기록은 기본 꺼짐으로 복원한다.
    next_live_reset=${next_live_reset:-false}
  fi
else
  prefix="$image_repository@sha256:"
  [[ "$image" = "$prefix"* ]] || die "Image must belong to $image_repository and use a digest"
  digest=${image#"$prefix"}
  [[ "$digest" =~ ^[a-f0-9]{64}$ ]] || die 'Expected a full sha256 image digest'
  # Pull before changing the running service or deployment state.
  docker pull "$image"
fi
[[ "$old_ddl_auto" =~ ^[a-z-]+$ && "$next_ddl_auto" =~ ^[a-z-]+$ ]] || die 'Invalid DDL_AUTO value'
for value in "$old_live_feeder" "$old_live_allow_remote" "$old_live_reset" "$next_live_feeder" "$next_live_allow_remote" "$next_live_reset"; do
  [[ "$value" = true || "$value" = false ]] || die 'Live settings must be true or false'
done
next_image_id=$(docker image inspect --format '{{.Id}}' "$image")

check_http() {
  local base=${1%/}
  curl --fail --silent --show-error --connect-timeout 5 --max-time 10 "$base/healthz" | grep -qx ok || return 1
  curl --fail --silent --show-error --connect-timeout 5 --max-time 10 "$base/login" | grep -q 'id="app"' || return 1
  # A 200 SPA fallback must not count as a working backend connection.
  curl --fail --silent --show-error --connect-timeout 5 --max-time 10 "$base/api/v1/auth/csrf" |
    python3 -c 'import json, sys; d = json.load(sys.stdin)["data"]; sys.exit(0 if isinstance(d.get("token"), str) and d["token"] and isinstance(d.get("headerName"), str) and d["headerName"] else 1)'
}

smoke() {
  local attempt
  for ((attempt = 1; attempt <= http_attempts; attempt++)); do
    if check_http "$SMOKE_BASE_URL" && { [ -z "${PUBLIC_BASE_URL:-}" ] || check_http "$PUBLIC_BASE_URL"; }; then
      return 0
    fi
    if [ "$attempt" -lt "$http_attempts" ]; then sleep "$http_retry_delay"; fi
  done
  return 1
}

if [ "$next_image_id" = "$old_image_id" ] && [ "$next_live_feeder" = "$old_live_feeder" ] \
    && [ "$next_live_allow_remote" = "$old_live_allow_remote" ] && [ "$next_live_reset" = "$old_live_reset" ]; then
  smoke || die 'The existing backend failed its smoke checks'
  echo 'The requested image is already running; rollback history was preserved.'
  exit 0
fi

# Verify the target JVM before changing the running backend.
docker run --rm --entrypoint java "$image" -version
# Take a consistent database snapshot before replacing the backend.
: "${DB_BACKUP_DIR:?Set DB_BACKUP_DIR}"
[[ "$DB_BACKUP_DIR" = /* ]] || die 'Use an absolute DB_BACKUP_DIR'
mkdir -p "$DB_BACKUP_DIR"
chmod 700 "$DB_BACKUP_DIR"
database=$("${active_compose[@]}" ps --status running -q postgres)
[[ -n "$database" && "$database" != *$'\n'* ]] || die 'Expected exactly one running postgres'
backup="$DB_BACKUP_DIR/$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
if ! docker exec "$database" sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "$backup.tmp"; then
  rm -f -- "$backup.tmp"
  die 'Database backup failed; backend was not changed'
fi
[ -s "$backup.tmp" ] || die 'Database backup was empty'
mv -- "$backup.tmp" "$backup"
echo "Database backup saved: $backup"

docker image tag "$old_image_id" "$rollback_image"
old_current_record=''
old_previous_record=''
old_previous_ddl_record=''
old_previous_live_record=''
if [ -f "$DEPLOY_STATE_DIR/current-image" ]; then old_current_record=$(cat "$DEPLOY_STATE_DIR/current-image"); fi
if [ -f "$DEPLOY_STATE_DIR/previous-image" ]; then old_previous_record=$(cat "$DEPLOY_STATE_DIR/previous-image"); fi
if [ -f "$DEPLOY_STATE_DIR/previous-ddl-auto" ]; then old_previous_ddl_record=$(cat "$DEPLOY_STATE_DIR/previous-ddl-auto"); fi
if [ -f "$DEPLOY_STATE_DIR/previous-live-env" ]; then old_previous_live_record=$(cat "$DEPLOY_STATE_DIR/previous-live-env"); fi
release_compose=("${compose[@]}" -f "$override")
write_image() {
  printf 'services:\n  backend:\n    image: "%s"\n    environment:\n      DDL_AUTO: %s\n      LIVE_FEEDER_ENABLED: "%s"\n      LIVE_ALLOW_REMOTE_DATABASE: "%s"\n      LIVE_RESET_ENABLED: "%s"\n' \
    "$1" "$2" "$3" "$4" "$5" > "$override.tmp"
  mv -- "$override.tmp" "$override"
}
start_backend() {
  "${release_compose[@]}" up --detach --no-deps --no-build --pull never --wait --wait-timeout "$health_timeout" backend
}
record() {
  printf '%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" >> "$DEPLOY_STATE_DIR/history.tsv"
}
restore_record() {
  if [ -n "$2" ]; then
    printf '%s\n' "$2" > "$DEPLOY_STATE_DIR/$1.tmp"
    mv -- "$DEPLOY_STATE_DIR/$1.tmp" "$DEPLOY_STATE_DIR/$1"
  else
    rm -f -- "$DEPLOY_STATE_DIR/$1"
  fi
}
restore_on_failure() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [ "$status" -ne 0 ]; then
    set +e
    echo 'Deployment failed; restoring the exact previous backend image.' >&2
    restore_record current-image "$old_current_record"
    restore_record previous-image "$old_previous_record"
    restore_record previous-ddl-auto "$old_previous_ddl_record"
    restore_record previous-live-env "$old_previous_live_record"
    if write_image "$rollback_image" "$old_ddl_auto" "$old_live_feeder" "$old_live_allow_remote" "$old_live_reset" && start_backend && smoke; then
      record restored "$rollback_image"
      echo 'Previous backend restored. This deployment remains failed.' >&2
    else
      record rollback-failed "$rollback_image"
      echo 'ERROR: Automatic rollback failed; inspect the backend and backend on the server.' >&2
    fi
  fi
  exit "$status"
}
trap restore_on_failure EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

write_image "$image" "$next_ddl_auto" "$next_live_feeder" "$next_live_allow_remote" "$next_live_reset"
start_backend
smoke || die 'Backend or API smoke checks failed'
printf '%s\n' "$rollback_image" > "$DEPLOY_STATE_DIR/previous-image.tmp"
printf '%s\n' "$old_ddl_auto" > "$DEPLOY_STATE_DIR/previous-ddl-auto.tmp"
printf 'LIVE_FEEDER_ENABLED=%s\nLIVE_ALLOW_REMOTE_DATABASE=%s\nLIVE_RESET_ENABLED=%s\n' "$old_live_feeder" "$old_live_allow_remote" "$old_live_reset" > "$DEPLOY_STATE_DIR/previous-live-env.tmp"
printf '%s\n' "$image" > "$DEPLOY_STATE_DIR/current-image.tmp"
mv -- "$DEPLOY_STATE_DIR/previous-image.tmp" "$DEPLOY_STATE_DIR/previous-image"
mv -- "$DEPLOY_STATE_DIR/previous-ddl-auto.tmp" "$DEPLOY_STATE_DIR/previous-ddl-auto"
mv -- "$DEPLOY_STATE_DIR/previous-live-env.tmp" "$DEPLOY_STATE_DIR/previous-live-env"
mv -- "$DEPLOY_STATE_DIR/current-image.tmp" "$DEPLOY_STATE_DIR/current-image"
record "$mode" "$image"
trap - EXIT HUP INT TERM
echo "Backend $mode succeeded: $image"
