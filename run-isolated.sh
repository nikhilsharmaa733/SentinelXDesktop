#!/usr/bin/env bash
#
# Launch SentinelX from a snapshot copy, isolated from the build directory.
#
# WHY THIS EXISTS
# `./gradlew run` puts build/classes/ on the classpath, and the JVM loads classes
# lazily from that directory as you navigate the app. Rebuilding while the app is
# open therefore breaks it: a screen you have not opened yet may vanish mid-session.
# That is exactly how a click on "Version History" produced
#
#     ClassNotFoundException: ...ui.components.HistoryDialogKt
#
# for a class that compiled perfectly well.
#
# Building the uber jar and copying it somewhere stable means the running app holds
# no reference to build/ at all, so it survives any number of rebuilds.
set -euo pipefail

cd "$(dirname "$0")"

SNAPSHOT_DIR="${TMPDIR:-/tmp}/sentinelx-run"
mkdir -p "$SNAPSHOT_DIR"

echo "Building uber jar…"
./gradlew packageUberJarForCurrentOS -q

JAR=$(find build/compose/jars -name "SentinelX-*.jar" -print -quit)
if [[ -z "$JAR" ]]; then
  echo "No jar produced — check the build output." >&2
  exit 1
fi

SNAPSHOT="$SNAPSHOT_DIR/$(basename "$JAR")"
cp -f "$JAR" "$SNAPSHOT"

echo "Launching from $SNAPSHOT"
echo "(safe to rebuild the project while this runs)"
exec java -jar "$SNAPSHOT"
