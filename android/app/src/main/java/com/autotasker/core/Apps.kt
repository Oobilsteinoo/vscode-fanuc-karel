package com.autotasker.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Liste des applications lançables, pour le sélecteur. */
object Apps {

    data class Info(val packageName: String, val label: String)

    fun list(context: Context): List<Info> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { resolved ->
                    val pkg = resolved.activityInfo?.packageName ?: return@mapNotNull null
                    Info(pkg, resolved.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun labelFor(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: PackageManager.NameNotFoundException) {
        packageName
    } catch (t: Throwable) {
        packageName
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null
}
