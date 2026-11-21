# Walkthrough Capture for iPhone

Choose the output and review finished captures in the iPhone app. A Mac running Xcode drives the target app through Apple's UI-testing framework. The conversation workflow saves numbered, screen-sized PNGs in one ZIP. The trajectory workflow opens the selected activities and each step in order and saves one silent MP4. Capture processing runs locally.

## Try the offline sample

Use a Mac with Xcode 16 or later, Python 3.9 or later and an installed iOS Simulator runtime. This project targets iOS 17 and later. Find a simulator UDID with `xcrun simctl list devices available`. From this directory, substitute that UDID in these commands.

```sh
DEVICE=your-simulator-udid
python3 Tools/capture.py --device "$DEVICE" --sample --mode conversation --out ./Captures
python3 Tools/capture.py --device "$DEVICE" --sample --mode discover --config Tools/sample.json --out ./Captures
python3 Tools/capture.py --device "$DEVICE" --sample --mode trajectory --config Tools/sample.json --out ./Captures
```

The separate sample app contains a thinking disclosure, a horizontal table and three fictional activities. The example plan selects the first and third activities from April 5, 2025, between 09:00 and 09:10 UTC. Discovery returns a list to the companion. To choose your own activities there, set the time range to include those timestamps in your local timezone, tap the activities you want and tap **Save configuration**. Omit `--config` when recording to use that selection.

Each run also saves its result in a new subfolder of `--out`. A finished ZIP or MP4 opens in the companion's **Capture saved** view. Use **Library** to reopen it or **Save to Files** to export it. ZIPs contain the coverage report and original PNGs. The Mac also saves the report beside the artifact. Partial captures are labeled in the filename and return exit code 2. Use `--keep-result` if you need the local Xcode result bundle and diagnostic log.

## Use a connected iPhone

Install the authorized target app on the iPhone. Pair it with Xcode, enable Developer Mode and sign the companion, sample and test runner with your own Apple Development team. Keep the phone unlocked and the intended conversation open in the target before starting capture. The standalone companion provides configuration and a library. Cross-app taps and scrolling require the Mac runner for the duration of each capture.

```sh
python3 Tools/capture.py --device "$DEVICE" --team "$TEAM" --target-bundle "$TARGET" --profile profile.json --mode discover --out ./Captures
python3 Tools/capture.py --device "$DEVICE" --team "$TEAM" --target-bundle "$TARGET" --profile profile.json --out ./Captures
```

The built-in iOS navigation profile is verified against the bundled sample. Production Muse on iOS still needs a device-tested profile. The Android profile uses different controls. Use Xcode Accessibility Inspector to identify scroll areas, disclosures, row identifiers, navigation buttons and reliable list boundaries. `--profile` takes a JSON object whose keys match `Profile` in [the runner](UITests/AutomaticCaptureTests.swift). Its values are accessibility identifiers or prefixes. The current driver expects stable activity IDs, ISO-8601 row timestamps and an observable expanded state. A target exposing different semantics requires a driver adapter. Missing boundaries or unverified overlap produce a partial result.

The iOS implementation supports time-range selection, step hold duration, screenshot overlap, local previews and exports. Android-specific visual overlays and privacy masking are currently available in the Android build only. The iOS exporter discards audio and delivers a single video track. Use a local output folder appropriate for unmasked captures.

Apple documents cross-app UI testing and physical-device signing in [XCUIApplication](https://developer.apple.com/documentation/xcuiautomation/xcuiapplication) and [Running your app on simulated or physical devices](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices).
