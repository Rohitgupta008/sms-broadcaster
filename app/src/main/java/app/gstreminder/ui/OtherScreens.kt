package app.gstreminder.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gstreminder.core.MessageBuilder
import app.gstreminder.core.Pending
import app.gstreminder.data.Repository
import app.gstreminder.data.SmsStatus
import app.gstreminder.sms.SmsSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(vm: MainViewModel, title: String, content: @Composable ColumnScope.() -> Unit) {
    val msg by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, Modifier.width(110.dp), color = StatusColors.muted)
        Text(value, Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------------------------ import

@Composable
fun ImportScreen(vm: MainViewModel) {
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val p = preview ?: return
    val parsed = p.parsed
    Page(vm, "Import preview") {
        Section("File") {
            KeyValue("File", p.fileName)
            KeyValue("Sheet used", parsed.sheetName)
            parsed.columns.describe().forEach { (k, v) -> KeyValue(k, v ?: "— not found —") }
        }
        Section("Return period (used in SMS)") {
            OutlinedTextField(
                value = p.period, onValueChange = vm::setImportPeriod, singleLine = true,
                supportingText = { Text("e.g. May-2026. " + if (p.isUpdateOfActive) "Same as current list – it will be UPDATED (already-sent SMS kept)." else "A NEW monthly list will be started.") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Section("Summary") {
            KeyValue("Dealers", "${parsed.total}")
            KeyValue("Filed", "${parsed.filed}")
            KeyValue("Not filed", "${parsed.notFiled}")
            KeyValue("SMS to send", "${parsed.toRemind}")
            KeyValue("No mobile", "${parsed.notFiledNoMobile}")
            val days = (parsed.toRemind + vm.settings.dailyLimit - 1) / vm.settings.dailyLimit
            if (parsed.toRemind > 0) Text("Takes about $days day(s) at ${vm.settings.dailyLimit} SMS/day.", color = StatusColors.muted)
        }
        val sample = parsed.dealers.firstOrNull { it.pending != Pending.NONE && it.phone != null }
        if (sample != null) {
            val m = MessageBuilder.build(vm.settings.template, sample.name, sample.gstin, p.period, sample.pending, vm.settings.sender)
            Section("Sample SMS") {
                Text(m.text, fontFamily = FontFamily.Monospace)
                Text("${m.info.chars} chars · ${m.info.segments} SMS", color = if (m.info.segments > 1) StatusColors.bad else StatusColors.muted)
            }
        }
        if (parsed.warnings.isNotEmpty()) {
            Section("Warnings (${parsed.warnings.size})") {
                parsed.warnings.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (parsed.warnings.size > 20) Text("…and ${parsed.warnings.size - 20} more", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.back() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = vm::confirmImport, enabled = p.period.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text(if (p.isUpdateOfActive) "Update list" else "Import")
            }
        }
    }
}

// ------------------------------------------------------------------------------------ dealer

@Composable
fun DealerScreen(vm: MainViewModel, id: Long) {
    val ctx = LocalContext.current
    val dealer by remember(id) { vm.dealer(id) }.collectAsStateWithLifecycle(null)
    val d = dealer ?: return
    val preview by produceState<MessageBuilder.Message?>(null, d, vm.settingsTick.collectAsStateWithLifecycle().value) {
        value = vm.preview(d)
    }
    var editPhone by remember { mutableStateOf(false) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.sendOne(d.id) else vm.message.value = "SMS permission is needed."
    }
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH) }

    Page(vm, d.name) {
        Section("Dealer") {
            KeyValue("Trade name", d.name)
            KeyValue("GSTIN", d.gstin)
            KeyValue("Mobile", d.phone ?: (d.rawPhone.ifBlank { "—" } + if (d.rawPhone.isNotBlank()) " (invalid)" else ""))
            KeyValue("Sheet row", "${d.rowNum}")
            d.extras.lines().filter { it.isNotBlank() }.forEach { line ->
                KeyValue(line.substringBefore(": "), line.substringAfter(": "))
            }
        }
        Section("Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filing", Modifier.width(110.dp), color = StatusColors.muted); FilingBadge(d.pendingEnum)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SMS", Modifier.width(110.dp), color = StatusColors.muted); SmsBadge(d.status)
            }
            if (d.attempts > 0) KeyValue("Attempts", "${d.attempts}")
            d.sentAt?.let { KeyValue("Sent", fmt.format(Date(it))) }
            d.deliveredAt?.let { KeyValue("Delivered", fmt.format(Date(it))) }
            d.lastError?.let { Text(it, color = StatusColors.bad) }
        }
        val text = d.message ?: preview?.text
        if (text != null) {
            Section(if (d.message != null) "Message sent" else "Message preview") {
                Text(text, fontFamily = FontFamily.Monospace)
                val info = app.gstreminder.core.SmsText.info(text)
                Text("${info.chars} chars · ${info.segments} SMS", color = StatusColors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.phone != null && !d.filed && d.status != SmsStatus.SENDING) {
                Button(onClick = {
                    if (SmsSender.hasPermission(ctx)) vm.sendOne(d.id) else smsPermission.launch(Manifest.permission.SEND_SMS)
                }, modifier = Modifier.weight(1f)) {
                    Text(if (d.status in setOf(SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.FAILED)) "Resend now" else "Send now")
                }
            }
            if (d.phone != null) {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${d.phone}"))) }
                }, modifier = Modifier.weight(1f)) { Text("Call") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!d.filed) OutlinedButton(onClick = { vm.markFiled(d.id) }, modifier = Modifier.weight(1f)) { Text("Mark as filed") }
            OutlinedButton(onClick = { editPhone = true }, modifier = Modifier.weight(1f)) { Text("Edit mobile") }
        }
        if (d.status == SmsStatus.FAILED || d.status == SmsStatus.SENT) {
            TextButton(onClick = { vm.requeue(d.id) }) { Text("Add back to queue (send in next batch)") }
        }
    }

    if (editPhone) {
        var value by remember { mutableStateOf(d.phone ?: d.rawPhone) }
        AlertDialog(
            onDismissRequest = { editPhone = false },
            title = { Text("Mobile number") },
            text = {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            },
            confirmButton = { TextButton(onClick = { if (vm.updatePhone(d.id, value)) editPhone = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editPhone = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings
    val ctx = LocalContext.current
    val quota by vm.quota.collectAsStateWithLifecycle()

    var limit by remember { mutableStateOf(s.dailyLimit.toString()) }
    var usedToday by remember(quota.used) { mutableStateOf(quota.used.toString()) }
    var sender by remember { mutableStateOf(s.sender) }
    var template by remember { mutableStateOf(s.template) }
    var autoSend by remember { mutableStateOf(s.autoSend) }
    var sendAt by remember { mutableStateOf("%02d:%02d".format(s.sendAtMinutes / 60, s.sendAtMinutes % 60)) }
    var hours by remember { mutableStateOf("${s.startHour}-${s.endHour}") }
    var gap by remember { mutableStateOf(s.gapSeconds.toString()) }
    var sim by remember { mutableStateOf(s.subscriptionId to s.subscriptionLabel) }
    var sims by remember { mutableStateOf(SmsSender.simCards(ctx)) }
    var simMenu by remember { mutableStateOf(false) }
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        sims = SmsSender.simCards(ctx); if (sims.isNotEmpty()) simMenu = true
    }

    Page(vm, "Settings") {
        Section("Daily SMS limit") {
            OutlinedTextField(
                value = limit, onValueChange = { limit = it.filter(Char::isDigit) }, label = { Text("SMS per day") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = usedToday, onValueChange = { usedToday = it.filter(Char::isDigit) }, label = { Text("Already used today") },
                supportingText = { Text("Correct this if you sent SMS from the phone's own messaging app today.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Section("Message") {
            OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text("Signature ({sender})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = template, onValueChange = { template = it }, label = { Text("Template") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Placeholders: {name} {gstin} {period} {returns} {sender}") },
            )
            val sample = MessageBuilder.build(template, "Sample Auto Motors Private Limited", "23ABCDE1234F1Z5", "May-2026", Pending.GST_RETURN, sender)
            Text(sample.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                "${sample.info.chars} chars · ${sample.info.segments} SMS" + if (!sample.info.gsm) " · contains special characters (70 chars/SMS)" else "",
                color = if (sample.info.segments > 1) StatusColors.bad else StatusColors.muted, style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { template = MessageBuilder.DEFAULT_TEMPLATE; sender = MessageBuilder.DEFAULT_SENDER }) { Text("Reset to default") }
        }
        Section("Automatic daily sending") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send queued reminders every day", Modifier.weight(1f))
                Switch(checked = autoSend, onCheckedChange = { autoSend = it })
            }
            OutlinedTextField(value = sendAt, onValueChange = { sendAt = it }, label = { Text("Time (HH:MM, 24h)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("Allowed hours (e.g. 9-20)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = gap, onValueChange = { gap = it.filter(Char::isDigit) }, label = { Text("Seconds between SMS") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Section("SIM card") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sim.second, Modifier.weight(1f))
                TextButton(onClick = {
                    if (sims.isEmpty()) phonePermission.launch(Manifest.permission.READ_PHONE_STATE) else simMenu = true
                }) { Text("Change") }
            }
            androidx.compose.material3.DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Default SIM") }, onClick = { sim = -1 to "Default SIM"; simMenu = false })
                sims.forEach { (id, label) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { sim = id to label; simMenu = false })
                }
            }
        }
        Section("App version") {
            val update by vm.update.collectAsStateWithLifecycle()
            Text("Build ${app.gstreminder.update.Updater.currentBuild}")
            Text(
                when (val u = update) {
                    is app.gstreminder.update.UpdateState.Checking -> "Checking…"
                    is app.gstreminder.update.UpdateState.UpToDate -> "Up to date."
                    is app.gstreminder.update.UpdateState.Available -> "Build ${u.info.build} is available – tap Update on the home screen."
                    is app.gstreminder.update.UpdateState.Downloading -> "Downloading ${u.percent}%"
                    is app.gstreminder.update.UpdateState.Installing -> "Installing…"
                    is app.gstreminder.update.UpdateState.Error -> u.message
                    else -> "The app checks for updates each time it opens."
                },
                style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
            )
            TextButton(onClick = { vm.checkForUpdate() }) { Text("Check for updates") }
        }
        Section("Tip") {
            Text(
                "Android may ask \"GST Reminder is sending a large number of SMS messages\". Tick \"Remember my choice\" and tap Allow, otherwise sending stops after ~30 messages.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            val (h, m) = sendAt.split(":").map { it.trim().toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 10 } to it.getOrElse(1) { 0 } }
            val (start, end) = hours.split("-").map { it.trim().toIntOrNull() }.let { (it.getOrNull(0) ?: 9) to (it.getOrNull(1) ?: 20) }
            vm.saveSettings {
                dailyLimit = limit.toIntOrNull() ?: 100
                this.sender = sender
                this.template = template.ifBlank { MessageBuilder.DEFAULT_TEMPLATE }
                this.autoSend = autoSend
                sendAtMinutes = h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59)
                startHour = start.coerceIn(0, 23)
                endHour = end.coerceIn(startHour + 1, 24)
                gapSeconds = gap.toIntOrNull() ?: 4
                subscriptionId = sim.first
                subscriptionLabel = sim.second
            }
            usedToday.toIntOrNull()?.let { if (it != quota.used) vm.setUsedToday(it) }
            vm.back()
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Text(
            "Status meaning: Sent = left your phone · Delivered = operator confirmed delivery · Failed = not sent after ${Repository.MAX_ATTEMPTS} tries.",
            style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
        )
    }
}
