# PLAN: TV App Anime Servers Broken + Update Not Prompting

**Date:** 2026-08-13
**User report:** TV app opened, no update prompt. All new anime servers (Anivexa 1-13 + AnimeXin) say "couldn't find content". AnimeXin also fails.

## Symptoms
- [ ] TV app did NOT prompt for update on launch
- [ ] All new anime servers fail to resolve content ("couldn't find content")
- [ ] AnimeXin specifically broken

## Investigation Checklist
- [ ] Check site/app-version.json vs tvApp/build.gradle.kts versionCode - is manifest ahead?
- [ ] Read TV update check code path (ApiClient / TvApp / update checker) - where does the prompt trigger?
- [ ] Read TvMediaRepository anime resolution - how AnimeServer is turned into a playable URL
- [ ] Read AnivexaApi.kt + server/anivexa-handlers.js - do the backend routes still work?
- [ ] Probe server endpoints live (curl Anivexa routes) to separate backend failure vs client failure
- [ ] Check TvBingeSession/TvDetailScreen for what URL actually gets handed to player
- [ ] Determine why AnimeXin fails (scraper selector drift?)

## Fix Checklist
- [ ] Fix update prompt (version gate / manifest mismatch)
- [ ] Fix anime server resolution (backend route or client wiring)
- [ ] Fix AnimeXin
- [ ] Compile verify tvApp
- [ ] Push / update manifest

## Status
- [ ] Investigation
- [ ] Fixes
- [ ] Verification
</write_to_file>
</write_to_file>
</write_to_file>
