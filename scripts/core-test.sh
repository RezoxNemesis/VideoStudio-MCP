#!/usr/bin/env bash
set -euo pipefail
studio_test_classes=$(mktemp -d /tmp/videostudio-core-tests.XXXXXX)
trap 'rm -rf "$studio_test_classes"' EXIT
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$studio_test_classes" \
  android/app/src/main/java/com/rezoxnemesis/videostudio/TimelineMath.java \
  android/app/src/main/java/com/rezoxnemesis/videostudio/VaultChunkStore.java \
  tests/core/VaultCoreTest.java \
  android/app/src/main/java/com/rezoxnemesis/videostudio/AudioDsp.java \
  tests/core/AudioDspCoreTest.java \
  android/app/src/main/java/com/rezoxnemesis/videostudio/NarrationChunks.java \
  android/app/src/main/java/com/rezoxnemesis/videostudio/WavFile.java \
  tests/core/NarrationCoreTest.java \
  tests/core/TimelineCoreTest.java
java -cp "$studio_test_classes" com.rezoxnemesis.videostudio.TimelineCoreTest
java -Xmx64m -cp "$studio_test_classes" com.rezoxnemesis.videostudio.VaultCoreTest
java -Xmx64m -cp "$studio_test_classes" com.rezoxnemesis.videostudio.AudioDspCoreTest
java -Xmx64m -cp "$studio_test_classes" com.rezoxnemesis.videostudio.NarrationCoreTest
