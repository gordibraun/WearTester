package com.example.weartester

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class PhoneMainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 18f
        }
        val openNotificationAccess = Button(this).apply {
            text = "Разрешить доступ к уведомлениям"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val refresh = Button(this).apply {
            text = "Обновить статус"
            setOnClickListener { updateStatus() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 40)
            addView(statusView, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(openNotificationAccess, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(refresh, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(root)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        statusView.text = buildString {
            appendLine("WearTester Relay")
            appendLine()
            appendLine(PhoneGlucoseSender.lastStatus(this@PhoneMainActivity))
            appendLine()
            appendLine(GlucoseSyncBridge.lastWatchStatus(this@PhoneMainActivity))
            appendLine()
            appendLine("Если xDrip broadcast не приходит, включи доступ к уведомлениям: тогда relay будет брать свежую глюкозу из уведомления xDrip/AAPS и отправлять на часы.")
        }
    }
}
