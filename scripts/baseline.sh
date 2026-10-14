#!/usr/bin/env bash
# Measures the code and writes doc/agent/baseline.md: the state the ratchet
# shrinks from. Reads what the last build left behind (target/pmd.xml,
# archunit_store/, jacoco.xml); run after `mvn verify` or ./scripts/verify.sh.
#
#   ./scripts/baseline.sh                 measure, write doc/agent/baseline.md
#   ./scripts/baseline.sh --refresh-pmd   the ratchet step for PMD: run the sensors over
#                                         the whole reactor without failing, rewrite
#                                         mc-java-parent/pmd-baseline.properties from the
#                                         reports, then measure. The file may only shrink;
#                                         review its diff before committing.
#   (ArchUnit's store is refreshed per area with
#    mvn -f <area>/*-arch-tests/pom.xml test -Darchunit.freeze.store.default.allowStoreUpdate=true)
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
out=doc/agent/baseline.md

if [ "${1:-}" = "--refresh-pmd" ]; then
    # Go through the lifecycle so the reports are the ones the build judges by, with the
    # old baseline switched off: pmd:check writes its report after removing excluded
    # violations, so a run with the baseline in force would only collect the remainder.
    # Never run two Maven builds in this checkout at once; they overwrite each other's reports.
    mvn -q -ntp verify -DskipTests -Djacoco.skip -Dpmd.failOnViolation=false -Dpmd.excludeFromFailureFile=
    python3 - <<'PY2'
import glob, os, collections, xml.etree.ElementTree as ET
ns = "{http://pmd.sourceforge.net/report/2.0.0}"
per_class = collections.defaultdict(set); total = 0
for rep in glob.glob("**/target/pmd.xml", recursive=True):
    try: root = ET.parse(rep).getroot()
    except ET.ParseError: continue
    for f in root.iter(ns + "file"):
        file_class = os.path.basename(f.get("name", "")).removesuffix(".java")
        for v in f.iter(ns + "violation"):
            pkg, cls = v.get("package"), v.get("class")
            # a violation outside any class (an import, a file-level metric) is
            # keyed by the file name, as the plugin's exclusion does
            cls = cls or file_class
            if pkg and cls:
                per_class[f"{pkg}.{cls}"].add(v.get("rule")); total += 1
lines = ["# PMD baseline: the violations that existed when the sensors were switched on",
         "# (doc/agent/quality-catalog.md). One line per class, the rules it may still violate.",
         "# Lines are removed as classes are tidied; never added outside a baseline refresh",
         "# (scripts/baseline.sh --refresh-pmd). Format: <fully.qualified.Class>=<Rule>,<Rule>",
         ""]
lines += [f"{c}={','.join(sorted(r))}" for c, r in sorted(per_class.items())]
open("mc-java-parent/pmd-baseline.properties", "w").write("\n".join(lines) + "\n")
print(f"pmd-baseline.properties: {total} violations in {len(per_class)} classes")
PY2
fi
python3 - "$out" <<'PY'
import sys, re, os, glob, collections, datetime, subprocess
out = sys.argv[1]
today = datetime.date.today().isoformat()
sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()

def main_java():
    for p in glob.glob("**/src/main/java/**/*.java", recursive=True):
        if "/target/" in p: continue
        yield p

def module_of(path):
    return path.split("/src/main/java/")[0]

