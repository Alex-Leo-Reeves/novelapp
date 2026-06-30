as ai is reading it should highlight texts
Option 1: The DuckDuckGo HTML Fallback (Automated)

Instead of asking your Ktor client to ping [https://anineko.to](https://anineko.to) directly, have your scraper search for the site through a privacy-focused search engine first, grab the very top result, and use that as the live domain.

Since search engines index these shifts in real-time, your code will look like this conceptually:
Kotlin

suspend fun getLiveAnimeLink(): String {
    // 1. Ask DuckDuckGo HTML for the top result matching the brand name
    val htmlResponse = ktorClient.get("https://html.duckduckgo.com/html/?q=anineko+official")
    
    // 2. Parse the search result links using JSoup
    val topLink = parseFirstResultDomain(htmlResponse) 
    
    // Returns the fresh live link (e.g., "https://anineko.to") completely automatically!
    return topLink
}
dont forget the app icon and new links for scrapper including new manga scrapper link
all fixes including previous ones should be applied across all apps 
