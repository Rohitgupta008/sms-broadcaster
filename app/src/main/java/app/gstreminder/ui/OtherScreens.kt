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
import app.gstreminder.BuildConfig
import app.gstreminder.core.MessageBuilder
import app.gstreminder.data.SendMode
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
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
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit)) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier,
    )
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
                supportingText = { Text("e.g. May-2026. " + if (p.isUpdateOfActive) "Same as the current list – it will be UPDATED (see options below)." else "A NEW monthly list will be started.") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Section("Summary") {
            KeyValue("Dealers", "${parsed.total}")
            KeyValue("Filed", "${parsed.filed}")
            KeyValue("Not filed", "${parsed.notFiled}")
            KeyValue("SMS to send", "${parsed.toRemind}")
            KeyValue("No mobile", "${parsed.notFiledNoMobile}")
            val perDay = vm.settings.totalLimit.coerceAtLeast(1)
            val days = (parsed.toRemind + perDay - 1) / perDay
            if (parsed.toRemind > 0 && !p.isUpdateOfActive) Text("Takes about $days day(s) at $perDay SMS/day.", color = StatusColors.muted)
        }
        val plan = p.plan
        if (p.isUpdateOfActive && plan != null) {
            Section("Updating the current list") {
                Text(
                    "${plan.removed} dealer(s) filed since the last import and are removed from the queue. " +
                        "${plan.pending} still-pending dealer(s) from before go to the top of the queue.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceRow(
                    selected = !p.newRound, onClick = { vm.setNewRound(false) },
                    title = "Update statuses only",
                    detail = "Send to the ${plan.pending} pending + ${plan.newOnes} new dealer(s). " +
                        "The ${plan.reminded} dealer(s) already reminded are not messaged again.",
                )
                ChoiceRow(
                    selected = p.newRound, onClick = { vm.setNewRound(true) },
                    title = "New reminder round",
                    detail = "Remind everyone still not filed: ${plan.pending} pending first, then " +
                        "${plan.reminded} already reminded and ${plan.newOnes} new – ${plan.pending + plan.reminded + plan.newOnes} in total.",
                )
            }
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
    val needsSim = vm.settings.dualSim || vm.settings.simSlot >= 0
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.sendOne(d.id) else vm.message.value = "SMS" + (if (needsSim) " and phone" else "") + " permission is needed."
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
            if (d.carryOver && d.status == SmsStatus.QUEUED) Text("↑ Pending from before – sends first", color = StatusColors.warn)
            if (d.reminders > 0) KeyValue("Reminders", "${d.reminders} sent")
            if (d.attempts > 0) KeyValue("Attempts", "${d.attempts}")
            d.sentAt?.let { KeyValue("Sent", fmt.format(Date(it)) + if (vm.settings.dualSim) " · SIM ${d.simSlot + 1}" else "") }
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
                    when {
                        vm.settings.sendMode == SendMode.ONE_TAP -> vm.startOneTap(d.id)
                        SmsSender.hasPermission(ctx) && (!needsSim || SmsSender.hasPhonePermission(ctx)) -> vm.sendOne(d.id)
                        else -> smsPermission.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
                    }
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
    var dualSim by remember { mutableStateOf(s.dualSim) }
    var sim1Limit by remember { mutableStateOf(s.sim1Limit.toString()) }
    var sim2Limit by remember { mutableStateOf(s.sim2Limit.toString()) }
    var simSlot by remember { mutableStateOf(s.simSlot) }
    // Today's actual usage per SIM slot (editable in case SMS were sent outside the app).
    val actualUsed by produceState(listOf(0, 0), quota) { value = listOf(vm.repo.usedOn(0), vm.repo.usedOn(1)) }
    var used1 by remember(actualUsed) { mutableStateOf(actualUsed[0].toString()) }
    var used2 by remember(actualUsed) { mutableStateOf(actualUsed[1].toString()) }
    var sender by remember { mutableStateOf(s.sender) }
    var template by remember { mutableStateOf(s.template) }
    var autoSend by remember { mutableStateOf(s.autoSend) }
    var mode by remember { mutableStateOf(s.sendMode) }
    var sendAt by remember { mutableStateOf("%02d:%02d".format(s.sendAtMinutes / 60, s.sendAtMinutes % 60)) }
    var hours by remember { mutableStateOf("${s.startHour}-${s.endHour}") }
    var gap by remember { mutableStateOf(s.gapSeconds.toString()) }

    Page(vm, "Settings") {
        Section("SIM cards & daily SMS limit") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use both SIM cards")
                    Text(
                        "Each SIM gets its own daily limit, so you can send more per day.",
                        style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
                    )
                }
                Switch(checked = dualSim, onCheckedChange = { dualSim = it })
            }
            if (dualSim) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(sim1Limit, { sim1Limit = it }, "${SmsSender.slotLabel(ctx, 0)} / day", Modifier.weight(1f))
                    NumberField(sim2Limit, { sim2Limit = it }, "${SmsSender.slotLabel(ctx, 1)} / day", Modifier.weight(1f))
                }
                Text(
                    "Total ${(sim1Limit.toIntOrNull() ?: 0) + (sim2Limit.toIntOrNull() ?: 0)} SMS per day. " +
                        "The app uses the SIM with more SMS left. Set 0 to stop using a SIM." +
                        if (mode == SendMode.ONE_TAP) " In one-tap mode the app tells you which SIM to pick in Messages." else "",
                    style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(used1, { used1 = it }, "Used today: SIM 1", Modifier.weight(1f))
                    NumberField(used2, { used2 = it }, "Used today: SIM 2", Modifier.weight(1f))
                }
            } else {
                NumberField(limit, { limit = it }, "SMS per day", Modifier.fillMaxWidth())
                Text("Send from", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                Row {
                    listOf(-1 to "Default SIM", 0 to "SIM 1", 1 to "SIM 2").forEach { (slot, label) ->
                        Row(Modifier.clickable { simSlot = slot }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = simSlot == slot, onClick = { simSlot = slot })
                            Text(label)
                        }
                    }
                }
                val bucket = simSlot.coerceAtLeast(0)
                NumberField(
                    if (bucket == 0) used1 else used2, { if (bucket == 0) used1 = it else used2 = it },
                    "Already used today", Modifier.fillMaxWidth(),
                )
            }
            Text(
                "Correct \"used today\" if you sent SMS from the phone's own messaging app.",
                style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
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
        if (BuildConfig.AUTO_SMS) {
            Section("Sending mode") {
                SendMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { mode = m },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Column {
                            Text(m.label)
                            Text(
                                when (m) {
                                    SendMode.AUTO -> "The app sends each SMS itself, in the background and daily at a set time."
                                    SendMode.ONE_TAP -> "The app opens Messages with the text ready; you tap Send for each dealer."
                                },
                                style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
                            )
                        }
                    }
                }
            }
        }
        if (mode == SendMode.AUTO) Section("Automatic daily sending") {
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
        if (!BuildConfig.SELF_UPDATE) Section("App version") {
            Text("Version ${BuildConfig.VERSION_NAME}")
            Text("Updates come through the Play Store.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
        }
        if (BuildConfig.SELF_UPDATE) Section("App version") {
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
            if (mode == SendMode.ONE_TAP) Text(
                "Tap Start sending: Messages opens with the reminder ready. Tap Send, then Back. The app marks it sent and opens the next dealer.",
                style = MaterialTheme.typography.bodySmall,
            ) else Text(
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
                sendModeChoice = mode
                this.dualSim = dualSim
                this.sim1Limit = sim1Limit.toIntOrNull() ?: 100
                this.sim2Limit = sim2Limit.toIntOrNull() ?: 100
                this.simSlot = simSlot
            }
            used1.toIntOrNull()?.let { if (it != actualUsed[0]) vm.setUsedToday(0, it) }
            used2.toIntOrNull()?.let { if (it != actualUsed[1]) vm.setUsedToday(1, it) }
            vm.back()
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Text(
            "Status meaning: Sent = left your phone (or you sent it in one-tap mode) · Delivered = operator confirmed delivery · Failed = not sent after ${Repository.MAX_ATTEMPTS} tries.",
            style = MaterialTheme.typography.bodySmall, color = StatusColors.muted,
        )
    }
}
