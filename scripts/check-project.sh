#!/usr/bin/env bash
# Sample 26: "Does my project work on AWS Emulator?" Look up the published fidelity tests and scores
# for just the AWS operations your project calls.
#
# AWS publishes two things for this (see the emulator guide, "Checking whether your project works"):
#   - the fidelity tests, in github.com/aws/aws-emulator-evaluation-suite. Its manifest.jsonl lists
#     every test with the operations it calls;
#   - the fidelity scores, with each release on github.com/aws/aws-emulator/releases, as the asset
#     scores.json.gz: which tests that release passes.
# This script joins the two for the operations in an operations file, so you see, per operation,
# how many fidelity tests cover it and how many of those your emulator version passes; and which
# operations no test covers (for those, compare the emulator with AWS yourself).
#
# Needs: curl, python3. Downloads about 3 MB; changes nothing.
# Run:   scripts/check-project.sh                                  (this project's operations, the
#                                                                    installed emulator's version)
#        scripts/check-project.sh -f my-ops.txt -r v1.0.531 -v     (your list, a given release,
#                                                                    and the failing tests by name)
set -euo pipefail

OPS_FILE="$(dirname "$0")/project-operations.txt"
RELEASE=""
VERBOSE=0
while getopts "f:r:vh" opt; do
  case "$opt" in
    f) OPS_FILE="$OPTARG" ;;
    r) RELEASE="$OPTARG" ;;
    v) VERBOSE=1 ;;
    *) sed -n '2,20p' "$0"; exit 2 ;;
  esac
done

# The release whose scores apply: the installed emulator's version, unless one is given.
if [[ -z "$RELEASE" ]]; then
  if ! command -v aws-emulator >/dev/null; then
    echo "aws-emulator is not installed: name a release with -r, for example -r v1.0.531" >&2
    exit 2
  fi
  RELEASE="v$(aws-emulator -v | awk '/^Version:/ {print $2}')"
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
echo "Fetching the fidelity tests (manifest.jsonl) and the scores of $RELEASE ..."
curl -fsSL -o "$WORK/manifest.jsonl" \
  https://raw.githubusercontent.com/aws/aws-emulator-evaluation-suite/main/manifest.jsonl
if ! curl -fsSL -o "$WORK/scores.json.gz" \
  "https://github.com/aws/aws-emulator/releases/download/$RELEASE/scores.json.gz"; then
  echo "No scores.json.gz for release $RELEASE on github.com/aws/aws-emulator/releases" >&2
  exit 1
fi

# The join, in Python (standard library only; -I: ignore the environment and the current directory).
python3 -I - "$WORK/manifest.jsonl" "$WORK/scores.json.gz" "$OPS_FILE" "$RELEASE" "$VERBOSE" <<'PY'
import collections, gzip, json, sys

manifest_path, scores_path, ops_path, release, verbose = sys.argv[1:6]

ops = [line.strip() for line in open(ops_path) if line.strip() and not line.startswith("#")]

# check_id -> the operations the test calls
tests = {}
for line in open(manifest_path):
    row = json.loads(line)
    tests[row["check_id"]] = set(row["operations"])

# check_id -> pass/fail/skipped in this release (the "behavioral" units are the fidelity tests)
scores = json.load(gzip.open(scores_path))
outcome = {u["unitId"]: u["outcome"] for u in scores["units"] if u["kind"] == "behavioral"}

print(f"\nRelease {release} (emulator commit {scores['commit'][:12]}), {len(tests)} fidelity tests in the suite today.")
print("A test counts for an operation if it calls it; a test passes only if every call in it matched AWS.\n")
print(f"{'Operation':45} {'tests':>6} {'passed':>7} {'failed':>7} {'unscored':>9}")

uncovered, weakest = [], []
for op in ops:
    covering = [t for t, called in tests.items() if op in called]
    count = collections.Counter(outcome.get(t, "unscored") for t in covering)
    if not covering:
        uncovered.append(op)
    print(f"{op:45} {len(covering):6} {count['pass']:7} {count['fail']:7} {count['unscored'] + count['skipped']:9}")
    failing = sorted(t for t in covering if outcome.get(t) == "fail")
    if failing:
        weakest.append((op, failing))

print()
if uncovered:
    print("No fidelity test covers: " + ", ".join(uncovered))
    print("  -> the emulator may still be right, but nothing confirms it: compare it with AWS yourself.")
if weakest:
    print(f"{len(weakest)} operations have failing tests. Read the failing tests (behavioral/<service>/check_<name>.py)")
    print("to see whether your project depends on the behaviour they check" + (":" if verbose == "1" else " (-v lists them)."))
    if verbose == "1":
        for op, failing in weakest:
            more = f" ... and {len(failing) - 10} more" if len(failing) > 10 else ""
            print(f"  {op}: " + ", ".join(failing[:10]) + more)
print("'unscored': tests newer than this release's scores. Compare scores only for the same suite version.")
PY
