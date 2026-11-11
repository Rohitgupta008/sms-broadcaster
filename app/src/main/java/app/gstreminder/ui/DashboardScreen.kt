package app.gstreminder.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gstreminder.core.Pending
import app.gstreminder.data.Dealer
import app.gstreminder.data.Repository
import app.gstreminder.data.SmsStatus
import app.gstreminder.sms.SmsSender
import app.gstreminder.work.Scheduler

private enum class Filter(val label: String, val test: (Dealer) -> Boolean) {
    ALL("All", { true }),
    NOT_FILED("Not filed", { !it.filed }),
    QUEUED("Queued", { it.status == SmsStatus.QUEUED || it.status == SmsStatus.SENDING }),
    SENT("Sent", { it.status == SmsStatus.SENT }),
    DELIVERED("Delivered", { it.status == SmsStatus.DELIVERED }),
    FAILED("Failed", { it.status == SmsStatus.FAILED }),
    NO_MOBILE("No mobile", { it.status == SmsStatus.NO_MOBILE }),
    FILED("Filed", { it.filed }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val cycle by vm.cycle.collectAsStateWithLifecycle()
    val dealers by vm.dealers.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    vm.settingsTick.collectAsStateWithLifecycle().value // refresh texts when settings change

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::readFile) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.let(vm::export) }
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.sendNow() else vm.message.value = "SMS permission is needed to send reminders."
    }
    fun onSend() {
        if (SmsSender.hasPermission(ctx)) vm.sendNow() else smsPermission.launch(Manifest.permission.SEND_SMS)
    }
    fun importFile() = pickFile.launch(arrayOf("*/*"))

    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GST Reminder") },
                actions = {
                    IconButton(onClick = { importFile() }) { Icon(Icons.Default.Add, "Import Excel") }
                    IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Export report (CSV)") }, enabled = cycle != null, onClick = {
                                menu = false
                                exportFile.launch("GST_Reminder_${cycle?.period ?: ""}.csv")
                            })
                            DropdownMenuItem(text = { Text("Retry all failed") }, enabled = stats.failed > 0, onClick = {
                                menu = false; vm.retryFailed()
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (cycle == null) {
            Column(Modifier.padding(pad)) {
                UpdateBanner(vm)
                EmptyState(Modifier, importing) { importFile() }
            }
            return@Scaffold
        }
        val shown = remember(dealers, filter, query) {
            val q = query.trim().lowercase()
            dealers.filter(filter.test).filter {
                q.isEmpty() || it.name.lowercase().contains(q) || it.gstin.lowercase().contains(q) || (it.phone ?: "").contains(q)
            }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { UpdateBanner(vm) }
            item {
                SummaryCard(vm, cycle!!.period, cycle!!.fileName, stats, quota, sending, ::onSend)
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Filter.entries.forEach { f ->
                        val count = dealers.count(f.test)
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} $count") })
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search name, GSTIN, mobile") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty()) item { Text("No dealers here.", Modifier.padding(24.dp), color = StatusColors.muted) }
            items(shown, key = { it.id }) { d ->
                DealerRow(d) { vm.open(Screen.Detail(d.id)) }
                HorizontalDivider()
            }
        }
    }
}

/** Shown only when a newer build is available, downloading, or failed. */
@Composable
fun UpdateBanner(vm: MainViewModel) {
    val state by vm.update.collectAsStateWithLifecycle()
    val s = state
    val (text, info, busy) = when (s) {
        is app.gstreminder.update.UpdateState.Available -> Triple("New version available (build ${s.info.build}).", s.info, false)
        is app.gstreminder.update.UpdateState.Downloading -> Triple("Downloading update… ${s.percent}%", null, true)
        is app.gstreminder.update.UpdateState.Installing -> Triple("Installing… confirm on the Android screen.", null, true)
        is app.gstreminder.update.UpdateState.Error -> if (s.info != null) Triple(s.message, s.info, false) else return
        else -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (busy) CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            if (info != null) Button(onClick = { vm.installUpdate(info) }) { Text("Update") }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, importing: Boolean, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No list imported yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import this month's Excel sheet (.xlsx or .csv) with columns GSTIN, Trade Name, STATUS and MOBILE NO.",
            color = StatusColors.muted,
        )
        Spacer(Modifier.height(24.dp))
        if (importing) CircularProgressIndicator() else Button(onClick = onImport) { Text("Import Excel sheet") }
    }
}

