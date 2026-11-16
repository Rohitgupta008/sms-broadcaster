package app.gstreminder.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.gstreminder.BuildConfig
import app.gstreminder.core.CsvReader
import app.gstreminder.core.DealerSheetParser
import app.gstreminder.core.ImportException
import app.gstreminder.core.ParsedImport
import app.gstreminder.core.PeriodExtractor
import app.gstreminder.data.Cycle
import app.gstreminder.data.Dealer
import app.gstreminder.data.Stats
import app.gstreminder.repo
import app.gstreminder.work.Scheduler
import app.gstreminder.work.SendWorker
import app.gstreminder.core.XlsxReader
import app.gstreminder.core.PhoneNormalizer
import app.gstreminder.update.UpdateInfo
import app.gstreminder.update.UpdateState
import app.gstreminder.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth

sealed interface Screen {
    data object Dashboard : Screen
    data object Import : Screen
    data class Detail(val dealerId: Long) : Screen
    data object Settings : Screen
}

data class ImportPreview(
    val fileName: String,
    val parsed: ParsedImport,
    val period: String,
    val isUpdateOfActive: Boolean,
)

data class Quota(val used: Int, val limit: Int) {
    val remaining get() = (limit - used).coerceAtLeast(0)
}

data class OneTapSession(
    val dealer: Dealer,
    val text: String,
    val segments: Int,
    val phase: Phase,
    /** Sent so far in this session. */
    val sent: Int,
    /** Set when sending to one dealer only (from the dealer page). */
    val singleId: Long?,
    val skipped: Set<Long>,
    /** Changes every time the Messages app should be (re)opened. */
    val launchId: Int,
) {
    enum class Phase { OPEN_SMS, CONFIRM, NOT_SENT }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = app.repo
    val settings = repo.settings

    val screen = MutableStateFlow<Screen>(Screen.Dashboard)
    val message = MutableStateFlow<String?>(null)

    val cycle: StateFlow<Cycle?> = repo.cycles.activeFlow().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val dealers: StateFlow<List<Dealer>> = cycle.flatMapLatest { c -> if (c == null) flowOf(emptyList()) else repo.dealers.forCycleFlow(c.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stats: StateFlow<Stats> = cycle.flatMapLatest { c -> if (c == null) flowOf(Stats()) else repo.dealers.statsFlow(c.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Stats())

    /** Re-evaluates "today" every minute so the counter resets at midnight. */
    private val todayFlow: Flow<String> = flow {
        while (true) { emit(LocalDate.now().toString()); delay(60_000) }
    }

    val settingsTick: StateFlow<Int> = settings.changes.map { System.nanoTime().toInt() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val quota: StateFlow<Quota> = combine(todayFlow.flatMapLatest { repo.usage.usedFlow(it) }, settingsTick) { used, _ ->
        Quota(used ?: 0, settings.dailyLimit)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Quota(0, settings.dailyLimit))

    /** True while a batch is being sent; with the number sent so far in this run. */
    val sending: StateFlow<Pair<Boolean, Int>> = combine(
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SendWorker.WORK_SEND),
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SendWorker.WORK_CONTINUE),
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SendWorker.WORK_DAILY),
    ) { a, b, c ->
        val running = (a + b + c).filter { it.state == WorkInfo.State.RUNNING }
        (running.isNotEmpty()) to running.sumOf { it.progress.getInt(SendWorker.KEY_SENT, 0) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false to 0)

    val update: StateFlow<UpdateState> = Updater.state

    init {
        if (BuildConfig.SELF_UPDATE) viewModelScope.launch { Updater.check() }
    }

    fun checkForUpdate() = viewModelScope.launch {
        val r = Updater.check()
        if (r is UpdateState.UpToDate) message.value = "You have the latest version."
    }

    fun installUpdate(info: UpdateInfo) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            message.value = "Allow \"Install unknown apps\" for GST Reminder, then come back and tap Update again."
            Updater.openInstallPermissionSettings(ctx)
            return
        }
        viewModelScope.launch { Updater.downloadAndInstall(ctx, info) }
    }

    val importPreview = MutableStateFlow<ImportPreview?>(null)
    val importing = MutableStateFlow(false)

    fun open(s: Screen) { screen.value = s }

    fun back(): Boolean {
        if (screen.value == Screen.Dashboard) return false
        screen.value = Screen.Dashboard
        importPreview.value = null
        return true
    }

    // ------------------------------------------------------------ import

    fun readFile(uri: Uri) {
        importing.value = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val name = displayName(uri)
                val parsed = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readBytes()
                        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                        val sheets = when {
                            isZip -> XlsxReader.read(bytes.inputStream())
                            name.endsWith(".xls", ignoreCase = true) ->
                                throw ImportException("Old .xls format is not supported. Open the file in Excel / Sheets and save it as .xlsx.")
                            else -> listOf(CsvReader.read(bytes.inputStream(), name))
                        }
                        DealerSheetParser.parse(sheets)
                    }
                }
                val period = (parsed.period ?: YearMonth.now().minusMonths(1)).let { PeriodExtractor.format(it) }
                val active = cycle.value
                importPreview.value = ImportPreview(name, parsed, period, active != null && active.period.equals(period, true))
                screen.value = Screen.Import
            } catch (e: ImportException) {
                message.value = e.message
            } catch (e: Exception) {
                message.value = "Could not read the file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importing.value = false
            }
        }
    }

    fun setImportPeriod(text: String) {
        val p = importPreview.value ?: return
        val active = cycle.value
        importPreview.value = p.copy(period = text, isUpdateOfActive = active != null && active.period.equals(text.trim(), true))
    }

    fun confirmImport() {
        val p = importPreview.value ?: return
        val period = PeriodExtractor.parse(p.period)?.let { PeriodExtractor.format(it) } ?: p.period.trim()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { repo.import(p.parsed, period, p.fileName) }
            message.value = if (r.updated) {
                "Updated $period: ${r.newlyQueued} newly queued, ${r.cancelled} removed from queue (filed)."
            } else {
                "Imported $period: ${r.newlyQueued} reminders queued."
            }
            importPreview.value = null
            screen.value = Screen.Dashboard
        }
    }

    private fun displayName(uri: Uri): String {
        val ctx = getApplication<Application>()
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
    }

    // ------------------------------------------------------------ sending

    fun sendNow() {
        Scheduler.sendNow(getApplication())
        message.value = "Sending started. You can leave the app; it continues in the background."
    }

    fun stopSending() {
        Scheduler.stop(getApplication())
        message.value = "Sending stopped. Remaining reminders stay queued."
    }

    fun sendOne(id: Long) {
        viewModelScope.launch {
            if (quota.value.remaining <= 0) { message.value = "Today's limit is used up."; return@launch }
            Scheduler.sendNow(getApplication(), id)
            message.value = "Sending…"
        }
    }

    // ------------------------------------------------------------ one-tap sending
    //
    // The app opens the phone's Messages app with the reminder filled in; the user taps Send and
    // comes back. On return the dealer is marked Sent (after a short countdown that can be cancelled)
    // and the next dealer opens automatically.

    val oneTap = MutableStateFlow<OneTapSession?>(null)
    private var launchCounter = 0

    fun startOneTap(singleDealerId: Long? = null) {
        viewModelScope.launch { loadNextOneTap(sent = 0, skipped = emptySet(), singleId = singleDealerId) }
    }

    private suspend fun loadNextOneTap(sent: Int, skipped: Set<Long>, singleId: Long?) {
        val c = cycle.value ?: run { oneTap.value = null; return }
        val remaining = settings.dailyLimit - repo.usedToday()
        val dealer = if (singleId != null) {
            if (sent > 0) null else repo.dealers.get(singleId)?.takeIf { it.phone != null }
        } else repo.nextForOneTap(c.id, skipped)
        if (dealer == null) {
            finishOneTap(sent, if (singleId == null) "All queued reminders are done." else null)
            return
        }
        val msg = repo.buildMessage(dealer)
        if (remaining <= 0 || msg.info.segments > remaining) {
            finishOneTap(sent, "Today's limit of ${settings.dailyLimit} is reached – the rest continue tomorrow.")
            return
        }
        oneTap.value = OneTapSession(
            dealer = dealer, text = msg.text, segments = msg.info.segments, phase = OneTapSession.Phase.OPEN_SMS,
            sent = sent, singleId = singleId, skipped = skipped, launchId = ++launchCounter,
        )
    }

    private fun finishOneTap(sent: Int, reason: String?) {
        oneTap.value = null
        message.value = listOfNotNull("$sent sent.", reason).joinToString(" ")
    }

    /** Returned to the app after the Messages app was opened. */
    fun oneTapReturned() {
        val s = oneTap.value ?: return
        if (s.phase == OneTapSession.Phase.OPEN_SMS) oneTap.value = s.copy(phase = OneTapSession.Phase.CONFIRM)
    }

    /** The user sent the SMS. [next] = continue with the next dealer. */
    fun oneTapSent(next: Boolean) {
        val s = oneTap.value ?: return
        oneTap.value = null
        viewModelScope.launch {
            repo.markSentByUser(s.dealer.id, s.text, s.segments)
            if (next && s.singleId == null) loadNextOneTap(s.sent + 1, s.skipped, null) else finishOneTap(s.sent + 1, null)
        }
    }

    fun oneTapNotSent() {
        val s = oneTap.value ?: return
        oneTap.value = s.copy(phase = OneTapSession.Phase.NOT_SENT)
    }

    fun oneTapOpenAgain() {
        val s = oneTap.value ?: return
        oneTap.value = s.copy(phase = OneTapSession.Phase.OPEN_SMS, launchId = ++launchCounter)
    }

    fun oneTapSkip() {
        val s = oneTap.value ?: return
        viewModelScope.launch { loadNextOneTap(s.sent, s.skipped + s.dealer.id, s.singleId) }
    }

    fun oneTapStop() {
        val s = oneTap.value ?: return
        finishOneTap(s.sent, "Stopped – the rest stay queued.")
    }

    fun oneTapCannotOpen() {
        oneTap.value = null
        message.value = "No SMS app found on this phone to open."
    }

    fun retryFailed() {
        val c = cycle.value ?: return
        viewModelScope.launch { repo.retryAllFailed(c.id); message.value = "Failed reminders moved back to the queue." }
    }

    // ------------------------------------------------------------ dealer actions

    fun dealer(id: Long) = repo.dealers.flow(id)

    suspend fun preview(d: Dealer) = repo.buildMessage(d)

    fun markFiled(id: Long) = viewModelScope.launch { repo.markFiled(id); message.value = "Marked as filed." }

    fun requeue(id: Long) = viewModelScope.launch { repo.requeue(id); message.value = "Added back to the queue." }

    fun updatePhone(id: Long, raw: String): Boolean {
        val phone = PhoneNormalizer.normalize(raw)
        if (raw.isNotBlank() && phone == null) { message.value = "Enter a valid 10-digit mobile number."; return false }
        viewModelScope.launch { repo.updatePhone(id, phone); message.value = "Mobile updated." }
        return true
    }

    fun export(out: OutputStream) {
        val c = cycle.value ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.exportCsv(c.id, out) } }
                .onSuccess { message.value = "Report saved." }
                .onFailure { message.value = "Export failed: ${it.message}" }
        }
    }

    // ------------------------------------------------------------ settings

    fun saveSettings(apply: app.gstreminder.data.Settings.() -> Unit) {
        settings.apply()
        Scheduler.scheduleDaily(getApplication(), settings, replace = true)
        message.value = "Settings saved."
    }

    fun setUsedToday(used: Int) = viewModelScope.launch { repo.usage.set(repo.today(), used) }
}
