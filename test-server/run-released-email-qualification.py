"""Real-Core callback qualification; published SDKs unless --local-rownd-sdk is explicit."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("artifacts", type=Path)
parser.add_argument("--minimum-age", type=int, default=605)
parser.add_argument("--device", required=True)
parser.add_argument("--api-port", type=int, default=3138)
parser.add_argument("--hub-port", type=int, default=8788)
parser.add_argument("--warm", action="store_true")
parser.add_argument("--local-rownd-sdk", action="store_true", help="Test local Rownd source; upstream remains published 0.5.4")
parser.add_argument("--secure-aged", action="store_true", help="Requires newly frozen Hub/plugin, numeric 600 policy and actual aged S0")
parser.add_argument("--refresh-profile", action="store_true")
parser.add_argument("--refresh-profile-after-restart", action="store_true")
parser.add_argument("--reuse-draft", action="store_true")
args = parser.parse_args()
if args.secure_aged and (not args.local_rownd_sdk or args.minimum_age <= 600 or args.reuse_draft):
    parser.error("--secure-aged requires --local-rownd-sdk, --minimum-age above 600 and a fresh draft")
if args.refresh_profile and not args.warm:
    parser.error("--refresh-profile requires --warm; cold qualification stays automatic")
if args.reuse_draft and args.warm:
    parser.error("--reuse-draft is only supported for cold callbacks")
if args.refresh_profile_after_restart and args.warm:
    parser.error("--refresh-profile-after-restart requires a cold run")
args.artifacts.mkdir(parents=True, exist_ok=True)
package = "io.rownd.rowndtestsandbox.app.local"
test_class = "io.rownd.rowndtestsandbox.RealHubE2ETest"
test_method = "secureAgedEmailChangeUsesExistingAccountSignInThenVerifies" if args.secure_aged else "draftEmailVerificationReplacesSessionThroughReleasedSdk"
api_url = f"http://127.0.0.1:{args.api_port}"
env = {**os.environ, "ANDROID_SERIAL": args.device, "ANDROID_API_URL": api_url,
       "ANDROID_HUB_URL": f"http://127.0.0.1:{args.hub_port}", "ANDROID_APP_KEY": "test_app_key"}
adb_command = ["adb", "-s", args.device]

def adb(*command):
    return subprocess.check_output([*adb_command, *command], text=True)

def ui():
    adb("shell", "rm", "-f", "/sdcard/draft-email-ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/draft-email-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/draft-email-ui.xml"))

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def save_diagnostics():
    lines = adb("logcat", "-d", "-v", "brief", "-s", "System.out", "Rownd.hub").splitlines()
    initiations = [line for line in lines if "DRAFT_EMAIL_INITIATION" in line]
    if not initiations:
        return
    pid = re.search(r"\(\s*(\d+)\)", initiations[-1]).group(1)
    process_lines = [line for line in lines if re.search(r"\(\s*" + pid + r"\)", line)]
    markers = [line.split(": ", 1)[-1] for line in process_lines if "DRAFT_EMAIL_" in line]
    message_counts = {}
    for line in process_lines:
        if "postMessage: " not in line:
            continue
        try:
            message_type = json.loads(line.split("postMessage: ", 1)[1])["type"]
        except (ValueError, KeyError):
            continue
        message_counts[message_type] = message_counts.get(message_type, 0) + 1
    (args.artifacts / "native-diagnostics.json").write_text(json.dumps({
        "markers": markers, "bridgeMessageCounts": message_counts,
    }, indent=2))

def assert_profile_persisted_before_restart():
    saved = ET.fromstring(adb("shell", "run-as", package, "cat", "shared_prefs/draft-email-qualification.xml"))
    destination = next(node.text for node in saved if node.attrib["name"] == "destination")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        state = json.loads(adb("shell", "run-as", package, "cat", "files/datastore/rownd_state.json"))
        if state.get("user", {}).get("data", {}).get("email") == destination:
            (args.artifacts / "profile-persistence.json").write_text(json.dumps({
                "profileMatchesDestinationBeforeRestart": True, "localRowndSdk": args.local_rownd_sdk,
            }, indent=2))
            return
        time.sleep(0.2)
    raise RuntimeError("Verified profile was not persisted before restart")

def verify_process_restart():
    adb("shell", "am", "force-stop", package)
    result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
        f"{test_class}#coldVerifiedEmailPersistsAndRefreshesAfterProcessRestart", "-e", "preserveDraft", "true",
        "-e", "refreshProfileAfterRestart", str(args.refresh_profile_after_restart).lower(),
        "-e", "harnessUrl", api_url, f"{package}.test/androidx.test.runner.AndroidJUnitRunner")
    (args.artifacts / "restart-refresh.log").write_text(result)
    if "OK (1 test)" not in result:
        raise RuntimeError("Persisted S1/refresh assertions failed; see restart-refresh.log")

for port in (args.api_port, args.hub_port):
    adb("reverse", f"tcp:{port}", f"tcp:{port}")
with (args.artifacts / "prepare-aged.log").open("a") as log:
    subprocess.run(["./gradlew", ":app:assembleLocalDebug", ":app:assembleLocalDebugAndroidTest", f"-ProwndUseLocalSdk={str(args.local_rownd_sdk).lower()}"],
        env=env, stdout=log, stderr=log, check=True)
    # Connected-test tasks uninstall the app, destroying S0 before the cold callback.
    for apk in ("app/build/outputs/apk/local/debug/app-local-debug.apk",
                "app/build/outputs/apk/androidTest/local/debug/app-local-debug-androidTest.apk"):
        subprocess.run([*adb_command, "install", "-r", "-t", apk], stdout=log, stderr=log, check=True)
    result = "OK (1 test)" if args.reuse_draft else adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
        f"{test_class}#{test_method}",
        "-e", "harnessUrl", api_url, "-e", "prepareDraftOnly", str(not args.warm).lower(),
        "-e", "refreshProfileAfterVerification", str(args.refresh_profile).lower(),
        "-e", "minimumSessionAgeSeconds", str(args.minimum_age),
        f"{package}.test/androidx.test.runner.AndroidJUnitRunner")
    log.write(result)
    log.flush()
    save_diagnostics()
    if "OK (1 test)" not in result:
        raise RuntimeError("Draft preparation/warm assertions failed; see prepare-aged.log")

if args.warm:
    if args.local_rownd_sdk:
        assert_profile_persisted_before_restart()
        verify_process_restart()
    print("PASS: warm callback" + (" with explicit public profile refresh" if args.refresh_profile else ""))
    raise SystemExit(0)

saved = ET.fromstring(adb("shell", "run-as", package, "cat", "shared_prefs/draft-email-qualification.xml"))
values = {node.attrib["name"]: node.text for node in saved}
if args.reuse_draft:
    with urllib.request.urlopen(api_url + "/test/email-change-session?handle=" + urllib.parse.quote(values["originalHandle"]), timeout=5) as response:
        original = json.load(response)
    if original["userId"] != values["owner"] or not any(
        pending["value"] == values["destination"] and pending["status"] == "PENDING"
        for pending in original["pending"]
    ):
        raise RuntimeError("Saved draft no longer belongs to live initiating S0")
    if args.minimum_age:
        raise RuntimeError("Reused drafts cannot establish initiation age; use --minimum-age 0")
callback = urllib.parse.urlsplit(values["callback"])
launcher = api_url + "/test/email-verification-launcher?" + callback.query
(args.artifacts / "identity.json").write_text(json.dumps({key: values[key] for key in ("owner", "originalHandle", "destination")}, indent=2))
recording = subprocess.Popen([*adb_command, "shell", "screenrecord", "--time-limit", "180", "/sdcard/draft-email-cold.mp4"])
try:
    adb("shell", "am", "force-stop", package)
    # Quote the URL for Android's shell without printing the challenge token.
    import shlex
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", shlex.quote(launcher), "-p", "com.android.chrome")
    deadline = time.monotonic() + 75
    dispatched = False
    last_link_tap = 0.0
    verified = False
    while time.monotonic() < deadline:
        try:
            tree = ui()
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
            continue
        for node in tree.iter("node"):
            label = node.attrib.get("text") or node.attrib.get("content-desc")
            if label == "Email verified":
                verified = True
                break
            if label in ("Accept & continue", "Use without an account", "No thanks", "Open", "Just once"):
                tap(node)
                break
            if (label == "Open email verification" and node.attrib.get("clickable") == "true"
                    and node.attrib.get("package") == "com.android.chrome"
                    and time.monotonic() - last_link_tap > 5):
                tap(node)
                last_link_tap = time.monotonic()
                dispatched = True
                break
        if verified:
            break
        time.sleep(1)
    if not dispatched or not verified:
        (args.artifacts / "cold-failure-ui.xml").write_bytes(ET.tostring(ui()))
        raise RuntimeError("Chrome → cold app did not show Email verified")
    with (args.artifacts / "cold-verified.png").open("wb") as screenshot:
        subprocess.run([*adb_command, "exec-out", "screencap", "-p"], stdout=screenshot, check=True)
finally:
    # Stop the device encoder cleanly, then pull the finalized recording.
    adb("shell", "pkill", "-2", "screenrecord")
    recording.wait(timeout=20)
    subprocess.run([*adb_command, "pull", "/sdcard/draft-email-cold.mp4", str(args.artifacts / "cold-callback.mp4")], check=True)

if args.local_rownd_sdk:
    assert_profile_persisted_before_restart()
verify_process_restart()
print(f"PASS: draft (minimum initiation age {args.minimum_age}s), Chrome cold callback, process restart and real S1 refresh"
      + ("; explicit public profile refresh required" if args.refresh_profile_after_restart else ""))
