package com.alexleoreeves.novelapp.data

/**
 * Curated UI strings for the app's language picker.
 *
 * Only the high-visibility chrome is translated (bottom tabs, TV sidebar
 * sections, You-screen settings); long-tail copy (dialogs, error text) still
 * falls back to English. Content localization (titles/overviews/genres) comes
 * from [AppLanguageState.tmdbLanguage] re-querying TMDB in the active language.
 *
 * Lookup order: active language → English → the raw key.
 */
object AppStrings {

    val ENGLISH: Map<String, String> = mapOf(
        "discover" to "Discover",
        "live_tv" to "Live TV",
        "asian" to "Asian",
        "sports" to "Sports",
        "read" to "Read",
        "you" to "You",
        "home" to "Home",
        "novels" to "Novels",
        "creation" to "Creation",
        "manga" to "Manga",
        "comics" to "Comics",
        "anime" to "Anime",
        "donghua" to "Donghua",
        "kdrama" to "K-Drama",
        "cartoon" to "Cartoon",
        "classic" to "Classic",
        "movies" to "Movies",
        "nollywood" to "Nollywood",
        "downloads" to "Downloads",
        "app_settings" to "App Settings",
        "language" to "Language",
        "server_selectors" to "Show server selectors",
        "device_language" to "Device language"
    )

