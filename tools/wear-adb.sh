#!/usr/bin/env bash
set -euo pipefail

# Set WATCH_ID to your watch's adb serial (see: adb devices -l).
WATCH_ID=${WATCH_ID:?Set WATCH_ID to the watch adb serial}
PACKAGE=${PACKAGE:-com.example.weartester}
ADB=${ADB:-adb}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
LOCK_DIR=${LOCK_DIR:-/tmp/wear-adb-${WATCH_ID}.lock}
CONNECT_TIMEOUT_SEC=${CONNECT_TIMEOUT_SEC:-8}
SERIAL_FILE=${SERIAL_FILE:-/tmp/wear-adb-${WATCH_ID}.serial}

log() { printf '[wear-adb] %s\n' "$*" >&2; }

acquire_lock() {
  local waited=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    waited=$((waited + 1))
    if [ "$waited" -gt 20 ]; then
      log "another wear-adb command is still running: $LOCK_DIR"
      exit 75
    fi
    sleep 1
  done
  trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT INT TERM
}

run_with_timeout() {
  local seconds=$1
  local out=$2
  shift 2
  rm -f "$out"
  ( "$@" >"$out" 2>&1 & echo $! >"$out.pid" )
  local elapsed=0
  local pid
  pid=$(cat "$out.pid")
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$elapsed" -ge "$seconds" ]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      echo "TIMEOUT after ${seconds}s: $*" >>"$out"
      return 124
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  wait "$pid" 2>/dev/null || true
}

adb_devices() {
  "$ADB" devices -l 2>/dev/null || true
}

known_serials() {
  adb_devices | awk -v id="$WATCH_ID" 'NR>1 && $2=="device" && $1==id {print $1}'
}

offline_serials() {
  adb_devices | awk 'NR>1 && $2=="offline" {print $1}'
}

cleanup_offline() {
  local serial
  for serial in $(offline_serials); do
    log "disconnect offline transport $serial"
    "$ADB" disconnect "$serial" >/dev/null 2>&1 || true
  done
}

check_known_conflicts() {
  local conflicts
  conflicts=$(pgrep -fl 'aaps_collect_7d|adb .*info\.nightscout\.androidaps|adb .*androidaps\.db' || true)
  if [ -n "$conflicts" ]; then
    log "warning: another ADB-heavy job may fight the watch transport:"
    printf '%s\n' "$conflicts" | sed 's/^/[wear-adb] conflict: /' >&2
  fi
}

resolve_host_v4() {
  local host=$1
  if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    printf '%s\n' "$host"
    return 0
  fi
  local out=/tmp/wear-adb-resolve-${WATCH_ID}.txt
  run_with_timeout 4 "$out" dns-sd -G v4 "$host" >/dev/null || true
  awk '/ Add / && $0 ~ /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/ {print $(NF-1)}' "$out" | tail -1
}

endpoint_from_lookup() {
  local instance=$1
  local type=$2
  local out=/tmp/wear-adb-lookup-${WATCH_ID}.txt
  run_with_timeout 5 "$out" dns-sd -L "$instance" "$type" local >/dev/null || true
  local line host port ip
  line=$(sed -n 's/.*can be reached at \(.*\):\([0-9][0-9]*\).*/\1 \2/p' "$out" | tail -1)
  [ -n "$line" ] || return 0
  host=$(printf '%s\n' "$line" | awk '{print $1}' | sed 's/\.$//')
  port=$(printf '%s\n' "$line" | awk '{print $2}')
  ip=$(resolve_host_v4 "$host")
  [ -n "$ip" ] && [ -n "$port" ] && printf '%s:%s\n' "$ip" "$port"
}

endpoints_from_adb_mdns() {
  "$ADB" mdns services 2>/dev/null | awk -v id="$WATCH_ID" '$0 ~ id && $(NF-1)=="_adb-tls-connect._tcp" && $NF ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+$/ {print $NF}' || true
}

