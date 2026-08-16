# PLAN — Donghua: AnimeXin Only + 13 Anivexa Servers Verified (v1.45)

Status: ⬜ PENDING → 🔄 IN PROGRESS → ✅ DONE

## Goal (user request 2026-08-14)
1. Remove all donghua servers EXCEPT AnimeXin.
2. Keep/verify the 13 Anivexa anime servers wired (they are — `AnimeServer` 14 entries, 13 Anivexa + VIDLINK last).
3. Start the nodebridge worker locally (it's the same pure-ESM worker that ships on-device) and test that **Dragon Ball Super returns a stream** — proving the residential-IP trick works end to end.

## Scope decision
- `DonghuaServer` enum → collapse to **ANIMEXIN only**. All references to removed
  entries (NONTONGO/AUTOEMBED/DONGHUA_STREAM/EMBEDSU/LUCIFER_DONGHUA/VIDSRC)
  must compile: UI selectors, resolve branches, downloads.
- Anime selectors stay as-is: 13 Anivexa providers (MKissa, Reanime, AniKoto,
  AnimeGG, AniNeko, AniDB App, 2DHive, AnimeNoSub, AniZone, AniBD, Senshi,
  KickAssAnime, AnimeDunya) + VIDLINK (last).

## Files to read first
- composeApp/.../data/MaServerSource.kt (DonghuaServer enum + helpers)
- composeApp/.../ui/MediaDetailScreen.kt (donghua chips + resolve/download)
- composeApp/.../ui/AnimeDetailScreen.kt (server chips)
- tvApp/.../data/TvMediaRepository.kt (read ✅ — donghua branches)
- tvApp/.../tv/ui/screens/TvDonghuaScreen.kt
- tvApp/.../tv/ui/screens/TvDetailScreen.kt
- tvApp/.../tv/data/TvBingeSession.kt

## Steps
1. Write PLAN + save memory. (this file)
2. Read all affected files.
3. Collapse DonghuaServer to ANIMEXIN in MaServerSource.kt.
4. Fix all compile references (mobile + TV) to removed entries.
5. Start nodebridge on this Linux box: `node nodebridge/main.js`
   → read bridge-port.json → curl Anivexa endpoints.
6. Test Dragon Ball Super: `/episodes/{provider}/{anilistId}` then
   `/watch/{provider}/{anilistId}/sub/{provider}-1` → expect streams.
7. Compile verify `:tvApp:compileDebugKotlin :composeApp:compileDebugKotlinAndroid`
   (user said GH Actions builds; but local compile gates are fine to run).
8. Update plan + memory.

## Verification definition
- Bridge boots on 127.0.0.1 and serves `/` route table (13 providers).
- Dragon Ball Super (AniList search) returns episodes for >= 1 provider.
- Watch route for that title returns >= 1 stream with a non-empty URL.
