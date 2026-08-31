# New Discovery 1 & 2 Execution Checklist

- [x] **Task 1**: Fix Demon Slayer search scoring (penalize TV_SHORT to avoid *Onigiri* ID 21612, select *Kimetsu no Yaiba* ID 101922) and Dragon Ball Z AniKoto/AniDao stream referer headers + HLS proxy fallback
- [x] **Task 2**: Implement Anime Server 19 (AutoEmbed: `https://watch-v2.autoembed.app/`) on Mobile and TV
- [x] **Task 3**: Implement Movie Server 3 (AutoEmbed) and Server 4 (2Embed.online: `https://www.2embed.online/embed/movie/{id}` & `tv/{id}/{s}/{e}`) verified with *Noobees*
- [x] **Task 4**: Implement ARM / MAL-Sync ID mapping for Anime Servers 17 (VidLink), 18 (VidSrc.to), and 19 (AutoEmbed)
- [x] **Task 5**: Fix Dragon Ball Z Kai & multi-season anime season mapping (DBZ Kai S1-S3 vs S4-S5 *The Final Chapters*)
- [x] **Task 6**: Add Recommended section on Home tab (top section matching TMDB & AniList recommendations based on watch/search history)
- [x] **Task 7**: Fix Download Engine (headers/referer forwarding, storage writing, worker execution)
- [x] **Task 8**: Fix Subtitles loading/rendering and Anime Dub/Sub audio selection system
- [x] **Task 9**: Fix Search routing from Home tab (clicking anime opens `AnimeDetailScreen` with 19 anime servers instead of movie servers)
- [x] **Task 10**: Fix Movie duration & timeline resume bug (dynamic timeline in ExoPlayer, no false duration clamping, smooth seeking)
- [x] **Task 11**: Fix Search back navigation (returning from media details returns to the active Search tab with query intact)
- [x] **Task 12**: Fix Continue Watching / watch progress persistence and instant resume
- [x] **Task 13**: TV Player Controls: 5-second overlay auto-hide and D-Pad OK play/pause behavior
- [x] **Task 14**: Replace Nollywood tab with Live TV Channels tab (300+ worldwide channels, 20 per page pagination, 2Embed/HLS streaming)
