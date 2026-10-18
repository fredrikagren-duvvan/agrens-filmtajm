package se.minapp.filmtajm

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Appens enda skärm: val överst, topplistan under och källorna längst ner.
 */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var choices: Choices

    private val serviceBoxes = mutableMapOf<Service, CheckBox>()
    private val genreBoxes = mutableMapOf<Genre, CheckBox>()
    private lateinit var movies: RadioButton
    private lateinit var series: RadioButton
    private lateinit var hideSeen: CheckBox
    private lateinit var showHidden: CheckBox

    private var catalog: Catalog? = null
    private var loading = false

    private val swedish = Locale.forLanguageTag("sv-SE")

    /** Vanlig textfärg (svart i ljust läge, vit i mörkt). TextView är annars grå. */
    private val primaryTextColor by lazy {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        attrs.getColorStateList(0).also { attrs.recycle() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (HttpResponseCache.getInstalled() == null) {
            // Sparar affischerna på disk så att de inte hämtas om varje gång.
            HttpResponseCache.install(File(cacheDir, "http"), 50L * 1024 * 1024)
        }
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        choices = Settings.choices(this)

        val servicesGrid = findViewById<GridLayout>(R.id.services)
        for (service in Service.entries) {
            serviceBoxes[service] = choiceBox(servicesGrid, service.label, service in choices.services)
        }
        val genresGrid = findViewById<GridLayout>(R.id.genres)
        for (genre in Genre.entries) {
            genreBoxes[genre] = choiceBox(genresGrid, genre.label, genre in choices.genres)
        }
        movies = findViewById(R.id.movies)
        series = findViewById(R.id.series)
        hideSeen = findViewById(R.id.hideSeen)
        showHidden = findViewById(R.id.showHidden)
        (if (choices.series) series else movies).isChecked = true
        hideSeen.isChecked = choices.hideSeen
        showHidden.isChecked = choices.showHidden
        for (box in listOf(movies, series, hideSeen, showHidden)) {
            box.setOnCheckedChangeListener { _, _ -> onChoicesChanged() }
        }

        findViewById<TextView>(R.id.version).text =
            "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Läser sparad data och hämtar ny om den är gammal (eller saknas). */
    private fun load() {
        if (loading) return
        loading = true
        if (catalog == null) status.text = "Hämtar filmer och serier…"
        thread {
            var problem: String? = null
            var result = catalog ?: Catalog.loadSaved(this)
            if (result == null || Catalog.isStale(this)) {
                try {
                    result = Catalog.download(this)
                } catch (e: Exception) {
                    problem = if (result == null) {
                        "Kunde inte hämta listan. Är du ansluten till internet?"
                    } else {
                        null // Visa den sparade listan utan felmeddelande.
                    }
                }
            }
            runOnUiThread {
                loading = false
                catalog = result
                render(problem)
            }
        }
    }

    private fun choiceBox(grid: GridLayout, label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, _ -> onChoicesChanged() }
        grid.addView(this, GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        })
    }

    private fun onChoicesChanged() {
        choices = Choices(
            services = serviceBoxes.filterValues { it.isChecked }.keys,
            genres = genreBoxes.filterValues { it.isChecked }.keys,
            series = series.isChecked,
            hideSeen = hideSeen.isChecked,
            showHidden = showHidden.isChecked,
        )
        Settings.save(this, choices)
        render()
    }

    private fun render(problem: String? = null) {
        list.removeAllViews()
        val current = catalog
        if (current == null) {
            status.text = problem ?: ""
            return
        }
        val seen = Settings.seen(this)
        val hidden = Settings.hidden(this)
        val top = current.top(choices, seen, hidden)
        top.forEachIndexed { index, title ->
            list.addView(row(index + 1, title, title.imdbId in seen, title.imdbId in hidden))
        }
        status.text = when {
            choices.services.isEmpty() -> "Välj minst en tjänst."
            choices.genres.isEmpty() -> "Välj minst en genre."
            top.isEmpty() -> "Inga titlar matchar valen."
            else -> "Topp ${top.size} just nu · listan uppdaterad ${formatDate(current.updated)}"
        }
    }

    private fun row(rank: Int, title: Title, isSeen: Boolean, isHidden: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        alpha = when {
            isHidden -> 0.4f
            isSeen -> 0.55f
            else -> 1f
        }

        addView(TextView(context).apply {
            text = "$rank"
            gravity = Gravity.CENTER
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
            contentDescription = title.name
            Posters.load(this, title.posterUrl)
        }, LinearLayout.LayoutParams(dp(52), dp(78)))

        // Titel, år, betyg och tjänster. Tryck öppnar IMDb.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(title.imdbUrl))) }
            addView(TextView(context).apply {
                text = title.name
                setTextColor(primaryTextColor)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                val year = title.year?.let { "$it · " } ?: ""
                setTextColor(primaryTextColor)
                text = "$year★ ${String.format(swedish, "%.1f", title.rating)} · ${formatVotes(title.votes)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, dp(4))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                title.services.forEach { addView(badge(it)) }
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Sett och Dölj.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(CheckBox(context).apply {
                text = "Sett"
                isChecked = isSeen
                setOnCheckedChangeListener { _, checked ->
                    Settings.setSeen(this@MainActivity, title.imdbId, checked)
                    render()
                }
            })
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (isHidden) "Visa igen" else "Dölj"
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener {
                    Settings.setHidden(this@MainActivity, title.imdbId, !isHidden)
                    render()
                }
            })
        })
    }

    /** Litet färgat märke med tjänstens namn. */
    private fun badge(service: Service) = TextView(this).apply {
        text = service.label
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply {
            setColor(service.color)
            cornerRadius = dp(4).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(4) }
    }

    /** "2,7 milj. röster" eller "458 594 röster". */
    private fun formatVotes(votes: Int) =
        if (votes >= 1_000_000) "${String.format(swedish, "%.1f", votes / 1_000_000.0)} milj. röster"
        else "${NumberFormat.getIntegerInstance(swedish).format(votes)} röster"

    /** "2026-10-18T03:12:00Z" → "18 okt". */
    private fun formatDate(iso: String): String {
        val months = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
        val parts = iso.take(10).split("-")
        if (parts.size != 3) return iso
        val month = parts[1].toIntOrNull() ?: return iso
        return "${parts[2].trimStart('0')} ${months.getOrElse(month - 1) { "" }}"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
