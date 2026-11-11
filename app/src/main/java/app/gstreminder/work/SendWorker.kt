package app.gstreminder.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.gstreminder.data.Repository
import app.gstreminder.data.Settings
import app.gstreminder.data.SmsStatus
import app.gstreminder.repo
import app.gstreminder.sms.SmsSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Sends queued reminders one by one until the daily limit is reached or the queue is empty.
 * Whatever is left stays QUEUED and is picked up by tomorrow's automatic run.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!running.tryLock()) return Result.success() // another batch is already sending
        try {
            return run()
        } finally {
            running.unlock()
        }
    }

    private suspend fun run(): Result {
        val ctx = applicationContext
        val repo = ctx.repo
        val settings = repo.settings
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val onlyId = inputData.getLong(KEY_DEALER_ID, -1)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"))
        val label = if (manual) "Manual" else "Auto"

        repo.expireStaleSending()

        if (!SmsSender.hasPermission(ctx)) {
            settings.lastRun = "$label $time: SMS permission not granted. Open the app and tap Send."
            return Result.success()
        }
        val hour = LocalTime.now().hour
        if (!manual && (hour < settings.startHour || hour >= settings.endHour)) {
            settings.lastRun = "$label $time: outside sending hours (${settings.startHour}:00–${settings.endHour}:00), skipped."
            return Result.success()
        }
        val cycle = repo.cycles.active() ?: return Result.success()

        val startedAt = System.currentTimeMillis()
        var sent = 0
        var failed = 0
        var consecutiveFailures = 0
        var stopReason: String? = null

        while (true) {
            val remaining = settings.dailyLimit - repo.usedToday()
            if (remaining <= 0) { stopReason = "daily limit of ${settings.dailyLimit} reached"; break }

            val dealer = if (onlyId >= 0) {
                if (sent + failed > 0) break
                repo.dealers.get(onlyId)?.takeIf { it.phone != null && it.status != SmsStatus.SENDING }
            } else repo.dealers.nextQueued(cycle.id)
            if (dealer == null || dealer.phone == null) break

            val msg = repo.buildMessage(dealer)
            if (msg.info.segments > remaining) { stopReason = "daily limit of ${settings.dailyLimit} reached"; break }

            val now = System.currentTimeMillis()
            repo.dealers.update(
                dealer.copy(
                    status = SmsStatus.SENDING, message = msg.text, segments = msg.info.segments,
                    attempts = dealer.attempts + 1, lastError = null, sentAt = now, deliveredAt = null, updatedAt = now,
                )
            )
            repo.usage.add(repo.today(), msg.info.segments)
            setProgress(workDataOf(KEY_SENT to sent, KEY_CURRENT to dealer.name))

            try {
                SmsSender.send(ctx, dealer.id, dealer.phone, msg.text, settings.subscriptionId)
            } catch (e: Exception) {
                repo.usage.add(repo.today(), -msg.info.segments)
                val d = repo.dealers.get(dealer.id)!!
                repo.dealers.update(d.copy(status = SmsStatus.FAILED, lastError = "Could not send: ${e.message}"))
                stopReason = "phone refused to send (${e.message})"
                failed++
                break
            }

            // Wait for the phone to confirm (or reject) this SMS before sending the next one.
            val outcome = awaitResult(repo, dealer.id)
            when {
                outcome == null -> { stopReason = "no confirmation from phone. Check for an Android prompt."; break }
                outcome.status == SmsStatus.SENT || outcome.status == SmsStatus.DELIVERED -> { sent++; consecutiveFailures = 0 }
                else -> {
                    failed++
                    consecutiveFailures++
                    if (outcome.lastError?.startsWith(Repository.ERR_ANDROID_LIMIT) == true) {
                        stopReason = "Android asked for permission to send many SMS. Tap Allow (tick 'Always allow') and press Send again."
                        break
                    }
                    if (consecutiveFailures >= 3) {
                        stopReason = "3 failures in a row (${outcome.lastError}). Check signal / SIM balance / operator limit."
                        break
                    }
                }
            }

            if (System.currentTimeMillis() - startedAt > MAX_RUN_MS) {
                // WorkManager stops workers after ~10 minutes: continue in a fresh run.
                enqueueContinuation(ctx, manual)
                stopReason = "continuing…"
                break
            }
            delay(settings.gapSeconds * 1000L)
        }

        val queuedLeft = repo.dealers.forCycle(cycle.id).count { it.status == SmsStatus.QUEUED }
        val summary = buildString {
            append("$label $time: sent $sent")
            if (failed > 0) append(", failed $failed")
            if (stopReason != null) append(" – stopped: $stopReason")
            if (queuedLeft > 0 && stopReason?.startsWith("continuing") != true) append(". $queuedLeft queued for next run.")
        }
        if (sent + failed > 0 || stopReason != null) settings.lastRun = summary
        return Result.success()
    }

    private suspend fun awaitResult(repo: Repository, id: Long): app.gstreminder.data.Dealer? {
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val d = repo.dealers.get(id) ?: return null
            if (d.status != SmsStatus.SENDING) return d
            delay(500)
        }
        return null
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_DEALER_ID = "dealerId"
        const val KEY_SENT = "sent"
        const val KEY_CURRENT = "current"
        private const val MAX_RUN_MS = 8 * 60 * 1000L
        private const val RESULT_TIMEOUT_MS = 45 * 1000L
        private val running = Mutex()

        const val WORK_SEND = "send"
        const val WORK_CONTINUE = "send-continue"
        const val WORK_DAILY = "daily"

        private fun enqueueContinuation(context: Context, manual: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_CONTINUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SendWorker>()
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .setInputData(workDataOf(KEY_MANUAL to manual))
                    .build(),
            )
        }
    }
}

object Scheduler {
    fun sendNow(context: Context, dealerId: Long = -1) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (dealerId >= 0) "send-one-$dealerId" else SendWorker.WORK_SEND,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf(SendWorker.KEY_MANUAL to true, SendWorker.KEY_DEALER_ID to dealerId))
                .build(),
        )
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SendWorker.WORK_SEND)
        wm.cancelUniqueWork(SendWorker.WORK_CONTINUE)
    }

    /** Runs a batch every day at the configured time. [replace] = settings changed. */
    fun scheduleDaily(context: Context, settings: Settings, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!settings.autoSend) {
            wm.cancelUniqueWork(SendWorker.WORK_DAILY)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(settings.sendAtMinutes / 60, settings.sendAtMinutes % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<SendWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().build())
            .setInputData(workDataOf(SendWorker.KEY_MANUAL to false))
            .build()
        wm.enqueueUniquePeriodicWork(
            SendWorker.WORK_DAILY,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun nextAutoRunText(settings: Settings): String {
        if (!settings.autoSend) return "Auto-send is off"
        val now = LocalDateTime.now()
        val at = LocalTime.of(settings.sendAtMinutes / 60, settings.sendAtMinutes % 60)
        val day = if (now.toLocalTime().isBefore(at)) "today" else "tomorrow"
        return "Next auto-send: $day ${at.format(DateTimeFormatter.ofPattern("hh:mm a"))}"
    }
}
