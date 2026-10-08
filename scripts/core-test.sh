#!/usr/bin/env bash
set -euo pipefail
studio_test_classes=$(mktemp -d /tmp/videostudio-core-tests.XXXXXX)
trap 'rm -rf "$studio_test_classes"' EXIT
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$studio_test_classes" \
  android/app/src/main/java/com/rezoxnemesis/videostudio/TimelineMath.java \
  tests/core/TimelineCoreTest.java
java -cp "$studio_test_classes" com.rezoxnemesis.videostudio.TimelineCoreTest
