package app.gstreminder.data

import android.app.Activity
import android.telephony.SmsManager
import androidx.room.withTransaction
import app.gstreminder.core.MessageBuilder
import app.gstreminder.core.ParsedDealer
import app.gstreminder.core.ParsedImport
import app.gstreminder.core.Pending
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class Repository(private val db: AppDb, val settings: Settings) {
    val cycles = db.cycles()
    val dealers = db.dealers()
    val usage = db.usage()

    fun today(): String = LocalDate.now().toString()

    suspend fun usedToday(): Int = usage.used(today()) ?: 0

    // ---------------------------------------------------------------- import

    data class ImportResult(val cycleId: Long, val updated: Boolean, val newlyQueued: Int, val cancelled: Int)

    /**
     * Same period as the active cycle -> update it in place (matched by GSTIN), so dealers who
     * filed since the last import are not reminded. Different period -> start a new cycle.
     */
    suspend fun import(parsed: ParsedImport, period: String, fileName: String): ImportResult = db.withTransaction {
        val now = System.currentTimeMillis()
        val active = cycles.active()
        if (active != null && active.period.equals(period, ignoreCase = true)) {
            val existing = dealers.forCycle(active.id).associateBy { it.gstin }
            val incoming = parsed.dealers.associateBy { it.gstin }
            var queued = 0
            var cancelled = 0
            val updates = ArrayList<Dealer>()
            for (d in existing.values) {
                val p = incoming[d.gstin]
                val merged = if (p == null) {
                    // Dropped from the non-filer list: don't remind any more.
                    if (d.status == SmsStatus.QUEUED || d.status == SmsStatus.NO_MOBILE) {
                        d.copy(status = SmsStatus.NOT_NEEDED, lastError = "Not in latest sheet", updatedAt = now)
                    } else d
                } else merge(d, p, now)
                if (merged.status == SmsStatus.QUEUED && d.status != SmsStatus.QUEUED) queued++
                if (d.status == SmsStatus.QUEUED && merged.status != SmsStatus.QUEUED) cancelled++
                if (merged != d) updates += merged
            }
            dealers.updateAll(updates)
            val added = parsed.dealers.filter { it.gstin !in existing }.map { newDealer(active.id, it, now) }
            dealers.insertAll(added)
            queued += added.count { it.status == SmsStatus.QUEUED }
            cycles.update(active.copy(fileName = fileName, sheetName = parsed.sheetName, importedAt = now))
            ImportResult(active.id, updated = true, newlyQueued = queued, cancelled = cancelled)
        } else {
            cycles.deactivateAll()
            val id = cycles.insert(Cycle(period = period, fileName = fileName, sheetName = parsed.sheetName, importedAt = now))
            val list = parsed.dealers.map { newDealer(id, it, now) }
            dealers.insertAll(list)
            ImportResult(id, updated = false, newlyQueued = list.count { it.status == SmsStatus.QUEUED }, cancelled = 0)
        }
    }

    private fun statusFor(p: ParsedDealer) = when {
        p.pending == Pending.NONE -> SmsStatus.NOT_NEEDED
        p.phone == null -> SmsStatus.NO_MOBILE
        else -> SmsStatus.QUEUED
    }

    private fun extrasText(p: ParsedDealer) = p.extras.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    private fun newDealer(cycleId: Long, p: ParsedDealer, now: Long) = Dealer(
        cycleId = cycleId, rowNum = p.row, gstin = p.gstin, name = p.name, phone = p.phone, rawPhone = p.rawPhone,
        pending = p.pending.name, priority = p.pending.priority, extras = extrasText(p), status = statusFor(p), updatedAt = now,
    )

    private fun merge(d: Dealer, p: ParsedDealer, now: Long): Dealer {
        val alreadyMessaged = d.status in setOf(SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.SENDING)
        val phoneChanged = p.phone != d.phone
        val status = when {
            alreadyMessaged && !(phoneChanged && p.pending != Pending.NONE && p.phone != null) -> d.status
            p.pending == Pending.NONE -> SmsStatus.NOT_NEEDED
            p.phone == null -> SmsStatus.NO_MOBILE
            d.status == SmsStatus.FAILED && !phoneChanged -> SmsStatus.FAILED
            else -> SmsStatus.QUEUED
        }
        val resetAttempts = status == SmsStatus.QUEUED && d.status != SmsStatus.QUEUED
        return d.copy(
            rowNum = p.row, name = p.name, phone = p.phone, rawPhone = p.rawPhone,
            pending = p.pending.name, priority = p.pending.priority, extras = extrasText(p),
            status = status,
            attempts = if (resetAttempts) 0 else d.attempts,
            lastError = if (status == d.status) d.lastError else null,
            updatedAt = if (status != d.status || phoneChanged) now else d.updatedAt,
        )
    }

    // ---------------------------------------------------------------- messages

    suspend fun buildMessage(d: Dealer): MessageBuilder.Message {
        val period = cycles.get(d.cycleId)?.period ?: ""
        return MessageBuilder.build(settings.template, d.name, d.gstin, period, d.pendingEnum, settings.sender)
    }

    // ---------------------------------------------------------------- dealer actions

    suspend fun markFiled(id: Long) {
        val d = dealers.get(id) ?: return
        val status = if (d.status in setOf(SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.SENDING)) d.status else SmsStatus.NOT_NEEDED
        dealers.update(d.copy(pending = Pending.NONE.name, priority = Pending.NONE.priority, status = status, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePhone(id: Long, phone: String?) {
        val d = dealers.get(id) ?: return
        val status = when {
            d.filed -> d.status
            phone == null -> SmsStatus.NO_MOBILE
            d.status in setOf(SmsStatus.NO_MOBILE, SmsStatus.FAILED) -> SmsStatus.QUEUED
            else -> d.status
        }
        dealers.update(
            d.copy(
                phone = phone, rawPhone = phone ?: "", status = status,
                attempts = if (status == SmsStatus.QUEUED && d.status != SmsStatus.QUEUED) 0 else d.attempts,
                lastError = if (status != d.status) null else d.lastError,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Put a failed / already-sent dealer back in the queue. */
    suspend fun requeue(id: Long) {
        val d = dealers.get(id) ?: return
        if (d.phone == null || d.filed) return
        dealers.update(d.copy(status = SmsStatus.QUEUED, attempts = 0, lastError = null, updatedAt = System.currentTimeMillis()))
    }

    suspend fun retryAllFailed(cycleId: Long) {
        val failed = dealers.forCycle(cycleId).filter { it.status == SmsStatus.FAILED && it.phone != null && !it.filed }
        dealers.updateAll(failed.map { it.copy(status = SmsStatus.QUEUED, attempts = 0, lastError = null, updatedAt = System.currentTimeMillis()) })
    }

    // ---------------------------------------------------------------- send results

    /** Called by the sent-status broadcast for each SMS part. */
    suspend fun onSent(id: Long, part: Int, parts: Int, resultCode: Int) {
        val d = dealers.get(id) ?: return
        if (d.status != SmsStatus.SENDING) return
        val now = System.currentTimeMillis()
        if (resultCode == Activity.RESULT_OK) {
            if (part == parts - 1) dealers.update(d.copy(status = SmsStatus.SENT, sentAt = now, lastError = null, updatedAt = now))
            return
        }
        val error = errorText(resultCode)
        if (resultCode in NOT_SENT_CODES) {
            // The SMS never left the phone: give back the quota and keep it queued.
            usage.add(today(), -d.segments)
            dealers.update(d.copy(status = SmsStatus.QUEUED, attempts = (d.attempts - 1).coerceAtLeast(0), lastError = error, updatedAt = now))
        } else {
            val status = if (d.attempts >= MAX_ATTEMPTS) SmsStatus.FAILED else SmsStatus.QUEUED
            dealers.update(d.copy(status = status, lastError = error, updatedAt = now))
        }
    }

    /** Called by the delivery-report broadcast. [delivered] null = report not conclusive. */
    suspend fun onDelivered(id: Long, delivered: Boolean?) {
        val d = dealers.get(id) ?: return
        val now = System.currentTimeMillis()
        when (delivered) {
            true -> dealers.update(d.copy(status = SmsStatus.DELIVERED, deliveredAt = now, lastError = null, updatedAt = now))
            false -> dealers.update(d.copy(status = SmsStatus.FAILED, lastError = "Not delivered (operator report)", updatedAt = now))
            null -> {}
        }
    }

    /** SMS still "sending" long after the fact: the phone never reported back. */
    suspend fun expireStaleSending() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        val stale = dealers.staleSending(cutoff)
        dealers.updateAll(stale.map { it.copy(status = SmsStatus.FAILED, lastError = "No confirmation from phone. Tap Resend if needed.") })
    }

    // ---------------------------------------------------------------- export

    suspend fun exportCsv(cycleId: Long, out: OutputStream) {
        val cycle = cycles.get(cycleId)
        val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ENGLISH)
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        fun t(ms: Long?) = ms?.let { fmt.format(Date(it)) } ?: ""
        out.bufferedWriter().use { w ->
            w.write("﻿") // Excel-friendly UTF-8
            w.write("Period,Row,GSTIN,Trade Name,Mobile,Filing Status,SMS Status,Sent At,Delivered At,Attempts,Error,Message\n")
            for (d in dealers.forCycle(cycleId)) {
                val filing = if (d.filed) "Filed" else "Not filed (${d.pendingEnum.label})"
                w.write(
                    listOf(
                        q(cycle?.period), d.rowNum.toString(), q(d.gstin), q(d.name), q(d.phone ?: d.rawPhone), q(filing),
                        q(statusLabel(d.status)), q(t(d.sentAt)), q(t(d.deliveredAt)), d.attempts.toString(), q(d.lastError), q(d.message),
                    ).joinToString(",") + "\n"
                )
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val ERR_ANDROID_LIMIT = "Android blocked sending"

        @Suppress("DEPRECATION")
        private val NOT_SENT_CODES = setOf(
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NULL_PDU,
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
        )

        fun errorText(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Failed (operator rejected or daily limit reached)"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Phone in airplane mode / radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Failed to create SMS"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No network signal"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "$ERR_ANDROID_LIMIT: allow 'send many messages' when Android asks"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Number not allowed"
            else -> "Failed (code $code)"
        }

        fun statusLabel(status: String): String = when (status) {
            SmsStatus.NOT_NEEDED -> "Not needed (filed)"
            SmsStatus.NO_MOBILE -> "No mobile"
            SmsStatus.QUEUED -> "Queued"
            SmsStatus.SENDING -> "Sending"
            SmsStatus.SENT -> "Sent"
            SmsStatus.DELIVERED -> "Delivered"
            SmsStatus.FAILED -> "Failed"
            else -> status
        }
    }
}
