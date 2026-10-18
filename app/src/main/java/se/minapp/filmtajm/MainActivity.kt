package se.minapp.filmtajm

import android.app.Activity
import android.app.AlertDialog
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

        findViewById<TextView>(R.id.version).text = "Version ${Updater.currentVersionName(this)}"
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkForUpdate(manual = true) }
        handleTestInstall(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestInstall(intent)
    }

    override fun onResume() {
        super.onResume()
        load()

        // Tillbaka från inställningen "Tillåt från den här källan"?
        val waiting = updateWaitingForPermission
        if (waiting != null && Updater.canInstall(this)) {
            updateWaitingForPermission = null
            startInstall(waiting)
        } else if (System.currentTimeMillis() - lastAutoCheck > AUTO_CHECK_INTERVAL) {
            // Varje gång appen visas (men högst var tionde minut).
            lastAutoCheck = System.currentTimeMillis()
            checkForUpdate(manual = false)
        }
    }

    // --- Självuppdatering (samma som i Nästa tunnelbana) ---

    private var updateWaitingForPermission: AppUpdate? = null
    private var updateDialog: AlertDialog? = null

    private fun checkForUpdate(manual: Boolean) {
        if (Updater.isDebugBuild(this)) {
            if (manual) message("Utvecklingsversionen", "Versionen från Android Studio letar inte efter uppdateringar.")
            return
        }
        thread {
            val result = runCatching { Updater.check(this) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val update = result.getOrNull()
                when {
                    update != null -> showUpdate(update)
                    result.isFailure && manual -> message("Det gick inte", "Kunde inte leta efter uppdatering. Är du ansluten till internet?")
                    manual -> message("Inga uppdateringar", "Du har den senaste versionen.")
                }
            }
        }
    }

    private fun showUpdate(update: AppUpdate) {
        if (updateDialog?.isShowing == true) return
        val notes = if (update.notes.isBlank()) "" else "\n\nNyheter:\n${update.notes}"
        updateDialog = AlertDialog.Builder(this)
            .setTitle("Ny version av Agréns filmtajm!")
            .setMessage("Version ${update.versionName} finns. Du har ${Updater.currentVersionName(this)}.$notes")
            .setPositiveButton("Installera") { _, _ -> startInstall(update) }
            .setNegativeButton("Senare", null)
            .show()
    }

    private fun startInstall(update: AppUpdate) {
        if (!Updater.canInstall(this)) {
            updateWaitingForPermission = update
            AlertDialog.Builder(this)
                .setTitle("Ett sista steg")
                .setMessage(
                    "Android behöver ditt lov för att appen ska få installera sina egna uppdateringar. " +
                        "Det behövs bara första gången.\n\n" +
                        "1. Tryck på \"Öppna inställningar\".\n" +
                        "2. Slå på \"Tillåt från den här källan\".\n" +
                        "3. Gå tillbaka hit, så fortsätter uppdateringen.",
                )
                .setPositiveButton("Öppna inställningar") { _, _ -> startActivity(Updater.installPermissionIntent(this)) }
                .setNegativeButton("Avbryt") { _, _ -> updateWaitingForPermission = null }
                .show()
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("Hämtar version ${update.versionName}…")
            .setMessage("0 %")
            .setCancelable(false)
            .show()
        thread {
            try {
                val apk = Updater.download(this, update) { part ->
                    runOnUiThread { progress.setMessage("${(part * 100).toInt()} %") }
                }
                Updater.install(this, apk)
                runOnUiThread { progress.dismiss() }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    message("Det gick inte", e.message ?: "Uppdateringen kunde inte hämtas.")
                }
            }
        }
    }

    /**
     * Bara för test i utvecklingsversionen: installerar filen updates/test.apk
     * i appens cache, så att installationsflödet kan provas utan att publicera.
     */
    private fun handleTestInstall(intent: Intent?) {
        if (intent?.action != ACTION_TEST_INSTALL || !Updater.isDebugBuild(this)) return
        val apk = File(cacheDir, "updates/test.apk")
        if (!apk.exists()) return message("Test", "Hittar ingen ${apk.path}")
        thread { Updater.install(this, apk) }
    }

    private fun message(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
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

    companion object {
        private const val ACTION_TEST_INSTALL = "se.minapp.filmtajm.TEST_INSTALL"

        /** Kolla efter uppdatering högst så här ofta när appen visas. */
        private const val AUTO_CHECK_INTERVAL = 10 * 60_000L
        private var lastAutoCheck = 0L
    }
}
