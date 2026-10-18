package se.minapp.filmtajm

import android.content.Context

/** Valen överst på skärmen. */
data class Choices(
    val services: Set<Service>,
    val genres: Set<Genre>,
    val series: Boolean,
    val hideSeen: Boolean,
    val showHidden: Boolean,
)

/**
 * Sparar valen och vilka titlar som är markerade som sedda eller dolda, så att
 * de finns kvar nästa gång appen öppnas. Titlar sparas med sitt IMDb-id.
 */
object Settings {

    private fun prefs(context: Context) = context.getSharedPreferences("val", Context.MODE_PRIVATE)

    fun choices(context: Context): Choices {
        val p = prefs(context)
        return Choices(
            services = p.getStringSet("tjanster", null)?.mapNotNull { Service.of(it) }?.toSet() ?: Service.entries.toSet(),
            genres = p.getStringSet("genrer", null)?.mapNotNull { Genre.of(it) }?.toSet() ?: Genre.entries.toSet(),
            series = p.getBoolean("serier", false),
            hideSeen = p.getBoolean("doljSedda", false),
            showHidden = p.getBoolean("visaDolda", false),
        )
    }

    fun save(context: Context, choices: Choices) {
        prefs(context).edit()
            .putStringSet("tjanster", choices.services.map { it.code }.toSet())
            .putStringSet("genrer", choices.genres.map { it.code }.toSet())
            .putBoolean("serier", choices.series)
            .putBoolean("doljSedda", choices.hideSeen)
            .putBoolean("visaDolda", choices.showHidden)
            .apply()
    }

    fun seen(context: Context): Set<String> = prefs(context).getStringSet("sedda", emptySet())!!.toSet()

    fun setSeen(context: Context, imdbId: String, seen: Boolean) = toggle(context, "sedda", imdbId, seen)

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("dolda", emptySet())!!.toSet()

    fun setHidden(context: Context, imdbId: String, hidden: Boolean) = toggle(context, "dolda", imdbId, hidden)

    private fun toggle(context: Context, key: String, imdbId: String, on: Boolean) {
        // getStringSet ger en mängd som inte får ändras, därför en kopia.
        val set = prefs(context).getStringSet(key, emptySet())!!.toMutableSet()
        if (on) set += imdbId else set -= imdbId
        prefs(context).edit().putStringSet(key, set).apply()
    }
}
