# PLAN — Restore Server Selectors + Add VidSrc.sbs (Android + TV)

## Goal
Bring back the manual server selectors (currently hidden behind `showServerSelector = false`)
for **movies**, **anime**, and **donghua** in BOTH apps (`composeApp` = Android, `tvApp` = Android TV),
with **cool names**, and add the new **vidSrc.sbs** host to movies, anime and donghua.
Playback must work **without ads**.

## Confirmed server lists (user-confirmed)
- **Movies** (`StreamServer`): VidLink, VidSrc.to, AutoEmbed, 2Embed.online → + **VidSrc.sbs**
- **Anime** (`AnimeServer`): AniNeko, AnimeGG, Reanime, MKissa → + VidLink.pro, VidSrc.to, **VidSrc.sbs**
- **Donghua** (`DonghuaServer`): VidLink, VidSrc.to, AniNeko, AniKoto, AnimeXin → + **VidSrc.sbs**

## New host — vidsrc.sbs (from HAR + user)
- Movie: `https://vidsrc.sbs/embed/movie/{tmdb}`
- TV:    `https://vidsrc.sbs/embed/tv/{tmdb}/{s}/{e}`
- HAR: embed loads player app at `web.nxsha.app`; sources `web.nxsha.app/api/sources?q=…`;
  stream HLS `wts.itsnitrox.tech/**/master.m3u8`; ads/popups `luugy.com`, `swiwetduchan.shop`.
- Ad handling: block `luugy.com`, `swiwetduchan.shop`, `llvpn.com`, `imagesharerhost.com`;
  allow `web.nxsha.app`, `wts.itsnitrox.tech`, `mp4.*.workers.dev`.

## Cool names
Movies/embeds (shared celestial theme):
- VidLink → **Nebula** · VidSrc.to → **Quasar** · AutoEmbed → **Pulsar** · 2Embed.online → **Comet** · VidSrc.sbs → **Astra**
Anime providers (Japanese theme):
- AniNeko → **Kitsune** · AnimeGG → **Ronin** · Reanime → **Sakura** · MKissa → **Kaiju**
Donghua extras: AniKoto → **Shogun** · AnimeXin → **Loong**

## Steps
- [ ] `MaServerSource.kt`: add `VIDSRC_SBS` to StreamServer / DonghuaServer / AnimeServer; set cool `displayName`s; add `providerName` to StreamServer; add curated selector lists (`MOVIE_SELECTOR`, `ANIME_SELECTOR`, `DONGHUA_SELECTOR`); extend mapping fns (`toStreamServer`, `toAnimeServer`).
- [ ] Fix compile exhaustiveness in every `when(StreamServer|AnimeServer|DonghuaServer)` (composeApp + tvApp).
- [ ] composeApp `MediaDetailScreen.kt`: unhide selector, iterate curated lists, cool-name chips.
- [ ] tvApp `TvDetailScreen.kt`: unhide selector, iterate curated lists; `TvMediaRepository.kt` wiring.
- [ ] Ad blocking: add vidsrc.sbs ad domains to android WebView player + TV player.
- [ ] Build: `./gradlew :composeApp:compileDebugKotlin` and `:tvApp:assembleDebug`; fix all errors.
- [ ] Sanity-check embed URLs for movie + tv.

## Status
IN PROGRESS — starting with `MaServerSource.kt`.
