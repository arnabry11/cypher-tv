package tv.cypher.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.cypher.core.Channel
import tv.cypher.core.Favourites
import tv.cypher.core.Numbering
import tv.cypher.core.NowNext
import tv.cypher.core.NumberEntry
import tv.cypher.core.Programme
import tv.cypher.core.QualityMemory
import tv.cypher.core.Stream
import tv.cypher.core.RetryBackoff
import tv.cypher.core.Zap
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : Activity(), PlayerController.Listener {
    private enum class Mode { PLAYER, LIST }

    private class Tab(val title: String, val language: String?)

    private lateinit var prefs: Prefs
    private lateinit var guide: GuideDb
    private lateinit var repo: Repo
    private lateinit var player: PlayerController
    private lateinit var time: DateFormat

    private lateinit var numberEntryView: TextView
    private lateinit var messageView: TextView
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerNow: TextView
    private lateinit var bannerNext: TextView
    private lateinit var listPanel: View
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var listView: RecyclerView
    private lateinit var listEmpty: TextView
    private lateinit var hints: TextView
    private lateinit var scheduleTitle: TextView
    private lateinit var scheduleView: RecyclerView

    private lateinit var channelAdapter: ChannelAdapter
    private val scheduleAdapter = ScheduleAdapter()

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var channels: List<Channel> = emptyList()
    private var byId: Map<String, Channel> = emptyMap()
    private var byNumber: Map<Int, String> = emptyMap()
    private var favourites = Favourites()
    private var tabs: List<Tab> = emptyList()
    private var tabIndex = 0
    private var currentId: String? = null
    private var mode = Mode.PLAYER
    private val numberEntry = NumberEntry()
    private var okDown = false
    private var longPressHandled = false
    /** "Switching to 720p": shown instead of plain "Buffering…" while the new stream starts. */
    private var switchText: String? = null
    private var lastBackAt = -BACK_AGAIN_MS
    private var updateOffered = false
    private var updateInProgress = false
    /** A downloaded, verified APK waiting for the user to allow installs from this app. */
    private var pendingApk: java.io.File? = null

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val commitNumber = Runnable { commitNumberEntry() }
    private val hideMessage = Runnable { messageView.visibility = View.GONE }
    private val backoff = RetryBackoff()
    private val refreshLoop = Runnable { refreshAsync() }
    private val showSchedule = Runnable { fillSchedule() }
    /** While the guide is open, keeps Now / Next and the progress bars moving. */
    private val tick = object : Runnable {
        override fun run() {
            if (mode != Mode.LIST) return
            channelAdapter.notifyDataSetChanged()
            fillSchedule()
            ui.postDelayed(this, TICK_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        guide = GuideDb(this)
        repo = Repo(this, prefs, guide)
        time = android.text.format.DateFormat.getTimeFormat(this)

        numberEntryView = findViewById(R.id.numberEntry)
        messageView = findViewById(R.id.message)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerNow = findViewById(R.id.bannerNow)
        bannerNext = findViewById(R.id.bannerNext)
        listPanel = findViewById(R.id.listPanel)
        tabScroll = findViewById(R.id.tabScroll)
        // A scroll view makes itself focusable whatever the layout says, and would then swallow Left / Right.
        tabScroll.isFocusable = false
        tabScroll.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        tabStrip = findViewById(R.id.tabStrip)
        listView = findViewById(R.id.list)
        listEmpty = findViewById(R.id.listEmpty)
        hints = findViewById(R.id.hints)
        scheduleTitle = findViewById(R.id.scheduleTitle)
        scheduleView = findViewById(R.id.schedule)

        channelAdapter = ChannelAdapter(guide, time) { it in favourites }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = channelAdapter
        listView.itemAnimator = null
        scheduleView.layoutManager = LinearLayoutManager(this)
        scheduleView.adapter = scheduleAdapter
        scheduleView.itemAnimator = null

        player = PlayerController(this, findViewById<SurfaceView>(R.id.surface), this)

        applyCatalog(repo.loadCachedChannels())
        val first = favourites.ids.firstOrNull { it in byId } ?: prefs.lastChannel?.takeIf { it in byId }
        if (first != null) {
            tune(byId.getValue(first))
        } else if (channels.isNotEmpty()) {
            openList()
        } else {
            showMessage("Loading channels…", 0)
        }
        ui.post(refreshLoop)
    }

    override fun onResume() {
        super.onResume()
        pendingApk?.let { apk ->
            pendingApk = null
            if (Updater.canInstall(this)) install(apk)
            else {
                // The user came back without allowing it: don't send them to Settings again.
                updateOffered = false
                showMessage("Update skipped: installs are not allowed for Cypher TV", 5000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val id = currentId
        if (!player.hasPlayer && id != null) byId[id]?.let { tune(it, showBanner = false) }
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        player.release()
        guide.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- catalog

    private fun applyCatalog(list: List<Channel>) {
        channels = list.sortedBy { it.num }
        byId = channels.associateBy { it.id }
        byNumber = channels.associate { it.num to it.id }
        // Keep favourites whose channel is missing from today's list: they are hidden, not forgotten, so a
        // channel that drops out for a day is still a favourite (in the same place) when it comes back.
        favourites = Favourites(prefs.favourites)
        val present = channels.map { it.lang }.toSet()
        val ordered = Numbering.languages.filter { it in present } + (present - Numbering.languages.toSet()).sorted()
        tabs = listOf(Tab("Favourites", null), Tab("All", null)) + ordered.map { Tab(it, it) }
        tabIndex = tabIndex.coerceIn(0, tabs.lastIndex)
        buildTabStrip()
        if (mode != Mode.PLAYER) refreshList()
    }

    private fun rowsForTab(): List<Channel> {
        val tab = tabs.getOrNull(tabIndex) ?: return emptyList()
        return when {
            tab.title == "Favourites" -> favourites.ids.mapNotNull { byId[it] }
            tab.language == null -> channels
            else -> channels.filter { it.lang == tab.language }
        }
    }

    private fun saveFavourites() { prefs.favourites = favourites.ids }

    private fun refreshAsync() {
        io.execute {
            val r = repo.refresh()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // Retry soon after a failure (e.g. Wi-Fi not up yet at boot), normally every few hours.
                ui.postDelayed(refreshLoop, backoff.next(r.ok, REFRESH_EVERY_MS))
                r.channels?.takeIf { it != channels }?.let { applyCatalog(it) }
                if (currentId == null && channels.isNotEmpty() && mode == Mode.PLAYER) {
                    val first = favourites.ids.firstOrNull { it in byId } ?: channels.first().id
                    byId[first]?.let { tune(it) }
                    messageView.visibility = View.GONE
                }
                if (r.guideUpdated) {
                    channelAdapter.notifyDataSetChanged()
                    if (banner.visibility == View.VISIBLE) currentId?.let { id -> byId[id]?.let { fillBanner(it) } }
                }
                r.update?.let { offerUpdate(it) }
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun tune(ch: Channel, showBanner: Boolean = true) {
        currentId = ch.id
        switchText = null
        prefs.lastChannel = ch.id
        messageView.visibility = View.GONE
        val start = QualityMemory.decode(prefs.qualityMemory(ch.id), System.currentTimeMillis())
        player.play(ch, start)
        if (showBanner) showBanner(ch)
    }

    /** Channel up/down always steps through every channel in numeric order, whether or not it is a favourite. */
    private fun zap(forward: Boolean) {
        val list = channels.map { it.id }
        val id = (if (forward) Zap.next(list, currentId) else Zap.prev(list, currentId)) ?: return
        byId[id]?.let { tune(it) }
    }

    override fun onPlaying(channel: Channel, streamIndex: Int, downgraded: Boolean) {
        // Remember a step-down for a while so zapping back does not repeat the stall; it expires, so the best
        // quality is tried again later.
        switchText = null
        if (downgraded) prefs.setQualityMemory(channel.id, QualityMemory.encode(streamIndex, System.currentTimeMillis()))
        if (messageView.tag == MSG_STATUS) messageView.visibility = View.GONE
    }

    override fun onBuffering(buffering: Boolean) {
        if (buffering) showMessage(switchText ?: "Buffering…", 0, MSG_STATUS)
        else if (messageView.tag == MSG_STATUS) messageView.visibility = View.GONE
    }

    override fun onSwitching(channel: Channel, stream: Stream) {
        switchText = stream.h?.let { "Switching to ${it}p…" } ?: "Switching stream…"
        showMessage(switchText!!, 0, MSG_STATUS)
    }

    override fun onFailed(channel: Channel) {
        showMessage("This channel is not playable", 0, MSG_STATUS)
    }

    // ---------------------------------------------------------------- banner and messages

    private fun showBanner(ch: Channel) {
        fillBanner(ch)
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, BANNER_MS)
    }

    private fun fillBanner(ch: Channel) {
        bannerTitle.text = "${ch.num}   ${ch.name}" + if (ch.id in favourites) "   ★" else ""
        val (now, next) = guide.nowNext(ch.id, System.currentTimeMillis() / 1000)
        bannerNow.text = now?.let { "Now   ${range(it)}   ${it.title}" }.orEmpty()
        bannerNext.text = next?.let { "Next  ${time.format(Date(it.start * 1000))}   ${it.title}" }.orEmpty()
    }

    private fun range(p: Programme) = "${time.format(Date(p.start * 1000))}–${time.format(Date(p.stop * 1000))}"

    private fun showMessage(text: String, forMs: Long, tag: String = MSG_NOTE) {
        messageView.text = text
        messageView.tag = tag
        messageView.visibility = View.VISIBLE
        ui.removeCallbacks(hideMessage)
        if (forMs > 0) ui.postDelayed(hideMessage, forMs)
    }

    // ---------------------------------------------------------------- number entry

    private fun pressDigit(d: Int) {
        numberEntry.press(d)
        numberEntryView.text = numberEntry.text
        numberEntryView.visibility = View.VISIBLE
        ui.removeCallbacks(commitNumber)
        ui.postDelayed(commitNumber, NUMBER_COMMIT_MS)
    }

    private fun commitNumberEntry() {
        numberEntryView.visibility = View.GONE
        val n = numberEntry.commit() ?: return
        val id = NumberEntry.resolve(n, byNumber)
        if (id == null) showMessage("No channel $n", 2000) else {
            closeList()
            byId[id]?.let { tune(it) }
        }
    }

    // ---------------------------------------------------------------- guide (channel list + schedule)

    private fun buildTabStrip() {
        tabStrip.removeAllViews()
        val pad = (resources.displayMetrics.density * 16).toInt()
        for (tab in tabs) {
            tabStrip.addView(TextView(this).apply {
                text = if (tab.title == "Favourites") "★ Favourites" else tab.title
                textSize = 20f
                setPadding(pad, pad / 3, pad, pad / 3)
            })
        }
    }

    /** Opens the guide on the channel being watched: in Favourites when it is one, otherwise in All. */
    private fun openList() {
        if (channels.isEmpty()) return
        mode = Mode.LIST
        val current = currentId
        tabIndex = if (current != null && current in favourites || current == null && favourites.ids.any { it in byId }) 0 else 1
        listPanel.visibility = View.VISIBLE
        banner.visibility = View.GONE
        refreshList()
        select(rowsForTab().indexOfFirst { it.id == current }.coerceAtLeast(0))
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, TICK_MS)
    }

    private fun closeList() {
        channelAdapter.moveMode = false
        mode = Mode.PLAYER
        listPanel.visibility = View.GONE
        ui.removeCallbacks(tick)
        ui.removeCallbacks(showSchedule)
    }

    private fun onFavouritesTab() = tabs.getOrNull(tabIndex)?.title == "Favourites"

    private fun refreshList() {
        val rows = rowsForTab()
        channelAdapter.rows = rows
        channelAdapter.selected = channelAdapter.selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        channelAdapter.notifyDataSetChanged()
        for (i in 0 until tabStrip.childCount) {
            val v = tabStrip.getChildAt(i) as TextView
            val on = i == tabIndex
            v.setTextColor(if (on) Color.WHITE else 0xFF9FB3BF.toInt())
            v.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            v.setBackgroundColor(if (on) 0xFF0B7285.toInt() else Color.TRANSPARENT)
        }
        tabStrip.getChildAt(tabIndex)?.let { v ->
            tabScroll.post { tabScroll.smoothScrollTo((v.left - tabScroll.width / 3).coerceAtLeast(0), 0) }
        }
        listEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        listEmpty.text = if (onFavouritesTab()) "No favourites yet.\nHold OK on any channel to add it." else "No channels here."
        updateHints()
        scheduleSoon()
    }

    private fun updateHints() {
        hints.text = when {
            channelAdapter.moveMode -> "▲ ▼  Move        OK  Done"
            onFavouritesTab() -> "OK  Watch        Hold OK  Move / Remove        ◀ ▶  Category        Back  Close"
            else -> "OK  Watch        Hold OK  ★ Favourite        ◀ ▶  Category        Back  Close"
        }
    }

    private fun select(index: Int) {
        val old = channelAdapter.selected
        channelAdapter.selected = index.coerceIn(0, (channelAdapter.rows.size - 1).coerceAtLeast(0))
        channelAdapter.notifyItemChanged(old)
        channelAdapter.notifyItemChanged(channelAdapter.selected)
        // Keep a row of context above and below the highlight instead of pinning it to the edge.
        val lm = listView.layoutManager as LinearLayoutManager
        val at = channelAdapter.selected
        if (at <= lm.findFirstCompletelyVisibleItemPosition()) listView.scrollToPosition((at - 1).coerceAtLeast(0))
        else if (at >= lm.findLastCompletelyVisibleItemPosition()) listView.scrollToPosition((at + 1).coerceAtMost(channelAdapter.rows.size - 1))
        scheduleSoon()
    }

    private fun selectedChannel(): Channel? = channelAdapter.rows.getOrNull(channelAdapter.selected)

    private fun changeTab(delta: Int) {
        tabIndex = (tabIndex + delta + tabs.size) % tabs.size
        channelAdapter.selected = 0
        refreshList()
        // Land on the channel being watched when the new tab has it.
        val at = channelAdapter.rows.indexOfFirst { it.id == currentId }
        if (at > 0) select(at) else listView.scrollToPosition(0)
    }

    private fun onListKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (channelAdapter.moveMode) moveSelected(-1) else select(channelAdapter.selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (channelAdapter.moveMode) moveSelected(+1) else select(channelAdapter.selected + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!channelAdapter.moveMode) changeTab(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!channelAdapter.moveMode) changeTab(+1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (!channelAdapter.moveMode) select(channelAdapter.selected - 6)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (!channelAdapter.moveMode) select(channelAdapter.selected + 6)
            else -> return false
        }
        return true
    }

    private fun moveSelected(delta: Int) {
        val ch = selectedChannel() ?: return
        favourites.moveAmongShown(ch.id, delta) { it in byId }
        saveFavourites()
        channelAdapter.rows = rowsForTab()
        val to = channelAdapter.rows.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        channelAdapter.selected = to
        channelAdapter.notifyDataSetChanged()
        listView.scrollToPosition(to)
    }

    private fun setMoveMode(on: Boolean) {
        channelAdapter.moveMode = on
        channelAdapter.notifyItemChanged(channelAdapter.selected)
        updateHints()
    }

    private fun activateSelected() {
        if (channelAdapter.moveMode) { setMoveMode(false); return }
        val ch = selectedChannel() ?: return
        closeList()
        tune(ch)
    }

    /** Long-press OK on a row. */
    private fun onRowLongPress() {
        val ch = selectedChannel() ?: return
        if (channelAdapter.moveMode) return
        if (onFavouritesTab()) {
            AlertDialog.Builder(this)
                .setTitle(ch.name)
                .setItems(arrayOf("Move", "Remove from favourites")) { _, which ->
                    if (which == 0) setMoveMode(true) else {
                        favourites.remove(ch.id)
                        saveFavourites()
                        refreshList()
                    }
                }
                .show()
        } else {
            toggleFavourite(ch)
            channelAdapter.notifyItemChanged(channelAdapter.selected)
            fillSchedule()
        }
    }

    /** Adds or removes [ch] and says so. */
    private fun toggleFavourite(ch: Channel) {
        favourites.toggle(ch.id)
        saveFavourites()
        showMessage(if (ch.id in favourites) "★  ${ch.name} added to favourites" else "${ch.name} removed from favourites", 2500)
    }

    // ---------------------------------------------------------------- schedule pane

    /** Fills the schedule shortly after the highlight settles, so holding Up/Down does not query for every row passed. */
    private fun scheduleSoon() {
        ui.removeCallbacks(showSchedule)
        ui.postDelayed(showSchedule, SCHEDULE_DELAY_MS)
    }

    private fun fillSchedule() {
        ui.removeCallbacks(showSchedule)
        val ch = selectedChannel()
        if (ch == null) {
            scheduleTitle.text = ""
            scheduleAdapter.rows = emptyList()
            scheduleAdapter.notifyDataSetChanged()
            return
        }
        val now = System.currentTimeMillis() / 1000
        val items = guide.schedule(ch.id, now, now + SCHEDULE_AHEAD_S)
        scheduleTitle.text = "${ch.num}   ${ch.name}" + if (ch.id in favourites) "   ★" else ""
        scheduleAdapter.rows = if (items.isEmpty()) listOf(ScheduleAdapter.Row("No guide for this channel")) else items.map {
            val text = "${time.format(Date(it.start * 1000))}   ${it.title}"
            if (it.start <= now && now < it.stop) {
                ScheduleAdapter.Row(text, NowNext.percent(it, now), "till ${time.format(Date(it.stop * 1000))}")
            } else ScheduleAdapter.Row(text)
        }
        scheduleAdapter.notifyDataSetChanged()
        scheduleView.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) longPressHandled = false
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            pressDigit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            pressDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Acted on at key-up, so a long press (favourite) can be told from a short one.
            if (event.repeatCount == 0) { okDown = true; event.startTracking() }
            return true
        }
        when (mode) {
            Mode.PLAYER -> when (keyCode) {
                // Up / CH+ go to the next channel, Down / CH- to the previous one.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(false); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> { openList(); return true }
                KeyEvent.KEYCODE_INFO -> { byId[currentId]?.let { showBanner(it) }; return true }
                KeyEvent.KEYCODE_BACK -> {
                    // One stray Back must not stop the TV: ask for a second press.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBackAt > BACK_AGAIN_MS) {
                        lastBackAt = now
                        showMessage("Press Back again to exit", BACK_AGAIN_MS)
                        return true
                    }
                }
            }
            Mode.LIST -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (channelAdapter.moveMode) setMoveMode(false) else closeList()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_GUIDE) { closeList(); return true }
                if (onListKey(keyCode)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressHandled = true
            when (mode) {
                Mode.LIST -> onRowLongPress()
                // Watching: favourite (or unfavourite) this channel without opening anything.
                Mode.PLAYER -> byId[currentId]?.let { toggleFavourite(it); showBanner(it) }
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (okDown && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            okDown = false
            if (!longPressHandled) {
                if (mode == Mode.LIST) activateSelected() else openList()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- self-update

    private fun offerUpdate(info: tv.cypher.core.AppInfo) {
        // A debug build is a side-by-side test copy: the published APK is a different app, never an update of it.
        if (BuildConfig.DEBUG || updateOffered || updateInProgress) return
        updateOffered = true
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(info.notes.ifBlank { "A new version of Cypher TV is available." })
            .setPositiveButton("Install") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** As [Updater.install]; null (with a message) when this device has no installer or settings screen to open. */
    private fun install(apk: java.io.File): Boolean? = try {
        Updater.install(this, apk)
    } catch (_: android.content.ActivityNotFoundException) {
        updateOffered = false
        showMessage("This device cannot install the update from here", 6000)
        null
    } catch (_: SecurityException) {
        updateOffered = false
        showMessage("This device cannot install the update from here", 6000)
        null
    }

    private fun startUpdate(info: tv.cypher.core.AppInfo) {
        updateInProgress = true
        showMessage("Downloading update… 0%", 0, MSG_UPDATE)
        io.execute {
            try {
                var last = -1
                val apk = Updater.download(this, info) { pct ->
                    if (pct != last) { last = pct; runOnUiThread { showMessage("Downloading update… $pct%", 0, MSG_UPDATE) } }
                }
                runOnUiThread {
                    updateInProgress = false
                    messageView.visibility = View.GONE
                    if (install(apk) == false) {
                        // Android opened its one-time "install unknown apps" screen; onResume continues from there.
                        pendingApk = apk
                        showMessage("Allow installs for Cypher TV, then press Back", 6000)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateInProgress = false
                    updateOffered = false
                    showMessage("Update failed: ${e.message}", 5000)
                }
            }
        }
    }

    companion object {
        private const val BANNER_MS = 4_000L
        private const val NUMBER_COMMIT_MS = 2_000L
        private const val REFRESH_EVERY_MS = 3 * 60 * 60 * 1000L
        private const val TICK_MS = 30_000L
        private const val SCHEDULE_DELAY_MS = 250L
        private const val SCHEDULE_AHEAD_S = 18 * 3600L
        private const val BACK_AGAIN_MS = 2_500L
        private const val MSG_STATUS = "status"
        private const val MSG_NOTE = "note"
        private const val MSG_UPDATE = "update"
    }
}
