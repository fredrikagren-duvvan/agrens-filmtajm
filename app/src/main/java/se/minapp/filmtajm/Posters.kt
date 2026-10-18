package se.minapp.filmtajm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/**
 * Laddar affischer från TMDB i bakgrunden. Bilderna sparas i minnet medan
 * appen är öppen och på disk via HttpResponseCache (installeras i MainActivity).
 */
object Posters {

    private val memory = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?) {
        view.tag = url
        view.setImageDrawable(null)
        if (url == null) return
        memory.get(url)?.let { return view.setImageBitmap(it) }
        pool.execute {
            val bitmap = runCatching {
                URL(url).openConnection().apply { useCaches = true }.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            memory.put(url, bitmap)
            // Raden kan ha fått en annan titel medan bilden hämtades.
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }
}
