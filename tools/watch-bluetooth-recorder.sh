#!/system/bin/sh
# Run as ADB shell. No Bluetooth, pairing, power or therapy settings are changed.
set -eu
umask 077
DIR=/data/local/tmp/weartester-bt-recorder
SELF=$DIR/recorder.sh

owned_pid() {
    [ -f "$DIR/runner.pid" ] || return 1
    PID=$(cat "$DIR/runner.pid")
    case "$PID" in ''|*[!0-9]*) return 1 ;; esac
    [ -r "/proc/$PID/cmdline" ] || return 1
    tr '\000' ' ' <"/proc/$PID/cmdline" | grep -F "$SELF run" >/dev/null
}

case "${1:-status}" in
    start)
        mkdir -p "$DIR"
        if owned_pid; then printf 'already running pid=%s\n' "$PID"; exit 0; fi
        # Only remove an empty lock left by a dead recorder.
        rmdir "$DIR/lock" 2>/dev/null || true
        mkdir "$DIR/lock" || { echo 'recorder start already pending'; exit 1; }
        nohup /system/bin/sh "$SELF" run </dev/null >"$DIR/runner.txt" 2>&1 &
        sleep 1
        owned_pid || { echo 'recorder did not start'; exit 1; }
        printf 'started pid=%s\n' "$PID"
        ;;
    run)
        printf '%s\n' "$$" >"$DIR/runner.pid"
        CHILD=''
        cleanup() {
            if [ -n "$CHILD" ] && [ -r "/proc/$CHILD/cmdline" ]; then
                if tr '\000' ' ' <"/proc/$CHILD/cmdline" | grep -F "$DIR/system.log" >/dev/null; then
                    kill "$CHILD" 2>/dev/null || true
                    wait "$CHILD" 2>/dev/null || true
                fi
            fi
            rm -f "$DIR/runner.pid"
            rmdir "$DIR/lock" 2>/dev/null || true
        }
        trap '' HUP
        trap 'exit 0' INT TERM
        trap cleanup EXIT
        while :; do
            # Eight approximately 1 MiB files; selected stack/connectivity tags, no app payloads.
            /system/bin/logcat -b main -b system -b crash -b events -v threadtime -T 1 \
                -f "$DIR/system.log" -r 1024 -n 7 \
                'BluetoothManagerService:V' 'BluetoothAdapterService:V' 'BluetoothAdapter:V' \
                'BluetoothGatt:V' 'BluetoothGattServer:V' 'BluetoothLeScanner:V' \
                'BtGatt.GattService:V' 'BtGatt.ScanManager:V' 'BtGatt.ContextMap:V' \
                'bt_stack:V' 'bt_btif:V' 'bt_btif_core:V' 'bt_btif_dm:V' \
                'bt_btm:V' 'bt_l2cap:V' 'bt_hci:V' 'bt_hci_layer:V' \
                'bt_gattc:V' 'bt_att:V' 'bt_vendor:V' 'bluetooth:V' \
                'android.hardware.bluetooth.hci_packetizer:V' \
                'AdbService:V' 'AdbDebuggingManager:V' 'adbd:I' \
                'WearWifiMediator:V' 'WearConnectivityService:V' \
                'WifiClientModeImpl:I' 'WifiController:I' 'WifiActiveModeWarden:I' \
                'Process:I' 'lmkd:I' 'Watchdog:W' 'libc:F' 'DEBUG:I' \
                'am_kill:I' 'am_proc_died:I' 'am_crash:I' 'am_anr:I' '*:S' \
                2>"$DIR/logcat-error.txt" &
            CHILD=$!
            printf '%s\n' "$CHILD" >"$DIR/logcat.pid"
            wait "$CHILD" || true
            CHILD=''
            sleep 5
        done
        ;;
    status)
        owned_pid || { echo 'not running'; exit 1; }
        printf 'runner pid=%s\n' "$PID"
        [ ! -f "$DIR/logcat.pid" ] || printf 'logcat pid=%s\n' "$(cat "$DIR/logcat.pid")"
        ls -ln "$DIR"/system.log* 2>/dev/null || true
        ;;
    stop)
        if owned_pid; then kill "$PID"; echo 'stop requested'; else echo 'not running'; fi
        ;;
    *) echo 'usage: recorder.sh start|status|stop'; exit 2 ;;
esac