@Composable
private fun SummaryCard(
    vm: MainViewModel,
    period: String,
    fileName: String,
    s: app.gstreminder.data.Stats,
    quota: Quota,
    sending: Pair<Boolean, Int>,
    onSend: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Return period $period", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(fileName, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${quota.used} / ${quota.limit}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("SMS used today", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (quota.used.toFloat() / quota.limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Dealers", s.total)
                Stat("Filed", s.filed, StatusColors.ok)
                Stat("Not filed", s.notFiled, StatusColors.bad)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Delivered", s.delivered, StatusColors.ok)
                Stat("Sent", s.sent + s.sending, StatusColors.info)
                Stat("Queued", s.queued, StatusColors.warn)
                Stat("Failed", s.failed, StatusColors.bad)
                Stat("No mobile", s.noMobile, StatusColors.muted)
            }
            Spacer(Modifier.height(12.dp))
            Text(eta(s.queued, quota), style = MaterialTheme.typography.bodyMedium)
            Text(Scheduler.nextAutoRunText(vm.settings), style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
            vm.settings.lastRun.takeIf { it.isNotBlank() }?.let {
                Text("Last run – $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
            }
            Spacer(Modifier.height(12.dp))
            if (sending.first) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Sending… ${sending.second} sent", Modifier.weight(1f))
                    OutlinedButton(onClick = vm::stopSending) { Text("Stop") }
                }
            } else {
                val canSend = s.queued > 0 && quota.remaining > 0
                Button(onClick = onSend, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            s.queued == 0 -> "Nothing to send"
                            quota.remaining == 0 -> "Today's limit used – continues tomorrow"
                            else -> "Send now (${minOf(s.queued, quota.remaining)} today)"
                        }
                    )
                }
            }
        }
    }
}

private fun eta(queued: Int, q: Quota): String = when {
    queued == 0 -> "All reminders are done for this list."
    queued <= q.remaining -> "$queued queued – can finish today."
    else -> {
        val days = 1 + (queued - q.remaining + q.limit - 1) / q.limit
        "$queued queued – about $days days at ${q.limit}/day."
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = StatusColors.muted)
    }
}

@Composable
private fun DealerRow(d: Dealer, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                d.gstin + (d.phone?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = StatusColors.muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            FilingBadge(d.pendingEnum)
            SmsBadge(d.status)
        }
    }
}

@Composable
fun FilingBadge(p: Pending) {
    val (text, color) = when (p) {
        Pending.NONE -> "✓ Filed" to StatusColors.ok
        Pending.GST_RETURN -> "✗ Not filed" to StatusColors.bad
        Pending.BOTH -> "✗ R1 & 3B" to StatusColors.bad
        Pending.GSTR1 -> "✗ GSTR-1" to StatusColors.bad
        Pending.GSTR3B -> "✗ GSTR-3B" to StatusColors.bad
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun SmsBadge(status: String) {
    val color = when (status) {
        SmsStatus.DELIVERED -> StatusColors.ok
        SmsStatus.SENT, SmsStatus.SENDING -> StatusColors.info
        SmsStatus.QUEUED -> StatusColors.warn
        SmsStatus.FAILED -> StatusColors.bad
        else -> StatusColors.muted
    }
    val icon = when (status) {
        SmsStatus.DELIVERED -> "✔✔ "
        SmsStatus.SENT -> "✔ "
        SmsStatus.QUEUED, SmsStatus.SENDING -> "⏳ "
        SmsStatus.FAILED -> "⚠ "
        else -> ""
    }
    Text(icon + Repository.statusLabel(status), color = color, style = MaterialTheme.typography.labelSmall)
}
