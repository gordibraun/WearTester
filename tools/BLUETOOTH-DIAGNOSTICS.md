# Bluetooth diagnostics on OnePlus Watch 3

These tools collect evidence. They do not fix GATT 133, restart Bluetooth,
change pairing, start a sensor session or issue therapy commands.

## App incident snapshots (collector 1.8)

Early failed GATT connections, scan failures, connection/discovery timeouts and adapter-off
events queue a snapshot on a separate worker. Admission is limited to one
snapshot per 30 seconds, with at most one outstanding capture. Exceptions in
the diagnostic entry point are contained. There is no diagnostics wake lock.

The snapshot contains event time, uptime, application version, firmware,
failure details, tails of the collector journal and these read-only dumps:

- `dumpsys -t 2 bluetooth_manager`
- `dumpsys -t 2 activity exit-info com.android.bluetooth`
- `dumpsys -t 2 power`

Only the already-authorized DUMP permission is used. Each subprocess has a
bounded wait; results include exit status, timeout and source size. Missing
permission and partial snapshots are reported rather than called complete.

Device verification on 2026-09-24: Bluetooth and power dumps succeeded;
`activity exit-info` was denied for missing PACKAGE_USAGE_STATS despite an
exit code of zero. The app correctly reported 2/3 complete dumps. No extra
permission was granted. Process-exit history remains readable through the
ADB shell and system process-exit events are included in the shell recorder.

Storage: app-private `no_backup/bluetooth-incidents/`, excluded from Android
backup. Twelve incident folders are retained, with five captured sections
limited to 256 KiB each (approximately 15 MiB plus small metadata). The limit
applies to completed sections; command output uses a temporary file during
the bounded dump. The two-second dumps may consume some CPU/disk after a
failure. App journal tails contain personal glucose data; do not publish them.

The app's Connection dialog shows the latest result. Its Snapshot button
captures a manual diagnostic without changing the collector. For verification:

```bash
adb -s SERIAL shell am start -n com.example.weartester/.MainActivity \
  --ez bluetooth_diagnostic_snapshot true
adb -s SERIAL exec-out run-as com.example.weartester \
  tar -cf - no_backup/bluetooth-incidents files/connection-journal > incidents.tar
```

After a reboot, automatic incident capture resumes when the existing collector
service starts. It does not require a computer connection.

## System log recorder (ADB shell)

Android 14 gates application access to other processes' logs with a foreground
consent dialog. DUMP alone does not grant this access. Instead, an explicit
ADB-installed shell recorder saves selected Bluetooth stack, Wi-Fi/ADB and
process-exit tags in `/data/local/tmp/weartester-bt-recorder/`. This avoids pretending that
the app can read system logs silently after a reboot.

```bash
ADB=/absolute/path/to/adb bash tools/install-bluetooth-recorder.sh SERIAL
adb -s SERIAL shell sh /data/local/tmp/weartester-bt-recorder/recorder.sh status
adb -s SERIAL pull /data/local/tmp/weartester-bt-recorder ./system-bluetooth-logs
adb -s SERIAL shell sh /data/local/tmp/weartester-bt-recorder/recorder.sh stop
```

The installer verifies the hardware serial when EXPECTED_SERIAL is set. The recorder
retains the current log and seven approximately 1 MiB rotations, ignores HUP
and redirects its standard streams, so it is intended to survive ADB/network
disconnect. On 2026-09-24 the recorder retained both process IDs and continued
growing its log through Wi-Fi ADB failures and a host ADB-server restart.
Physical USB unplug/replug was also verified later that evening: both recorder
PIDs survived and the file continued growing. Battery-only longevity has not
been established.
It restarts its own logcat child
after a child exit, not Bluetooth or Android. Stop validates process identity
before sending a signal. Logs are readable by the shell account, not shared
storage; they can contain paired-device addresses, network identifiers and process names.

## Wireless debugging availability

`tools/wear-adb.sh repair` requests persistent Wi-Fi with the Android global
`wifi_always_requested=1` setting and leaves paired TLS ADB in use. It does
not switch to the legacy port 5555 and sets `stay_on_while_plugged_in=0`.
It also sets `device_config put wifi high_perf_lock_deprecated false` on
the verified watch. Collector 1.9 requests HIGH_PERF rather than LOW_LATENCY
while wireless debugging is enabled. Android 14 otherwise silently converts
HIGH_PERF to LOW_LATENCY, which is inactive for this app with the screen off.
The collector rechecks the debug switch every 30 seconds and releases the
lock when the switch is off or the service stops. No traffic generator or
screen wake lock is used for this fix.

