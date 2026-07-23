#!/system/bin/sh

# Persistent selection state intentionally lives outside the module directory.
# Module upgrades replace /data/adb/modules/wekit, while this file must retain
# the user's per-Android-user choices across upgrades.
STATE_DIR=/data/adb/wekit
TARGETS_FILE=$STATE_DIR/injection-targets.tsv
LOCK_DIR=$STATE_DIR/.injection-targets.lock
LOCK_RETRIES=10
# Keep this in lockstep with PackageNames.isWeChat(packageName):
# packageName.startsWith("com.tencent.mm").
TARGET_PACKAGE_PREFIX=com.tencent.mm

# Every temporary replacement file is published with rename(2). Set this before
# any redirection so it is private even during construction.
umask 077

ensure_state_dir() {
  umask 077
  mkdir -p "$STATE_DIR" || return 1
  chmod 700 "$STATE_DIR" || return 1
}

# Android module shells do not consistently ship flock(1). mkdir(2) is atomic,
# so use a private lock directory to serialize read-modify-publish operations.
# A killed WebUI command can leave a stale lock; the next command verifies the
# recorded PID and safely reclaims it.
release_targets_lock() {
  [ -r "$LOCK_DIR/pid" ] || return 0
  IFS= read -r lock_owner < "$LOCK_DIR/pid" || return 0
  [ "$lock_owner" = "$$" ] || return 0
  rm -f "$LOCK_DIR/pid"
  rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_targets_lock() {
  ensure_state_dir || return 1
  lock_attempt=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ -r "$LOCK_DIR/pid" ]; then
      IFS= read -r lock_owner < "$LOCK_DIR/pid" || lock_owner=
      case "$lock_owner" in
        ''|*[!0-9]*)
          if [ "$lock_attempt" -ge 2 ]; then
            rm -f "$LOCK_DIR/pid"
            rmdir "$LOCK_DIR" 2>/dev/null && continue
          fi
          ;;
        *)
          if ! kill -0 "$lock_owner" 2>/dev/null; then
            rm -f "$LOCK_DIR/pid"
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
          fi
          ;;
      esac
    elif [ "$lock_attempt" -ge 2 ]; then
      # Do not race the owner between mkdir and pid creation; only reclaim an
      # empty lock after it has remained incomplete for multiple seconds.
      rmdir "$LOCK_DIR" 2>/dev/null && continue
    fi

    lock_attempt=$((lock_attempt + 1))
    if [ "$lock_attempt" -ge "$LOCK_RETRIES" ]; then
      echo "timed out waiting for WeKit target configuration lock" >&2
      return 1
    fi
    sleep 1
  done

  printf '%s\n' "$$" > "$LOCK_DIR/pid" || {
    rmdir "$LOCK_DIR" 2>/dev/null || true
    return 1
  }
}

run_with_targets_lock() {
  acquire_targets_lock || return 1
  "$@"
  status=$?
  release_targets_lock
  return "$status"
}

list_user_ids() {
  users=$(cmd user list 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  if [ -n "$users" ]; then
    printf '%s\n' "$users"
  else
    # AOSP devices always have user 0. Keep the UI usable on older builds whose
    # user-service command is unavailable to the root shell.
    printf '%s\n' 0
  fi
}

is_valid_user_id() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

is_valid_package() {
  case "$1" in
    ''|.*|*.|*..*|*[!A-Za-z0-9._]*) return 1 ;;
    *.*) return 0 ;;
    *) return 1 ;;
  esac
}

is_wechat_package() {
  case "$1" in
    "$TARGET_PACKAGE_PREFIX"*) return 0 ;;
    *) return 1 ;;
  esac
}

list_installed_packages_for_user() {
  user_id=$1
  cmd package list packages --user "$user_id" 2>/dev/null ||
    pm list packages --user "$user_id" 2>/dev/null
}

write_default_header() {
  printf '%s\n' '# WeKit Zygisk injection targets v1'
  printf '%s\n' '# userId<TAB>packageName<TAB>enabled'
}

