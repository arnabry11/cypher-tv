package tv.cypher.app

import android.content.Context

/** Everything small the app remembers: favourites order, last channel, last-good stream per channel. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cypher", Context.MODE_PRIVATE)

    var favourites: List<String>
        get() = sp.getString("fav", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("fav", v.joinToString("\n")).apply()

    var lastChannel: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    fun lastGood(channelId: String): Int = sp.getInt("good:$channelId", -1)

    fun setLastGood(channelId: String, index: Int) {
        if (lastGood(channelId) != index) sp.edit().putInt("good:$channelId", index).apply()
    }

    fun etag(url: String): String? = sp.getString("etag:$url", null)

    fun setEtag(url: String, etag: String?) = sp.edit().putString("etag:$url", etag).apply()
}
