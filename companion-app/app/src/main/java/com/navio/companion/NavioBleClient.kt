package com.navio.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class NavioBleClient(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onLog(msg: String)
    }

    private val appContext = context.applicationContext
    private val writeMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var ready = false

    val isConnected: Boolean get() = ready

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        ready = false
        characteristic = null
        gatt?.let { runCatching { it.close() } }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    characteristic = null
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("BLE service discovery failed ($status)")
                return
            }
            val svc: BluetoothGattService? = g.getService(SERVICE_UUID)
            val ch: BluetoothGattCharacteristic? = svc?.getCharacteristic(CHARACTERISTIC_UUID)
            if (svc == null || ch == null) {
                listener.onLog("Nordic UART service not found on device")
                return
            }
            characteristic = ch
            ready = true
            listener.onConnected()
        }
    }

    suspend fun sendJson(json: String) = writeMutex.withLock {
        if (!ready) throw IllegalStateException("Not connected")
        val a = asciiOnly(json)
        val total = a.length
        var off = 0
        var seq = 0
        while (off < total) {
            val cl = minOf(MAX_PAYLOAD, total - off)
            val end = if (off + cl >= total) "1" else "0"
            val header = seq.toString(16).padStart(2, '0').uppercase() + end
            val packet = (header + a.substring(off, off + cl)).toByteArray(Charsets.US_ASCII)
            rawWrite(packet)
            off += cl
            seq++
            if (seq > 255) throw IllegalStateException("Packet too large for BLE protocol")
            delay(INTER_PACKET_DELAY_MS)
        }
        listener.onLog("sent $total B")
    }

    @SuppressLint("MissingPermission")
    private fun rawWrite(packet: ByteArray) {
        var tries = 0
        while (true) {
            val ch = characteristic ?: throw IllegalStateException("Not connected")
            val g = gatt ?: throw IllegalStateException("Not connected")
            @Suppress("DEPRECATION")
            ch.value = packet
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(ch)) return
            tries++
            if (tries >= MAX_TRIES) throw IllegalStateException("BLE write busy")
            Thread.sleep(40L * tries)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private const val MAX_PAYLOAD = 17
        private const val INTER_PACKET_DELAY_MS = 8L
        private const val MAX_TRIES = 5

        private fun asciiOnly(t: String): String {
            val sb = StringBuilder(t.length)
            for (c in t) if (c.code in 0x20..0x7E) sb.append(c)
            return sb.toString()
        }
    }
}
