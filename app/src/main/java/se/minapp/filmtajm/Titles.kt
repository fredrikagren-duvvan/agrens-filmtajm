package se.minapp.filmtajm

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/** Streamingtjänsterna. Koden är samma som i data.json. */
enum class Service(val code: String, val label: String, val color: Int) {
    NETFLIX("nf", "Netflix", 0xFFE50914.toInt()),
    DISNEY("dp", "Disney+", 0xFF113CCF.toInt()),
    HBO("hbo", "HBO Max", 0xFF5822B4.toInt()),
    VIAPLAY("vp", "Viaplay", 0xFFD6005A.toInt()),
    PRIME("pv", "Prime Video", 0xFF00A8E1.toInt());

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

/** Våra fem genrer. Kopplingen till TMDB:s genrer görs i scripts/uppdatera.mjs. */
enum class Genre(val code: String, val label: String) {
    ROMANCE("rom", "Romantik"),
    COMEDY("kom", "Komedi/humor"),
    ACTION("act", "Action/thriller"),
    SCIFI("sci", "Sci-fi"),
    DRAMA("dra", "Drama");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

class Title(
    val name: String,
    val year: Int?,
    val isSeries: Boolean,
    val genres: Set<Genre>,
    val services: List<Service>,
    val imdbId: String,
    val rating: Double,
    val votes: Int,
    val poster: String?,
) {
    /** Viktat betyg, räknas ut av [Catalog]. Listan sorteras på det. */
    var weighted = 0.0

    val imdbUrl get() = "https://www.imdb.com/title/$imdbId/"
    val posterUrl get() = poster?.let { "https://image.tmdb.org/t/p/w154$it" }
}

/** Alla titlar från data.json och när de hämtades av skriptet. */
class Catalog(val titles: List<Title>, val updated: String) {

    init {
        // Bayesianskt viktat betyg, som IMDb:s topp 250. C = snittbetyget för
        // alla filmer (eller alla serier) i datat.
        for (series in listOf(false, true)) {
            val group = titles.filter { it.isSeries == series }
            if (group.isEmpty()) continue
            val c = group.sumOf { it.rating } / group.size
            val m = minVotes(series).toDouble()
            for (t in group) t.weighted = (t.votes / (t.votes + m)) * t.rating + (m / (t.votes + m)) * c
        }
    }

    /**
     * Topplistan för valen. Dolda titlar visas bara om [Choices.showHidden] är
     * på, sedda tas bort om [Choices.hideSeen] är på.
     */
    fun top(choices: Choices, seen: Set<String>, hidden: Set<String>): List<Title> =
        titles.asSequence()
            .filter { it.isSeries == choices.series }
            .filter { it.votes >= minVotes(it.isSeries) }
            .filter { t -> t.services.any { it in choices.services } }
            .filter { t -> t.genres.any { it in choices.genres } }
            .filter { choices.showHidden || it.imdbId !in hidden }
            .filter { !choices.hideSeen || it.imdbId !in seen }
            .sortedByDescending { it.weighted }
            .take(TOP_COUNT)
            .toList()

    companion object {
        // --- Inställningar för rankningen ---

        /** Minsta antal IMDb-röster för att en film ska visas. */
        const val MIN_VOTES_MOVIES = 5_000

        /** Minsta antal IMDb-röster för att en serie ska visas. */
        const val MIN_VOTES_SERIES = 10_000

        /** Så många titlar visas i listan. */
        const val TOP_COUNT = 50

        fun minVotes(series: Boolean) = if (series) MIN_VOTES_SERIES else MIN_VOTES_MOVIES

        // --- Hämtning ---

        /** Uppdateras en gång per natt av GitHub Actions. */
        private const val DATA_URL = "https://fredrikagren-duvvan.github.io/agrens-filmtajm/data.json"

        /** Hämta inte ny data oftare än så här. */
        private const val MAX_AGE = 6 * 60 * 60_000L

        private fun file(context: Context) = File(context.filesDir, "data.json")

        /** Senast hämtade data, eller null om appen aldrig hämtat något. */
        fun loadSaved(context: Context): Catalog? =
            file(context).takeIf { it.exists() }?.let { runCatching { parse(it.readText()) }.getOrNull() }

        fun isStale(context: Context) =
            System.currentTimeMillis() - file(context).lastModified() > MAX_AGE

        /** Hämtar data.json från nätet och sparar den. Kastar fel om det inte går. */
        fun download(context: Context): Catalog {
            val connection = URL(DATA_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != 200) error("Servern svarade ${connection.responseCode}")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val catalog = parse(text)
                val tmp = File(context.filesDir, "data.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(file(context))
                return catalog
            } finally {
                connection.disconnect()
            }
        }

        /** Läser data.json bit för bit (mycket snabbare än att bygga upp hela JSON-trädet). */
        private fun parse(text: String): Catalog {
            val titles = ArrayList<Title>(12_000)
            var updated = ""
            JsonReader(StringReader(text)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "uppdaterad" -> updated = r.nextString()
                        "titlar" -> {
                            r.beginArray()
                            while (r.hasNext()) titles += readTitle(r)
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            return Catalog(titles, updated)
        }

        private fun readTitle(r: JsonReader): Title {
            var name = ""
            var year: Int? = null
            var series = false
            val genres = mutableSetOf<Genre>()
            val services = mutableListOf<Service>()
            var imdb = ""
            var rating = 0.0
            var votes = 0
            var poster: String? = null
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                if (r.peek() == JsonToken.NULL) {
                    r.nextNull()
                    continue
                }
                when (key) {
                    "t" -> name = r.nextString()
                    "y" -> year = r.nextInt()
                    "k" -> series = r.nextString() == "s"
                    "g" -> { r.beginArray(); while (r.hasNext()) Genre.of(r.nextString())?.let { genres += it }; r.endArray() }
                    "s" -> { r.beginArray(); while (r.hasNext()) Service.of(r.nextString())?.let { services += it }; r.endArray() }
                    "i" -> imdb = r.nextString()
                    "r" -> rating = r.nextDouble()
                    "v" -> votes = r.nextInt()
                    "p" -> poster = r.nextString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return Title(name, year, series, genres, services, imdb, rating, votes, poster)
        }
    }
}
