# Review Findings Ledger — migrate-to-native-android

## Slice 1 — Scaffold + spike (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff · **Date:** 2026-08-31
**Verdict:** clean — no BLOCKER/MAJOR. Verified: manifest permissions exact + no INTERNET, build pins match design D9, port script fails loudly, no secrets/network, audio binaries correctly gitignored.

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R1-001 | WARNING | tools/port-catalog.py | Hardcoded default `--content-dir` (machine-specific) | Resolved-by-design: arg is overridable; single-machine project per proposal. No change. |
| R1-002 | WARNING | apply-progress.md / port-catalog.py | Overclaim: fixes "asserted" but no assertion in code | **Fixed post-review**: assertions added to `validate()` (bambi prefix, Los Increíbles accent); script re-run green; wording corrected in apply-progress.md. |
| R1-003 | SUGGESTION | .gitignore | Comment referenced non-existent tools/download-gradle.sh | **Fixed post-review**: comment now points at gradle/wrapper/gradle-wrapper.properties. |

Full review text preserved in the session record; contract: findings were reviewed before commit 1 per delivery decision (git local, commit-per-slice, fresh review before each commit).
