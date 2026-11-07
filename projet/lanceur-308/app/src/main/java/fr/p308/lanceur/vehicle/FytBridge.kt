package fr.p308.lanceur.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Accès au service interne des autoradios FYT (com.syu.ms), par lequel passent les données CAN.
 * Interfaces AIDL du fabricant (com.syu.ipc.*), appelées « à la main » par transactions Binder :
 *  IRemoteToolkit.getRemoteModule(int module)                              → transaction 1
 *  IRemoteModule.register(IModuleCallback cb, int updateCode, int update)  → transaction 3
 *  IModuleCallback.update(int code, int[] ints, float[] flts, String[] strs) ← transaction 1
 */
class FytBridge(private val onUpdate: (module: Int, code: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String>?) -> Unit,
                private val log: (String) -> Unit) {
    private var conn: ServiceConnection? = null
    private val callbacks = mutableListOf<Binder>()

    fun connect(ctx: Context, modules: IntRange = 0..15, codes: Iterable<Int> = 0..300) =
        connect(ctx, modules.associateWith { codes })

    fun connect(ctx: Context, subscriptions: Map<Int, Iterable<Int>>) {
        val intent = Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, toolkit: IBinder) {
                log("FYT : connecté au service com.syu.ms")
                Thread {
                    for ((m, codes) in subscriptions) {
                        val module = runCatching { getModule(toolkit, m) }.getOrNull() ?: continue
                        val cb = callback(m)
                        callbacks += cb
                        var ok = 0
                        for (code in codes) if (runCatching { register(module, cb, code) }.isSuccess) ok++
                        log("FYT : module $m abonné ($ok canaux)")
                    }
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) = log("FYT : service déconnecté")
        }
        val bound = runCatching { ctx.applicationContext.bindService(intent, c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (bound) conn = c else log("FYT : service com.syu.ms introuvable (autoradio non FYT ?)")
    }

    fun disconnect(ctx: Context) {
        conn?.let { runCatching { ctx.applicationContext.unbindService(it) } }
        conn = null
        callbacks.clear()
    }

    private fun getModule(toolkit: IBinder, module: Int): IBinder? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.syu.ipc.IRemoteToolkit")
            data.writeInt(module)
            toolkit.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun register(module: IBinder, cb: Binder, code: Int) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.syu.ipc.IRemoteModule")
            data.writeStrongBinder(cb)
            data.writeInt(code)
            data.writeInt(1)
            module.transact(IBinder.FIRST_CALL_TRANSACTION + 2, data, reply, 0)
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun callback(module: Int) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            runCatching {
                data.enforceInterface(DESCRIPTOR)
                val updateCode = data.readInt()
                onUpdate(module, updateCode, data.createIntArray(), data.createFloatArray(), data.createStringArray())
            }
            reply?.writeNoException()
            return true
        }
    }

    companion object { const val DESCRIPTOR = "com.syu.ipc.IModuleCallback" }
}
