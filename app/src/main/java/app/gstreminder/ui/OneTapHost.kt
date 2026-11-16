package app.gstreminder.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Seconds before a returned-from-Messages SMS is marked sent and the next one opens. */
private const val AUTO_NEXT_SECONDS = 3

/**
 * Drives one-tap sending: opens the Messages app with the reminder filled in, notices when the
 * user comes back, then marks it sent and opens the next dealer after a short, cancellable countdown.
 */
@Composable
fun OneTapHost(vm: MainViewModel) {
    val session by vm.oneTap.collectAsStateWithLifecycle()
    val s = session ?: return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var leftApp by remember { mutableStateOf(false) }

    // Open the Messages app each time a new launch is requested.
    LaunchedEffect(s.launchId) {
        if (s.phase != OneTapSession.Phase.OPEN_SMS) return@LaunchedEffect
        val phone = s.dealer.phone ?: return@LaunchedEffect vm.oneTapSkip()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", "+91$phone", null))
            .putExtra("sms_body", s.text)
            .putExtra(Intent.EXTRA_TEXT, s.text)
        leftApp = false
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            vm.oneTapCannotOpen()
        }
    }

    // Coming back from the Messages app -> ask for confirmation.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftApp = true
                Lifecycle.Event.ON_RESUME -> if (leftApp) { leftApp = false; vm.oneTapReturned() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = vm::oneTapStop,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (s.singleId == null) "One-tap sending · ${s.sent} sent" else "Send reminder",
                    style = MaterialTheme.typography.labelLarge, color = StatusColors.muted,
                )
                Text(s.dealer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("+91 ${s.dealer.phone}", fontFamily = FontFamily.Monospace)
                when (s.phase) {
                    OneTapSession.Phase.OPEN_SMS -> OpenSmsPanel(vm)
                    OneTapSession.Phase.CONFIRM -> ConfirmPanel(vm, s)
                    OneTapSession.Phase.NOT_SENT -> NotSentPanel(vm)
                }
            }
        }
    }
}

@Composable
private fun OpenSmsPanel(vm: MainViewModel) {
    Text("Messages is open with the reminder ready. Tap Send there, then come back here.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::oneTapOpenAgain, modifier = Modifier.weight(1f)) { Text("Open again") }
        TextButton(onClick = vm::oneTapStop) { Text("Stop") }
    }
}

@Composable
private fun ConfirmPanel(vm: MainViewModel, s: OneTapSession) {
    val autoNext = s.singleId == null
    var left by remember(s.launchId) { mutableIntStateOf(AUTO_NEXT_SECONDS) }
    if (autoNext) {
        LaunchedEffect(s.launchId) {
            while (left > 0) { delay(1000); left-- }
            vm.oneTapSent(next = true)
        }
    }
    Text(
        if (autoNext) "Marked as sent. Opening next in $left…" else "Did you send it?",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (autoNext) {
        Button(onClick = { vm.oneTapSent(next = true) }, modifier = Modifier.fillMaxWidth()) { Text("Next now") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.oneTapSent(next = false) }, modifier = Modifier.weight(1f)) { Text("Sent, stop here") }
            OutlinedButton(onClick = vm::oneTapNotSent, modifier = Modifier.weight(1f)) { Text("Not sent") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.oneTapSent(next = false) }, modifier = Modifier.weight(1f)) { Text("Yes, sent") }
            OutlinedButton(onClick = vm::oneTapNotSent, modifier = Modifier.weight(1f)) { Text("Not sent") }
        }
    }
}

@Composable
private fun NotSentPanel(vm: MainViewModel) {
    Text("Not sent – it stays in the queue.")
    Button(onClick = vm::oneTapOpenAgain, modifier = Modifier.fillMaxWidth()) { Text("Open Messages again") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::oneTapSkip, modifier = Modifier.weight(1f)) { Text("Skip dealer") }
        OutlinedButton(onClick = vm::oneTapStop, modifier = Modifier.weight(1f)) { Text("Stop") }
    }
}