# 1) size: classes over 300 lines
big = []
javadoc = []
for p in main_java():
    with open(p, encoding="utf-8", errors="replace") as f:
        lines = f.readlines()
    n = len(lines)
    comments = sum(1 for l in lines if re.match(r"^\s*(\*|/\*\*|//)", l))
    if n > 300:
        big.append((n, p))
    if n > 60:
        javadoc.append((comments * 100 // n, comments, n, p))
big.sort(reverse=True)
javadoc.sort(reverse=True)

# 2) PMD: the frozen baseline (class/rule pairs from pmd-baseline.properties) and,
#    from the last build's reports, what is NOT in the baseline (should be zero;
#    pmd:check writes its report after removing excluded violations)
pmd = collections.Counter(); pmd_mod = collections.Counter(); baseline_pairs = 0
for line in open("mc-java-parent/pmd-baseline.properties"):
    if "=" in line and not line.startswith("#"):
        cls, rules = line.rstrip("\n").split("=", 1)
        pkg = cls.rsplit(".", 1)[0]
        for r in rules.split(","):
            pmd[r] += 1; pmd_mod[pkg] += 1; baseline_pairs += 1
new_violations = 0
ns = "{http://pmd.sourceforge.net/report/2.0.0}"
import xml.etree.ElementTree as ET
for rep in glob.glob("**/target/pmd.xml", recursive=True):
    try:
        new_violations += sum(1 for _ in ET.parse(rep).getroot().iter(ns + "violation"))
    except ET.ParseError:
        pass

# 3) ArchUnit stores
arch = []
for store in glob.glob("*/*arch-tests/archunit_store"):
    rules = {}
    sr = os.path.join(store, "stored.rules")
    if os.path.exists(sr):
        for line in open(sr):
            if "=" in line:
                desc, fid = line.rstrip("\n").split("=", 1)
                desc = desc.replace("\\:", ":").replace("\\ ", " ")
                f = os.path.join(store, fid)
                rules[desc] = sum(1 for _ in open(f)) if os.path.exists(f) else 0
    arch.append((store.split("/")[0], rules))

# 4) tests per area
tests = collections.Counter()
for p in glob.glob("**/src/test/java/**/*.java", recursive=True):
    if "/target/" in p: continue
    tests[p.split("/")[0]] += open(p, encoding="utf-8", errors="replace").read().count("@Test")

# 5) known gaps
gaps_open = 0
if os.path.exists("doc/known-gaps.md"):
    text = open("doc/known-gaps.md").read()
    openpart = text.split("## Closed")[0]
    gaps_open = openpart.count("- [ ]")

with open(out, "w") as w:
    w.write(f"# Baseline\n\nMeasured {today} at {sha} by scripts/baseline.sh. Rewritten by the ratchet; the numbers may only fall.\n\n")
    w.write("## Summary\n\n| Measure | Value |\n| --- | --- |\n")
    w.write(f"| Main classes over 300 lines | {len(big)} |\n")
    w.write(f"| PMD class/rule pairs in the baseline | {baseline_pairs} |\n")
    w.write(f"| PMD violations outside the baseline (last build) | {new_violations} |\n")
    w.write(f"| ArchUnit frozen violations | {sum(sum(r.values()) for _, r in arch)} |\n")
    w.write(f"| Files over 30 % comment lines (of files over 60 lines) | {sum(1 for q, *_ in javadoc if q > 30)} |\n")
    w.write(f"| @Test methods | {sum(tests.values())} |\n")
    w.write(f"| Known gaps open | {gaps_open} |\n\n")
    w.write("## ArchUnit, frozen per area\n\n| Area | Rule | Violations |\n| --- | --- | --- |\n")
    for area, rules in sorted(arch):
        for desc, n in sorted(rules.items()):
            w.write(f"| {area} | {desc} | {n} |\n")
    w.write("\n## PMD baseline, per rule (classes still allowed to violate it)\n\n| Rule | Classes |\n| --- | --- |\n")
    for r, n in pmd.most_common():
        w.write(f"| {r} | {n} |\n")
    w.write("\n## PMD baseline, packages with most entries\n\n| Package | Class/rule pairs |\n| --- | --- |\n")
    for m, n in pmd_mod.most_common(15):
        w.write(f"| {m} | {n} |\n")
    w.write("\n## Classes over 300 lines\n\n| Lines | File |\n| --- | --- |\n")
    for n, p in big:
        w.write(f"| {n} | {p} |\n")
    w.write("\n## Highest comment share (files over 60 lines)\n\n| Comment % | Comment / total lines | File |\n| --- | --- | --- |\n")
    for q, c, n, p in javadoc[:25]:
        w.write(f"| {q} | {c} / {n} | {p} |\n")
    w.write("\n## Tests per area\n\n| Area | @Test |\n| --- | --- |\n")
    for a, n in sorted(tests.items()):
        w.write(f"| {a} | {n} |\n")
print("wrote", out)
PY
