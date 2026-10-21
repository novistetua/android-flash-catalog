package com.novis.flashcatalog

import android.content.Context

object AppVer {
    /** Версия вида «5.8» (из номера сборки). */
    fun name(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) { "1.0" }

    /** Имя APK при отправке: FlashCatalog-5.8.apk */
    fun apkName(ctx: Context): String = "FlashCatalog-" + name(ctx) + ".apk"
}
