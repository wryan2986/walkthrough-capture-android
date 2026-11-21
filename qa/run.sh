#!/bin/sh
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sources="$project/app/src/main/java/local/walkthrough"
classes=$(mktemp -d "${TMPDIR:-/tmp}/walkthrough-qa.XXXXXXXX")
trap 'rm -rf "$classes"' EXIT
javac --release 17 -encoding UTF-8 -d "$classes" \
    "$sources/ConversationOverlap.java" "$sources/ManualGuideMatch.java" "$sources/TimeLabels.java" "$sources/MuseSteps.java" "$sources/ManualStepOrder.java" "$sources/MuseDays.java" "$sources/PrivacyTextRules.java" "$project"/qa/*.java
java -cp "$classes" local.walkthrough.RangeTimeTest
java -cp "$classes" local.walkthrough.StepOrderTest
java -cp "$classes" local.walkthrough.MuseDaysTest
java -cp "$classes" local.walkthrough.PrivacyTextTest
java -cp "$classes" local.walkthrough.ConversationOverlapTest
java -cp "$classes" local.walkthrough.ManualGuideMatchTest
java -cp "$classes" local.walkthrough.ManualStepOrderTest

# Compile the planner with a local node tree to cover actual tap-to-destination state changes.
guide="$project/qa/manual-guide"
javac --release 17 -encoding UTF-8 -d "$classes/guide" \
    "$guide/android/graphics/Rect.java" "$guide/android/view/accessibility/AccessibilityNodeInfo.java" \
    "$guide/ManualTrajectoryClipTest.java" "$sources/WalkTree.java" "$sources/MuseProfile.java" \
    "$sources/Selector.java" "$sources/ManualStepOrder.java" "$sources/ManualTrajectoryGuide.java" "$sources/PreviewRow.java"
java -cp "$classes/guide" local.walkthrough.ManualTrajectoryClipTest
