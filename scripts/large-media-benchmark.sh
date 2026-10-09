#!/usr/bin/env bash
set -euo pipefail
studio_benchmark_classes=$(mktemp -d /tmp/videostudio-benchmark-classes.XXXXXX)
trap 'rm -rf "$studio_benchmark_classes"' EXIT
studio_benchmark_root=${1:?Supply a fresh evidence directory for the retained synthetic original and Vault objects}
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$studio_benchmark_classes" \
  android/app/src/main/java/com/rezoxnemesis/videostudio/VaultChunkStore.java \
  tests/benchmarks/VaultLargeProjectBenchmark.java
java -Xmx64m -cp "$studio_benchmark_classes" com.rezoxnemesis.videostudio.VaultLargeProjectBenchmark "$studio_benchmark_root"