endpoints_from_dns_sd() {
  local out=/tmp/wear-adb-browse-${WATCH_ID}.txt
  : >"$out"
  run_with_timeout 4 "$out.legacy" dns-sd -B _adb._tcp local >/dev/null || true
  run_with_timeout 4 "$out.tls" dns-sd -B _adb-tls-connect._tcp local >/dev/null || true
  cat "$out.legacy" "$out.tls" >"$out"

  local instance
  while IFS= read -r instance; do
    endpoint_from_lookup "$instance" _adb._tcp
  done < <(awk -v id="$WATCH_ID" '$0 ~ id && $0 ~ /_adb\._tcp/ {sub(/^.*_adb\._tcp\. /, ""); print}' "$out" | sort -u)

  while IFS= read -r instance; do
    endpoint_from_lookup "$instance" _adb-tls-connect._tcp
  done < <(awk -v id="$WATCH_ID" '$0 ~ id && $0 ~ /_adb-tls-connect\._tcp/ {sub(/^.*_adb-tls-connect\._tcp\. /, ""); print}' "$out" | sort -u)
}

candidate_endpoints() {
  [ -f "$SERIAL_FILE" ] && cat "$SERIAL_FILE" || true
}

discovered_endpoints() {
  endpoints_from_dns_sd
}

is_device() {
  local serial=$1
  "$ADB" devices | awk -v s="$serial" '$1==s && $2=="device" {found=1} END {exit found?0:1}'
}

target_matches_watch() {
  local serial=$1
  local actual
  actual="$("$ADB" -s "$serial" shell 'getprop ro.serialno; getprop ro.boot.serialno' 2>/dev/null | tr -d '\r' | awk 'NF {print; exit}')"
  if [ "$actual" = "$WATCH_ID" ]; then
    return 0
  fi
  log "reject $serial: device serial is '${actual:-unknown}', expected '$WATCH_ID'"
  "$ADB" disconnect "$serial" >/dev/null 2>&1 || true
  return 1
}

connect_one() {
  local endpoint=$1
  [ -n "$endpoint" ] || return 1
  if is_device "$endpoint"; then
    if target_matches_watch "$endpoint"; then
      printf '%s\n' "$endpoint"
      return 0
    fi
    return 1
  fi
  "$ADB" disconnect "$endpoint" >/dev/null 2>&1 || true
  local out=/tmp/wear-adb-connect-${WATCH_ID}.txt
  log "trying $endpoint"
  run_with_timeout "$CONNECT_TIMEOUT_SEC" "$out" "$ADB" connect "$endpoint" >/dev/null || true
  sed 's/^/[wear-adb] adb: /' "$out" >&2 || true
  if is_device "$endpoint" && target_matches_watch "$endpoint"; then
    printf '%s\n' "$endpoint" | tee "$SERIAL_FILE"
    return 0
  fi
  "$ADB" disconnect "$endpoint" >/dev/null 2>&1 || true
  return 1
}

connect_watch() {
  check_known_conflicts
  cleanup_offline
  local endpoint
  while IFS= read -r endpoint; do
    [ -n "$endpoint" ] || continue
    if connect_one "$endpoint"; then
      return 0
    fi
  done < <(known_serials | awk 'NF && !seen[$0]++')
  while IFS= read -r endpoint; do
    [ -n "$endpoint" ] || continue
    if connect_one "$endpoint"; then
      return 0
    fi
  done < <(endpoints_from_adb_mdns | awk 'NF && !seen[$0]++')
  while IFS= read -r endpoint; do
    [ -n "$endpoint" ] || continue
    if connect_one "$endpoint"; then
      return 0
    fi
  done < <(candidate_endpoints | awk 'NF && !seen[$0]++')
  while IFS= read -r endpoint; do
    [ -n "$endpoint" ] || continue
    if connect_one "$endpoint"; then
      return 0
    fi
  done < <(discovered_endpoints | awk 'NF && !seen[$0]++')
  return 1
}

shell_watch() {
  local serial
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  "$ADB" -s "$serial" shell "$@"
}

prefs() {
  local serial xml recv now glucose event
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  xml=$("$ADB" -s "$serial" shell run-as "$PACKAGE" cat shared_prefs/dexcom_config.xml 2>/dev/null || true)
  printf '%s\n' "$xml" | sed -e 's/></>\n</g' | awk '
    /<string name="event_log">/ {
      print "    <string name=\"event_log\">... use tools/wear-adb.sh events ...</string>"
      skip=1
      if ($0 ~ /<\/string>/) skip=0
      next
    }
    skip && /<\/string>/ { skip=0; next }
    !skip { print }
  '
  recv=$(printf '%s' "$xml" | sed -n 's/.*<long name="glucose_received_at_millis" value="\([0-9-]*\)".*/\1/p')
  glucose=$(printf '%s' "$xml" | sed -n 's/.*<int name="glucose_mgdl" value="\([0-9-]*\)".*/\1/p')
  event=$(printf '%s' "$xml" | sed -n 's/.*<string name="last_event">\(.*\)<\/string>.*/\1/p')
  now=$(date +%s000)
  if [ -n "$recv" ]; then
    printf 'SUMMARY glucose=%s host_age_sec=%s event=%s serial=%s\n' "${glucose:-?}" "$(( (now - recv) / 1000 ))" "${event:-?}" "$serial"
  else
    printf 'SUMMARY glucose=? host_age_sec=? event=%s serial=%s\n' "${event:-?}" "$serial"
  fi
}

