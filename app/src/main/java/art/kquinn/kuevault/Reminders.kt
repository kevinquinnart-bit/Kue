package art.kquinn.kuevault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Warranty reminders: once a day the phone looks at My things, and says when a warranty has 30 days left
 * and when it has 7, so anything wrong can be claimed for in time. Each is said once.
 */
object Reminders {
    private const val CHANNEL = "warranty"
    private const val WORK = "warranty-check"

    fun start(context: Context) {
        val req = PeriodicWorkRequestBuilder<Check>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Whether the phone lets KueVault show reminders (Android 13 on asks the person once). */
    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The reminders due today that haven't been said: (thing, days left), for 30 and 7 days. */
    fun due(things: List<Thing>, said: Set<String>, today: java.time.LocalDate): List<Pair<Thing, Int>> = things.mapNotNull { t ->
        val end = t.warrantyEnds ?: return@mapNotNull null
        val left = java.time.temporal.ChronoUnit.DAYS.between(today, end).toInt()
        val mark = when (left) { in 8..30 -> 30; in 0..7 -> 7; else -> return@mapNotNull null }
        if ((t.id + ":" + mark + ":" + end) in said) null else t to left
    }

    fun mark(t: Thing, left: Int) = t.id + ":" + (if (left > 7) 30 else 7) + ":" + t.warrantyEnds

    class Check(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext
            if (Things.all.isEmpty()) Things.init(c)
            if (!allowed(c)) return Result.success()
            val prefs = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
            val said = prefs.getStringSet("said", emptySet())!!.toMutableSet()
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Warranty reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val day = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
            for ((t, left) in due(Things.all.toList(), said, Things.today())) {
                val text = t.title + "'s warranty ends " + (if (left == 0) "today" else "in $left day" + (if (left == 1) "" else "s")) + ", on " + t.warrantyEnds!!.format(day) + "."
                val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.kue_status).setContentTitle("Warranty ending")
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text + " If anything is wrong with it, claim before then."))
                    .setContentIntent(open).setAutoCancel(true).build()
                runCatching { nm.notify(t.id.hashCode(), n) }
                said += mark(t, left)
            }
            prefs.edit().putStringSet("said", said).apply()
            return Result.success()
        }
    }
}

/** Reads words out loud in the phone's own voice, British English where the phone has it. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    fun say(context: Context, text: String) {
        val t = tts
        if (t != null && ready) { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kuevault"); return }
        waiting = text
        if (t == null) tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.UK
                waiting?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "kuevault") }
            } else Work.problem = "This phone has no voice to read with. It can be added in the phone's Settings, under Text-to-speech."
            waiting = null
        }
    }

    fun stop() { tts?.stop() }
}
