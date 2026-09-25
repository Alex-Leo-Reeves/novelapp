# PLAN — Fix iOS IPA workflow failure (V152)

## Symptom
GitHub Actions job **"Build NovaRead IPA (shared Compose app)"** fails at the final step
**"Verify bundled Info.plist"**.

```
##[error]Missing MARKETING_VERSION in iosApp/project.yml
##[error]Process completed with exit code 1.
```

## Evidence (from ioslogs.zip)
- Run started `2026-09-25T10:02:04Z`; checked out `1f536122268852d952db64c171ec5fc6a7cc82a1`
  (origin/main HEAD, commit `1f53612` "Update version extraction in ios-ipa-build.yml").
- Step 14 (xcodebuild compile check) — **passed**, no `error:` lines in 127 KB of log.
- Step 15 (Package unsigned IPA) — **passed**, "Unsigned IPA created … size: 19 MB".
- Step 16 (Upload unsigned IPA) — **passed**.
- Step 20 (Verify bundled Info.plist) — **failed**. Nothing before it failed.

## Root cause
Commit `1f53612` replaced the real version-extraction Python with a **literal placeholder**:

```yaml
EXPECTED_VERSION="$(
  python3 -c '...'
)"
EXPECTED_BUILD="$(
  python3 -c '...'
)"
```

`python3 -c '...'` is valid Python (the `Ellipsis` literal). It exits **0** and prints **nothing**,
so both `EXPECTED_VERSION` and `EXPECTED_BUILD` are empty and the guard fires:

```
if [ -z "$EXPECTED_VERSION" ]; then
  echo "::error::Missing MARKETING_VERSION in iosApp/project.yml"
  exit 1
fi
```

The failure is **not** in the Compose/Kotlin/Xcode build and **not** in `project.yml` —
`iosApp/project.yml` does declare `MARKETING_VERSION: "1.44"` and `CURRENT_PROJECT_VERSION: 44`.

Secondary hazard: the previous revisions embedded multi-line Python heredocs directly inside the
YAML `run:` block. That indentation-sensitive form is what let a hand edit silently degrade into
`python3 -c '...'`. Removing it is part of the fix.

## Fix
1. Pull the latest `.github/workflows/ios-ipa-build.yml` from `origin/main` (local was behind by 3).
2. Add `scripts/read-ios-version.py` — stdlib-only, reads `settings.base` from `iosApp/project.yml`,
   prints `MARKETING_VERSION` then `CURRENT_PROJECT_VERSION` on two lines, exits non-zero with a clear
   message when absent. No heredoc-in-YAML, no fragile quoting.
3. Replace the placeholder block in the **Verify bundled Info.plist** step with a call to that script
   plus an explicit guard.
4. Add the new script to the workflow's `on.push.paths` trigger so changes to it rebuild.
5. Test the script locally against the real `iosApp/project.yml` (positive + negative cases).
6. Validate the workflow YAML parses, and statically confirm every `REQUIRED_KEYS` entry in
   `scripts/verify-ios-info-plist.py` is present in `iosApp/Info.plist`.
7. Commit and push to `main` so CI re-runs.

## Checklist
- [x] Extract and triage ioslogs.zip
- [x] Identify failing step and root cause
- [x] Statically verify Info.plist meets verify-script requirements
- [x] Confirm workflow is current — local `main` and `origin/main` were both `1f53612` after
      `git fetch`, so the "behind by 3" note was stale and no pull was required
- [x] Add scripts/read-ios-version.py
- [x] Patch workflow (placeholder -> script call, add script to `on.push.paths`)
- [x] Test + validate
- [x] Commit and push

## Verification log (all local, before push)

`scripts/read-ios-version.py`:

| input | stdout | exit |
|---|---|---|
| `iosApp/project.yml` (default path) | `1.44` / `44` | 0 |
| `iosApp/project.yml` (explicit path) | `1.44` / `44` | 0 |
| fixture: no `MARKETING_VERSION` | `::error::Missing MARKETING_VERSION in …` | 1 |
| fixture: flat `settings:` + inline comment + single quotes | `2.10` / `210` | 0 |
| fixture: version only in `targets.*` | `::error::Missing CURRENT_PROJECT_VERSION in …` | 1 |
| nonexistent file | `::error::cannot read …` | 1 |

Workflow:

- `yaml.safe_load` parses the file — 19 steps.
- `bash -n` on all 14 `run:` blocks — clean.
- The replaced shell pipeline, run verbatim in the repo, prints `version=1.44 build=44`
  (proves the guards no longer fire).
- `python3 -m py_compile` on both scripts — clean.
- File ends with a newline (last byte `0x0a`).

`scripts/verify-ios-info-plist.py`, exercised end to end:

- Accepts an "as built" plist with every `$(...)` expanded — `OK … version=1.44 build=44 keys=26`.
- Rejects the authored, unexpanded `iosApp/Info.plist` — 12 `unexpanded build setting` errors.
- Rejects an expanded plist whose `CFBundleShortVersionString` is stale (`1.0`).

All 26 `REQUIRED_KEYS` were confirmed present in `iosApp/Info.plist`, including the two
behavioural ones (`NSAppTransportSecurity.NSAllowsArbitraryLoads == true`,
`UIBackgroundModes` contains `audio`) and all 10 `$(API_KEY)` entries that the workflow
writes into `iosApp/Config.xcconfig`.

## Notes

- The diff is two hunks: one added line in `on.push.paths`, and the placeholder block swapped
  for the script call plus an explicit failure branch. A stray trailing blank line at EOF was
  dropped by the editor as a side effect.
- `ioslogs.zip` is left untracked — it is a transient CI log dump, not a build input.
- Pushed as `d135fa0` on `main` (`1f53612..d135fa0`), so `on.push.paths` matched
  `.github/workflows/ios-ipa-build.yml` and the workflow re-ran on this commit.
- The commit is authored `Sixth <sixth@local>` because no git identity is configured in this
  checkout. `git commit --amend --reset-author` re-attributes it if a different author is wanted.