events() {
  local serial xml
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  xml=$("$ADB" -s "$serial" shell run-as "$PACKAGE" cat shared_prefs/dexcom_config.xml 2>/dev/null || true)
  printf '%s\n' "$xml" |
    perl -0777 -ne '
      if (m{<string name="event_log">(.*?)</string>}s) {
        $s = $1;
        $s =~ s/&#10;/\n/g;
        $s =~ s/&quot;/"/g;
        $s =~ s/&apos;/'"'"'/g;
        $s =~ s/&lt;/</g;
        $s =~ s/&gt;/>/g;
        $s =~ s/&amp;/&/g;
        print "$s\n";
      }
    ' |
    sed '/^$/d'
}

logs() {
  local serial
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  "$ADB" -s "$serial" logcat -d -v time -t "${1:-260}" BondProbe:D AlarmManager:D BluetoothAdapter:V bt_stack:I '*:S'
}

repair() {
  local serial
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  log "repair settings on $serial"
  local setting key value op
  for setting in \
    'development_settings_enabled 1' 'adb_enabled 1' 'adb_wifi_enabled 1' \
    'adb_allowed_connection_time 0' 'wifi_always_requested 1' \
    'wifi_sleep_policy 2' 'stay_on_while_plugged_in 0'; do
    read -r key value <<<"$setting"
    "$ADB" -s "$serial" shell settings put global "$key" "$value"
  done
  "$ADB" -s "$serial" shell svc wifi enable
  "$ADB" -s "$serial" shell svc power stayon false
  # Android 14 otherwise converts HIGH_PERF into a screen-on-only LOW_LATENCY lock.
  "$ADB" -s "$serial" shell device_config put wifi high_perf_lock_deprecated false
  "$ADB" -s "$serial" shell cmd deviceidle whitelist +com.example.weartester
  for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND; do
    "$ADB" -s "$serial" shell cmd appops set com.example.weartester "$op" allow
  done
  # Retain paired TLS ADB; do not replace it with the legacy TCP listener.
  "$ADB" -s "$serial" shell settings get global wifi_always_requested
  "$ADB" -s "$serial" shell settings get global adb_wifi_enabled
  "$ADB" -s "$serial" shell device_config get wifi high_perf_lock_deprecated
  adb_devices
}

install_app() {
  local serial
  serial=$(connect_watch) || { log "watch is not reachable"; return 1; }
  [ -f "$APK" ] || { log "APK not found: $APK"; return 1; }
  log "installing $APK to $serial"
  "$ADB" -s "$serial" install --no-streaming -r "$APK"
  "$ADB" -s "$serial" shell am force-stop "$PACKAGE"
  "$ADB" -s "$serial" shell am start -n "$PACKAGE/.MainActivity" --ez auto_start_probe true
}

monitor() {
  while true; do
    date '+--- %Y-%m-%d %H:%M:%S %Z ---'
    prefs || true
    sleep "${1:-30}"
  done
}

usage() {
  cat <<USAGE
Usage: $0 <command>
Commands:
  connect        connect to watch and print serial
  prefs|status   print app prefs and glucose age summary
  events         print persisted app event log
  logs [N]       print last N BondProbe logcat lines
  install        install debug APK and restart app
  repair         retain Wi-Fi requests and paired TLS debugging without keeping screen on
  monitor [SEC]  loop prefs with reconnect, one ADB command at a time
  shell ...      run adb shell via discovered watch serial
USAGE
}

main() {
  acquire_lock
  local cmd=${1:-status}
  shift || true
  case "$cmd" in
    connect) connect_watch ;;
    prefs|status) prefs ;;
    events) events ;;
    logs|logcat) logs "${1:-260}" ;;
    install) install_app ;;
    repair) repair ;;
    monitor) monitor "${1:-30}" ;;
    shell) shell_watch "$@" ;;
    *) usage; exit 64 ;;
  esac
}

main "$@"
