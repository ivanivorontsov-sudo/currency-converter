package ru.vorontsov.kurs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RatesWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Rates.load(applicationContext)
        val ok = Rates.refresh(applicationContext)
        checkAlerts(applicationContext)
        RatesWidget.updateAll(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
    companion object {
        fun schedule(ctx: Context) {
            val st = Store.get(ctx); val wm = WorkManager.getInstance(ctx)
            if (st.bgHours <= 0) { wm.cancelUniqueWork("rates"); return }
            val req = PeriodicWorkRequestBuilder<RatesWorker>(st.bgHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork("rates", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

fun checkAlerts(ctx: Context) {
    val st = Store.get(ctx); val r = Rates.effective(st); if (r.isEmpty()) return
    var changed = false
    val list = st.alerts.map { a ->
        val v = Rates.rate(r, a.from, a.to) ?: return@map a
        val hit = if (a.above) v >= a.value else v <= a.value
        if (hit && !a.fired) { notify(ctx, a, v); changed = true; a.copy(fired = true) }
        else if (!hit && a.fired) { changed = true; a.copy(fired = false) }   // снова взводим
        else a
    }
    if (changed) { st.alerts = list; st.save() }
}

private fun notify(ctx: Context, a: Alert, v: Double) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("alerts", "Уведомления о курсах", NotificationManager.IMPORTANCE_HIGH))
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(ctx, "alerts").setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("${a.from}/${a.to} ${if (a.above) "выше" else "ниже"} ${fmtPlain(a.value)}")
        .setContentText("Сейчас 1 ${a.from} = ${fmtPlain(v)} ${a.to}").setContentIntent(pi).setAutoCancel(true).build()
    NotificationManagerCompat.from(ctx).notify(a.id.toInt(), n)
}

fun fmtPlain(v: Double): String = Fmt.num(v, -1, true)

class RatesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { Rates.load(ctx); render(ctx, m, ids) }
    companion object {
        fun updateAll(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            render(ctx, m, m.getAppWidgetIds(ComponentName(ctx, RatesWidget::class.java)))
        }
        private fun render(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val st = Store.get(ctx); val r = Rates.effective(st); val q = st.queue
            val base = q.firstOrNull() ?: "RUB"
            val body = q.drop(1).take(5).joinToString("\n") { c ->
                val v = Rates.rate(r, c, base)
                "${cur(c).flag} 1 $c = ${v?.let { Fmt.num(it, -1, true) } ?: "—"} $base"
            }.ifEmpty { "Добавьте валюты в очередь" }
            val t = Rates.snap?.fetchedAt?.let { "Обновлено " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(it)) } ?: "Нет данных"
            val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.widget)
                v.setTextViewText(R.id.title, "Курсы · ${st.profiles[st.active].name}")
                v.setTextViewText(R.id.body, body); v.setTextViewText(R.id.time, t)
                v.setOnClickPendingIntent(R.id.root, pi)
                m.updateAppWidget(id, v)
            }
        }
    }
}
