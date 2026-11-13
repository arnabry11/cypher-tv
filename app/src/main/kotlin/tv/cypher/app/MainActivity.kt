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
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.cypher.core.Channel
import tv.cypher.core.Favourites
import tv.cypher.core.Numbering
import tv.cypher.core.NumberEntry
import tv.cypher.core.Programme
import tv.cypher.core.Zap
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : Activity(), PlayerController.Listener {
    private enum class Mode { PLAYER, LIST, SCHEDULE }

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
    private lateinit var tabLabel: TextView
    private lateinit var listView: RecyclerView
    private lateinit var schedulePanel: View
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
    private var inTabs = false
    private var currentId: String? = null
    private var mode = Mode.PLAYER
    private val numberEntry = NumberEntry()
    private var centerDownInList = false
    private var longPressHandled = false
    private var updateOffered = false
    private var updateInProgress = false
    /** A downloaded, verified APK waiting for the user to allow installs from this app. */
    private var pendingApk: java.io.File? = null

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val commitNumber = Runnable { commitNumberEntry() }
    private val hideMessage = Runnable { messageView.visibility = View.GONE }
    private val refreshLoop = object : Runnable {
        override fun run() { refreshAsync(); ui.postDelayed(this, REFRESH_EVERY_MS) }
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
        tabLabel = findViewById(R.id.tabLabel)
        listView = findViewById(R.id.list)
        schedulePanel = findViewById(R.id.schedulePanel)
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
        val first = favourites.ids.firstOrNull() ?: prefs.lastChannel?.takeIf { it in byId }
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
            if (!Updater.install(this, apk)) pendingApk = apk
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
        if (channels.isNotEmpty()) favourites = Favourites(prefs.favourites.filter { it in byId })
        val present = channels.map { it.lang }.toSet()
        val ordered = Numbering.languages.filter { it in present } + (present - Numbering.languages.toSet()).sorted()
        tabs = listOf(Tab("Favourites", null), Tab("All", null)) + ordered.map { Tab(it, it) }
        tabIndex = tabIndex.coerceIn(0, tabs.lastIndex)
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
                r.channels?.takeIf { it != channels }?.let { applyCatalog(it) }
                if (currentId == null && channels.isNotEmpty() && mode == Mode.PLAYER) {
                    val first = favourites.ids.firstOrNull() ?: channels.first().id
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
        prefs.lastChannel = ch.id
        messageView.visibility = View.GONE
        player.play(ch, prefs.lastGood(ch.id))
        if (showBanner) showBanner(ch)
    }

    private fun zapList(): List<String> {
        val favs = favourites.ids.filter { it in byId }
        return favs.ifEmpty { channels.map { it.id } }
    }

    private fun zap(forward: Boolean) {
        val list = zapList()
        val id = (if (forward) Zap.next(list, currentId) else Zap.prev(list, currentId)) ?: return
        byId[id]?.let { tune(it) }
    }

    override fun onPlaying(channel: Channel, streamIndex: Int) {
        prefs.setLastGood(channel.id, streamIndex)
        if (messageView.tag == MSG_STATUS) messageView.visibility = View.GONE
    }

    override fun onBuffering(buffering: Boolean) {
        if (buffering) showMessage("Buffering…", 0, MSG_STATUS)
        else if (messageView.tag == MSG_STATUS) messageView.visibility = View.GONE
    }

    override fun onFailed(channel: Channel) {
        showMessage("Channel unavailable", 0, MSG_STATUS)
    }

    // ---------------------------------------------------------------- banner and messages

    private fun showBanner(ch: Channel) {
        fillBanner(ch)
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, BANNER_MS)
    }

    private fun fillBanner(ch: Channel) {
        bannerTitle.text = "${ch.num}   ${ch.name}"
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

    // ---------------------------------------------------------------- list overlay

    private fun openList() {
        if (channels.isEmpty()) return
        mode = Mode.LIST
        inTabs = false
        tabIndex = if (favourites.ids.isEmpty()) 1 else 0
        listPanel.visibility = View.VISIBLE
        schedulePanel.visibility = View.GONE
        banner.visibility = View.GONE
        refreshList()
        val at = rowsForTab().indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        select(at)
    }

    private fun closeList() {
        channelAdapter.moveMode = false
        mode = Mode.PLAYER
        listPanel.visibility = View.GONE
        schedulePanel.visibility = View.GONE
    }

    private fun refreshList() {
        val rows = rowsForTab()
        channelAdapter.rows = rows
        channelAdapter.selected = channelAdapter.selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        channelAdapter.notifyDataSetChanged()
        val tab = tabs[tabIndex]
        tabLabel.text = "◀   ${tab.title} (${rows.size})   ▶"
        tabLabel.setBackgroundColor(if (inTabs) 0xFF0B7285.toInt() else Color.TRANSPARENT)
    }

    private fun select(index: Int) {
        val old = channelAdapter.selected
        channelAdapter.selected = index.coerceIn(0, (channelAdapter.rows.size - 1).coerceAtLeast(0))
        channelAdapter.notifyItemChanged(old)
        channelAdapter.notifyItemChanged(channelAdapter.selected)
        listView.scrollToPosition(channelAdapter.selected)
    }

    private fun selectedChannel(): Channel? = channelAdapter.rows.getOrNull(channelAdapter.selected)

    private fun changeTab(delta: Int) {
        tabIndex = (tabIndex + delta + tabs.size) % tabs.size
        channelAdapter.selected = 0
        refreshList()
    }

    private fun onListKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when {
                inTabs -> Unit
                channelAdapter.moveMode -> moveSelected(-1)
                channelAdapter.selected > 0 -> select(channelAdapter.selected - 1)
                else -> { inTabs = true; refreshList() }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                inTabs -> { inTabs = false; refreshList() }
                channelAdapter.moveMode -> moveSelected(+1)
                else -> select(channelAdapter.selected + 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inTabs) changeTab(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inTabs) changeTab(+1) else openSchedule()
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> select(channelAdapter.selected - 8)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> select(channelAdapter.selected + 8)
            else -> return false
        }
        return true
    }

    private fun moveSelected(delta: Int) {
        val to = favourites.move(channelAdapter.selected, delta)
        saveFavourites()
        channelAdapter.selected = to
        channelAdapter.rows = rowsForTab()
        channelAdapter.notifyDataSetChanged()
        listView.scrollToPosition(to)
    }

    private fun activateSelected() {
        if (inTabs) { inTabs = false; refreshList(); return }
        if (channelAdapter.moveMode) {
            channelAdapter.moveMode = false
            channelAdapter.notifyItemChanged(channelAdapter.selected)
            return
        }
        val ch = selectedChannel() ?: return
        closeList()
        tune(ch)
    }

    /** Long-press OK on a row. */
    private fun onRowLongPress() {
        val ch = selectedChannel() ?: return
        if (channelAdapter.moveMode || inTabs) return
        if (tabs[tabIndex].title == "Favourites") {
            AlertDialog.Builder(this)
                .setTitle(ch.name)
                .setItems(arrayOf("Move", "Remove from favourites")) { _, which ->
                    if (which == 0) {
                        channelAdapter.moveMode = true
                        channelAdapter.notifyItemChanged(channelAdapter.selected)
                    } else {
                        favourites.remove(ch.id)
                        saveFavourites()
                        refreshList()
                    }
                }
                .show()
        } else {
            favourites.toggle(ch.id)
            saveFavourites()
            val on = ch.id in favourites
            Toast.makeText(this, if (on) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
            channelAdapter.notifyItemChanged(channelAdapter.selected)
        }
    }

    // ---------------------------------------------------------------- schedule

    private fun openSchedule() {
        val ch = selectedChannel() ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = cal.timeInMillis / 1000
        val now = System.currentTimeMillis() / 1000
        val items = guide.schedule(ch.id, from, from + 86400)
        scheduleTitle.text = ch.name
        scheduleAdapter.rows = if (items.isEmpty()) listOf(ScheduleAdapter.Row("No guide for this channel", false))
        else items.map { ScheduleAdapter.Row("${time.format(Date(it.start * 1000))}  ${it.title}", it.start <= now && now < it.stop) }
        scheduleAdapter.notifyDataSetChanged()
        schedulePanel.visibility = View.VISIBLE
        mode = Mode.SCHEDULE
        scheduleView.scrollToPosition(scheduleAdapter.rows.indexOfFirst { it.current }.coerceAtLeast(0))
    }

    private fun closeSchedule() {
        schedulePanel.visibility = View.GONE
        mode = Mode.LIST
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
        val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        when (mode) {
            Mode.PLAYER -> when (keyCode) {
                // Up / CH+ go to the next channel, Down / CH- to the previous one.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(false); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> { openList(); return true }
                KeyEvent.KEYCODE_INFO -> { byId[currentId]?.let { showBanner(it) }; return true }
                else -> if (isOk) { openList(); return true }
            }
            Mode.LIST -> {
                if (isOk) {
                    if (event.repeatCount == 0) { centerDownInList = true; event.startTracking() }
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (channelAdapter.moveMode) {
                        channelAdapter.moveMode = false
                        channelAdapter.notifyItemChanged(channelAdapter.selected)
                    } else closeList()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) { closeList(); return true }
                if (onListKey(keyCode)) return true
            }
            Mode.SCHEDULE -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> scheduleView.scrollBy(0, -SCROLL_PX)
                    KeyEvent.KEYCODE_DPAD_DOWN -> scheduleView.scrollBy(0, SCROLL_PX)
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> closeSchedule()
                    else -> if (!isOk) return super.onKeyDown(keyCode, event)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == Mode.LIST && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            longPressHandled = true
            onRowLongPress()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == Mode.LIST && centerDownInList &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            centerDownInList = false
            if (!longPressHandled) activateSelected()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- self-update

    private fun offerUpdate(info: tv.cypher.core.AppInfo) {
        if (updateOffered || updateInProgress) return
        updateOffered = true
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(info.notes.ifBlank { "A new version of Cypher TV is available." })
            .setPositiveButton("Install") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
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
                    if (!Updater.install(this, apk)) {
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
        private const val SCROLL_PX = 240
        private const val MSG_STATUS = "status"
        private const val MSG_NOTE = "note"
        private const val MSG_UPDATE = "update"
    }
}
