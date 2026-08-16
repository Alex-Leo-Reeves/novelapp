# PLAN — Surface a non-fatal error when nodebridge fails to start (V148)

## Goal
Today `NodeBridgeRuntime.start()` logs failures and silently falls back to the
backend (datacenter egress → provider CDNs block → streams=0 for many anime).
The user wants the app to actually TELL them when the embedded Node.js engine
didn't boot, instead of failing invisibly.

## Design
- New shared observables: `NodeBridgeStatus` (androidMain/nodebridge pkg, compiled
  into BOTH phone (composeApp) and TV (tvApp) via the existing shared srcDir).
  - `message: StateFlow<String?>` — `null` = still booting, `""` = bridge OK,
    non-blank = failure reason (user-facing).
- `NodeBridgeRuntime.start()` reports every failure path into `NodeBridgeStatus`
  and `reportStarted()` on success. `NodeNativeBridge.isLoaded` adds a clear
  message for ABIs without the shim (x86_64 emulator, armeabi-v7a TV).
- Phone UI: `App()` gains `nodeBridgeMessage: String? = null` (default null keeps
  desktop/iOS call sites compiling); `MainActivity` collects the flow and passes
  it. A dismissible, non-blocking AlertDialog shows the reason once past auth.
- TV UI: `TvApp()` collects `NodeBridgeStatus.message` directly and shows the
  same dismissible AlertDialog.
- Failure is informational — backend fallback remains, app never blocks.

## Files
- [x] (new) `PLAN-BRIDGE-ERROR-V148.md`
- [ ] (new) `composeApp/src/androidMain/.../nodebridge/NodeBridgeStatus.kt`
- [ ] `composeApp/src/androidMain/.../nodebridge/NodeBridgeRuntime.kt` — report status
- [ ] `composeApp/src/androidMain/.../nodebridge/NodeNativeBridge.kt` — expose isLoaded
- [ ] `composeApp/src/commonMain/.../App.kt` — nodeBridgeMessage param + dialog
- [ ] `composeApp/src/androidMain/.../MainActivity.kt` — collect + pass status
- [ ] `tvApp/src/main/.../tv/ui/TvApp.kt` — dialog + collect status
- [ ] Compile `:composeApp:compileDebugKotlinAndroid` + `:tvApp:compileDebugKotlin`
- [ ] Update server-memory with outcome
