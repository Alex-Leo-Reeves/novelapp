# PLAN: Stray Artifact Cleanup (dsml etc)

## Goal
Scan the novelapp codebase for stray/injected artifacts (e.g. `dsml`, stray marker text, accidental debris left in source files) and remove them via a Python script.

## Steps
- [x] Save memory context about task
- [ ] Create plan file (this)
- [ ] Scan codebase for `dsml` and related stray patterns (full repo, git-tracked only)
- [ ] Inspect matches — determine which are real stray artifacts vs legitimate strings
- [ ] Write Python cleanup script (safe, line/block targeted, no destructive rewrites)
- [ ] Dry-run the script (report what would change)
- [ ] Apply fixes
- [ ] Verify no stray artifacts remain (re-scan)
- [ ] Verify build integrity of touched source files (syntax check / grep for leftover)
- [ ] Update memory with results
