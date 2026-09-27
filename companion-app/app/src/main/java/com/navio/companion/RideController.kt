package com.navio.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

object RideController {

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(connected: Boolean, riding: Boolean)
        fun onDestination(text: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gpsMutex = Mutex()
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var bleClient: NavioBleClient? = null
    private var locationProvider: LocationProvider? = null

    private var routeCoords: List<LatLon> = emptyList()
    private var routeNames: List<String> = emptyList()
    private var routeCum: List<Double> = emptyList()
    private var destination: String? = null
    private var bleConnected = false
    private var riding = false
    private var lastSentLoc: LatLon? = null
    private var lastSendTs = 0L
    private var speedSmooth = 0.0

    val isRiding: Boolean get() = riding
    val currentDestination: String? get() = destination

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun log(msg: String) {
        listeners.forEach { it.onLog(msg) }
    }

    private fun emitStatus() {
        listeners.forEach { it.onStatus(bleConnected, riding) }
    }

    private fun emitDestination(t: String) {
        listeners.forEach { it.onDestination(t) }
    }

    fun ensureClients(context: Context) {
        if (bleClient == null) {
            bleClient = NavioBleClient(context.applicationContext, object : NavioBleClient.Listener {
                override fun onConnected() {
                    bleConnected = true
                    emitStatus()
                    log("BLE connected")
                }

                override fun onDisconnected() {
                    bleConnected = false
                    emitStatus()
                    log("BLE disconnected")
                }

                override fun onLog(msg: String) {
                    log(msg)
                }
            })
        }
        if (locationProvider == null) {
            locationProvider = LocationProvider(context.applicationContext)
        }
    }

    fun connectBle(context: Context) {
        ensureClients(context)
        val appContext = context.applicationContext
        scope.launch { scanAndConnect(appContext) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanAndConnect(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner: BluetoothLeScanner? = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth LE unavailable")
            return
        }
        log("Scanning for ESP32...")
        val device = scanForNus(scanner)
        if (device == null) {
            log("No ESP32 found in 10s — make sure it is powered on")
            return
        }
        log("Connecting to ${device.name ?: "ESP32"}...")
        bleClient?.connect(device)
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForNus(scanner: BluetoothLeScanner): BluetoothDevice? =
        suspendCancellableCoroutine { cont ->
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NavioBleClient.SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    runCatching { scanner.stopScan(this) }
                    if (cont.isActive) cont.resume(result.device)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            scanner.startScan(listOf(filter), settings, cb)
            scope.launch {
                delay(10_000L)
                runCatching { scanner.stopScan(cb) }
                if (cont.isActive) cont.resume(null)
            }
        }

    fun startRide(destinationText: String, context: Context) {
        ensureClients(context)
        val dest = destinationText.trim()
        if (dest.isEmpty()) {
            log("No destination — waiting for Swiggy order or manual address")
            return
        }
        scope.launch {
            destination = dest
            emitDestination(dest)
            riding = true
            emitStatus()
            log("Starting ride to: $dest")
            if (!routeAndSend()) log("Route failed — check internet, GPS fix, or address")
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        val provider = locationProvider ?: return
        provider.start(object : LocationProvider.Callback {
            override fun onLocation(lat: Double, lon: Double, speedMps: Float) {
                scope.launch { onGps(LatLon(lat, lon), speedMps) }
            }
        })
    }

    private suspend fun onGps(p: LatLon, speedMps: Float) = gpsMutex.withLock {
        if (!riding) return@withLock
        val now = SystemClock.elapsedRealtime()
        val moved = lastSentLoc?.let { GeoMath.haversine(it, p) } ?: Double.MAX_VALUE
        if (moved < 10.0 && now - lastSendTs < 3000L) return@withLock
        try {
            sendUpdate(p, speedMps)
        } catch (e: Exception) {
            log("Update send failed: ${e.message}")
        }
        lastSentLoc = p
        lastSendTs = now
    }

    private suspend fun sendUpdate(p: LatLon, speedMps: Float) {
        if (routeCoords.isEmpty()) return
        val client = bleClient ?: return
        val si = RouteMath.snapIndex(routeCoords, p).coerceAtMost(routeCoords.size - 1)
        val r = routeCoords[si]
        val nx = routeCoords[minOf(si + 1, routeCoords.size - 1)]
        val hdg = GeoMath.bearing(r, nx)
        val planned = RouteMath.plannedSpeedKmh(routeCoords, si)
        val measured = if (speedMps >= 0f) speedMps * 3.6 else planned
        speedSmooth += (measured - speedSmooth) * 0.3
        val dist = RouteMath.estimateDistanceToTurn(routeCoords, routeCum, si)
        val turn = RouteMath.estimateTurnType(routeCoords, si)
        val road = RouteMath.sanitizeRoad(routeNames.getOrElse(si) { "Route" })
        val json = "{\"t\":\"U\",\"lat\":${GeoMath.round6(p.lat)},\"lon\":${GeoMath.round6(p.lon)}," +
            "\"hdg\":${GeoMath.round1(hdg)},\"spd\":${GeoMath.round1(speedSmooth)}," +
            "\"dist\":$dist,\"turn\":$turn,\"road\":\"$road\"}"
        client.sendJson(json)
    }

    private suspend fun routeAndSend(): Boolean {
        val dest = destination ?: return false
        val provider = locationProvider ?: return false
        val current = provider.lastKnown() ?: run {
            log("No GPS fix yet — try again once GPS locks")
            return false
        }
        log("Geocoding destination...")
        val end = RoutingService.geocode(dest) ?: run {
            log("Geocode failed — check address/internet")
            return false
        }
        log("Fetching route from OSRM...")
        val result = RoutingService.route(current, end) ?: run {
            log("All routers failed")
            return false
        }
        val straight = GeoMath.haversine(current, end)
        if (result.distanceM > straight * 4 && result.distanceM > 2000) {
            log("Route looks unrealistically long — verify destination")
        }
        routeCoords = result.coords
        routeNames = result.names
        routeCum = RouteMath.computeCum(result.coords)
        lastSentLoc = null
        lastSendTs = 0L
        log("Route: ${routeCoords.size} pts, ${"%.2f".format(result.distanceM / 1000.0)} km")
        return sendRoutePacket()
    }

    private suspend fun sendRoutePacket(): Boolean {
        if (routeCoords.isEmpty()) return false
        val client = bleClient ?: run {
            log("Not connected — route will send after BLE connects")
            return false
        }
        val pts = RouteMath.downsample(routeCoords, 48)
            .map { "[${GeoMath.round6(it.lat)},${GeoMath.round6(it.lon)}]" }
        val json = "{\"t\":\"R\",\"pts\":${pts.joinToString(",", prefix = "[", postfix = "]")}}"
        return try {
            client.sendJson(json)
            true
        } catch (e: Exception) {
            log("Route send failed: ${e.message}")
            false
        }
    }

    fun onOrderDetected(order: ParsedOrder) {
        val address = order.address
        if (address.isNullOrBlank()) {
            log("Order from ${order.source} had no address — enter it manually")
            return
        }
        log("New ${order.source} order: ${address.take(48)}")
        destination = address
        emitDestination(address)
        if (!riding) {
            log("Tap Start Ride to begin navigation")
            return
        }
        scope.launch {
            gpsMutex.withLock { routeAndSend() }
        }
    }

    fun stopRide() {
        riding = false
        locationProvider?.stop()
        routeCoords = emptyList()
        routeNames = emptyList()
        routeCum = emptyList()
        lastSentLoc = null
        emitStatus()
        log("Ride stopped")
    }
}
