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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 0
        private const val KEY_HIDE_ICONS = "hide_status_icons"
    }

    private lateinit var tvOverlay: TextView
    private lateinit var tvListener: TextView
    private lateinit var tvService: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvShizukuLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnShizukuPermission: Button
    private lateinit var btnShizukuApply: Button
    private lateinit var swHideIcons: Switch
    private lateinit var rgTheme: RadioGroup

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
     * Hides (or restores) the status-bar icons via icon_blacklist.
     * Prefers Shizuku; if the app already holds WRITE_SECURE_SETTINGS it
     * writes the setting directly. The setting itself survives reboots.
     */
    private fun setHideIcons(hide: Boolean) {
        getSharedPreferences(IslandTheme.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_ICONS, hide)
            .apply()
        if (ShizukuHelper.isBinderAlive() && ShizukuHelper.hasPermission()) {
            ShizukuHelper.bind(
                this,
                onReady = {
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
                appendShizukuLog("Iconos: aplicado directo ✓")
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
