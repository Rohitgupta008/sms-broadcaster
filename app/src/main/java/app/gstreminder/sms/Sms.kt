package app.gstreminder.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import app.gstreminder.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsSender {
    const val ACTION_SENT = "app.gstreminder.SMS_SENT"
    const val ACTION_DELIVERED = "app.gstreminder.SMS_DELIVERED"
    const val EXTRA_ID = "id"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun manager(context: Context, subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) {
            val m = context.getSystemService(SmsManager::class.java)
            if (subscriptionId >= 0) m.createForSubscriptionId(subscriptionId) else m
        } else {
            if (subscriptionId >= 0) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }

    private fun pending(context: Context, action: String, id: Long, part: Int, parts: Int): PendingIntent {
        val intent = Intent(action)
            .setClass(context, SmsResultReceiver::class.java)
            .setData(Uri.parse("gstreminder://sms/$action/$id/$part"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, parts)
        // Mutable so the system can attach the delivery-report PDU.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, (id * 16 + part).toInt(), intent, flags)
    }

    /** Hands the SMS to the phone. Results arrive later in [SmsResultReceiver]. */
    fun send(context: Context, dealerId: Long, phone: String, text: String, subscriptionId: Int) {
        val sms = manager(context, subscriptionId)
        val parts = sms.divideMessage(text)
        val dest = "+91$phone"
        if (parts.size <= 1) {
            sms.sendTextMessage(
                dest, null, text,
                pending(context, ACTION_SENT, dealerId, 0, 1),
                pending(context, ACTION_DELIVERED, dealerId, 0, 1),
            )
        } else {
            val sent = ArrayList((0 until parts.size).map { pending(context, ACTION_SENT, dealerId, it, parts.size) })
            // Delivery report only for the last part.
            val delivered = ArrayList((0 until parts.size).map {
                if (it == parts.size - 1) pending(context, ACTION_DELIVERED, dealerId, it, parts.size) else null
            })
            sms.sendMultipartTextMessage(dest, null, parts, sent, delivered)
        }
    }

    /** SIM cards available for SMS, as (subscriptionId, label). Needs READ_PHONE_STATE. */
    fun simCards(context: Context): List<Pair<Int, String>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().map {
                it.subscriptionId to "SIM ${it.simSlotIndex + 1} – ${it.carrierName ?: it.displayName}"
            }
        }.getOrDefault(emptyList())
    }
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(SmsSender.EXTRA_ID, -1)
        if (id < 0) return
        val code = resultCode
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = context.repo
                when (intent.action) {
                    SmsSender.ACTION_SENT -> repo.onSent(id, part, parts, code)
                    SmsSender.ACTION_DELIVERED -> repo.onDelivered(id, deliveryStatus(intent))
                }
            } finally {
                result.finish()
            }
        }
    }

    /** true = delivered, false = failed, null = unknown / still pending. */
    private fun deliveryStatus(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return true
        val format = intent.getStringExtra("format")
        val msg = runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return true
        val status = msg.status
        return when {
            format == "3gpp2" -> true                // CDMA (rare in India): treat report as delivered
            status == 0 -> true                      // GSM: complete
            status >= 0x40 -> false                  // permanent / temporary failure, no more retries
            else -> null                             // still trying
        }
    }
}
