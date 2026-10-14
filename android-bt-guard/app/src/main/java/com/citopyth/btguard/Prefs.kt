package com.citopyth.btguard

import android.content.Context

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("btguard", Context.MODE_PRIVATE)

    fun sirenEnabled(context: Context) = prefs(context).getBoolean("siren", true)
    fun setSirenEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("siren", on).apply()

    fun originalName(context: Context): String? = prefs(context).getString("originalName", null)
    fun setOriginalName(context: Context, name: String) =
        prefs(context).edit().putString("originalName", name).apply()
    fun clearOriginalName(context: Context) = prefs(context).edit().remove("originalName").apply()

    fun bondedImported(context: Context) = prefs(context).getBoolean("bondedImported", false)
    fun setBondedImported(context: Context) = prefs(context).edit().putBoolean("bondedImported", true).apply()

    fun lastBootTime(context: Context) = prefs(context).getLong("lastBootTime", 0L)
    fun setLastBootTime(context: Context, time: Long) = prefs(context).edit().putLong("lastBootTime", time).apply()

    /** "Wächter dauerhaft aktiv": keep the foreground service running. On by default. */
    fun watchEnabled(context: Context) = prefs(context).getBoolean("watchEnabled", true)
    fun setWatchEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("watchEnabled", on).apply()

    fun bondBaseline(context: Context) = prefs(context).getBoolean("bondBaseline", false)
    fun setBondBaseline(context: Context) = prefs(context).edit().putBoolean("bondBaseline", true).apply()
}
