package tv.cypher.tools

import tv.cypher.core.Channel
import tv.cypher.core.Epg
import java.io.InputStream
import java.io.OutputStream

/** Converts an XMLTV file into the compact guide the TV downloads: today and tomorrow, IST. */
object BuildEpg {
    private const val IST = 19800L
    private const val DAY = 86400L

    fun startOfIstDay(now: Long): Long = Math.floorDiv(now + IST, DAY) * DAY - IST

    /** Returns how many channels have guide data. */
    fun convert(xmltv: InputStream, channels: List<Channel>, now: Long, out: OutputStream): Int {
        val from = startOfIstDay(now)
        val data = Xmltv.fromXmltv(xmltv, channels.map { it.id }.toSet(), from, from + 2 * DAY)
        Epg.writeGzip(data, out)
        return data.size
    }
}
