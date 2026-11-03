#!/usr/bin/env bash
# Runs the tests of the library's core (names, catalog, scanner, local server). Needs only a JDK.
set -euo pipefail
export LC_ALL="${LC_ALL:-C.UTF-8}"   # file names with Cyrillic letters
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -d "$OUT" $(find src/io/freemytv/library/core test -name '*.java')
for t in $(cd test && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g'); do
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" "$t"
done
