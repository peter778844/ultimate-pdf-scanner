package de.ultimatepdfscanner.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Entry points outside the app (tile, widget, launcher shortcuts) open the camera in a mode. */
object QuickAccess {
    const val EXTRA = "start"

    fun intent(context: Context, start: String): Intent = Intent(context, MainActivity::class.java)
        .setAction("de.ultimatepdfscanner.app.START.$start") // distinct actions keep the pending intents apart
        .putExtra(EXTRA, start)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, start: String, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, intent(context, start), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Camera mode for a start value. */
    fun mode(start: String?): CaptureMode? = when (start) {
        "scan" -> CaptureMode.DOCUMENT
        "book" -> CaptureMode.BOOK
        "qr" -> CaptureMode.QR
        else -> null
    }
}

/** Quick Settings tile "Scannen". */
class ScanTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(QuickAccess.pending(this, "scan", 1))
        else @Suppress("DEPRECATION") startActivityAndCollapse(QuickAccess.intent(this, "scan"))
    }
}

/** Home-screen widget: Scannen / Buch / QR-Code. */
class ScanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
            setOnClickPendingIntent(R.id.w_scan, QuickAccess.pending(context, "scan", 11))
            setOnClickPendingIntent(R.id.w_book, QuickAccess.pending(context, "book", 12))
            setOnClickPendingIntent(R.id.w_qr, QuickAccess.pending(context, "qr", 13))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
