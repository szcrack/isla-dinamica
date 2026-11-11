package com.rayo.isladinamica

import android.content.Context

/**
 * Privileged shell service. Shizuku starts this class in its server
 * process, running as shell (UID 2000, or root UID 0 if Shizuku was
 * started with root). Every [exec] call runs a shell command with that
 * identity, so the app can grant itself ADB-level permissions without a PC.
 *
 * NOTE: this process is NOT a normal app process — most Android APIs
 * (ContentResolver, BroadcastReceiver, ...) do not work here. Only plain
 * JVM calls and Runtime.exec are used, which is all the grants need.
 */
class IslandUserService : IslandShellStub {

    constructor() : super()

    // Shizuku v13 tries the Context constructor first.
    @Suppress("unused")
    constructor(context: Context) : super()

    override fun exec(command: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            "exit=$code\n$out".trimEnd()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    override fun destroy() {
        // Shizuku asks us to go away; exit the process.
        System.exit(0)
    }
}
