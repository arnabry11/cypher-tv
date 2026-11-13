package tv.cypher.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.cypher.core.Channel
import tv.cypher.core.NowNext
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
        val progress: ProgressBar = v.findViewById(R.id.progress)
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
        bindLine(h.now, now?.title)
        h.progress.visibility = if (now == null) View.GONE else View.VISIBLE
        if (now != null) h.progress.progress = NowNext.percent(now, nowSec)
        bindLine(h.next, next?.let { "${time.format(Date(it.start * 1000))}  ${it.title}" })
        h.itemView.setBackgroundColor(
            when {
                position != selected -> Color.TRANSPARENT
                moveMode -> 0xFFB8860B.toInt()
                else -> 0xFF0B7285.toInt()
            },
        )
    }
}

private fun bindLine(view: TextView, text: String?) {
    view.text = text.orEmpty()
    view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
}

/** The highlighted channel's programmes from now on; the one on air shows how far along it is. */
class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {
    /** [percent] and [till] are set only for the programme on air. */
    class Row(val text: String, val percent: Int? = null, val till: String? = null)

    var rows: List<Row> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.text)
        val progressRow: View = v.findViewById(R.id.progressRow)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val till: TextView = v.findViewById(R.id.till)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.row_schedule, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        val current = r.percent != null
        h.text.text = r.text
        h.text.setTextColor(if (current) 0xFFFFFFFF.toInt() else 0xFFA9BAC4.toInt())
        h.itemView.setBackgroundColor(if (current) 0x550B7285 else Color.TRANSPARENT)
        h.progressRow.visibility = if (current) View.VISIBLE else View.GONE
        h.progress.progress = r.percent ?: 0
        h.till.text = r.till.orEmpty()
    }
}
