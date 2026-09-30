import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("wear-adb.sh")
WATCH = "TESTWATCHSERIAL0001"
ENDPOINT = "192.0.2.10:40123"
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
with open(os.environ["ADB_TEST_LOG"], "a") as log:
    log.write(json.dumps(a) + "\n")
serial = os.environ["ADB_TEST_SERIAL"]
if a == ["devices"] or a == ["devices", "-l"]:
    print("List of devices attached\n" + serial + "\tdevice product:OPWWE251")
elif a == ["mdns", "services"]:
    print("adb-TESTWATCHSERIAL0001-pair _adb-tls-pairing._tcp 192.0.2.10:49999")
    print("adb-OTHER-watch _adb-tls-connect._tcp 192.0.2.11:41111")
    print("adb-TESTWATCHSERIAL0001-test (2) _adb-tls-connect._tcp " + serial)
elif a[:3] == ["-s", serial, "shell"]:
    if a[3] == "getprop ro.serialno; getprop ro.boot.serialno":
        print("TESTWATCHSERIAL0001")
elif a and a[0] in ("connect", "disconnect"):
    sys.exit("Unexpected transport mutation in fake test")
else:
    sys.exit("Unexpected adb command: " + repr(a))
'''


class WearAdbRepairTest(unittest.TestCase):
    def run_tool(self, command, serial):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o700)
            log = root / "commands.jsonl"
            cache = root / "serial"
            cache.write_text("192.0.2.99:33333\n")
            env = dict(os.environ, ADB=str(adb), ADB_TEST_LOG=str(log),
                       ADB_TEST_SERIAL=serial, SERIAL_FILE=str(cache),
                       LOCK_DIR=str(root / "lock"))
            result = subprocess.run(["bash", str(SCRIPT), command], env=env,
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout, [json.loads(line) for line in log.read_text().splitlines()]

    def test_repair_retains_tls_and_allows_screen_to_sleep(self):
        _, calls = self.run_tool("repair", WATCH)
        commands = [call[3:] for call in calls if call[:3] == ["-s", WATCH, "shell"]]
        self.assertIn(["settings", "put", "global", "wifi_always_requested", "1"], commands)
        self.assertIn(["device_config", "put", "wifi", "high_perf_lock_deprecated", "false"], commands)
        self.assertIn(["settings", "put", "global", "stay_on_while_plugged_in", "0"], commands)
        self.assertIn(["svc", "power", "stayon", "false"], commands)
        self.assertFalse(any("tcpip" in call or "root" in call for call in calls))
        self.assertNotIn(["svc", "power", "stayon", "true"], commands)

    def test_current_watch_tls_endpoint_precedes_stale_cache_and_pairing_port(self):
        output, calls = self.run_tool("connect", ENDPOINT)
        self.assertEqual(ENDPOINT, output.strip())
        self.assertIn(["-s", ENDPOINT, "shell", "getprop ro.serialno; getprop ro.boot.serialno"], calls)
        self.assertFalse(any("192.0.2.99:33333" in call for call in calls))
        self.assertFalse(any("192.0.2.10:49999" in call for call in calls))
        self.assertFalse(any("192.0.2.11:41111" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