    private val TRANSLATIONS: Map<String, Map<String, String>> = mapOf(
        "es" to mapOf(
            "discover" to "Descubrir", "live_tv" to "TV en vivo", "asian" to "Asia",
            "sports" to "Deportes", "read" to "Leer", "you" to "Tú", "home" to "Inicio",
            "novels" to "Novelas", "creation" to "Creación", "comics" to "Cómics",
            "cartoon" to "Dibujos animados", "classic" to "Clásicos", "movies" to "Películas",
            "downloads" to "Descargas", "app_settings" to "Ajustes de la app",
            "language" to "Idioma", "server_selectors" to "Mostrar selectores de servidor",
            "device_language" to "Idioma del dispositivo"
        ),
        "fr" to mapOf(
            "discover" to "Découvrir", "live_tv" to "TV en direct", "asian" to "Asie",
            "sports" to "Sports", "read" to "Lire", "you" to "Vous", "home" to "Accueil",
            "novels" to "Romans", "creation" to "Création", "comics" to "Comics",
            "cartoon" to "Dessins animés", "classic" to "Classiques", "movies" to "Films",
            "downloads" to "Téléchargements", "app_settings" to "Réglages de l'app",
            "language" to "Langue", "server_selectors" to "Afficher les sélecteurs de serveur",
            "device_language" to "Langue de l'appareil"
        ),
        "de" to mapOf(
            "discover" to "Entdecken", "live_tv" to "Live-TV", "asian" to "Asien",
            "sports" to "Sport", "read" to "Lesen", "you" to "Du", "home" to "Start",
            "novels" to "Romane", "creation" to "Erstellung", "comics" to "Comics",
            "cartoon" to "Cartoons", "classic" to "Klassiker", "movies" to "Filme",
            "downloads" to "Downloads", "app_settings" to "App-Einstellungen",
            "language" to "Sprache", "server_selectors" to "Serverauswahl anzeigen",
            "device_language" to "Gerätesprache"
        ),
        "pt" to mapOf(
            "discover" to "Descobrir", "live_tv" to "TV ao vivo", "asian" to "Ásia",
            "sports" to "Esportes", "read" to "Ler", "you" to "Você", "home" to "Início",
            "novels" to "Romances", "creation" to "Criação", "comics" to "Quadrinhos",
            "cartoon" to "Desenhos animados", "classic" to "Clássicos", "movies" to "Filmes",
            "downloads" to "Downloads", "app_settings" to "Configurações do app",
            "language" to "Idioma", "server_selectors" to "Mostrar seletores de servidor",
            "device_language" to "Idioma do aparelho"
        ),
        "it" to mapOf(
            "discover" to "Scopri", "live_tv" to "TV in diretta", "asian" to "Asia",
            "sports" to "Sport", "read" to "Leggi", "you" to "Tu", "home" to "Home",
            "novels" to "Romanzi", "creation" to "Creazione", "comics" to "Fumetti",
            "cartoon" to "Cartoni animati", "classic" to "Classici", "movies" to "Film",
            "downloads" to "Download", "app_settings" to "Impostazioni app",
            "language" to "Lingua", "server_selectors" to "Mostra selettori server",
            "device_language" to "Lingua del dispositivo"
        ),
        "ru" to mapOf(
            "discover" to "Обзор", "live_tv" to "Прямой эфир", "asian" to "Азия",
            "sports" to "Спорт", "read" to "Чтение", "you" to "Профиль", "home" to "Главная",
            "novels" to "Романы", "creation" to "Творчество", "comics" to "Комиксы",
            "cartoon" to "Мультфильмы", "classic" to "Классика", "movies" to "Фильмы",
            "downloads" to "Загрузки", "app_settings" to "Настройки приложения",
            "language" to "Язык", "server_selectors" to "Показывать выбор сервера",
            "device_language" to "Язык устройства"
        ),
        "ar" to mapOf(
            "discover" to "استكشاف", "live_tv" to "بث مباشر", "asian" to "آسيا",
            "sports" to "رياضة", "read" to "قراءة", "you" to "حسابي", "home" to "الرئيسية",
            "novels" to "روايات", "creation" to "إنشاء", "comics" to "قصص مصورة",
            "cartoon" to "كرتون", "classic" to "كلاسيكي", "movies" to "أفلام",
            "downloads" to "التنزيلات", "app_settings" to "إعدادات التطبيق",
            "language" to "اللغة", "server_selectors" to "إظهار اختيار الخادم",
            "device_language" to "لغة الجهاز"
        ),
        "hi" to mapOf(
            "discover" to "खोजें", "live_tv" to "लाइव टीवी", "asian" to "एशिया",
            "sports" to "खेल", "read" to "पढ़ें", "you" to "आप", "home" to "मुख्य",
            "novels" to "उपन्यास", "creation" to "रचना", "comics" to "कॉमिक्स",
            "cartoon" to "कार्टून", "classic" to "क्लासिक", "movies" to "फ़िल्में",
            "downloads" to "डाउनलोड", "app_settings" to "ऐप सेटिंग्स",
            "language" to "भाषा", "server_selectors" to "सर्वर चयनकर्ता दिखाएँ",
            "device_language" to "डिवाइस की भाषा"
        ),
        "zh" to mapOf(
            "discover" to "发现", "live_tv" to "直播", "asian" to "亚洲",
            "sports" to "体育", "read" to "阅读", "you" to "我的", "home" to "首页",
            "novels" to "小说", "creation" to "创作", "manga" to "日漫", "comics" to "漫画",
            "donghua" to "国漫", "kdrama" to "韩剧", "cartoon" to "卡通",
            "classic" to "经典", "movies" to "电影", "downloads" to "下载",
            "app_settings" to "应用设置", "language" to "语言",
            "server_selectors" to "显示服务器选择器", "device_language" to "设备语言"
        ),
        "ja" to mapOf(
            "discover" to "発見", "live_tv" to "ライブTV", "asian" to "アジア",
            "sports" to "スポーツ", "read" to "読む", "you" to "マイページ", "home" to "ホーム",
            "novels" to "小説", "creation" to "クリエイト", "comics" to "コミック",
            "donghua" to "中国アニメ", "kdrama" to "韓国ドラマ", "cartoon" to "カートゥーン",
            "classic" to "名作", "movies" to "映画", "downloads" to "ダウンロード",
            "app_settings" to "アプリ設定", "language" to "言語",
            "server_selectors" to "サーバー選択を表示", "device_language" to "デバイスの言語"
        ),
        "ko" to mapOf(
            "discover" to "발견", "live_tv" to "라이브 TV", "asian" to "아시아",
            "sports" to "스포츠", "read" to "읽기", "you" to "내 정보", "home" to "홈",
            "novels" to "소설", "creation" to "만들기", "manga" to "만화", "comics" to "코믹스",
            "donghua" to "중국 애니", "kdrama" to "한국 드라마", "cartoon" to "카툰",
            "classic" to "명작", "movies" to "영화", "downloads" to "다운로드",
            "app_settings" to "앱 설정", "language" to "언어",
            "server_selectors" to "서버 선택 표시", "device_language" to "기기 언어"
        ),
        "id" to mapOf(
            "discover" to "Jelajahi", "live_tv" to "TV Langsung", "asian" to "Asia",
            "sports" to "Olahraga", "read" to "Baca", "you" to "Profil", "home" to "Beranda",
            "novels" to "Novel", "creation" to "Kreasi", "comics" to "Komik",
            "cartoon" to "Kartun", "classic" to "Klasik", "movies" to "Film",
            "downloads" to "Unduhan", "app_settings" to "Pengaturan aplikasi",
            "language" to "Bahasa", "server_selectors" to "Tampilkan pemilih server",
            "device_language" to "Bahasa perangkat"
        ),
        "tr" to mapOf(
            "discover" to "Keşfet", "live_tv" to "Canlı TV", "asian" to "Asya",
            "sports" to "Spor", "read" to "Oku", "you" to "Profil", "home" to "Ana Sayfa",
            "novels" to "Romanlar", "creation" to "Oluşturma", "comics" to "Çizgi Romanlar",
            "cartoon" to "Çizgi Filmler", "classic" to "Klasikler", "movies" to "Filmler",
            "downloads" to "İndirilenler", "app_settings" to "Uygulama ayarları",
            "language" to "Dil", "server_selectors" to "Sunucu seçicileri göster",
            "device_language" to "Cihaz dili"
        ),
        "vi" to mapOf(
            "discover" to "Khám phá", "live_tv" to "Trực tiếp", "asian" to "Châu Á",
            "sports" to "Thể thao", "read" to "Đọc", "you" to "Cá nhân", "home" to "Trang chủ",
            "novels" to "Tiểu thuyết", "creation" to "Sáng tạo", "comics" to "Truyện tranh",
            "cartoon" to "Hoạt hình", "classic" to "Cổ điển", "movies" to "Phim",
            "downloads" to "Tải xuống", "app_settings" to "Cài đặt ứng dụng",
            "language" to "Ngôn ngữ", "server_selectors" to "Hiển thị bộ chọn máy chủ",
            "device_language" to "Ngôn ngữ thiết bị"
        ),
        "sw" to mapOf(
            "discover" to "Gundua", "live_tv" to "TV Moja kwa Moja", "asian" to "Asia",
            "sports" to "Michezo", "read" to "Soma", "you" to "Wewe", "home" to "Mwanzo",
            "novels" to "Romaani", "creation" to "Uumbaji", "cartoon" to "Cartoon",
            "classic" to "Klasiki", "movies" to "Filamu", "downloads" to "Pakua",
            "app_settings" to "Mipangilio ya programu", "language" to "Lugha",
            "server_selectors" to "Onyesha chaguo za seva", "device_language" to "Lugha ya kifaa"
        )
    )

    /** Effective table for the active language (SYSTEM resolves via the device). */
    fun table(): Map<String, String> {
        val code = AppLanguageState.effective.code
        if (code != cachedCode) {
            cached = if (code.isEmpty()) ENGLISH else ENGLISH + (TRANSLATIONS[code] ?: emptyMap())
            cachedCode = code
        }
        return cached
    }

    fun get(key: String): String = table()[key] ?: ENGLISH[key] ?: key

    /**
     * Localized sidebar/section label for a tv-config.json key; falls back to
     * the remote config's own label for keys this table doesn't know.
     */
    fun sectionLabel(key: String, fallback: String): String =
        if (ENGLISH.containsKey(key)) get(key) else fallback

    private var cachedCode: String? = null
    private var cached: Map<String, String> = ENGLISH
}
