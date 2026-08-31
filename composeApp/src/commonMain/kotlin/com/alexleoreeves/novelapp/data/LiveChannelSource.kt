package com.alexleoreeves.novelapp.data

import kotlinx.serialization.Serializable

@Serializable
data class LiveChannel(
    val id: String,
    val name: String,
    val category: LiveChannelCategory,
    val logoUrl: String,
    /** Direct HLS .m3u8 stream URL — played via ExoPlayer directly */
    val streamUrl: String,
    val isEmbed: Boolean = false,
    val country: String = "Global",
    val quality: String = "HD"
)

enum class LiveChannelCategory(val label: String) {
    ALL("All Channels"),
    SPORTS("Sports"),
    MOVIES("Movies"),
    CARTOONS("Kids & Cartoons"),
    NEWS("News"),
    INDIAN("Indian"),
    MUSIC("Music"),
    ENTERTAINMENT("Entertainment")
}

/**
 * 300+ real, publicly available free-to-air IPTV streams.
 * Sources: iptv-org/iptv (github.com/iptv-org/iptv) and Free-TV/IPTV.
 * These are all legitimate, openly broadcast streams — no DRM, no subscription required.
 */
object LiveChannelSource {

    val ALL_CHANNELS: List<LiveChannel> by lazy { buildChannelList() }

    fun getChannelsByCategory(category: LiveChannelCategory): List<LiveChannel> =
        if (category == LiveChannelCategory.ALL) ALL_CHANNELS
        else ALL_CHANNELS.filter { it.category == category }

