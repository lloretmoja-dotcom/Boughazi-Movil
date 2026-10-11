package tv.boughazi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cargador de logos muy sencillo (sin librerías externas): descarga
 * la imagen una vez y la guarda en memoria para no volver a
 * descargarla cada vez que aparece el mismo canal.
 *
 * En el móvil los logos salen también en la lista de canales (no solo
 * en el cartel de abajo, como en la tele), así que aquí se cuidan
 * algunas cosas más:
 *  - al hacer scroll, una fila reutilizada nunca enseña el logo de otro
 *    canal (cada imagen comprueba que su fila sigue siendo la suya);
 *  - se siguen las redirecciones de http:// a https:// (muchas webs de
 *    logos lo hacen y Android no lo hace solo);
 *  - se manda un "User-Agent" normal (algunas webs, como Wikipedia,
 *    rechazan las descargas sin él);
 *  - los logos enormes se reducen al cargarlos, para que el móvil no
 *    se quede sin memoria con listas de cientos de canales.
 */
object ImageLoader {
    private const val MAX_SIDE_PX = 256
    private val cache = HashMap<String, Bitmap>()

    fun load(scope: CoroutineScope, url: String?, into: ImageView) {
        into.tag = url
        if (url.isNullOrBlank()) {
            into.setImageDrawable(null)
            return
        }
        cache[url]?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageDrawable(null)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { download(url) }
            if (bitmap != null) {
                cache[url] = bitmap
                if (into.tag == url) into.setImageBitmap(bitmap)
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val bytes = open(url, 0)?.use { it.readBytes() }
        if (bytes == null) null else decode(bytes)
    } catch (e: Throwable) {
        null
    }

    private fun open(url: String, redirects: Int): java.io.InputStream? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BoughaziMovil/1.0")
        val code = conn.responseCode
        if (code in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location") ?: return null
            conn.disconnect()
            return open(URL(URL(url), next).toString(), redirects + 1)
        }
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        return conn.inputStream
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE_PX && bounds.outHeight / (sample * 2) >= MAX_SIDE_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
