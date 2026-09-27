package com.navio.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), RideController.Listener {

    private lateinit var statusView: TextView
    private lateinit var hintNotif: TextView
    private lateinit var destView: TextView
    private lateinit var logView: TextView
    private lateinit var addressInput: EditText
    private lateinit var logScroll: ScrollView
    private val logBuffer = StringBuilder()
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RideController.addListener(this)

        statusView = findViewById(R.id.statusView)
        hintNotif = findViewById(R.id.hintNotif)
        destView = findViewById(R.id.destView)
        logView = findViewById(R.id.logView)
        addressInput = findViewById(R.id.addressInput)
        logScroll = findViewById(R.id.logScroll)
        logView.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnNotifSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            requestPermsThen { RideController.connectBle(this) }
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestPermsThen {
                RideForegroundService.start(this)
                val manual = addressInput.text.toString().trim()
                RideController.startRide(
                    manual.ifEmpty { RideController.currentDestination.orEmpty() },
                    this
                )
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            RideController.stopRide()
            RideForegroundService.stop(this)
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        RideController.removeListener(this)
    }

    private fun refreshUi() {
        val granted = notificationAccessGranted()
        hintNotif.visibility = if (granted) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnNotifSettings).visibility =
            if (granted) View.GONE else View.VISIBLE
    }

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(":").any { it.contains(packageName) }
    }

    private fun requestPermsThen(action: () -> Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAction = action
        requestPermissions(missing.toTypedArray(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        if (requestCode == REQUEST_CODE && action != null) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Some permissions denied — may not work fully", Toast.LENGTH_LONG).show()
            }
            action()
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            logBuffer.append(ts()).append(msg).append("\n")
            if (logBuffer.length > 8000) logBuffer.delete(0, logBuffer.length - 8000)
            logView.text = logBuffer.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onStatus(connected: Boolean, riding: Boolean) {
        runOnUiThread {
            statusView.text = when {
                riding && connected -> "RIDING • CONNECTED"
                riding -> "RIDING • NO BLE"
                connected -> "CONNECTED"
                else -> "DISCONNECTED"
            }
            statusView.setTextColor(if (connected) 0xFF16A34A.toInt() else 0xFFDC2626.toInt())
        }
    }

    override fun onDestination(text: String) {
        runOnUiThread {
            destView.text = text
        }
    }

    private fun ts(): String =
        SimpleDateFormat("[HH:mm:ss] ", Locale.US).format(Date())

    companion object {
        private const val REQUEST_CODE = 1
    }
}
