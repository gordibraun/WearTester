package com.example.weartester

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PhoneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("WearTesterRelay", "Phone relay ready after boot")
        }
    }
}

