package com.rayo.isladinamica

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 0
    }

    private lateinit var tvOverlay: TextView
    private lateinit var tvListener: TextView
    private lateinit var tvService: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvShizukuLog: TextView
    private lateinit var tvUsageAccessStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnShizukuPermission: Button
    private lateinit var btnShizukuApply: Button
    private lateinit var btnUsageAccess: Button
    private lateinit var swHideIcons: Switch
    private lateinit var swSwipeDismiss: Switch
    private lateinit var swShowLockscreen: Switch
    private lateinit var swNotchShape: Switch
    private lateinit var swVisualizer: Switch
    private lateinit var rgTheme: RadioGroup
    private lateinit var sbOffsetX: SeekBar
    private lateinit var sbOffsetY: SeekBar
    private lateinit var tvOffsetX: TextView
    private lateinit var tvOffsetY: TextView
    private lateinit var sbSizeW: SeekBar
    private lateinit var sbSizeH: SeekBar
    private lateinit var tvSizeW: TextView
    private lateinit var tvSizeH: TextView
    private lateinit var sbPopupW: SeekBar
    private lateinit var tvPopupW: TextView
    private lateinit var sbAutoClose: SeekBar
    private lateinit var tvAutoClose: TextView
    private lateinit var sbLongPress: SeekBar
    private lateinit var tvLongPress: TextView

    private var syncingSwitch = false
    private var grantsAutoApplied = false

    private val binderListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
        // Shizuku just came up and we already have its permission:
        // make sure the self-grants are in place (idempotent commands).
        if (ShizukuHelper.hasPermission() && !grantsAutoApplied) {
            grantsAutoApplied = true
            applyShizukuGrants()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatus()
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            updateShizukuStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                grantsAutoApplied = true
                applyShizukuGrants()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlay = findViewById(R.id.tvOverlayStatus)
        tvListener = findViewById(R.id.tvListenerStatus)
        tvService = findViewById(R.id.tvServiceStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvShizukuLog = findViewById(R.id.tvShizukuLog)
        tvUsageAccessStatus = findViewById(R.id.tvUsageAccessStatus)
        btnUsageAccess = findViewById(R.id.btnUsageAccess)
        btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btnToggle = findViewById(R.id.btnToggleService)
        btnShizukuPermission = findViewById(R.id.btnShizukuPermission)
        btnShizukuApply = findViewById(R.id.btnShizukuApply)
        swHideIcons = findViewById(R.id.swHideIcons)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnListener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotifPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
        btnToggle.setOnClickListener {
            if (IslandService.isRunning) {
                stopService(Intent(this, IslandService::class.java))
            } else {
                startForegroundService(Intent(this, IslandService::class.java))
            }
            updateStatus()
        }

        // ---- Shizuku ----
        btnShizukuPermission.setOnClickListener {
            if (!ShizukuHelper.isBinderAlive()) {
                Toast.makeText(
                    this,
                    "Shizuku no está en ejecución. Ábrelo y actívalo primero.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            ShizukuHelper.requestPermission(SHIZUKU_REQUEST_CODE)
        }
        btnShizukuApply.setOnClickListener {
            if (!ShizukuHelper.hasPermission()) {
                if (ShizukuHelper.isBinderAlive()) {
                    ShizukuHelper.requestPermission(SHIZUKU_REQUEST_CODE)
                } else {
                    Toast.makeText(
                        this,
                        "Shizuku no está en ejecución.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@setOnClickListener
            }
            applyShizukuGrants()
        }
        swHideIcons.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            setHideIcons(isChecked)
        }

        // ---- Posición de la isla (deslizadores X/Y, -40..+40 dp) ----
        sbOffsetX = findViewById(R.id.sbOffsetX)
        sbOffsetY = findViewById(R.id.sbOffsetY)
        tvOffsetX = findViewById(R.id.tvOffsetX)
        tvOffsetY = findViewById(R.id.tvOffsetY)
        val prefs = getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
        sbOffsetX.progress =
            prefs.getInt(IslandService.KEY_OFFSET_X, 0) + IslandService.OFFSET_RANGE_DP
        sbOffsetY.progress =
            prefs.getInt(IslandService.KEY_OFFSET_Y, 0) + IslandService.OFFSET_RANGE_DP
        updateOffsetLabels()
        val offsetListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (!fromUser) return
                val value = progress - IslandService.OFFSET_RANGE_DP
                val key = if (seekBar?.id == R.id.sbOffsetX) {
                    IslandService.KEY_OFFSET_X
                } else {
                    IslandService.KEY_OFFSET_Y
                }
                getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(key, value)
                    .apply()
                updateOffsetLabels()
                // The collapsed pill is the size of the camera hole and hard
                // to see: expand the island as a live preview so the new
                // position is visible while dragging.
                IslandService.previewPosition()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbOffsetX.setOnSeekBarChangeListener(offsetListener)
        sbOffsetY.setOnSeekBarChangeListener(offsetListener)
        findViewById<Button>(R.id.btnCenterIsland).setOnClickListener {
            getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(IslandService.KEY_OFFSET_X, 0)
                .putInt(IslandService.KEY_OFFSET_Y, 0)
                .apply()
            sbOffsetX.progress = IslandService.OFFSET_RANGE_DP
            sbOffsetY.progress = IslandService.OFFSET_RANGE_DP
            updateOffsetLabels()
            IslandService.previewPosition()
        }

        // ---- Tamaño y comportamiento (ajustes de DynamicSpot) ----
        sbSizeW = findViewById(R.id.sbSizeW)
        sbSizeH = findViewById(R.id.sbSizeH)
        tvSizeW = findViewById(R.id.tvSizeW)
        tvSizeH = findViewById(R.id.tvSizeH)
        sbPopupW = findViewById(R.id.sbPopupW)
        tvPopupW = findViewById(R.id.tvPopupW)
        sbAutoClose = findViewById(R.id.sbAutoClose)
        tvAutoClose = findViewById(R.id.tvAutoClose)
        sbLongPress = findViewById(R.id.sbLongPress)
        tvLongPress = findViewById(R.id.tvLongPress)
        swSwipeDismiss = findViewById(R.id.swSwipeDismiss)
        // SeekBar progress is offset from the minimum of each range.
        sbSizeW.progress =
            prefs.getInt(IslandService.KEY_SIZE_W, 100) - 50
        sbSizeH.progress =
            prefs.getInt(IslandService.KEY_SIZE_H, 100) - 50
        sbPopupW.progress =
            prefs.getInt(IslandService.KEY_POPUP_W, 352) - 280
        sbAutoClose.progress =
            (prefs.getLong(IslandService.KEY_POPUP_TIMEOUT_MS, 8000L) / 1000).toInt()
        sbLongPress.progress =
            (prefs.getInt(IslandService.KEY_LONG_PRESS_MS, 500) - 300) / 100
        swSwipeDismiss.isChecked =
            prefs.getBoolean(IslandService.KEY_SWIPE_DISMISS, true)
        updateBehaviorLabels()
        val behaviorListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (!fromUser) return
                val editor = getSharedPreferences(
                    IslandTheme.PREFS_NAME, MODE_PRIVATE
                ).edit()
                var preview = false
                when (seekBar?.id) {
                    R.id.sbSizeW -> {
                        editor.putInt(IslandService.KEY_SIZE_W, progress + 50)
                        preview = true
                    }
                    R.id.sbSizeH -> {
                        editor.putInt(IslandService.KEY_SIZE_H, progress + 50)
                        preview = true
                    }
                    R.id.sbPopupW -> {
                        editor.putInt(IslandService.KEY_POPUP_W, progress + 280)
                        preview = true
                    }
                    R.id.sbAutoClose -> {
                        // SeekBar de 0 a 20 → 0 s (nunca) a 20 s (Claude, v1.9)
                        editor.putLong(
                            IslandService.KEY_POPUP_TIMEOUT_MS, progress * 1000L
                        )
                    }
                    R.id.sbLongPress -> {
                        editor.putInt(
                            IslandService.KEY_LONG_PRESS_MS, progress * 100 + 300
                        )
                    }
                }
                editor.apply()
                updateBehaviorLabels()
                if (preview) IslandService.previewPosition()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbSizeW.setOnSeekBarChangeListener(behaviorListener)
        sbSizeH.setOnSeekBarChangeListener(behaviorListener)
        sbPopupW.setOnSeekBarChangeListener(behaviorListener)
        sbAutoClose.setOnSeekBarChangeListener(behaviorListener)
        sbLongPress.setOnSeekBarChangeListener(behaviorListener)
        swSwipeDismiss.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(IslandService.KEY_SWIPE_DISMISS, isChecked)
                .apply()
        }

        // ---- DynamicSpot: pantalla de bloqueo, forma de muesca y visualizador ----
        swShowLockscreen = findViewById(R.id.swShowLockscreen)
        swNotchShape = findViewById(R.id.swNotchShape)
        swVisualizer = findViewById(R.id.swVisualizer)
        swShowLockscreen.isChecked =
            prefs.getBoolean(IslandService.KEY_SHOW_LOCKSCREEN, true)
        swNotchShape.isChecked =
            prefs.getBoolean(IslandService.KEY_NOTCH_SHAPE, true)
        swVisualizer.isChecked =
            prefs.getBoolean(IslandService.KEY_MUSIC_VISUALIZER, true)
        val islandPrefs =
            getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
        swShowLockscreen.setOnCheckedChangeListener { _, isChecked ->
            islandPrefs.edit()
                .putBoolean(IslandService.KEY_SHOW_LOCKSCREEN, isChecked)
                .apply()
        }
        swNotchShape.setOnCheckedChangeListener { _, isChecked ->
            islandPrefs.edit()
                .putBoolean(IslandService.KEY_NOTCH_SHAPE, isChecked)
                .apply()
        }
        swVisualizer.setOnCheckedChangeListener { _, isChecked ->
            islandPrefs.edit()
                .putBoolean(IslandService.KEY_MUSIC_VISUALIZER, isChecked)
                .apply()
        }

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        rgTheme = findViewById(R.id.rgTheme)
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbThemeFrosted -> IslandTheme.FROSTED
                R.id.rbThemeGlow -> IslandTheme.GLOW
                else -> IslandTheme.BLACK
            }
            IslandTheme.save(this, theme)
            // IslandService picks the change up through its SharedPreferences
            // listener and re-styles the live overlay, no restart needed.
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateShizukuStatus()
        updateUsageAccessStatus()
    }

    /** Usage access (for the launcher-aware clock): granted in Settings. */
    private fun updateUsageAccessStatus() {
        val granted = isUsageAccessGranted()
        tvUsageAccessStatus.text = if (granted) {
            "Acceso a uso: concedido — el reloj se oculta solo en el inicio"
        } else {
            "Acceso a uso: pendiente — sin él, el reloj se oculta en todas partes mientras el interruptor esté activo"
        }
        btnUsageAccess.isEnabled = !granted
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        return appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        ShizukuHelper.unbind()
        super.onDestroy()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlay.text = if (overlayOk) {
            "Superposición: concedida"
        } else {
            "Superposición: pendiente"
        }

        val cn = ComponentName(this, IslandNotificationListener::class.java)
        val enabled =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val listenerOk = enabled.contains(cn.flattenToString())
        tvListener.text = if (listenerOk) {
            "Acceso a notificaciones: concedido"
        } else {
            "Acceso a notificaciones: pendiente"
        }

        tvService.text = if (IslandService.isRunning) "Isla: en ejecución" else "Isla: detenida"
        btnToggle.text = if (IslandService.isRunning) "Detener isla" else "Iniciar isla"

        val themeButtonId = when (IslandTheme.current(this)) {
            IslandTheme.FROSTED -> R.id.rbThemeFrosted
            IslandTheme.GLOW -> R.id.rbThemeGlow
            else -> R.id.rbThemeBlack
        }
        if (rgTheme.checkedRadioButtonId != themeButtonId) {
            rgTheme.check(themeButtonId)
        }
    }

    private fun updateOffsetLabels() {
        val x = sbOffsetX.progress - IslandService.OFFSET_RANGE_DP
        val y = sbOffsetY.progress - IslandService.OFFSET_RANGE_DP
        tvOffsetX.text = "Posición horizontal: ${if (x >= 0) "+$x" else "$x"} dp"
        tvOffsetY.text = "Posición vertical: ${if (y >= 0) "+$y" else "$y"} dp"
    }

    private fun updateBehaviorLabels() {
        tvSizeW.text = "Ancho de la isla: ${sbSizeW.progress + 50}%"
        tvSizeH.text = "Alto de la isla: ${sbSizeH.progress + 50}%"
        tvPopupW.text = "Ancho de la tarjeta expandida: ${sbPopupW.progress + 280} dp"
        tvAutoClose.text = if (sbAutoClose.progress == 0) {
            "Cerrar sola a los: Nunca"
        } else {
            "Cerrar sola a los: ${sbAutoClose.progress} s"
        }
        tvLongPress.text =
            "Toque largo para abrir la app: ${sbLongPress.progress * 100 + 300} ms"
    }

    // ---- Shizuku UI ----

    private fun updateShizukuStatus() {
        val alive = ShizukuHelper.isBinderAlive()
        val granted = ShizukuHelper.hasPermission()
        tvShizukuStatus.text = when {
            !alive -> "Shizuku: no está en ejecución (usa los botones manuales de arriba)"
            !granted -> "Shizuku: en ejecución, sin permiso — toca «Conceder permiso Shizuku»"
            else -> "Shizuku: listo ✓"
        }
        btnShizukuPermission.isEnabled = alive && !granted
        btnShizukuApply.isEnabled = alive && granted
        swHideIcons.isEnabled = (alive && granted) || hasWriteSecureSettings()
        syncHideIconsSwitch()
    }

    /** Shows the real system value of icon_blacklist in the toggle. */
    private fun syncHideIconsSwitch() {
        val current = Settings.Secure.getString(contentResolver, "icon_blacklist").orEmpty()
        syncingSwitch = true
        swHideIcons.isChecked = current.isNotEmpty()
        syncingSwitch = false
    }

    private fun hasWriteSecureSettings(): Boolean =
        checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Runs the four self-grants through the Shizuku shell service. */
    private fun applyShizukuGrants() {
        appendShizukuLog("Aplicando permisos con Shizuku…")
        ShizukuHelper.bind(
            this,
            onReady = {
                ShizukuHelper.execAll(
                    ShizukuHelper.grantCommands(),
                    onLog = { appendShizukuLog(it) },
                    onDone = {
                        updateStatus()
                        updateShizukuStatus()
                    }
                )
            },
            onError = { appendShizukuLog("Shizuku: $it") }
        )
    }

    /**
     * Hides (or restores) the system icons and the notification icons in
     * the status bar, plus the status-bar clock via the blacklist. Two
     * mechanisms, same as Essentials:
     * - `cmd statusbar send-disable-flag ...` via the Shizuku shell (system
     *   and notification icons only — the `clock` flag is NOT used because
     *   it also hides the quick-settings panel clock).
     * - `icon_blacklist` (persistent; hides the status-bar clock — but on
     *   this Motorola/Android 15 it also seems to hide the QS clock, which
     *   is under investigation; see v1.4 notes in the README).
     * Without Shizuku only the blacklist part can run (clock only).
     */
    private fun setHideIcons(hide: Boolean) {
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(ShizukuHelper.KEY_HIDE_ICONS, hide)
            .apply()
        if (ShizukuHelper.isBinderAlive() && ShizukuHelper.hasPermission()) {
            ShizukuHelper.bind(
                this,
                onReady = {
                    ShizukuHelper.exec(ShizukuHelper.hideStatusBarCommand(hide)) { res ->
                        appendShizukuLog("Barra: $res")
                    }
                    ShizukuHelper.exec(ShizukuHelper.hideIconsCommand(hide)) { res ->
                        appendShizukuLog("Iconos: $res")
                        syncHideIconsSwitch()
                    }
                },
                onError = {
                    appendShizukuLog("Iconos: $it")
                    syncHideIconsSwitch()
                }
            )
            return
        }
        if (hasWriteSecureSettings()) {
            try {
                Settings.Secure.putString(
                    contentResolver,
                    "icon_blacklist",
                    if (hide) ShizukuHelper.ICON_BLACKLIST else ""
                )
                appendShizukuLog("Iconos: solo reloj (sin Shizuku no se ocultan sistema/notificaciones)")
            } catch (e: Exception) {
                appendShizukuLog("Iconos: ${e.message}")
            }
            syncHideIconsSwitch()
            return
        }
        Toast.makeText(
            this,
            "Necesitas Shizuku para ocultar los iconos.",
            Toast.LENGTH_LONG
        ).show()
        syncHideIconsSwitch()
    }

    private fun appendShizukuLog(line: String) {
        val lines = (tvShizukuLog.text.toString() + "\n" + line)
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(8)
        tvShizukuLog.text = lines.joinToString("\n")
    }
}
