#!/usr/bin/env python3
"""Run an authorized cross-app capture and return the artifact to its iPhone library."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from uuid import uuid4

HERE = Path(__file__).resolve().parent
APP = "local.walkthrough.capture"
SAMPLE = "local.walkthrough.sample"
DOCUMENTS = Path("Documents/WalkthroughCapture")


def run(*command, **options):
    return subprocess.run([str(arg) for arg in command], check=True, **options)


class Device:
    def __init__(self, udid):
        self.id = udid
        inventory = json.loads(run("xcrun", "simctl", "list", "devices", "-j", capture_output=True).stdout)
        self.sim = any(d["udid"] == udid for devices in inventory["devices"].values() for d in devices)
        self.kind = "iOS Simulator" if self.sim else "iOS"
        self.destination = f"platform={self.kind},id={udid}"

    def install(self, product):
        if self.sim:
            run("xcrun", "simctl", "install", self.id, product)
        else:
            run("xcrun", "devicectl", "device", "install", "app", "--device", self.id, product)

    def launch(self, bundle):
        if self.sim:
            run("xcrun", "simctl", "launch", self.id, bundle)
        else:
            run("xcrun", "devicectl", "device", "process", "launch", "--device", self.id, bundle)

    def documents(self):
        container = run("xcrun", "simctl", "get_app_container", self.id, APP, "data", capture_output=True, text=True)
        return Path(container.stdout.strip()) / DOCUMENTS

    def transfer(self, direction, source, dest):
        run("xcrun", "devicectl", "device", "copy", direction, "--device", self.id,
            "--domain-type", "appDataContainer", "--domain-identifier", APP,
            "--source", source, "--destination", dest, "-q")

    def read_config(self, temp):
        if self.sim:
            return json.loads((self.documents() / "config.json").read_bytes())
        local = temp / "companion-config.json"
        self.transfer("from", DOCUMENTS / "config.json", local)
        return json.loads(local.read_bytes())

    def send(self, local, relative):
        if self.sim:
            dest = self.documents() / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            pending = dest.with_name("." + uuid4().hex + ".pending")
            try:
                shutil.copyfile(local, pending)
                os.replace(pending, dest)
            finally:
                pending.unlink(missing_ok=True)
        else:
            self.transfer("to", local, DOCUMENTS / relative)

    def deliver(self, artifact, mode, temp):
        if mode == "discover":
            self.send(artifact, "trajectories.json")
        elif self.sim:
            self.send(artifact, Path("Inbox") / artifact.name)
        else:
            # The app only opens a transfer after a second, small completion receipt arrives.
            key = uuid4().hex
            self.send(artifact, f"Inbox/.{key}.part")
            receipt = temp / f"{key}.ready"
            receipt.write_text(json.dumps({"name": artifact.name, "bytes": artifact.stat().st_size}))
            self.send(receipt, Path("Inbox") / receipt.name)
        self.launch(APP)


def test_settings(plan):
    if "TestConfigurations" in plan:
        return [target for cfg in plan["TestConfigurations"] for target in cfg["TestTargets"]
                if target.get("BlueprintName") == "AutomaticCaptureTests"]
    return [target for key, target in plan.items() if key == "AutomaticCaptureTests"]


def drive(args, device, mode, config, product_dir, result):
    platform = "iphonesimulator" if device.sim else "iphoneos"
    templates = sorted(product_dir.glob(f"WalkthroughCapture_{platform}*.xctestrun"))
    if len(templates) != 1:
        raise ValueError("Expected one Xcode test plan for this SDK. Rebuild in an empty --derived-data directory.")
    with templates[0].open("rb") as f:
        plan = plistlib.load(f)
    targets = test_settings(plan)
    if len(targets) != 1:
        raise ValueError("Could not identify the capture UI test in the Xcode test plan.")
    target = targets[0]
    target.update({"ParallelizationEnabled": False,
                   "PreferredScreenCaptureFormat": "screenRecording" if mode == "trajectory" else "screenshots",
                   "SystemAttachmentLifetime": "keepAlways" if mode == "trajectory" else "deleteOnSuccess",
                   "UserAttachmentLifetime": "keepAlways"})
    target.setdefault("EnvironmentVariables", {}).update(
        WC_MODE=mode, WC_TARGET_BUNDLE=SAMPLE if args.sample else args.target_bundle,
        WC_CONFIG_JSON=json.dumps(config, separators=(",", ":")))
    # __TESTROOT__ in this file is resolved relative to its parent directory.
    fd, path = tempfile.mkstemp(prefix="capture-", suffix=".xctestrun", dir=product_dir)
    try:
        with os.fdopen(fd, "wb") as f:
            plistlib.dump(plan, f)
        cmd = ["xcodebuild", "test-without-building", "-xctestrun", path, "-destination", device.destination,
               "-parallel-testing-enabled", "NO", "-resultBundlePath", str(result)]
        with result.with_suffix(".log").open("w") as log:
            return subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT).returncode
    finally:
        Path(path).unlink(missing_ok=True)


def capture(args):
    from package_capture import package
    device = Device(args.device)
    derived = args.derived_data.resolve()
    products = derived / "Build/Products"
    variant = products / ("Debug-iphonesimulator" if device.sim else "Debug-iphoneos")
    if device.sim:
        run("xcrun", "simctl", "bootstatus", device.id, "-b")
    if not args.skip_build:
        cmd = ["xcodebuild", "-project", HERE.parent / "WalkthroughCapture.xcodeproj", "-scheme",
               "WalkthroughCapture", "-configuration", "Debug", "-destination", device.destination,
               "-derivedDataPath", derived, "-parallel-testing-enabled", "NO"]
        if args.team:
            cmd.extend(["DEVELOPMENT_TEAM=" + args.team, "-allowProvisioningUpdates"])
        run(*cmd, "build-for-testing")
    device.install(variant / "WalkthroughCapture.app")
    if args.sample:
        device.install(variant / "CaptureSample.app")
    device.launch(APP)
    args.out.mkdir(parents=True, exist_ok=True)
    directory = args.out.resolve() / (datetime.now().strftime("%Y-%m-%d_%H-%M-%S_") + uuid4().hex[:8])
    directory.mkdir()
    with tempfile.TemporaryDirectory(prefix="walkthrough-") as tmp:
        temp = Path(tmp)
        if args.config:
            config = json.loads(args.config.read_bytes())
        else:
            # The first launch creates the companion's Documents directory.
            for attempt in range(8):
                try:
                    config = device.read_config(temp)
                    break
                except (FileNotFoundError, subprocess.CalledProcessError):
                    if attempt == 7:
                        raise
                    time.sleep(0.5)
        if args.profile:
            config["selectors"] = json.loads(args.profile.read_bytes())
        mode = args.mode or config.get("mode", "screenshots")
        if mode == "screenshots":
            mode = "conversation"
        if mode not in ("conversation", "trajectory", "discover"):
            raise ValueError("Unknown capture mode in saved configuration.")
        if mode == "trajectory" and not config.get("selectedTrajectories"):
            raise ValueError("Run discovery, select trajectories on iPhone, and save configuration first.")
        if args.sample:
            # Reset only the bundled fictional sample. Preserve the real target's active conversation.
            if device.sim:
                subprocess.run(["xcrun", "simctl", "terminate", device.id, SAMPLE], capture_output=True)
            else:
                run("xcrun", "devicectl", "device", "process", "launch", "--terminate-existing",
                    "--device", device.id, SAMPLE)
        result = (directory if args.keep_result else temp) / "capture.xcresult"
        print(f"Running {mode} capture on {device.kind} …", flush=True)
        code = drive(args, device, mode, config, products, result)
        artifact, status = package(result, directory, mode)
        (directory / "result.json").write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
        if code != 0 and status.get("status") == "complete":
            raise ValueError("Xcode reported a test failure after the capture. Inspect the local result before exporting.")
        print(f"{status.get('status', 'unknown').upper()} {status.get('reason', '')}", flush=True)
        if artifact:
            print(f"Saved {artifact}", flush=True)
            device.deliver(artifact, mode, temp)
        else:
            device.launch(APP)
        return 0 if code == 0 and status.get("status") == "complete" else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Simulator UDID or paired iPhone identifier")
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--sample", action="store_true", help="Use the bundled offline sample")
    target.add_argument("--target-bundle", help="Bundle ID of the installed, authorized target")
    parser.add_argument("--mode", choices=["discover", "trajectory", "conversation"], help="Defaults to the companion settings")
    parser.add_argument("--config", type=Path, help="Override the configuration saved on the iPhone")
    parser.add_argument("--profile", type=Path, help="JSON mapping of iOS accessibility selectors")
    parser.add_argument("--out", type=Path, required=True, help="Local folder for the result and artifact")
    parser.add_argument("--derived-data", type=Path, default=Path(tempfile.gettempdir()) / ("walkthrough-build-" + str(os.getuid())))
    parser.add_argument("--team", help="Development Team ID for physical-device signing and provisioning")
    parser.add_argument("--skip-build", action="store_true", help="Reuse build products in --derived-data")
    parser.add_argument("--keep-result", action="store_true", help="Retain the local Xcode recording, attachments and log")
    try:
        args = parser.parse_args()
        device_key = hashlib.sha256(args.device.encode()).hexdigest()[:32]
        lock = Path(tempfile.gettempdir()) / f"walkthrough-{os.getuid()}-{device_key}.lock"
        with lock.open("a") as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("Another capture is already using this device.")
            return capture(args)
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Capture failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
