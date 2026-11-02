package de.privat.boom2local.app

import android.content.Context
import android.content.pm.PackageManager

/** One self-check, as shown on the security screen. */
class CheckResult(val title: String, val ok: Boolean, val detail: String)

/**
 * Checks the app's own guarantees at runtime, from the installed package rather than from
 * the source tree.
 *
 * This matters because it is the one report you do not have to take on trust: it reads the
 * permission list Android itself holds for this APK. If the app ever shipped with network
 * access, this screen would say so on your own phone.
 */
object SelfCheck {

    private val forbidden = listOf(
        "android.permission.INTERNET" to "Netzwerkzugriff",
        "android.permission.ACCESS_NETWORK_STATE" to "Netzwerkstatus",
        "android.permission.ACCESS_WIFI_STATE" to "WLAN-Status",
        "android.permission.ACCESS_FINE_LOCATION" to "Standort (genau)",
        "android.permission.ACCESS_COARSE_LOCATION" to "Standort (grob)",
        "android.permission.RECORD_AUDIO" to "Mikrofon",
        "android.permission.READ_CONTACTS" to "Kontakte",
    )

    fun declaredPermissions(context: Context): List<String> = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.toList()?.sorted() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun run(context: Context): List<CheckResult> {
        val declared = declaredPermissions(context)
        val results = mutableListOf<CheckResult>()

        if (declared.isEmpty()) {
            results += CheckResult(
                "Berechtigungsliste",
                false,
                "Konnte nicht gelesen werden. Die folgenden Pruefungen sind dann nicht aussagekraeftig.",
            )
        }

        for ((permission, label) in forbidden) {
            val requested = declared.contains(permission)
            val granted = context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
            results += CheckResult(
                title = label,
                ok = !requested && !granted,
                detail = when {
                    requested -> "$permission wird angefordert - das darf nicht sein."
                    granted -> "$permission ist erteilt, obwohl es nicht angefordert wird."
                    else -> "$permission ist nicht angefordert und nicht erteilt."
                },
            )
        }

        results += CheckResult(
            title = "Angeforderte Berechtigungen",
            ok = declared.all { it.startsWith("android.permission.BLUETOOTH") },
            detail = if (declared.isEmpty()) {
                "unbekannt"
            } else {
                declared.joinToString("\n") { it.removePrefix("android.permission.") }
            },
        )

        return results
    }
}
