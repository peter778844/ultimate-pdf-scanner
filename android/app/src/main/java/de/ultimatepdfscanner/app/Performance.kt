package de.ultimatepdfscanner.app

import android.app.ActivityManager
import android.content.Context

/**
 * How much processing runs in parallel on this phone, decided at runtime from its hardware:
 * number of CPU cores, free memory and Android's low-RAM flag. A budget phone gets one photo at
 * a time, a recent flagship several photos plus both pages of a spread in parallel.
 */
object Performance {
    /** Memory one photo in flight needs: its intermediate images (~34 MB per megapixel) and the OCR models (~200 MB). */
    const val MB_PER_PHOTO = 600L // 12 MP
    const val MB_BASE = 200L
    const val MB_PER_MEGAPIXEL = 34L
    const val MAX_PHOTO_WORKERS = 3

    data class Plan(val photoWorkers: Int, val parallelPages: Boolean, val cores: Int, val availableMb: Long, val lowRam: Boolean, val hot: Boolean = false)

    /**
     * A weak phone (Android's low-RAM flag, under 3 GB memory or at most 4 cores): new installs start
     * with the fast scan quality there, so that a page does not take a minute.
     */
    fun weakDevice(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem < 3L * 1024 * 1024 * 1024 || Runtime.getRuntime().availableProcessors() <= 4
    }

    /**
     * Under 6 GB memory (or Android's low-RAM flag): the OCR models run without ONNX Runtime's memory arena.
     * The arena kept ~650 MB after one page (measured) and a 1.5 GB phone killed the app; ~20 % slower without.
     */
    fun leanMemory(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem < 6L * 1024 * 1024 * 1024
    }

    /** The phone reports that it throttles because of heat (Android 10+): process one photo at a time. */
    fun hot(context: Context): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
        context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE

    /** Model, Android version, CPU, memory and Java heap, for the log (diagnosing reports from other phones). */
    fun describe(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
            "${android.os.Build.SUPPORTED_ABIS.firstOrNull()}, ${Runtime.getRuntime().availableProcessors()} cores, " +
            "${info.totalMem / (1024 * 1024)} MB RAM, heap ${am.largeMemoryClass} MB, lowRam=${am.isLowRamDevice}, weak=${weakDevice(context)}, lean=${leanMemory(context)}"
    }

    fun plan(context: Context, megapixels: Int = 12): Plan {
        val mbPerPhoto = MB_BASE + MB_PER_MEGAPIXEL * megapixels
        val cores = Runtime.getRuntime().availableProcessors()
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availableMb = info.availMem / (1024 * 1024)
        val lowRam = am.isLowRamDevice || info.lowMemory
        // keep half of the free memory for the system and other apps
        val byMemory = (availableMb / 2 / mbPerPhoto).toInt()
        // one photo keeps about three cores busy (two pages plus OpenCV helper threads)
        val byCpu = cores / 3
        val hot = hot(context)
        val workers = if (lowRam || hot) 1 else minOf(MAX_PHOTO_WORKERS, byMemory, byCpu).coerceAtLeast(1)
        val parallelPages = !lowRam && !hot && cores >= 4 && availableMb >= 2 * mbPerPhoto
        return Plan(workers, parallelPages, cores, availableMb, lowRam, hot)
    }
}
