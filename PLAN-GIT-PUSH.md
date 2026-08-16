# Plan: Push Full Codebase to Git (no rebase)

## Rules
- NEVER rebase / revert / reverse.
- Remove `.git` from nested cloned repos so the parent repo doesn't treat them as embedded repos/submodules.

## Steps
- [ ] Inspect git state (branch, status, log, nested .git dirs, .gitignore)
- [ ] Confirm build dirs are gitignored (no build artifacts committed)
- [ ] Remove nested .git directories from cloned repos (githubanime/Anivexa-API, Anivault-Scraper, cinepro, nodebridge if present)
- [ ] git add -A
- [ ] Verify staged file list is sane (no accidental secrets/binaries)
- [ ] git commit (single commit of full state)
- [ ] git push origin main (plain push — no rebase)
- [ ] Verify remote is up to date (git status shows clean)
- [ ] Save progress to memory MCP
