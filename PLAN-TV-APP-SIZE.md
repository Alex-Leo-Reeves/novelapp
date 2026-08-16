# Plan: Reduce TV App APK Size

## Steps
- [ ] Inspect tvApp/build.gradle.kts (deps, ABIs, minify, jniLibs, assets)
- [ ] Measure current APK sizes (universal + per-ABI if present)
- [ ] Inspect APK contents by size (find the big contributors: .so libs, assets, dex)
- [ ] Identify root causes (likely libnode.so per ABI, duplicated assets, no R8)
- [ ] Implement fixes:
  - Enable minify (R8) + resource shrink with safe keep rules
  - ABI splits so each APK carries one ABI
  - Remove duplicated/embedded nodebridge assets if they're not needed at runtime
  - Trim oversized bundled assets (models, fonts) if any
- [ ] Rebuild assembleRelease
- [ ] Verify new APK size(s) and report before/after
