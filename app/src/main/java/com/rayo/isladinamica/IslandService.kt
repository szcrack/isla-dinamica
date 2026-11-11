package com.rayo.isladinamica

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.ArrayDeque

class IslandService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "isla_dinamica"
        private const val NOTIF_ID = 1
        private const val AUTO_COLLAPSE_MS = 4000L
        private const val PROGRESS_POLL_MS = 500L
        private const val ANIM_MS = 300L
        private const val MAX_QUEUE = 5
        private const val COLLAPSED_W_DP = 132f
        private const val COLLAPSED_H_DP = 48f
        private const val EXPANDED_W_DP = 352f
        private const val EXPANDED_CONTENT_DP = 340f
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
    private var albumArt: ImageView? = null
    private var musicTitle: TextView? = null
    private var musicArtist: TextView? = null
    private var musicTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnPrev: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnNext: ImageButton? = null

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

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == IslandTheme.KEY_THEME) applyTheme()
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
        currentTheme = IslandTheme.current(this)
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification())
        setupOverlay()
        setupMediaSessions()
        IslandBridge.onNotification = { notif -> showNotification(notif) }
        IslandBridge.onNotificationRemoved = { key -> onNotifRemoved(key) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
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
        progressBar?.progressDrawable = resources.getDrawable(t.progressDrawableRes, null)
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
        albumArt = view.findViewById(R.id.albumArt)
        musicTitle = view.findViewById(R.id.musicTitle)
        musicArtist = view.findViewById(R.id.musicArtist)
        musicTime = view.findViewById(R.id.musicTime)
        progressBar = view.findViewById(R.id.progressBar)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnNext = view.findViewById(R.id.btnNext)

        // Tap on empty pill area toggles expand/collapse. Taps on the two
        // content sections are consumed by their own listeners below, so
        // they open the app instead of toggling.
        view.setOnClickListener { toggleExpand() }
        notifSection?.setOnClickListener { openCurrentNotif() }
        musicSection?.setOnClickListener { openPlayerApp() }
        btnPlayPause?.setOnClickListener {
            val c = mediaController ?: return@setOnClickListener
            if (isPlaying) c.transportControls.pause() else c.transportControls.play()
        }
        btnPrev?.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        btnNext?.setOnClickListener { mediaController?.transportControls?.skipToNext() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = statusBarHeight() + dp(8f)
        windowManager.addView(view, params)
        applyTheme()
        updateCollapsedUi()
    }

    private fun toggleExpand() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        if (isExpanded && !isAnimating) return
        isExpanded = true
        collapseRunnable?.let { handler.removeCallbacks(it) }
        updateExpandedUi()
        animateSize(toExpanded = true)
        if (isPlaying && currentTheme.showProgressBar) startProgressPolling()
    }

    private fun collapse() {
        val wasShowingNotif = showingNotification
        isExpanded = false
        stopProgressPolling()
        showingNotification = false
        currentNotif = null
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
        val targetW = dp(if (toExpanded) EXPANDED_W_DP else COLLAPSED_W_DP)
        val targetH = if (toExpanded) measureExpandedHeight() else dp(COLLAPSED_H_DP)
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
        val pos = controller.playbackState?.position ?: 0L
        if (duration > 0) {
            progressBar?.max = 100
            progressBar?.progress = ((pos * 100) / duration).toInt().coerceIn(0, 100)
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
        progressBar?.visibility = if (showProgress) View.VISIBLE else View.GONE
        musicTime?.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) updateProgress()
    }

    private fun updateCollapsedUi() {
        if (isPlaying) {
            collapsedIcon?.visibility = View.VISIBLE
            collapsedDot?.visibility = View.GONE
        } else {
            collapsedIcon?.visibility = View.GONE
            collapsedDot?.visibility = View.VISIBLE
        }
    }
}
