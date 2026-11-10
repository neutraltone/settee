/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.baselineprofile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * A made-up Xtream Codes panel for the profile generator, listening on 127.0.0.1 in the test
 * process, which the app reaches over loopback.
 *
 * The Stalker mock (tools/stalker_mock.py) would need a host-side script and has no movies or
 * shows, and a real provider must never appear here. So this serves just enough of the API
 * XtreamApi calls to fill every screen the journeys walk: channels with a guide, movies and series
 * in a few categories, episodes, and poster art so image decoding is in the profile too. Its
 * streams don't play; the journeys only browse.
 */
class FakeXtreamPanel : AutoCloseable {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))

    @Volatile private var running = true

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(name = "fake-xtream", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // The app drops connections it no longer wants, such as a poster scrolled away; an
                // uncaught broken pipe here would take the whole test process down with it.
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    override fun close() {
        running = false
        socket.close()
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        // Closing with the headers still unread resets the connection, which can cut off a reply.
        while (!reader.readLine().isNullOrEmpty()) Unit
        val target = requestLine.split(' ').getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }
        val out = client.getOutputStream()
        when {
            path == "/player_api.php" -> respond(out, "application/json", playerApi(query).toByteArray())
            path == "/xmltv.php" -> respond(out, "application/xml", xmltv().toByteArray())
            path.startsWith("/art/") -> respond(out, "image/png", poster(path.removePrefix("/art/")))
            else -> respond(out, "text/plain", "Not found".toByteArray(), status = "404 Not Found")
        }
    }

    private fun playerApi(query: Map<String, String>): String = when (query["action"]) {
        null -> """{"user_info":{"username":"u","status":"Active","auth":1,"exp_date":null},""" +
            """"server_info":{"timezone":"UTC"}}"""
        "get_live_categories" -> categories(LIVE_CATEGORIES)
        "get_vod_categories" -> categories(MOVIE_CATEGORIES)
        "get_series_categories" -> categories(SERIES_CATEGORIES)
        "get_live_streams" -> array(LIVE_CATEGORIES.size * PER_CATEGORY) { i ->
            val n = i + 1
            """{"num":$n,"name":"Channel $n","stream_id":"$n","stream_icon":"$url/art/c$n",""" +
                """"epg_channel_id":"ch$n.test","category_id":"${i / PER_CATEGORY + 1}"}"""
        }
        "get_vod_streams" -> array(MOVIE_CATEGORIES.size * PER_CATEGORY) { i ->
            val n = i + 1
            """{"name":"Movie $n","stream_id":"$n","stream_icon":"$url/art/m$n","rating":"7.1",""" +
                """"year":"2024","added":"${addedSeconds(i)}","category_id":"${i / PER_CATEGORY + 1}",""" +
                """"container_extension":"mp4","plot":"A film made up for the profile generator."}"""
        }
        "get_series" -> array(SERIES_CATEGORIES.size * PER_CATEGORY) { i ->
            val n = i + 1
            """{"name":"Show $n","series_id":"$n","cover":"$url/art/s$n","rating":"8.0",""" +
                """"year":"2023","last_modified":"${addedSeconds(i)}","category_id":"${i / PER_CATEGORY + 1}",""" +
                """"plot":"A series made up for the profile generator."}"""
        }
        "get_vod_info" -> """{"info":{"plot":"A film made up for the profile generator.","genre":"Drama",""" +
            """"cast":"Nobody","director":"Nobody","duration_secs":5400}}"""
        "get_series_info" -> seriesInfo(query["series_id"].orEmpty())
        else -> "[]"
    }

    // Episode ids are unique across a provider, as the app keys episodes by them.
    private fun seriesInfo(seriesId: String): String {
        val seasons = (1..SEASONS).joinToString(",") { season ->
            val episodes = array(EPISODES) { e ->
                val number = e + 1
                """{"id":"$seriesId-$season-$number","episode_num":$number,"season":$season,""" +
                    """"title":"Episode $number","container_extension":"mp4",""" +
                    """"info":{"plot":"An episode.","duration_secs":2700}}"""
            }
            "\"$season\":$episodes"
        }
        return """{"info":{"plot":"A series made up for the profile generator.","genre":"Drama"},""" +
            """"episodes":{$seasons}}"""
    }

    private fun xmltv(): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hour = 3_600_000L
        val start = System.currentTimeMillis() / hour * hour - 2 * hour
        val channels = LIVE_CATEGORIES.size * PER_CATEGORY
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            for (n in 1..channels) append("<channel id=\"ch$n.test\"><display-name>Channel $n</display-name></channel>")
            for (n in 1..channels) {
                for (slot in 0 until GUIDE_HOURS * 2) {
                    val from = start + slot * hour / 2
                    append("<programme start=\"${format.format(Date(from))}\" stop=\"${format.format(Date(from + hour / 2))}\"")
                    append(" channel=\"ch$n.test\"><title>Programme $slot</title><desc>Made up.</desc></programme>")
                }
            }
            append("</tv>")
        }
    }

    /** A flat colour per title, so Coil decodes real images without any artwork in the repo. */
    private fun poster(id: String): ByteArray {
        val bitmap = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf((id.hashCode() and 0xff) * 360f / 256f, 0.5f, 0.6f)))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun categories(names: List<String>): String =
        array(names.size) { i -> """{"category_id":"${i + 1}","category_name":"${names[i]}"}""" }

    private fun array(count: Int, element: (Int) -> String): String =
        (0 until count).joinToString(",", "[", "]", transform = element)

    // Newest first, a day apart, so Home has rows of new titles to move along.
    private fun addedSeconds(index: Int): Long = System.currentTimeMillis() / 1000 - index * 86_400L

    private fun respond(out: OutputStream, type: String, body: ByteArray, status: String = "200 OK") {
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        const val BACKLOG = 50
        const val PER_CATEGORY = 24
        const val SEASONS = 2
        const val EPISODES = 6
        const val GUIDE_HOURS = 8
        const val POSTER_WIDTH = 200
        const val POSTER_HEIGHT = 300
        val LIVE_CATEGORIES = listOf("News", "Sport", "Films")
        val MOVIE_CATEGORIES = listOf("Action", "Comedy", "Drama")
        val SERIES_CATEGORIES = listOf("Crime", "Comedy")
    }
}
