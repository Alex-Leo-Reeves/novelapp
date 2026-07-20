import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStreamReader
import java.io.BufferedReader

fun downloadText(urlStr: String): String {
    val connection = URL(urlStr).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    val reader = BufferedReader(InputStreamReader(connection.inputStream))
    return reader.readText()
}

try {
    val html = downloadText("https://readcomiconline.li/Comic/Batman-2016/Issue-1?id=83685")
    println(html.take(500))
} catch (e: Exception) {
    println("Error: ${e.message}")
}
