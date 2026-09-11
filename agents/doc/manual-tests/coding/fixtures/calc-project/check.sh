#!/usr/bin/env bash
# The project's one build-and-test command: compile src/, then run the checks.
set -euo pipefail
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" src/calc/*.java
java -cp "$out" calc.CalculatorCheck
