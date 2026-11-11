package com.rayo.isladinamica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.util.ArrayDeque

class IslandService : Service() {

    companion object {
        var isRunning = false
        private var instance: IslandService? = null
        private const val CHANNEL_ID = "isla_dinamica"
        private const val NOTIF_ID = 1
        private const val AUTO_COLLAPSE_MS = 4000L
        private const val PROGRESS_POLL_MS = 500L
        private const val ANIM_MS = 300L
        private const val MAX_QUEUE = 5
        private const val CLOCK_POLL_MS = 2000L
        private const val COLLAPSED_W_DP = 64f
        private const val COLLAPSED_H_DP = 48f
        private const val EXPANDED_W_DP = 352f
        private const val EXPANDED_CONTENT_DP = 340f

        /** Manual island position offsets (dp, -40..+40), set in MainActivity. */
        const val KEY_OFFSET_X = "island_offset_x_dp"
        const val KEY_OFFSET_Y = "island_offset_y_dp"
        const val OFFSET_RANGE_DP = 40

        /**
         * Shows the island so the user can see where it sits. Used while
         * adjusting the position sliders in MainActivity: the collapsed pill
         * is the size of the camera hole and hard to see, so the island is
         * expanded as a live preview of the new position.
         */
        fun previewPosition() {
            val service = instance ?: return
            if (!service.isExpanded) service.expand()
        }
    }

    private lateinit var windowManager: WindowManager
    private var pillView: View? = null
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var collapsedIcon: ImageView? = null
    private var collapsedDot: View? = null
    private var notifSection: View? = null
    private var musicSection: View? = null
    private var sectionDivider: View? = null
    private var notifIcon: ImageView? = null
    private var notifHeader: TextView? = null
    private var notifText: TextView? = null
    private var replyRow: View? = null
    private var replyInput: EditText? = null
    private var btnSendReply: ImageButton? = null
    private var clockSection: View? = null
    private var albumArt: ImageView? = null
    private var musicTitle: TextView? = null
    private var musicArtist: TextView? = null
    private var musicTime: TextView? = null
    private var seekBar: SeekBar? = null
    private var seeking = false
    private var lastDuration = 0L
    /** Camera cutout size in px when known — the collapsed pill is exactly this size. */
    private var exactCutoutSize: Pair<Int, Int>? = null
    private var btnPrev: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var clockTime: TextView? = null
    private var clockDate: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null
    private var sizeAnimator: ValueAnimator? = null
    private var isAnimating = false

    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private var isPlaying = false
    private var isExpanded = false
    private var showingNotification = false
    private var currentNotif: IslandBridge.Notif? = null
    private val notifQueue = ArrayDeque<IslandBridge.Notif>()
    private var currentTheme: IslandTheme = IslandTheme.BLACK

