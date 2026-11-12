package com.latencot.speek.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.latencot.speek.data.Store

/** A scheduled call's time has come. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val book = CallBook(context)
        val call = book.scheduled.firstOrNull { it.first.key == key }?.first ?: return
        book.unschedule(key)
        RingService.start(context, call)
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}

/** After a reboot or an update: set the alarms again and reconnect. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Calls.restore(context)
        if (Store(context).connection != null && Store(context).listen) ListenService.start(context)
    }
}
