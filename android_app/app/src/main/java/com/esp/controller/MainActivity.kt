package com.esp.controller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动入口：直接进控制页（App 的默认界面就是四个控制按键）。
 * 没有设备时控制页会显示空状态，引导用户去「设置」添加设备，
 * 不再像旧版本那样强制跳到配网页。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DeviceStore.migrateLegacy(this)
        startActivity(Intent(this, ControlActivity::class.java))
        finish()
    }
}