    // Launcher-aware clock: the status-bar clock is hidden only on the
    // launcher home screen (where the island shows the time) and visible
    // in other apps and in the quick-settings panel. Needs UsageStats
    // access; without it the clock stays hidden while the toggle is on.
    private var usageStatsManager: UsageStatsManager? = null
    private var launcherPackage: String? = null
    private var clockHidden: Boolean? = null
    private val clockPollRunnable = object : Runnable {
        override fun run() {
            updateLauncherClock()
            handler.postDelayed(this, CLOCK_POLL_MS)
        }
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                IslandTheme.KEY_THEME -> applyTheme()
                KEY_OFFSET_X, KEY_OFFSET_Y -> repositionPill()
                ShizukuHelper.KEY_HIDE_ICONS -> {
                    // The hide toggle changed: (re)start or stop the
                    // launcher-aware clock polling and apply it at once.
                    clockHidden = null
                    updateLauncherClock()
                    if (hideIconsEnabled()) startClockPolling() else stopClockPolling()
                }
            }
        }

    // Progress is only polled while the expanded card is visible and music
    // is playing; nothing runs in the background otherwise.
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isPlaying && isExpanded && currentTheme.showProgressBar) {
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateMediaController(controllers ?: emptyList())
        }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            isPlaying = state?.state == PlaybackState.STATE_PLAYING
            updateMusicUi()
            if (isPlaying && isExpanded && currentTheme.showProgressBar) {
                startProgressPolling()
            } else {
                stopProgressPolling()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMusicUi()
        }

        override fun onSessionDestroyed() {
            mediaController = null
            isPlaying = false
            updateMusicUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        currentTheme = IslandTheme.current(this)
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification())
        setupOverlay()
        setupMediaSessions()
        reapplyHideIcons()
        usageStatsManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        launcherPackage = resolveLauncherPackage()
        if (hideIconsEnabled()) startClockPolling()
        IslandBridge.onNotification = { notif -> showNotification(notif) }
        IslandBridge.onNotificationRemoved = { key -> onNotifRemoved(key) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopClockPolling()
        IslandBridge.onNotification = null
        IslandBridge.onNotificationRemoved = null
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        sizeAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // Listener was never registered (e.g. notification access not granted).
        }
        mediaController?.unregisterCallback(mediaCallback)
        pillView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // View was already removed.
            }
        }
        pillView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Theme ----

    /** Re-reads the saved theme and re-styles the live overlay views. */
    private fun applyTheme() {
        currentTheme = IslandTheme.current(this)
        val t = currentTheme
        pillView?.setBackgroundResource(
            if (isExpanded) t.cardBackgroundRes else t.pillBackgroundRes
        )
        notifHeader?.setTextColor(t.titleTextColor)
        notifText?.setTextColor(t.subtitleTextColor)
        musicTitle?.setTextColor(t.titleTextColor)
        musicArtist?.setTextColor(t.subtitleTextColor)
        musicTime?.setTextColor(t.subtitleTextColor)
        sectionDivider?.setBackgroundColor(t.dividerColor)
        btnPrev?.setBackgroundResource(t.controlBackgroundRes)
        btnNext?.setBackgroundResource(t.controlBackgroundRes)
        btnPlayPause?.setBackgroundResource(t.playPauseBackgroundRes)
        seekBar?.progressDrawable = resources.getDrawable(t.progressDrawableRes, null)
        updateExpandedUi()
        if (isPlaying && isExpanded && t.showProgressBar) {
            startProgressPolling()
        } else {
            stopProgressPolling()
        }
    }

    // ---- Foreground notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Isla Dinámica",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.description = "Mantiene la isla dinámica activa"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Isla Dinámica")
            .setContentText("La isla está activa")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    // ---- Overlay ----

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24f)
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.island_pill, null)
        pillView = view
        collapsedView = view.findViewById(R.id.collapsedView)
        expandedView = view.findViewById(R.id.expandedView)
        collapsedIcon = view.findViewById(R.id.collapsedIcon)
        collapsedDot = view.findViewById(R.id.collapsedDot)
        notifSection = view.findViewById(R.id.notifSection)
        musicSection = view.findViewById(R.id.musicSection)
        sectionDivider = view.findViewById(R.id.sectionDivider)
        notifIcon = view.findViewById(R.id.notifIcon)
        notifHeader = view.findViewById(R.id.notifHeader)
        notifText = view.findViewById(R.id.notifText)
        replyRow = view.findViewById(R.id.replyRow)
        replyInput = view.findViewById(R.id.replyInput)
        btnSendReply = view.findViewById(R.id.btnSendReply)
        clockSection = view.findViewById(R.id.clockSection)
        albumArt = view.findViewById(R.id.albumArt)
        musicTitle = view.findViewById(R.id.musicTitle)
        musicArtist = view.findViewById(R.id.musicArtist)
        musicTime = view.findViewById(R.id.musicTime)
        seekBar = view.findViewById(R.id.seekBar)
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // While dragging, show the target time instead of the live one.
                if (fromUser && lastDuration > 0) {
                    val target = (progress * lastDuration) / 100
                    musicTime?.text = "${formatTime(target)} / ${formatTime(lastDuration)}"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val progress = sb?.progress ?: return
                val duration = lastDuration
                if (duration > 0) {
                    mediaController?.transportControls
                        ?.seekTo((progress * duration) / 100)
                }
            }
        })
        btnPrev = view.findViewById(R.id.btnPrev)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnNext = view.findViewById(R.id.btnNext)
        clockTime = view.findViewById(R.id.tvIslandTime)
        clockDate = view.findViewById(R.id.tvIslandDate)

        // Tap on empty pill area toggles expand/collapse. Taps on the two
        // content sections are consumed by their own listeners below, so
        // they open the app instead of toggling. The clock area at the top
        // (right under the camera) also toggles: it is the "notch" the user
        // taps to minimize the island. It needs its own listener because it
        // is long-clickable (long-press opens the app), which would otherwise
        // swallow the tap before it reaches the root.
        view.setOnClickListener { toggleExpand() }
        clockSection?.setOnClickListener { toggleExpand() }
        // Tap outside the island closes the expanded card. The window gets
        // FLAG_WATCH_OUTSIDE_TOUCH below, so touches outside its bounds
        // arrive here as ACTION_OUTSIDE (and still reach the app underneath).
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && isExpanded) {
                collapse()
                true
            } else {
                false
            }
        }
        notifSection?.setOnClickListener { openCurrentNotif() }
        musicSection?.setOnClickListener { openPlayerApp() }
        btnPlayPause?.setOnClickListener {
            val c = mediaController ?: return@setOnClickListener
            if (isPlaying) c.transportControls.pause() else c.transportControls.play()
        }
        btnPrev?.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        btnNext?.setOnClickListener { mediaController?.transportControls?.skipToNext() }

        // Long-press on the expanded island opens the app (the notification's
        // app, or the music player). Attached to the card itself and to both
        // content sections, so it works anywhere on the card except on the
        // transport buttons and the seek bar (they keep their own touch).
        val openAppLongClick = View.OnLongClickListener {
            if (isExpanded) {
                openCurrentApp()
                true
            } else {
                false
            }
        }
        view.setOnLongClickListener(openAppLongClick)
        notifSection?.setOnLongClickListener(openAppLongClick)
        musicSection?.setOnLongClickListener(openAppLongClick)
        clockSection?.setOnLongClickListener(openAppLongClick)

        // Inline reply: the window is NOT_FOCUSABLE by default, so the
        // keyboard cannot appear. On the first touch of the input the window
        // is made focusable; when the input loses focus it goes back to
        // not-focusable (and collapse() restores it too).
        replyInput?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setWindowFocusable(true)
            }
            false
        }
        replyInput?.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Don't auto-collapse while the user is typing a reply.
                    collapseRunnable?.let { handler.removeCallbacks(it) }
                    collapseRunnable = null
                } else {
                    setWindowFocusable(false)
                }
            }
        replyInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendReply()
                true
            } else {
                false
            }
        }
        btnSendReply?.setOnClickListener { sendReply() }

        // The collapsed pill is exactly the camera hole: its size comes from
        // the display cutout bounding rect (in pixels, no dp conversion, so
        // it stays exact at any density). The root padding is dropped so the
        // window is exactly the rect — no extra margin around the hole.
        cutoutRect()?.let { rect ->
            exactCutoutSize = rect.width() to rect.height()
            val lp = collapsedView?.layoutParams
            if (lp != null) {
                lp.width = rect.width()
                lp.height = rect.height()
                collapsedView?.layoutParams = lp
            }
            view.setPadding(0, 0, 0, 0)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // DynamicSpot does the same on its island window (rg0.d / m0):
        // always allow the window into the display-cutout area, and set
        // privateFlags bit 64 (NO_MOVE_ANIMATION) so updateViewLayout
        // moves while dragging the sliders don't animate.
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            val field = params.javaClass.getField("privateFlags")
            field.isAccessible = true
            field.set(params, (field.get(params) as Int) or 64)
        } catch (e: Exception) {
            // Hidden field; harmless if it disappears on another ROM.
        }
        val (baseX, baseY) = computePillPosition()
        params.x = baseX
        params.y = baseY
        windowManager.addView(view, params)
        applyTheme()
        updateCollapsedUi()
    }

    // ---- Camera-centered positioning ----

    /** The camera cutout rect closest to the screen center, or null. */
    private fun cutoutRect(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val cutout = windowManager.currentWindowMetrics.windowInsets.displayCutout
                ?: return null
            val rects = cutout.boundingRects
            if (rects.isEmpty()) return null
            val screenW = resources.displayMetrics.widthPixels
            rects.minByOrNull { kotlin.math.abs(it.centerX() - screenW / 2) } ?: rects[0]
        } catch (e: Exception) {
            null
        }
    }

    /** Collapsed pill size in px: exactly the camera hole when known. */
    private fun collapsedSizePx(): Pair<Int, Int> {
        return exactCutoutSize ?: (dp(COLLAPSED_W_DP) to dp(COLLAPSED_H_DP))
    }

    /** Center of the camera cutout in screen pixels, or null when unknown. */
    private fun cutoutCenter(): Pair<Int, Int>? {
        val rect = cutoutRect() ?: return null
        return rect.centerX() to rect.centerY()
    }

    /**
     * Pill window position (x offset from screen center, y from the top) so
     * the collapsed pill sits centered on the camera cutout, plus the user's
     * manual X/Y offsets. Falls back to vertically centered in the status
     * bar when the cutout is unknown.
     *
     * Negative Y is allowed on purpose (v1.6): DynamicSpot positions its
     * island at -4dp by default and its popups at -2dp, so the pill can sit
     * slightly above the top edge, overlapping it. v1.5 clamped y >= 0 and
     * that is exactly why the island "wouldn't go up" past the status bar.
     * The slider range (-40..+40dp) now moves the window for real.
     */
    private fun computePillPosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
        val offX = dp(prefs.getInt(KEY_OFFSET_X, 0).toFloat())
        val offY = dp(prefs.getInt(KEY_OFFSET_Y, 0).toFloat())
        val pillH = collapsedSizePx().second
        val center = cutoutCenter()
        val (x, y) = if (center != null) {
            val screenW = resources.displayMetrics.widthPixels
            (center.first - screenW / 2 + offX) to (center.second - pillH / 2 + offY)
        } else {
            offX to ((statusBarHeight() - pillH) / 2 + offY)
        }
        return x to y
    }

    /** Moves the live pill window to the current computed position. */
    private fun repositionPill() {
        val root = pillView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val (x, y) = computePillPosition()
        params.x = x
        params.y = y
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            // Window is gone.
        }
    }

    // ---- Clock in the expanded island ----

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            if (isExpanded) {
                // Next tick at the next minute boundary.
                val delay = 60_000L - (System.currentTimeMillis() % 60_000L)
                handler.postDelayed(this, delay)
            }
        }
    }

    private fun updateClock() {
        val locale = java.util.Locale("es", "MX")
        val now = java.util.Date()
        clockTime?.text =
            java.text.SimpleDateFormat("HH:mm", locale).format(now)
        clockDate?.text =
            java.text.SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(now)
    }

    private fun startClockTicker() {
        handler.removeCallbacks(clockRunnable)
        updateClock()
        val delay = 60_000L - (System.currentTimeMillis() % 60_000L)
        handler.postDelayed(clockRunnable, delay)
    }

    private fun stopClockTicker() {
        handler.removeCallbacks(clockRunnable)
    }

    /**
     * The `cmd statusbar` disable flags do not survive a SystemUI restart
     * or a reboot, so re-apply them (and the icon blacklist) every time
     * the island starts, when the toggle is on. Silent no-op without Shizuku.
     */
    private fun reapplyHideIcons() {
        val hide = getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(ShizukuHelper.KEY_HIDE_ICONS, false)
        if (!hide) return
        if (!ShizukuHelper.isBinderAlive() || !ShizukuHelper.hasPermission()) return
        try {
            ShizukuHelper.bind(
                this,
                onReady = {
                    ShizukuHelper.exec(ShizukuHelper.hideStatusBarCommand(true)) { }
                    ShizukuHelper.exec(ShizukuHelper.hideIconsCommand(true)) { }
                },
                onError = { }
            )
        } catch (e: Exception) {
            // Hiding is best-effort; the island works without it.
        }
    }

    // ---- Launcher-aware clock ----

    private fun hideIconsEnabled(): Boolean =
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(ShizukuHelper.KEY_HIDE_ICONS, false)

    private fun startClockPolling() {
        handler.removeCallbacks(clockPollRunnable)
        updateLauncherClock()
        handler.postDelayed(clockPollRunnable, CLOCK_POLL_MS)
    }

    private fun stopClockPolling() {
        handler.removeCallbacks(clockPollRunnable)
    }

    /**
     * Hides the status-bar clock only while the launcher home screen is in
     * front (where the island shows the time) and shows it everywhere else
     * — other apps and the quick-settings panel, where it is essential.
     * The icon_blacklist hides the QS clock too, so the blacklist is the
     * only thing toggled here; the `cmd statusbar` flags (system and
     * notification icons) stay applied. Only writes when the state changes.
     * Without UsageStats access there is nothing to detect, so the clock
     * stays hidden while the toggle is on (the v1.3 behavior).
     */
    private fun updateLauncherClock() {
        if (!hideIconsEnabled()) return
        if (!isUsageAccessGranted()) return
        val foreground = foregroundPackage() ?: return
        val onLauncher = foreground == launcherPackage
        val shouldHide = onLauncher
        if (clockHidden == shouldHide) return
        clockHidden = shouldHide
        setClockHidden(shouldHide)
    }

    /**
     * Shows or hides just the clock slot of the icon blacklist, leaving the
     * alarm icon (and everything else) alone. Needs WRITE_SECURE_SETTINGS,
     * which the app self-grants through Shizuku.
     */
    private fun setClockHidden(hide: Boolean) {
        try {
            val current =
                Settings.Secure.getString(contentResolver, "icon_blacklist")
                    .orEmpty()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableSet()
            if (hide) current.add("clock") else current.remove("clock")
            if (!current.contains("alarm_clock")) current.add("alarm_clock")
            Settings.Secure.putString(
                contentResolver,
                "icon_blacklist",
                current.joinToString(",")
            )
        } catch (e: Exception) {
            // No WRITE_SECURE_SETTINGS (yet): the clock just stays as it is.
            clockHidden = null
        }
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The package currently in front. Uses the usage-events stream (last
     * MOVE_TO_FOREGROUND in the last 10 minutes) instead of a 10-second
     * queryUsageStats window: an app that has been sitting idle in front
     * (the launcher, doing nothing) has no usage in the last 10 seconds,
     * so the old code picked some other app's stale event and the clock
     * came back "after a while" on its own. The events stream records the
     * foreground change itself, so it stays correct while idle.
     * Falls back to most-recently-used stats if no events are available.
     * Null if unknown.
     */
    private fun foregroundPackage(): String? {
        val manager = usageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = try {
            manager.queryEvents(now - 600_000L, now)
        } catch (e: Exception) {
            null
        }
        if (events != null) {
            val event = UsageEvents.Event()
            var lastForeground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForeground = event.packageName
                }
            }
            if (lastForeground != null) return lastForeground
        }
        val stats = try {
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 60_000L,
                now
            )
        } catch (e: Exception) {
            null
        } ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    /** The default launcher package (the home screen app). */
    private fun resolveLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, 0)
            ?.activityInfo?.packageName
    }

    private fun toggleExpand() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        if (isExpanded && !isAnimating) return
        isExpanded = true
        // Re-apply the live position: if a slider change was missed while
        // the island was idle, the position is corrected on next use.
        repositionPill()
        collapseRunnable?.let { handler.removeCallbacks(it) }
        updateExpandedUi()
        startClockTicker()
        animateSize(toExpanded = true)
        if (isPlaying && currentTheme.showProgressBar) startProgressPolling()
    }

    private fun collapse() {
        val wasShowingNotif = showingNotification
        isExpanded = false
        stopProgressPolling()
        stopClockTicker()
        showingNotification = false
        currentNotif = null
        // The reply input gives the window focus for the keyboard; give it
        // back when the island closes so touches pass through as usual.
        hideKeyboard()
        setWindowFocusable(false)
        updateCollapsedUi()
        animateSize(toExpanded = false)
        // Queued notifications are shown in sequence, one after another.
        if (wasShowingNotif && notifQueue.isNotEmpty()) {
            handler.postDelayed({ showNextFromQueue() }, ANIM_MS + 120)
        }
    }

    private fun scheduleAutoCollapse() {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { collapse() }
        collapseRunnable = r
        handler.postDelayed(r, AUTO_COLLAPSE_MS)
    }

    // ---- Smooth expand / collapse animation ----

    private fun measureExpandedHeight(): Int {
        val w = View.MeasureSpec.makeMeasureSpec(
            dp(EXPANDED_CONTENT_DP), View.MeasureSpec.EXACTLY
        )
        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        expandedView?.measure(w, h)
        return (expandedView?.measuredHeight ?: dp(120f)) + dp(12f)
    }

    /**
     * Animates the pill window width/height between collapsed and expanded
     * sizes and cross-fades the two content views. Falls back to an instant
     * switch if anything about the window goes wrong.
     */
    private fun animateSize(toExpanded: Boolean) {
        val root = pillView ?: run { finishSwitch(toExpanded); return }
        val params = root.layoutParams as? WindowManager.LayoutParams
            ?: run { finishSwitch(toExpanded); return }
        sizeAnimator?.cancel()
        val startW = params.width.takeIf { it > 0 } ?: root.width
        val startH = params.height.takeIf { it > 0 } ?: root.height
        val targetW = if (toExpanded) dp(EXPANDED_W_DP) else collapsedSizePx().first
        val targetH = if (toExpanded) measureExpandedHeight() else collapsedSizePx().second
        if (startW == targetW && startH == targetH) {
            finishSwitch(toExpanded)
            return
        }
        if (toExpanded) {
            expandedView?.visibility = View.VISIBLE
            expandedView?.alpha = 0f
            collapsedView?.visibility = View.VISIBLE
            collapsedView?.alpha = 1f
            root.setBackgroundResource(currentTheme.cardBackgroundRes)
        } else {
            expandedView?.visibility = View.VISIBLE
            expandedView?.alpha = 1f
            collapsedView?.visibility = View.VISIBLE
            collapsedView?.alpha = 0f
        }
        isAnimating = true
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ANIM_MS
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedValue as Float
            params.width = (startW + ((targetW - startW) * f)).toInt()
            params.height = (startH + ((targetH - startH) * f)).toInt()
            try {
                windowManager.updateViewLayout(root, params)
            } catch (e: Exception) {
                // Window is gone; the animation will finish below.
            }
            if (toExpanded) {
                expandedView?.alpha = f
                collapsedView?.alpha = 1f - f
            } else {
                expandedView?.alpha = 1f - f
                collapsedView?.alpha = f
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                sizeAnimator = null
                finishSwitch(toExpanded)
            }

            override fun onAnimationCancel(animation: Animator) {
                isAnimating = false
                sizeAnimator = null
                finishSwitch(toExpanded)
            }
        })
        sizeAnimator = anim
        anim.start()
    }

    /** Final visibility + WRAP_CONTENT window so content reflows naturally. */
    private fun finishSwitch(toExpanded: Boolean) {
        if (toExpanded) {
            expandedView?.visibility = View.VISIBLE
            expandedView?.alpha = 1f
            collapsedView?.visibility = View.GONE
            pillView?.setBackgroundResource(currentTheme.cardBackgroundRes)
        } else {
            expandedView?.visibility = View.GONE
            collapsedView?.visibility = View.VISIBLE
            collapsedView?.alpha = 1f
            pillView?.setBackgroundResource(currentTheme.pillBackgroundRes)
        }
        val root = pillView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            // Window is gone.
        }
    }

    // ---- Notifications (queued, tap to open) ----

    private fun showNotification(notif: IslandBridge.Notif) {
        if (showingNotification) {
            // Already showing one: queue it instead of dropping it.
            if (notifQueue.size >= MAX_QUEUE) notifQueue.pollFirst()
            notifQueue.addLast(notif)
            return
        }
        displayNotif(notif)
    }

    private fun showNextFromQueue() {
        val next = notifQueue.pollFirst() ?: return
        displayNotif(next)
    }

    private fun displayNotif(notif: IslandBridge.Notif) {
        currentNotif = notif
        showingNotification = true
        notifIcon?.setImageDrawable(notif.appIcon)
        notifHeader?.text = if (notif.title.isEmpty()) {
            notif.appName
        } else {
            "${notif.appName} • ${notif.title}"
        }
        notifText?.text = notif.text
        // The reply row only appears when this notification can be answered
        // inline (e.g. WhatsApp, Telegram); otherwise it stays hidden.
        val canReply =
            notif.replyAction != null && notif.replyRemoteInput != null
        replyRow?.visibility = if (canReply) View.VISIBLE else View.GONE
        replyInput?.setText("")
        notifSection?.visibility = View.VISIBLE
        expand()
        scheduleAutoCollapse()
    }

    private fun onNotifRemoved(key: String) {
        notifQueue.removeAll { it.key == key }
        if (currentNotif?.key == key) collapse()
    }

    /** Tap on the notification card: open the app, then show the next queued one. */
    private fun openCurrentNotif() {
        val notif = currentNotif
        collapse()
        if (notif == null) return
        try {
            notif.contentIntent?.send()
        } catch (e: PendingIntent.CanceledException) {
            openPackage(notif.packageName)
        } catch (e: Exception) {
            openPackage(notif.packageName)
        }
    }

    /** Tap on the music card: open the player app. */
    private fun openPlayerApp() {
        val pkg = mediaController?.packageName ?: return
        collapse()
        openPackage(pkg)
    }

    /** Long-press on the expanded island: open the app being shown. */
    private fun openCurrentApp() {
        if (showingNotification && currentNotif != null) {
            openCurrentNotif()
        } else if (mediaController != null) {
            openPlayerApp()
        }
    }

    // ---- Inline reply ----

    /** The overlay window is NOT_FOCUSABLE by default (so touches pass to
     * other apps). The keyboard only shows when the window is focusable,
     * so the reply input toggles that flag on demand. */
    private fun setWindowFocusable(focusable: Boolean) {
        val root = pillView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val newFlags = if (focusable) params.flags and flag.inv() else params.flags or flag
        if (newFlags == params.flags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Exception) {
            // Window is gone.
        }
    }

    private fun hideKeyboard() {
        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val view = replyInput ?: pillView ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Sends the typed text as a direct reply to the current notification. */
    private fun sendReply() {
        val notif = currentNotif ?: return
        val action = notif.replyAction ?: return
        val remoteInput = notif.replyRemoteInput ?: return
        val text = replyInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        try {
            val intent = Intent()
            val results = Bundle()
            results.putCharSequence(remoteInput.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            action.actionIntent.send(this, 0, intent)
        } catch (e: Exception) {
            // Reply failed (e.g. the action expired); stay open so the user
            // can try again or open the app instead.
            return
        }
        replyInput?.setText("")
        hideKeyboard()
        collapse()
    }

    private fun openPackage(pkg: String) {
        if (pkg.isEmpty()) return
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // Player app cannot be launched; stay on the island.
        }
    }

    // ---- Music ----

    private fun setupMediaSessions() {
        mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, IslandNotificationListener::class.java)
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val controllers = mediaSessionManager?.getActiveSessions(component) ?: emptyList()
            updateMediaController(controllers)
        } catch (e: SecurityException) {
            // Notification listener not enabled yet; music starts working once granted.
        }
    }

    private fun updateMediaController(controllers: List<MediaController>) {
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val next = playing ?: controllers.firstOrNull()
        if (next !== mediaController) {
            mediaController?.unregisterCallback(mediaCallback)
            mediaController = next
            next?.registerCallback(mediaCallback)
        }
        isPlaying = next?.playbackState?.state == PlaybackState.STATE_PLAYING
        updateMusicUi()
    }

    private fun updateMusicUi() {
        val controller = mediaController
        if (controller == null) {
            musicSection?.visibility = View.GONE
            updateCollapsedUi()
            updateExpandedUi()
            return
        }
        val metadata = controller.metadata
        musicTitle?.text =
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Sin título"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        musicArtist?.text = if (isPlaying && artist.isNotEmpty()) {
            "$artist • Now Playing"
        } else {
            artist
        }
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) {
            // Downscale once: the views are 56dp / 20dp, a full-res cover
            // would waste megabytes of the phone's tight RAM.
            albumArt?.setImageBitmap(scaledBitmap(art, dp(168f)))
            collapsedIcon?.setImageBitmap(scaledBitmap(art, dp(64f)))
        } else {
            albumArt?.setImageResource(R.drawable.ic_music_note)
            collapsedIcon?.setImageResource(R.drawable.ic_music_note)
        }
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateCollapsedUi()
        updateExpandedUi()
    }

    private fun scaledBitmap(src: Bitmap, px: Int): Bitmap {
        if (src.width <= px && src.height <= px) return src
        return Bitmap.createScaledBitmap(src, px, px, true)
    }

    private fun updateProgress() {
        val controller = mediaController ?: return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        lastDuration = duration
        if (seeking) return
        val pos = controller.playbackState?.position ?: 0L
        if (duration > 0) {
            seekBar?.max = 100
            seekBar?.progress = ((pos * 100) / duration).toInt().coerceIn(0, 100)
            musicTime?.text = "${formatTime(pos)} / ${formatTime(duration)}"
        }
    }

    private fun startProgressPolling() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun stopProgressPolling() {
        handler.removeCallbacks(progressRunnable)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    // ---- View state ----

    private fun updateExpandedUi() {
        val hasMusic = mediaController != null
        musicSection?.visibility = if (hasMusic) View.VISIBLE else View.GONE
        notifSection?.visibility = if (showingNotification) View.VISIBLE else View.GONE
        sectionDivider?.visibility =
            if (hasMusic && showingNotification) View.VISIBLE else View.GONE
        val showProgress = currentTheme.showProgressBar && hasMusic
        seekBar?.visibility = if (showProgress) View.VISIBLE else View.GONE
        musicTime?.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) updateProgress()
    }

    private fun updateCollapsedUi() {
        // At exact camera-hole size there is no room for the mini icon or
        // the dot — the pill is just the ring around the camera.
        if (exactCutoutSize != null) {
            collapsedIcon?.visibility = View.GONE
            collapsedDot?.visibility = View.GONE
            return
        }
        if (isPlaying) {
            collapsedIcon?.visibility = View.VISIBLE
            collapsedDot?.visibility = View.GONE
        } else {
            collapsedIcon?.visibility = View.GONE
            collapsedDot?.visibility = View.VISIBLE
        }
    }
}