Both the persistent Wi-Fi request and active HIGH_PERF lock can increase
battery use. To undo the new system settings (original high-perf flag was true):

```bash
adb -s SERIAL shell settings put global wifi_always_requested 0
adb -s SERIAL shell device_config put wifi high_perf_lock_deprecated true
```

Disable wireless debugging and allow up to 30 seconds for the app to release
an existing lock before restoring the flag; changing the flag alone does not
convert a lock already acquired. Re-enabling debugging reacquires the lock.
DeviceConfig sync was not globally disabled, so a future system configuration
update may change the flag again. Recheck the effective `WifiLock` type in
`dumpsys wifi`, not just the application's `isHeld` property.

The connection helper now prioritizes the watch's current TLS mDNS endpoint
over its cached address, ignores pairing ports and other device identifiers,
and no longer probes hard-coded historical addresses.

This is a persistent network request, not a guarantee of uninterrupted ADB:
firmware power policy, unavailable Wi-Fi and transport failures can still
disconnect it. The TLS port may change. Screen-off and reboot behavior must
be verified on the actual watch; an enabled debug toggle is not proof that
the watch is reachable. Do not disable TLS authentication to address this.

Device test on 2026-09-24: the effective lock was type 3 with the screen asleep
and stay-on disabled. Wireless ADB still suffered TCP/TLS timeouts, including
with host ADB 37.0.1 and after a Wi-Fi restart. Neither workaround fixed all
transport failures. Four consecutive sensor readings on collector 1.9 arrived
about five minutes apart during the test; this does not establish that earlier
GATT 133 incidents are fixed. All tests so far retained USB power.

**The shell recorder does not survive a full watch reboot.** Run the installer
again after a reboot. File-size limits are not a battery-use guarantee.
The app's incident snapshots and system recorder are separate; starting one
does not mean the other is running. Verify both and file growth explicitly.

No full HCI capture is enabled by these tools. On this vendor firmware the
standard dump previously had no useful HCI history; lower-level vendor/HCI
logging may still be necessary if system logs do not expose the initial error.
Android's HCI logging instructions require a Bluetooth restart, which is not
performed here: [AOSP Bluetooth debugging](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging).
The app log-access behavior is described by [Android 14 LogcatManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/logcat/LogcatManagerService.java).

## Vendor HCI capture enabled separately on 2026-09-24

With explicit permission, the standard Developer Options Bluetooth log switch
was enabled and Bluetooth was restarted once at 21:19 MSK. Runtime reported
`sSnoopLogSettingAtEnable = FULL`, but the resulting bugreport had no snoop file.
Do not treat the switch alone as evidence of packet capture on this firmware.

The installed native Bluetooth library gates its vendor snoop file with
`persist.sys.btlog.enable`. ADB shell cannot set this property (SELinux denial).
The installed, privileged HeyLogKit implements the supported logging path:

1. Open `com.heytap.wearable.logkit/.main.OppoLogkitMainActivity`.
2. Select Bluetooth, then Create logs (Russian: "Создать журналы").
3. Verify `persist.sys.btlog.enable=true`, `persist.sys.btlog.channel=erpc`
   and, separately, actual packet data in the next bugreport/export.

The UI was used to start capture at 21:29:26 MSK. Its Bluetooth profile also
collects normal system/CPU/MCU diagnostic logs; it is not the small shell
recorder above. It may increase battery and storage usage. It should not be
left enabled permanently after evidence is captured. Stop the capture through
HeyLogKit and verify `persist.sys.btlog.enable=false` and the channel stopped;
do not select upload/send without separate user authorization. Turn off the
standard Developer Options Bluetooth log switch when the investigation ends.
The diagnostic capture did not change pairing or restart the collector, and
the temporary screen stay-on setting was restored to zero.

HeyLogKit uses `dumpsys bluetooth_manager --bt-snoop 1` after setting the vendor
property, and `--bt-snoop 0` when stopping. The numeric argument is significant.
Calling enable from shell without the vendor property was not sufficient to
verify capture. No root, security-policy change or firmware modification was
used.

Verification succeeded: `bugreport-vendor-snoop.zip` contains
`FS/data/misc/bluetooth/logs/btsnoop_hci.cfa` (792338 bytes). The complete
btsnoop v1 / link type 1002 stream parses into 2325 records: 57 HCI commands,
1340 HCI events and 928 ACL packets, including a successful LE connection
and remote termination reason 0x13. Capture continued with the screen asleep.
No failed sensor attempt has yet been captured in this file. Standard timestamp
conversion gives a three-hour offset versus the app journal; align clocks before
correlating future incidents. The vendor recording remains enabled temporarily
to capture a failure, not as a permanent battery-independent setting.
