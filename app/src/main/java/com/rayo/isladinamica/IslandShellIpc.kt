package com.rayo.isladinamica

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Hand-written Binder IPC for the privileged shell service — exactly what
 * AIDL would generate, but without needing AIDL (AGP 8.5.2 in this setup
 * does not create the aidl compile task). [IslandUserService] runs in the
 * Shizuku server process (shell/root identity); the app talks to it
 * through [IslandShellStub.asInterface].
 */
interface IIslandShell : IInterface {
    /** Runs a shell command as shell/root, returns "exit=N" + output. */
    fun exec(command: String): String

    /** Shizuku asks the service to go away. */
    fun destroy()
}

abstract class IslandShellStub : Binder(), IIslandShell {

    companion object {
        const val DESCRIPTOR = "com.rayo.isladinamica.IIslandShell"
        const val TRANSACTION_exec = 1
        // Shizuku calls destroy with transaction code 16777115; accept
        // 16777114 too (the value the AIDL docs tell you to declare).
        const val TRANSACTION_destroy = 16777114
        const val TRANSACTION_destroy_alt = 16777115

        fun asInterface(binder: IBinder?): IIslandShell? {
            if (binder == null) return null
            val local = binder.queryLocalInterface(DESCRIPTOR)
            if (local is IIslandShell) return local
            return Proxy(binder)
        }
    }

    init {
        attachInterface(this, DESCRIPTOR)
    }

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            TRANSACTION_exec -> {
                data.enforceInterface(DESCRIPTOR)
                val command = data.readString() ?: ""
                val result = exec(command)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
            TRANSACTION_destroy, TRANSACTION_destroy_alt -> {
                data.enforceInterface(DESCRIPTOR)
                destroy()
                reply?.writeNoException()
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private class Proxy(private val remote: IBinder) : IIslandShell {
        override fun asBinder(): IBinder = remote

        override fun exec(command: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                remote.transact(TRANSACTION_exec, data, reply, 0)
                reply.readException()
                return reply.readString() ?: ""
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun destroy() {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_destroy, data, reply, 0)
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