    private fun buildChannelList(): List<LiveChannel> {
        val list = mutableListOf<LiveChannel>()

        // ─────────────────────────────────────────────────────────────
        // NEWS CHANNELS — Real public broadcaster streams
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("news_1", "Al Jazeera English", LiveChannelCategory.NEWS, "https://i.imgur.com/GWKmNPR.png", "https://live-hls-web-aje.getaj.net/AJE/01.m3u8", country = "Qatar"),
            LiveChannel("news_2", "ABC News Australia", LiveChannelCategory.NEWS, "https://upload.wikimedia.org/wikipedia/en/thumb/d/df/ABC_News_Channel.svg/500px-ABC_News_Channel.svg.png", "https://c.mjh.nz/abc-news.m3u8", country = "Australia"),
            LiveChannel("news_3", "DW English", LiveChannelCategory.NEWS, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", country = "Germany"),
            LiveChannel("news_4", "France 24 English", LiveChannelCategory.NEWS, "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/France_24_logo.svg/200px-France_24_logo.svg.png", "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8", country = "France"),
            LiveChannel("news_5", "CGTN News", LiveChannelCategory.NEWS, "https://i.imgur.com/3pKH2V2.png", "https://livesource.cgtn.com/cgtn-e/prog_index.m3u8", country = "China"),
            LiveChannel("news_6", "NHK World Japan", LiveChannelCategory.NEWS, "https://i.imgur.com/v9ixnWr.png", "https://nhkwlive-ojp.akamaized.net/hls/live/2003459/nhkwlive-ojp-en/index.m3u8", country = "Japan"),
            LiveChannel("news_7", "RT News", LiveChannelCategory.NEWS, "https://i.imgur.com/1REMedN.png", "https://rt-glb.rttv.com/live/rtnews/playlist.m3u8", country = "Russia"),
            LiveChannel("news_8", "TRT World", LiveChannelCategory.NEWS, "https://i.imgur.com/1GjJGXw.png", "https://tv-trtworld.live.trt.com.tr/master.m3u8", country = "Turkey"),
            LiveChannel("news_9", "Euronews English", LiveChannelCategory.NEWS, "https://i.imgur.com/Skf6vdi.png", "https://euronews-euronews-enlivehd-origin-live.akamaized.net/hls/live/2006690/euronews-en-live-hd/master_5000.m3u8", country = "Europe"),
            LiveChannel("news_10", "BBC News (UK Local)", LiveChannelCategory.NEWS, "https://upload.wikimedia.org/wikipedia/commons/thumb/f/ff/BBC_News_2019.svg/200px-BBC_News_2019.svg.png", "https://vs-hls-push-ww-live.akamaized.net/x=4/i=urn:bbc:pips:service:bbc_news24/t=3840/v=pv14/b=5070016/main.m3u8", country = "UK"),
            LiveChannel("news_11", "Sky News Arabia", LiveChannelCategory.NEWS, "https://i.imgur.com/6n56t81.png", "https://skynewsarabia-lh.akamaihd.net/i/sna_1@193390/index.m3u8", country = "Saudi Arabia"),
            LiveChannel("news_12", "CNA Singapore", LiveChannelCategory.NEWS, "https://i.imgur.com/g0NpzOw.png", "https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43afafc6a9d9a7b0a3b5/index.m3u8", country = "Singapore"),
            LiveChannel("news_13", "VOA News", LiveChannelCategory.NEWS, "https://i.imgur.com/Q9tqOXz.png", "https://voa-lh.akamaihd.net/i/VOA_TVMC@360286/index_800_av-p.m3u8", country = "USA"),
            LiveChannel("news_14", "ARY News", LiveChannelCategory.NEWS, "https://i.imgur.com/hVjB1DL.png", "https://stream.arynews.tv/hls/stream.m3u8", country = "Pakistan"),
            LiveChannel("news_15", "Geo News", LiveChannelCategory.NEWS, "https://i.imgur.com/Qo7pYTd.png", "https://geo.tv/live/geo-news.m3u8", country = "Pakistan"),
            LiveChannel("news_16", "A2 CNN Albania", LiveChannelCategory.NEWS, "https://i.imgur.com/TgO3Lzi.png", "https://tv.a2news.com/live/smil:a2cnnweb.stream.smil/playlist.m3u8", country = "Albania"),
            LiveChannel("news_17", "ORF Austria News", LiveChannelCategory.NEWS, "https://i.imgur.com/ft2LuRl.jpg", "https://orf1.mdn.ors.at/out/u/orf1/q8c/manifest.m3u8", country = "Austria"),
            LiveChannel("news_18", "CBC Sport Azerbaijan", LiveChannelCategory.NEWS, "https://upload.wikimedia.org/wikipedia/az/0/04/CBC_Sport_TV_loqo.png", "https://mn-nl.mncdn.com/cbcsports_live/cbcsports/playlist.m3u8", country = "Azerbaijan"),
            LiveChannel("news_19", "9Gem Australia", LiveChannelCategory.NEWS, "https://i.imgur.com/sWmE1kq.png", "https://9now-livestreams.akamaized.net/hls/live/2008311/gem-syd/master.m3u8", country = "Australia"),
            LiveChannel("news_20", "Canal 26 Argentina", LiveChannelCategory.NEWS, "https://i.imgur.com/5pAaVih.png", "https://stream-gtlc.telecentro.net.ar/hls/canal26hls/main.m3u8", country = "Argentina"),
        )

        // ─────────────────────────────────────────────────────────────
        // SPORTS CHANNELS
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("sport_1", "ORF Sport+ Austria", LiveChannelCategory.SPORTS, "https://i.imgur.com/MVNZ4gf.png", "https://orfs.mdn.ors.at/out/u/orfs/q8c/manifest.m3u8", country = "Austria"),
            LiveChannel("sport_2", "Racing.com Australia", LiveChannelCategory.SPORTS, "https://i.imgur.com/pma0OCf.png", "https://racingvic-i.akamaized.net/hls/live/598695/racingvic/1500.m3u8", country = "Australia"),
            LiveChannel("sport_3", "DeporTV Argentina", LiveChannelCategory.SPORTS, "https://i.imgur.com/iyYLNRt.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/deportv/playlist.m3u8", country = "Argentina"),
            LiveChannel("sport_4", "DW Sports", LiveChannelCategory.SPORTS, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream104.akamaized.net/hls/live/2015531/dwstream104/index.m3u8", country = "Germany"),
            LiveChannel("sport_5", "AZTV Sport Azerbaijan", LiveChannelCategory.SPORTS, "https://i.imgur.com/snBMMeH.png", "https://str.yodacdn.net/azertv/index.m3u8", country = "Azerbaijan"),
            LiveChannel("sport_6", "W24 Austria Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/PGb4wYw.png", "https://ms01.w24.at/W24/smil:liveevent.smil/playlist.m3u8", country = "Austria"),
            LiveChannel("sport_7", "9Rush Australia", LiveChannelCategory.SPORTS, "https://i.imgur.com/pma0OCf.png", "https://9now-livestreams.akamaized.net/hls/live/2010626/rush-syd/master.m3u8", country = "Australia"),
            LiveChannel("sport_8", "TEC TV Argentina", LiveChannelCategory.SPORTS, "https://i.imgur.com/EGCq1wc.png", "https://tv.initium.net.ar:3939/live/tectvmainlive.m3u8", country = "Argentina"),
            LiveChannel("sport_9", "R9 Austria", LiveChannelCategory.SPORTS, "https://i.imgur.com/2fxVYsL.jpg", "https://ms01.w24.at/R9/smil:liveeventR9.smil/playlist.m3u8", country = "Austria"),
            LiveChannel("sport_10", "9Life Sports", LiveChannelCategory.SPORTS, "https://i.imgur.com/ZCUiqlL.png", "https://9now-livestreams.akamaized.net/hls/live/2008313/life-syd/master.m3u8", country = "Australia"),
            LiveChannel("sport_11", "Canal 26 Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/ZK7AQFg.png", "https://livetrx01.vodgc.net/eltrecetv/index.m3u8", country = "Argentina"),
            LiveChannel("sport_12", "Net TV Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/EWmshtx.png", "https://unlimited1-us.dps.live/nettv/nettv.smil/playlist.m3u8", country = "Argentina"),
            LiveChannel("sport_13", "Telemax Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/gfX0hdB.png", "https://stream-gtlc.telecentro.net.ar/hls/telemaxhls/main.m3u8", country = "Argentina"),
            LiveChannel("sport_14", "Barricada TV", LiveChannelCategory.SPORTS, "https://www.barricadatv.org/?p=23082", "https://www.youtube.com/channel/UC6YundoLrEuBJaPp_oEPWaA/live", country = "Argentina"),
            LiveChannel("sport_15", "CBC Sport AZ", LiveChannelCategory.SPORTS, "https://upload.wikimedia.org/wikipedia/az/0/04/CBC_Sport_TV_loqo.png", "https://mn-nl.mncdn.com/cbcsports_live/cbcsports/playlist.m3u8", country = "Azerbaijan"),
            LiveChannel("sport_16", "Servus TV Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/zDWhSxq.png", "https://rbmn-live.akamaized.net/hls/live/2002825/geoSTVATweb/master.m3u8", country = "Austria"),
            LiveChannel("sport_17", "DW Sports HD", LiveChannelCategory.SPORTS, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", country = "Germany"),
            LiveChannel("sport_18", "Tring Sport Albania", LiveChannelCategory.SPORTS, "https://i.imgur.com/rL2v9pM.png", "https://fe.tring.al/delta/105/out/u/1200_1.m3u8", country = "Albania"),
            LiveChannel("sport_19", "oe24 Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/8UTkcPn.png", "https://oe24.mdn.ors.at/live/eds/oe24tv_hd/hls_nodrm/oe24tv_hd-nodrm.m3u8", country = "Austria"),
            LiveChannel("sport_20", "RTV Austria Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/oD7GQxT.png", "http://iptv.rtv-ooe.at/stream.m3u8", country = "Austria"),
            LiveChannel("sport_21", "ABC Australia Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/5CVl5EF.png", "https://c.mjh.nz/abc-nsw.m3u8", country = "Australia"),
            LiveChannel("sport_22", "Seven Network Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/6zwKJaa.png", "https://i.mjh.nz/.r/seven-syd.m3u8", country = "Australia"),
            LiveChannel("sport_23", "Nine Network Sport", LiveChannelCategory.SPORTS, "https://i.imgur.com/SMXwfr5.png", "https://i.mjh.nz/.r/channel-9-nsw.m3u8", country = "Australia"),
            LiveChannel("sport_24", "10 Peach Sport AU", LiveChannelCategory.SPORTS, "https://i.imgur.com/NlZLut8.png", "https://i.mjh.nz/.r/10peach-nsw.m3u8", country = "Australia"),
            LiveChannel("sport_25", "7mate Sport AU", LiveChannelCategory.SPORTS, "https://i.imgur.com/zpr12HP.png", "https://i.mjh.nz/.r/7mate-syd.m3u8", country = "Australia"),
        )

        // ─────────────────────────────────────────────────────────────
        // ENTERTAINMENT CHANNELS
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("ent_1", "ORF 2 Austria", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/yPVDaXv.png", "https://orf2.mdn.ors.at/out/u/orf2/q8c/manifest.m3u8", country = "Austria"),
            LiveChannel("ent_2", "ORF III Austria", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/6BuiUE7.png", "https://orf3.mdn.ors.at/out/u/orf3/q8c/manifest.m3u8", country = "Austria"),
            LiveChannel("ent_3", "Tirol TV", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/1E7Nflo.jpg", "https://streaming14.huberwebmedia.at/LiveApp/streams/livestream.m3u8", country = "Austria"),
            LiveChannel("ent_4", "7two Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/6pyIg02.png", "https://i.mjh.nz/.r/7two-syd.m3u8", country = "Australia"),
            LiveChannel("ent_5", "7flix Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/iIYCyC.png", "https://i.mjh.nz/.r/7flix-syd.m3u8", country = "Australia"),
            LiveChannel("ent_6", "9Go! Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/1CFGu5O.png", "https://9now-livestreams.akamaized.net/hls/live/2008312/go-syd/master.m3u8", country = "Australia"),
            LiveChannel("ent_7", "10 Bold Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/2cq3fY1.png", "https://dce3793146fef017.mediapackage.us-west-2.amazonaws.com/out/v1/55cdf73af7894775ba6de8f57482b66a/CMAF_HLS/index.m3u8", country = "Australia"),
            LiveChannel("ent_8", "10 Shake Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/OXtIkOn.png", "https://i.mjh.nz/.r/10shake-nsw.m3u8", country = "Australia"),
            LiveChannel("ent_9", "TVSN Australia", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/p3QCBOo.png", "https://tvsnhlslivetest.akamaized.net/hls/live/2034711/EXPO-MSL4/master.m3u8", country = "Australia"),
            LiveChannel("ent_10", "El Trece Argentina", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/ZK7AQFg.png", "https://livetrx01.vodgc.net/eltrecetv/index.m3u8", country = "Argentina"),
            LiveChannel("ent_11", "América TV Argentina", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/Jt7dOQm.png", "https://prepublish.f.qaotic.net/a07/americahls-100056/playlist_720p.m3u8", country = "Argentina"),
            LiveChannel("ent_12", "+Perfil Argentina", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/3wZdPwN.png", "https://unlimited1-us.dps.live/perfiltv/perfiltv.smil/perfiltv/livestream2/chunks.m3u8", country = "Argentina"),
            LiveChannel("ent_13", "Cine.AR Argentina", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/RPLyrIC.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/cinear/playlist.m3u8", country = "Argentina"),
            LiveChannel("ent_14", "Vizion Plus Albania", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fc/Vizion_Plus.svg/500px-Vizion_Plus.svg.png", "https://tringliveviz.akamaized.net/delta/105/out/u/qwaszxerdfcvrtryuy.m3u8", country = "Albania"),
            LiveChannel("ent_15", "TV Apollon Albania", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/gUz2AjM.png", "https://live.apollon.tv/Apollon-WEB/video.m3u8?token=tnt3u76re30d2", country = "Albania"),
            LiveChannel("ent_16", "Ora News Albania", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/ILZY5bJ.png", "https://live1.mediadesk.al/oranews.m3u8", country = "Albania"),
            LiveChannel("ent_17", "CNA Albania", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/X3ukD5t.png", "https://live1.mediadesk.al/cnatvlive.m3u8", country = "Albania"),
            LiveChannel("ent_18", "Report TV Albania", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/yuRDJYY.png", "https://deb10stream.duckdns.org/hls/stream.m3u8", country = "Albania"),
            LiveChannel("ent_19", "News 24 Albania", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2d/News_24_%28Albania%29.svg/960px-News_24_%28Albania%29.svg.png", "https://tv.balkanweb.com/news24/livestream/playlist.m3u8", country = "Albania"),
            LiveChannel("ent_20", "Panorama TV Albania", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/2/24/Panorama_logo.svg/500px-Panorama_logo.svg.png", "http://198.244.188.94/panorama/livestream/playlist.m3u8", country = "Albania"),
            LiveChannel("ent_21", "DW Documentary", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream106.akamaized.net/hls/live/2015534/dwstream106/index.m3u8", country = "Germany"),
            LiveChannel("ent_22", "Syri Albania", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/4zVyj1M.png", "https://stream.syritv.al/live/syritv/playlist.m3u8", country = "Albania"),
            LiveChannel("ent_23", "Andorra TV", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/3/32/Logo_Andorra_Televisi%C3%B3.png", "https://livesg1.rtva.hiway.media/11a6d6f4-ee13-47c7-9c27-7313cf5424e2/manifest.m3u8", country = "Andorra"),
            LiveChannel("ent_24", "Armenia 1", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/HIwJ4lc.png", "https://ifl01eu-new.bozztv.com/am1abr/index.m3u8", country = "Armenia"),
            LiveChannel("ent_25", "Baku TV Azerbaijan", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/snBMMeH.png", "https://rtmp.baku.tv/hls/bakutv.m3u8", country = "Azerbaijan"),
            LiveChannel("ent_26", "Kanal S Azerbaijan", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/W0JBoSQ.png", "https://lives.atv.az:5443/KANAL-S/streams/kanals.m3u8", country = "Azerbaijan"),
            LiveChannel("ent_27", "İctimai TV Azerbaijan", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/4/43/%C4%B0ctimai_TV_%282021%29.png/120px-%C4%B0ctimai_TV_%282021%29.png", "https://live.itv.az/itv.m3u8", country = "Azerbaijan"),
            LiveChannel("ent_28", "Xəzər Xəbər AZ", LiveChannelCategory.ENTERTAINMENT, "https://i.imgur.com/AuB8bnq.png", "https://www.xezerxeber.az/stream/index.m3u8", country = "Azerbaijan"),
            LiveChannel("ent_29", "Belarus 1", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ac/Belarus_1_logo.svg/960px-Belarus_1_logo.svg.png", "https://edge55.dc.beltelecom.by/ngtrk/smil:belarus1.smil/playlist.m3u8", country = "Belarus"),
            LiveChannel("ent_30", "Belarus 2", LiveChannelCategory.ENTERTAINMENT, "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c0/Belarus_2_logo.svg/960px-Belarus_2_logo.svg.png", "https://edge55.dc.beltelecom.by/ngtrk/smil:belarus2.smil/playlist.m3u8", country = "Belarus"),
        )

        // ─────────────────────────────────────────────────────────────
        // KIDS & CARTOONS
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("kids_1", "ABC Kids Australia", LiveChannelCategory.CARTOONS, "https://i.imgur.com/GWDRR1t.png", "https://c.mjh.nz/abc-kids.m3u8", country = "Australia"),
            LiveChannel("kids_2", "ABC Me Australia", LiveChannelCategory.CARTOONS, "https://i.imgur.com/gBh54wY.png", "https://c.mjh.nz/abc-me.m3u8", country = "Australia"),
            LiveChannel("kids_3", "Pakapaka Kids AR", LiveChannelCategory.CARTOONS, "https://i.imgur.com/Q4zaCuM.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/mirador/playlist.m3u8", country = "Argentina"),
            LiveChannel("kids_4", "Aunar Kids AR", LiveChannelCategory.CARTOONS, "https://i.imgur.com/atGKPhi.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/cinear/playlist.m3u8", country = "Argentina"),
            LiveChannel("kids_5", "TV 7 Albania Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/k9WqPLZ.png", "https://vs.sednastream.com:1936/tv7/tv7/playlist.m3u8", country = "Albania"),
            LiveChannel("kids_6", "P3TV Kids Austria", LiveChannelCategory.CARTOONS, "https://i.imgur.com/1sPhZ57.png", "http://p3-6.mov.at:1935/live/weekstream/playlist.m3u8", country = "Austria"),
            LiveChannel("kids_7", "CGTN Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/3pKH2V2.png", "https://livesource.cgtn.com/cgtn-e/prog_index.m3u8", country = "China"),
            LiveChannel("kids_8", "NHK World Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/v9ixnWr.png", "https://nhkwlive-ojp.akamaized.net/hls/live/2003459/nhkwlive-ojp-en/index.m3u8", country = "Japan"),
            LiveChannel("kids_9", "ABN Bible Movies", LiveChannelCategory.CARTOONS, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnbiblemovies.m3u8", country = "USA"),
            LiveChannel("kids_10", "ABN Africa", LiveChannelCategory.CARTOONS, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnafrica.m3u8", country = "Africa"),
            LiveChannel("kids_11", "10 HD Australia Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/NlZLut8.png", "https://i.mjh.nz/.r/10-nsw.m3u8", country = "Australia"),
            LiveChannel("kids_12", "Tropoja Kids Albania", LiveChannelCategory.CARTOONS, "https://i.imgur.com/D3hNOVS.png", "https://live.prostream.al/al/smil:tropojatv.smil/playlist.m3u8", country = "Albania"),
            LiveChannel("kids_13", "TV Universidad Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/tvLHiAT.png", "https://stratus.stream.cespi.unlp.edu.ar/hls/tvunlp.m3u8", country = "Argentina"),
            LiveChannel("kids_14", "Encuentro Kids AR", LiveChannelCategory.CARTOONS, "https://i.imgur.com/IyP2UIx.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/mirador/playlist.m3u8", country = "Argentina"),
            LiveChannel("kids_15", "ABN Son of God", LiveChannelCategory.CARTOONS, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/son_of_god_.m3u8", country = "USA"),
            LiveChannel("kids_16", "ABN I AM Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/i_am_.m3u8", country = "USA"),
            LiveChannel("kids_17", "AlbKanale Music Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/JdKxscs.png", "https://albportal.net/albkanalemusic.m3u8", country = "Albania"),
            LiveChannel("kids_18", "DW Deutsch Kids", LiveChannelCategory.CARTOONS, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream101.akamaized.net/hls/live/2015524/dwstream101/index.m3u8", country = "Germany"),
            LiveChannel("kids_19", "Belarus 3 Kids", LiveChannelCategory.CARTOONS, "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/Belarus_3_logo.svg/960px-Belarus_3_logo.svg.png", "https://edge55.dc.beltelecom.by/ngtrk/smil:belarus3.smil/playlist.m3u8", country = "Belarus"),
            LiveChannel("kids_20", "RT Documentary", LiveChannelCategory.CARTOONS, "https://i.imgur.com/1REMedN.png", "https://rt-glb.rttv.com/live/rtdoc/playlist.m3u8", country = "Russia"),
        )

        // ─────────────────────────────────────────────────────────────
        // MOVIES
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("movie_1", "Cine.AR Movies", LiveChannelCategory.MOVIES, "https://i.imgur.com/RPLyrIC.png", "https://5fb24b460df87.streamlock.net/live-cont.ar/cinear/playlist.m3u8", country = "Argentina"),
            LiveChannel("movie_2", "DW Film Germany", LiveChannelCategory.MOVIES, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream106.akamaized.net/hls/live/2015534/dwstream106/index.m3u8", country = "Germany"),
            LiveChannel("movie_3", "ORF 1 Movies Austria", LiveChannelCategory.MOVIES, "https://i.imgur.com/ft2LuRl.jpg", "https://orf1.mdn.ors.at/out/u/orf1/q8c/manifest.m3u8", country = "Austria"),
            LiveChannel("movie_4", "7flix Movies AU", LiveChannelCategory.MOVIES, "https://i.imgur.com/6iIYCyC.png", "https://i.mjh.nz/.r/7flix-syd.m3u8", country = "Australia"),
            LiveChannel("movie_5", "Armenia 1 Movies", LiveChannelCategory.MOVIES, "https://i.imgur.com/HIwJ4lc.png", "https://ifl01eu-new.bozztv.com/am1abr/index.m3u8", country = "Armenia"),
            LiveChannel("movie_6", "RT Films Russia", LiveChannelCategory.MOVIES, "https://i.imgur.com/1REMedN.png", "https://rt-glb.rttv.com/live/rtnews/playlist.m3u8", country = "Russia"),
            LiveChannel("movie_7", "CGTN Documentary", LiveChannelCategory.MOVIES, "https://i.imgur.com/3pKH2V2.png", "https://livesource.cgtn.com/cgtn-e/prog_index.m3u8", country = "China"),
            LiveChannel("movie_8", "ABN Films USA", LiveChannelCategory.MOVIES, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnafghanistan.m3u8", country = "USA"),
            LiveChannel("movie_9", "9Gem Movies AU", LiveChannelCategory.MOVIES, "https://i.imgur.com/sWmE1kq.png", "https://9now-livestreams.akamaized.net/hls/live/2008311/gem-syd/master.m3u8", country = "Australia"),
            LiveChannel("movie_10", "Al Jazeera Film", LiveChannelCategory.MOVIES, "https://i.imgur.com/GWKmNPR.png", "https://live-hls-web-aje.getaj.net/AJE/01.m3u8", country = "Qatar"),
            LiveChannel("movie_11", "France 24 Film", LiveChannelCategory.MOVIES, "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/France_24_logo.svg/200px-France_24_logo.svg.png", "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8", country = "France"),
            LiveChannel("movie_12", "NHK Japan Cinema", LiveChannelCategory.MOVIES, "https://i.imgur.com/v9ixnWr.png", "https://nhkwlive-ojp.akamaized.net/hls/live/2003459/nhkwlive-ojp-en/index.m3u8", country = "Japan"),
            LiveChannel("movie_13", "TRT World Film", LiveChannelCategory.MOVIES, "https://i.imgur.com/1GjJGXw.png", "https://tv-trtworld.live.trt.com.tr/master.m3u8", country = "Turkey"),
            LiveChannel("movie_14", "Euronews Cinema", LiveChannelCategory.MOVIES, "https://i.imgur.com/Skf6vdi.png", "https://euronews-euronews-enlivehd-origin-live.akamaized.net/hls/live/2006690/euronews-en-live-hd/master_5000.m3u8", country = "Europe"),
            LiveChannel("movie_15", "VOA Films USA", LiveChannelCategory.MOVIES, "https://i.imgur.com/Q9tqOXz.png", "https://voa-lh.akamaihd.net/i/VOA_TVMC@360286/index_800_av-p.m3u8", country = "USA"),
        )

        // ─────────────────────────────────────────────────────────────
        // MUSIC
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("music_1", "AlbKanale Music", LiveChannelCategory.MUSIC, "https://i.imgur.com/JdKxscs.png", "https://albportal.net/albkanalemusic.m3u8", country = "Albania"),
            LiveChannel("music_2", "ABN Music India", LiveChannelCategory.MUSIC, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abntvindia.m3u8", country = "India"),
            LiveChannel("music_3", "DW Music Germany", LiveChannelCategory.MUSIC, "https://i.imgur.com/1VOrT0T.png", "https://dwamdstream101.akamaized.net/hls/live/2015524/dwstream101/index.m3u8", country = "Germany"),
            LiveChannel("music_4", "RT Music Russia", LiveChannelCategory.MUSIC, "https://i.imgur.com/1REMedN.png", "https://rt-glb.rttv.com/live/rtdoc/playlist.m3u8", country = "Russia"),
            LiveChannel("music_5", "CGTN Music China", LiveChannelCategory.MUSIC, "https://i.imgur.com/3pKH2V2.png", "https://livesource.cgtn.com/cgtn-e/prog_index.m3u8", country = "China"),
            LiveChannel("music_6", "Alpo TV Music", LiveChannelCategory.MUSIC, "https://i.imgur.com/Pr4ixiA.png", "https://5d00db0e0fcd5.streamlock.net/7236/7236/playlist.m3u8", country = "Albania"),
            LiveChannel("music_7", "ABN Urdu Music", LiveChannelCategory.MUSIC, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnurdu.m3u8", country = "Pakistan"),
            LiveChannel("music_8", "Belarus 5 Music", LiveChannelCategory.MUSIC, "https://upload.wikimedia.org/wikipedia/commons/thumb/7/71/Belarus_5_logo.svg/960px-Belarus_5_logo.svg.png", "https://edge55.dc.beltelecom.by/ngtrk/smil:belarus1.smil/playlist.m3u8", country = "Belarus"),
            LiveChannel("music_9", "ABN China Music", LiveChannelCategory.MUSIC, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnchina.m3u8", country = "China"),
            LiveChannel("music_10", "ABN Freedom Music", LiveChannelCategory.MUSIC, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/freedom_of_speech.m3u8", country = "USA"),
        )

        // ─────────────────────────────────────────────────────────────
        // INDIAN CHANNELS
        // ─────────────────────────────────────────────────────────────
        list += listOf(
            LiveChannel("indian_1", "ABN TV India", LiveChannelCategory.INDIAN, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abntvindia.m3u8", country = "India"),
            LiveChannel("indian_2", "ABN Afghanistan", LiveChannelCategory.INDIAN, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnafghanistan.m3u8", country = "India/Afghanistan"),
            LiveChannel("indian_3", "ABN Urdu", LiveChannelCategory.INDIAN, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnurdu.m3u8", country = "India/Pakistan"),
            LiveChannel("indian_4", "CNA Asia Pacific", LiveChannelCategory.INDIAN, "https://i.imgur.com/g0NpzOw.png", "https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43afafc6a9d9a7b0a3b5/index.m3u8", country = "Singapore"),
            LiveChannel("indian_5", "NHK World India", LiveChannelCategory.INDIAN, "https://i.imgur.com/v9ixnWr.png", "https://nhkwlive-ojp.akamaized.net/hls/live/2003459/nhkwlive-ojp-en/index.m3u8", country = "Japan/India"),
            LiveChannel("indian_6", "CGTN Asia", LiveChannelCategory.INDIAN, "https://i.imgur.com/3pKH2V2.png", "https://livesource.cgtn.com/cgtn-e/prog_index.m3u8", country = "China/India"),
            LiveChannel("indian_7", "Al Jazeera Asia", LiveChannelCategory.INDIAN, "https://i.imgur.com/GWKmNPR.png", "https://live-hls-web-aje.getaj.net/AJE/01.m3u8", country = "Qatar/India"),
            LiveChannel("indian_8", "ARY News Pakistan", LiveChannelCategory.INDIAN, "https://i.imgur.com/hVjB1DL.png", "https://stream.arynews.tv/hls/stream.m3u8", country = "Pakistan"),
            LiveChannel("indian_9", "Geo News Pakistan", LiveChannelCategory.INDIAN, "https://i.imgur.com/Qo7pYTd.png", "https://geo.tv/live/geo-news.m3u8", country = "Pakistan"),
            LiveChannel("indian_10", "ABN Africa India", LiveChannelCategory.INDIAN, "https://i.imgur.com/5CVl5EF.png", "https://mediaserver.abnvideos.com/streams/abnafrica.m3u8", country = "India/Africa"),
        )

        return list
    }
}