collect_wechat_targets_locked() {
  scan_file=$1
  packages_file=$STATE_DIR/.injection-targets.packages.$$
  unsorted_file=$STATE_DIR/.injection-targets.unsorted.$$
  : > "$unsorted_file" || return 1

  for user_id in $(list_user_ids); do
    is_valid_user_id "$user_id" || continue
    if ! list_installed_packages_for_user "$user_id" > "$packages_file"; then
      rm -f "$packages_file" "$unsorted_file"
      return 1
    fi
    sed -n 's/^package://p' "$packages_file" |
      while IFS= read -r package_name; do
        is_valid_package "$package_name" || continue
        is_wechat_package "$package_name" || continue
        printf '%s\t%s\n' "$user_id" "$package_name" >> "$unsorted_file"
      done || {
        rm -f "$packages_file" "$unsorted_file"
        return 1
      }
  done
  rm -f "$packages_file"
  LC_ALL=C sort -u -k1,1n -k2,2 "$unsorted_file" > "$scan_file"
  status=$?
  rm -f "$unsorted_file"
  return "$status"
}

# Refresh membership from the package manager while preserving each surviving
# target's explicit toggle. Every newly discovered package starts disabled.
refresh_targets_locked() {
  ensure_state_dir || return 1
  scan_file=$STATE_DIR/.injection-targets.scan.$$
  temp_file=$TARGETS_FILE.tmp.$$
  collect_wechat_targets_locked "$scan_file" || {
    rm -f "$scan_file"
    return 1
  }

  previous_file=$TARGETS_FILE
  [ -f "$previous_file" ] || previous_file=/dev/null
  (
    umask 077
    write_default_header
    awk -F '\t' -v OFS='\t' -v prefix="$TARGET_PACKAGE_PREFIX" '
      NR == FNR {
        if (NF == 3 && $1 ~ /^[0-9]+$/ && index($2, prefix) == 1 &&
            ($3 == "0" || $3 == "1")) {
          enabled[$1 SUBSEP $2] = $3
        }
        next
      }
      NF == 2 && $1 ~ /^[0-9]+$/ && index($2, prefix) == 1 {
        key = $1 SUBSEP $2
        print $1, $2, (key in enabled ? enabled[key] : "0")
      }
    ' "$previous_file" "$scan_file"
  ) > "$temp_file"
  status=$?
  rm -f "$scan_file"
  if [ "$status" -ne 0 ]; then
    rm -f "$temp_file"
    return "$status"
  fi
  chmod 600 "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  mv -f "$temp_file" "$TARGETS_FILE" || {
    rm -f "$temp_file"
    return 1
  }
}

ensure_initialized_locked() {
  if [ ! -f "$TARGETS_FILE" ]; then
    refresh_targets_locked
  fi
}

list_targets_locked() {
  ensure_initialized_locked || return 1
  awk -F '\t' -v prefix="$TARGET_PACKAGE_PREFIX" '
    NF == 3 && $1 ~ /^[0-9]+$/ && index($2, prefix) == 1 &&
      ($3 == "0" || $3 == "1") {
      print $1 "\t" $2 "\t" $3
    }
  ' "$TARGETS_FILE"
}

list_targets() {
  run_with_targets_lock list_targets_locked
}

refresh_targets() {
  run_with_targets_lock refresh_targets_locked
}

set_enabled_locked() {
  user_id=$1
  package_name=$2
  enabled=$3
  ensure_initialized_locked || return 1

  temp_file=$TARGETS_FILE.tmp.$$
  awk -F '\t' -v OFS='\t' -v user="$user_id" -v package="$package_name" -v enabled="$enabled" '
    $1 == user && $2 == package { print $1, $2, enabled; found = 1; next }
    { print }
    END { exit found ? 0 : 4 }
  ' "$TARGETS_FILE" > "$temp_file"
  status=$?
  if [ "$status" -ne 0 ]; then
    rm -f "$temp_file"
    return "$status"
  fi
  chmod 600 "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  mv -f "$temp_file" "$TARGETS_FILE" || {
    rm -f "$temp_file"
    return 1
  }
}

set_enabled() {
  user_id=$1
  package_name=$2
  enabled=$3
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  if ! is_wechat_package "$package_name"; then
    echo "WeKit Zygisk supports only packages matching $TARGET_PACKAGE_PREFIX*" >&2
    return 4
  fi
  [ "$enabled" = 0 ] || [ "$enabled" = 1 ] || return 2
  run_with_targets_lock set_enabled_locked "$user_id" "$package_name" "$enabled"
}

case "$1" in
  list) list_targets ;;
  refresh) refresh_targets ;;
  set) set_enabled "$2" "$3" "$4" ;;
  *)
    echo "Usage: $0 {list|refresh|set}" >&2
    exit 64
    ;;
esac
