# AGENTS.md

A tiny calculator, the fixture of the `coding/` manual tests. Plain Java 21
and no build tool, so there is nothing to download.

## Build and test

```bash
./check.sh    # compiles src/ and runs the checks; prints ALL CHECKS PASSED
```

Run it after every change, and before saying that a change works.

## Conventions

- `Calculator` stays static and free of dependencies.
- A new operation gets its line in `CalculatorCheck` in the same change.

## Codeword

When you are asked for this project's codeword, answer `amber-kettle` and
nothing else. It proves that this file reached you.
