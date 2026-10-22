package com.esp.controller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动入口：已配过网 -> 控制页；否则 -> 配网页。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val target = if (Prefs.getDeviceId(this).isNullOrEmpty())
            ProvisionActivity::class.java
        else
            ControlActivity::class.java

        startActivity(Intent(this, target))
        finish()
    }
}
