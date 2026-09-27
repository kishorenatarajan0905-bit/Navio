package com.navio.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationListener : NotificationListenerService() {

    private val knownPackages = setOf(
        "com.swiggy.gulerix",
        "in.swiggy.android",
        "com.application.zomato"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        val combined = "$title $big".lowercase()
        val relevant = pkg in knownPackages || combined.contains("swiggy") || combined.contains("zomato")
        if (!relevant) return
        val order = OrderParser.parse(pkg, title, big)
        if (order != null) RideController.onOrderDetected(order)
    }
}
