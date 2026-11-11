package com.rayo.isladinamica

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Client side of the Shizuku integration. Binds [IslandUserService]
 * (which runs as shell/root in the Shizuku server process) and runs shell
 * commands through it so the app can grant itself the permissions the
 * island needs. Everything here fails gracefully when Shizuku is not
 * running — the caller falls back to the manual Settings buttons.
 */
object ShizukuHelper {
    private const val TAG = "IslaShizuku"
    const val PKG = "com.rayo.isladinamica"

    /** Status-bar slots hidden by the "Ocultar iconos" toggle. */
    const val ICON_BLACKLIST = "clock,alarm_clock"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var shell: IIslandShell? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var connecting = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shell = IslandShellStub.asInterface(service)
            connecting = false
            Log.i(TAG, "UserService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            connecting = false
            Log.i(TAG, "UserService disconnected")
        }
    }

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun requestPermission(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Exception) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    /**
     * Binds the shell UserService. [onReady] runs on the main thread once
     * the service is usable; [onError] runs on the main thread if binding
     * fails or times out (~10 s).
     */
    fun bind(context: Context, onReady: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        if (shell != null) {
            onReady?.let { mainHandler.post(it) }
            return
        }
        if (!isBinderAlive() || !hasPermission()) {
            onError?.invoke("Shizuku no disponible o sin permiso")
            return
        }
        if (connecting) return
        connecting = true
        try {
            val a = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, IslandUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("island_shell")
                .version(1)
                .tag("island_shell")
            args = a
            Shizuku.bindUserService(a, connection)
            executor.execute {
                var waited = 0
                while (shell == null && waited < 10_000) {
                    try {
                        Thread.sleep(200)
                    } catch (ignored: InterruptedException) {
                        break
                    }
                    waited += 200
                }
                if (shell != null) {
                    onReady?.let { mainHandler.post(it) }
                } else {
                    connecting = false
                    onError?.let {
                        mainHandler.post { it("No se pudo conectar al servicio Shizuku") }
                    }
                }
            }
        } catch (e: Exception) {
            connecting = false
            Log.w(TAG, "bind failed", e)
            onError?.invoke(e.message ?: "bind falló")
        }
    }

    fun unbind() {
        try {
            val a = args
            if (a != null) Shizuku.unbindUserService(a, connection, false)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        shell = null
        args = null
        connecting = false
    }

    /** Runs one shell command as shell/root. Result posted on the main thread. */
    fun exec(command: String, onResult: (String) -> Unit) {
        executor.execute {
            val s = shell
            if (s == null) {
                mainHandler.post { onResult("error: servicio Shizuku no conectado") }
                return@execute
            }
            val res = try {
                s.exec(command)
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            Log.i(TAG, "exec [$command] -> $res")
            mainHandler.post { onResult(res) }
        }
    }

    /**
     * Runs [commands] in order on the background thread. Each result is
     * reported through [onLog] on the main thread as "✓ label: exit=0" or
     * "✗ label: <first line>"; [onDone] runs on the main thread at the end.
     */
    fun execAll(
        commands: List<Pair<String, String>>,
        onLog: (String) -> Unit,
        onDone: (() -> Unit)? = null
    ) {
        executor.execute {
            for ((label, cmd) in commands) {
                val s = shell ?: break
                val res = try {
                    s.exec(cmd)
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                Log.i(TAG, "exec [$label] -> $res")
                val ok = res.startsWith("exit=0")
                val firstLine = res.lineSequence().firstOrNull().orEmpty()
                mainHandler.post { onLog("${if (ok) "✓" else "✗"} $label: $firstLine") }
            }
            onDone?.let { mainHandler.post(it) }
        }
    }

    /** The four self-grants the island needs, run in this order. */
    fun grantCommands(): List<Pair<String, String>> = listOf(
        "WRITE_SECURE_SETTINGS" to
            "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS",
        "Listener de notificaciones" to
            "cmd notification allow_listener $PKG/.IslandNotificationListener",
        "Superposición (overlay)" to
            "appops set --uid $PKG SYSTEM_ALERT_WINDOW allow",
        "Sin optimización de batería" to
            "dumpsys deviceidle whitelist +$PKG"
    )

    fun hideIconsCommand(hide: Boolean): String =
        if (hide) {
            "settings put secure icon_blacklist \"$ICON_BLACKLIST\""
        } else {
            "settings delete secure icon_blacklist"
        }
}
