package fr.p308.lanceur.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// v0.49 : sorti de Engine.kt (sans changement) : liaisons avec le boÃ®tier ELM327, Bluetooth classique (SPP) et
// Bluetooth Low Energy (GATT), et identifiants des services Bluetooth connus.

internal val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
internal val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
// Services UART BLE connus : Vgate vLinker MC+/FS/BM (E7810A71, 1 seule caractéristique bidirectionnelle),
// Vgate iCar Pro / 2S (18F0, FFF0) et adaptateurs OBD BLE courants.
internal val VLINKER_SERVICE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
internal val VLINKER_CHAR = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
internal val BLE_SERVICE_UUIDS = listOf(
    VLINKER_SERVICE,
    UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
)
internal val VGATE_NOTIFY = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
internal val VGATE_WRITE = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")

/** Interface commune ELM327 (Bluetooth classique SPP ou Bluetooth Low Energy GATT). */
internal abstract class ElmTransport(val name: String) : Closeable {
    abstract fun cmd(command: String, timeoutMs: Long = 1500): String

    /** Octets de données d'un PID du mode 01, ou null si absent ou plus court que `minBytes`. */
    fun pid(pid: String, timeoutMs: Long = 1500, minBytes: Int = 1): IntArray? {
        val hex = cmd("01$pid", timeoutMs).uppercase().filter { it.isLetterOrDigit() }
        val i = hex.indexOf("41$pid")
        if (i < 0) return null
        val data = hex.substring(i + 4).chunked(2).takeWhile { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        return data.toIntArray().takeIf { it.size >= minBytes }
    }

    fun readBatteryVolts(): Float? {
        val resp = cmd("ATRV", 1200)
        val v = VOLTS_REGEX.find(resp)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
        return v?.takeIf { it in 8.0f..18.5f }
    }

    companion object {
        private val VOLTS_REGEX = Regex("(\\d{1,2}[.,]\\d{1,2})")
    }
}

/** Transport Bluetooth classique RFCOMM SPP (Android-Vlink). */
internal class SppTransport(private val socket: BluetoothSocket, label: String) : ElmTransport(label) {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun cmd(command: String, timeoutMs: Long): String {
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("socket fermé")
                if (c == '>'.code) break
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        return sb.toString()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice): SppTransport {
            val sock = runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
                .getOrElse { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
            return SppTransport(sock, "${device.name ?: "OBD"} (SPP)")
        }
    }
}

/** Transport Bluetooth Low Energy GATT (Vgate iCar Pro 2S « IOS-Vlink » : service 18F0 / 2AF0 / 2AF1 ou FFF0). */
@SuppressLint("MissingPermission")
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal class BleTransport(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val rxLock: java.lang.Object,
    private val rxBuf: StringBuilder,
    private val stateRef: AtomicReference<Boolean>,
    private val writeLatchRef: AtomicReference<CountDownLatch?>,
    label: String,
) : ElmTransport(label) {

    @Suppress("DEPRECATION")
    override fun cmd(command: String, timeoutMs: Long): String {
        if (!stateRef.get()) throw IOException("BLE déconnecté")
        synchronized(rxLock) { rxBuf.setLength(0) }

        val payload = "$command\r".toByteArray(Charsets.US_ASCII)
        val props = writeChar.properties
        val writeNoResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + 20, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val latch = CountDownLatch(1)
            writeLatchRef.set(latch)
            writeChar.writeType = if (writeNoResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            writeChar.value = chunk
            if (!gatt.writeCharacteristic(writeChar)) {
                Thread.sleep(15)
                if (!gatt.writeCharacteristic(writeChar)) throw IOException("échec écriture BLE")
            }
            latch.await(450, TimeUnit.MILLISECONDS)
            offset = end
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(rxLock) {
            while (System.currentTimeMillis() < deadline) {
                if (!stateRef.get()) throw IOException("BLE déconnecté")
                val idx = rxBuf.indexOf(">")
                if (idx >= 0) return rxBuf.substring(0, idx)
                val rem = deadline - System.currentTimeMillis()
                if (rem > 0) rxLock.wait(minOf(rem, 40L))
            }
            return rxBuf.toString()
        }
    }

    override fun close() {
        stateRef.set(false)
        synchronized(rxLock) { rxLock.notifyAll() }
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        @Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun connect(ctx: Context, device: BluetoothDevice, labelOverride: String? = null): BleTransport {
            val rxLock = java.lang.Object()
            val rxBuf = StringBuilder()
            val alive = AtomicReference(false)
            val readyLatch = CountDownLatch(1)
            val descLatch = CountDownLatch(1)
            val writeLatchRef = AtomicReference<CountDownLatch?>(null)
            var notifyFound: BluetoothGattCharacteristic? = null
            var writeFound: BluetoothGattCharacteristic? = null

            fun appendRx(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                synchronized(rxLock) {
                    rxBuf.append(String(bytes, Charsets.US_ASCII))
                    if (rxBuf.indexOf(">") >= 0) rxLock.notifyAll()
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        alive.set(true)
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        alive.set(false)
                        readyLatch.countDown()
                        descLatch.countDown()
                        writeLatchRef.get()?.countDown()
                        synchronized(rxLock) { rxLock.notifyAll() }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val pair = pickUartCharacteristics(g)
                        if (pair != null) {
                            notifyFound = pair.first
                            writeFound = pair.second
                        }
                    }
                    readyLatch.countDown()
                }

                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    descLatch.countDown()
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    writeLatchRef.get()?.countDown()
                }

                @Deprecated("Appelée sur Android 8..12")
                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    val v = characteristic.value ?: return
                    appendRx(v)
                }

                override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    appendRx(value)
                }
            }

            val gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("GATT indisponible")

            val ok = readyLatch.await(5_000, TimeUnit.MILLISECONDS) && alive.get()
            val nChar = notifyFound
            val wChar = writeFound
            if (!ok || nChar == null || wChar == null) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                throw IOException("service UART BLE introuvable")
            }

            gatt.setCharacteristicNotification(nChar, true)
            nChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                val useIndicate = (nChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                    (nChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                cccd.value = if (useIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                if (gatt.writeDescriptor(cccd)) {
                    descLatch.await(1_500, TimeUnit.MILLISECONDS)
                }
            }

            val label = "${labelOverride ?: device.name ?: "Vgate"} (BLE)"
            return BleTransport(gatt, wChar, rxLock, rxBuf, alive, writeLatchRef, label)
        }

        private fun pickUartCharacteristics(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val services = gatt.services.orEmpty()
            val ordered = BLE_SERVICE_UUIDS.mapNotNull { gatt.getService(it) } +
                services.filter { s ->
                    val u = s.uuid.toString().lowercase()
                    !u.startsWith("00001800") && !u.startsWith("00001801") && !u.startsWith("0000180a")
                }
            for (svc in ordered.distinctBy { it.uuid }) {
                val chars = svc.characteristics.orEmpty()
                // Priorité explicite : vLinker MC+ (BEF8D6C9 = notify + write sur la même caractéristique),
                // puis Vgate iCar Pro / 2S (2AF0 = notify, 2AF1 = write).
                svc.getCharacteristic(VLINKER_CHAR)?.let { c -> return c to c }
                val nChar = svc.getCharacteristic(VGATE_NOTIFY) ?: chars.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                }
                val wChar = svc.getCharacteristic(VGATE_WRITE) ?: chars.firstOrNull {
                    it != nChar && (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                } ?: chars.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }
                if (nChar != null && wChar != null) return nChar to wChar
            }
            return null
        }
    }
}
