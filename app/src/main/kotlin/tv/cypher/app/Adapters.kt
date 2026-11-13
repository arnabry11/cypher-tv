package tv.cypher.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.cypher.core.Channel
import java.text.DateFormat
import java.util.Date

/** Channel rows: number, name, Now / Next, favourite star. Selection is drawn by the adapter, not by view focus. */
class ChannelAdapter(
    private val guide: GuideDb,
    private val time: DateFormat,
    private val isFavourite: (String) -> Boolean,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    var rows: List<Channel> = emptyList()
    var selected = 0
    var moveMode = false

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val next: TextView = v.findViewById(R.id.next)
        val star: TextView = v.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.row_channel, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = rows[position]
        h.num.text = ch.num.toString()
        h.name.text = ch.name
        h.star.visibility = if (isFavourite(ch.id)) View.VISIBLE else View.INVISIBLE
        val nowSec = System.currentTimeMillis() / 1000
        val (now, next) = guide.nowNext(ch.id, nowSec)
        h.now.text = now?.let { "${time.format(Date(it.start * 1000))}  ${it.title}" }.orEmpty()
        h.next.text = next?.let { "${time.format(Date(it.start * 1000))}  ${it.title}" }.orEmpty()
        h.itemView.setBackgroundColor(
            when {
                position != selected -> Color.TRANSPARENT
                moveMode -> 0xFFB8860B.toInt()
                else -> 0xFF0B7285.toInt()
            },
        )
    }
}

class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {
    class Row(val text: String, val current: Boolean)

    var rows: List<Row> = emptyList()

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            textSize = 20f
            setPadding(32, 12, 24, 12)
            maxLines = 2
        }
        return VH(tv)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.tv.text = r.text
        h.tv.setTextColor(if (r.current) 0xFFFFFFFF.toInt() else 0xFFA9BAC4.toInt())
        h.tv.setBackgroundColor(if (r.current) 0xFF0B7285.toInt() else Color.TRANSPARENT)
    }
}
