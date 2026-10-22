package com.esp.controller

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(ctx: Context): String? = sp(ctx).getString(Constants.KEY_DEVICE_ID, null)

    fun setDeviceId(ctx: Context, id: String) {
        sp(ctx).edit().putString(Constants.KEY_DEVICE_ID, id).apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(Constants.KEY_DEVICE_ID).apply()
    }
}
