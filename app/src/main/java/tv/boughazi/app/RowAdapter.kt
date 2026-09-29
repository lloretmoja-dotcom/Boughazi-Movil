package tv.boughazi.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

/**
 * Fila simple: icono/bandera opcional, logo del canal opcional (imagen
 * de verdad, no emoji), título y subtítulo opcional.
 * Se usa tanto para la lista de categorías (sin logo) como para la
 * lista de canales dentro de una categoría (con logo).
 */
data class RowItem(
    val flag: String = "",
    val logoUrl: String? = null,
    val title: String,
    val subtitle: String = ""
)

class RowAdapter(
    private val scope: CoroutineScope,
    private var items: List<RowItem>,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.RowViewHolder>() {

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val flag: TextView = view.findViewById(R.id.row_flag)
        val logo: ImageView = view.findViewById(R.id.row_logo)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val item = items[position]
        holder.flag.text = item.flag
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        ImageLoader.load(scope, item.logoUrl, holder.logo)
        holder.itemView.setOnClickListener { onSelect(position) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            ) {
                onSelect(position)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<RowItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
