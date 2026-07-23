#!/system/bin/sh

# Persistent selection state intentionally lives outside the module directory.
# Module upgrades replace /data/adb/modules/wekit, while this file must retain
# the user's per-Android-user choices across upgrades.
STATE_DIR=/data/adb/wekit
TARGETS_FILE=$STATE_DIR/injection-targets.tsv
TARGET_PACKAGE=com.tencent.mm

# Every temporary replacement file is published with rename(2). Set this before
# any redirection so it is private even during construction.
umask 077

ensure_state_dir() {
  umask 077
  mkdir -p "$STATE_DIR" || return 1
  chmod 700 "$STATE_DIR" || return 1
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

is_installed_for_user() {
  user_id=$1
  package_name=$2
  cmd package list packages --user "$user_id" 2>/dev/null |
    sed -n 's/^package://p' |
    grep -F -x "$package_name" >/dev/null 2>&1
}

write_default_header() {
  printf '%s\n' '# WeKit Zygisk injection targets v1'
  printf '%s\n' '# userId<TAB>packageName<TAB>enabled'
}

write_scan_result() {
  ensure_state_dir || return 1
  temp_file=$TARGETS_FILE.tmp.$$
  (
    umask 077
    write_default_header
    for user_id in $(list_user_ids); do
      if is_installed_for_user "$user_id" "$TARGET_PACKAGE"; then
        printf '%s\t%s\t0\n' "$user_id" "$TARGET_PACKAGE"
      fi
    done
  ) > "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  chmod 600 "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  mv -f "$temp_file" "$TARGETS_FILE"
}

ensure_initialized() {
  if [ ! -f "$TARGETS_FILE" ]; then
    write_scan_result
  fi
}

list_targets() {
  ensure_initialized || return 1
  awk -F '\t' 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 != "" && ($3 == "0" || $3 == "1") { print $1 "\t" $2 "\t" $3 }' "$TARGETS_FILE"
}

list_apps() {
  for user_id in $(list_user_ids); do
    cmd package list packages --user "$user_id" 2>/dev/null |
      sed -n 's/^package://p' |
      while IFS= read -r package_name; do
        [ -n "$package_name" ] && printf '%s\t%s\n' "$user_id" "$package_name"
      done
  done
}

add_target() {
  user_id=$1
  package_name=$2
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  ensure_initialized || return 1
  is_installed_for_user "$user_id" "$package_name" || return 3

  if awk -F '\t' -v user="$user_id" -v package="$package_name" \
    '$1 == user && $2 == package { found = 1 } END { exit found ? 0 : 1 }' "$TARGETS_FILE"; then
    return 0
  fi

  temp_file=$TARGETS_FILE.tmp.$$
  (
    cat "$TARGETS_FILE"
    printf '%s\t%s\t0\n' "$user_id" "$package_name"
  ) > "$temp_file" || return 1
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

set_enabled() {
  user_id=$1
  package_name=$2
  enabled=$3
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  [ "$enabled" = 0 ] || [ "$enabled" = 1 ] || return 2
  ensure_initialized || return 1

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
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

delete_target() {
  user_id=$1
  package_name=$2
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  ensure_initialized || return 1

  temp_file=$TARGETS_FILE.tmp.$$
  awk -F '\t' -v user="$user_id" -v package="$package_name" '
    $1 == user && $2 == package { found = 1; next }
    { print }
    END { exit found ? 0 : 4 }
  ' "$TARGETS_FILE" > "$temp_file"
  status=$?
  if [ "$status" -ne 0 ]; then
    rm -f "$temp_file"
    return "$status"
  fi
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

reset_targets() {
  ensure_state_dir || return 1
  rm -f "$TARGETS_FILE"
  write_scan_result
}

case "$1" in
  list) list_targets ;;
  apps) list_apps ;;
  add) add_target "$2" "$3" ;;
  set) set_enabled "$2" "$3" "$4" ;;
  delete) delete_target "$2" "$3" ;;
  reset) reset_targets ;;
  *)
    echo "Usage: $0 {list|apps|add|set|delete|reset}" >&2
    exit 64
    ;;
esac
