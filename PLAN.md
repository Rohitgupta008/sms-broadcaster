# GST Filing Reminder – SMS Broadcaster (Android) · Plan v1

## 1. Goal
Every month you get an Excel list of dealers with their GSTR-1 and GSTR-3B filing status.
The app should:
1. Import that Excel sheet.
2. Find dealers who have **not filed** GSTR-1 and/or GSTR-3B.
3. Write a short, personalised reminder SMS for each.
4. Send them from your own SIM, **never more than 100 SMS a day**, and carry the rest over to the next day automatically.
5. Track every SMS (queued → sent → delivered / failed) and show a status list.
6. Start fresh each month when a new sheet is imported, keeping past months as history.

## 2. Key design decisions

| Topic | Decision | Why |
|---|---|---|
| Platform | Native Android, **Kotlin + Jetpack Compose**, minSdk 26 (Android 8) | Small APK (~3–5 MB), direct access to SMS APIs |
| SMS sending | Android `SmsManager` via your SIM, with *sent* and *delivery* callbacks | No paid gateway; real per-message status |
| Excel reading | **Small built-in .xlsx reader** (xlsx is a zip of XML files) plus CSV support | Apache POI would add ~10 MB and is slow on phones |
| Storage | Room (SQLite) on the phone | Works offline; data stays on your device |
| Daily batches | WorkManager job that runs each morning (time is configurable) | Survives reboots and app being closed |
| Distribution | Install the APK directly (sideload), not via Play Store | Google Play only allows `SEND_SMS` for default SMS apps. Fine for personal or office use |
| Message length | Keep every SMS **≤160 characters, plain English characters only (GSM-7)** | One SMS = one unit of your 100 quota. Using `₹`, Hindi text or emojis drops the limit to 70 characters per SMS and can turn 1 message into 2–3 |

## 3. Data model

```
Cycle (one per monthly import)
  id, period ("Oct-2026"), importedAt, sourceFileName, status (ACTIVE / CLOSED)

Dealer (one row per dealer per cycle)
  id, cycleId, gstin, name, phone (normalised to 10 digits), phoneValid,
  gstr1Filed (bool), gstr3bFiled (bool), extra columns (json)

SmsJob (one per dealer who needs a reminder)
  id, dealerId, messageText, segments, priority,
  status: QUEUED | SENDING | SENT | DELIVERED | FAILED | SKIPPED
  attempts, lastError, sentAt, deliveredAt, scheduledDay

DailyQuota
  date, limit (default 100), used
```

**Daily limit rule:** `used` goes up by the number of SMS *parts* (segments) per message, so the
count matches what your operator counts. The 100 limit is a setting you can change.

## 4. Main flows

### 4.1 Import (monthly)
1. Pick a `.xlsx` or `.csv` file (Android file picker – works with WhatsApp or Gmail downloads).
2. **Column mapping screen**: the app guesses the columns from the header names
   (`Name / Trade Name`, `GSTIN`, `Mobile / Phone`, `GSTR-1`, `GSTR-3B`) and lets you correct them once.
   The mapping is saved, so later months need one tap.
3. Filing status values such as `Filed / Not Filed / Y / N / Yes / No / ✓ / blank` are converted to true/false.
4. Preview: *"412 dealers · 287 filed both · 125 to remind · 6 invalid phone numbers"* → **Confirm**.
5. A new Cycle is created. The previous cycle is closed and its unsent jobs are cancelled.
6. **Re-importing an updated sheet in the same month** matches dealers by GSTIN. Anyone who has since filed
   gets their unsent SMS set to `SKIPPED`, so you never remind someone who has already filed.

### 4.2 Message generation (no internet or AI needed – a fixed template)
Three versions, chosen by what is pending:

- Both pending:
  `Dear {Name}, GSTR-1 & GSTR-3B for {Period} are not filed (GSTIN {GSTIN}). Please file at the earliest to avoid late fee. -{Sender}`
- Only GSTR-1 pending / only GSTR-3B pending: the same text with one return named.

Rules:
- `{Name}` is shortened automatically (e.g. "M/S Example Traders Pvt Ltd" → "Example Traders") so the SMS stays ≤160 characters.
- The template is editable in Settings, with a live character and segment counter and a warning if it goes over 160 characters or uses Unicode.
- Preview any dealer's SMS before sending.

### 4.3 Sending and daily carry-over
- **"Send today's batch"** button, plus an optional automatic daily run (e.g. 10:00 AM).
- Takes up to `remainingQuota` jobs in priority order:
  1. Both returns pending
  2. Only GSTR-3B pending (tax payment)
  3. Only GSTR-1 pending
  4. Retries of failed messages
- Sends one SMS every ~3–5 seconds so the operator does not flag it as spam. Shows a progress bar and can be paused or stopped.
- Each SMS has a *sent* callback (OK / no service / radio off / generic failure) and a *delivered* callback.
- A failure caused by no signal stays `QUEUED` and is retried later. A real failure is retried at most 2 times, then marked `FAILED`.
- When the quota runs out, the rest stay `QUEUED`, and the next day's job picks them up automatically.
- Option to choose which SIM to use on dual-SIM phones.
- Sends only during business hours (setting, e.g. 9 AM–8 PM).

### 4.4 Status / dashboard (home screen)
```
Oct-2026 cycle                         Today: 64 / 100 used
─────────────────────────────────────────────────────────
 Dealers 412 │ Filed both 287 │ To remind 125
 SMS: Delivered 58 · Sent 6 · Queued 55 · Failed 2 · Skipped 4
 Estimated finish: tomorrow
─────────────────────────────────────────────────────────
[All] [Not filed] [R1 ✗] [3B ✗] [Queued] [Failed] [Delivered]   🔍 search

 Example Traders     27ABCDE1234F1Z5   R1 ✗  3B ✗   ✔✔ Delivered 10:02
 Sample Enterprises       27PQRSX9876K1Z2   R1 ✓  3B ✗   ⏳ Queued (tomorrow)
 Om Sai Hardware          27LMNOP4567Q1Z9   R1 ✗  3B ✓   ✖ Failed – retry
```
- Tap a row to see dealer details, the exact message, attempt history, and buttons to **resend now**, **call**, **mark filed**, or **edit phone number**.
- **Export report** (.xlsx/.csv) with SMS status for each dealer, ready to share with the office.
- **History** tab lists past cycles with totals.

### 4.5 Settings
Daily limit · auto-send time · business hours · SIM choice · sender signature · message templates · gap between SMS · column mapping reset.

## 5. Screens (5 in total)
1. **Home / Dashboard** – summary, today's quota, filterable dealer list
2. **Import** – file picker → column mapping → preview → confirm
3. **Dealer detail** – message preview, history, actions
4. **Send** – progress of the batch, pause/stop
5. **Settings** (+ History of cycles)

## 6. Project structure
```
app/src/main/java/.../
  data/      Room entities, DAOs, repository
  importer/  XlsxReader, CsvReader, ColumnMapper, StatusParser, PhoneNormalizer
  sms/       MessageBuilder, SegmentCounter, SmsSender, SentDeliveredReceiver, QuotaManager
  work/      DailyBatchWorker (WorkManager)
  ui/        dashboard/, import/, detail/, send/, settings/
```
Dependencies: Compose, Room, WorkManager, DataStore. No network libraries and no analytics.

## 7. Build phases
1. **Phase 1 – Core (MVP):** project setup, xlsx/csv import with column mapping, Room DB, message builder, manual "Send today's batch" with sent/delivered tracking, 100/day quota, dashboard list with filters.
2. **Phase 2 – Automation:** WorkManager daily auto-send, retries, business hours, dual-SIM choice, re-import matching by GSTIN.
3. **Phase 3 – Polish:** export report, cycle history, template editor with counter, dealer actions (call / mark filed).
4. **Build:** GitHub Actions workflow that builds the APK on every push, so you can download and install it without Android Studio.

## 8. Tests
- Unit tests: xlsx parsing, status parsing ("Not Filed", "N", blank…), phone normalisation (+91, 0-prefix, spaces), message length ≤160 / GSM-7 check, quota roll-over across midnight, priority order.
- Manual test on a real phone with a 5-row sample sheet and a low limit (e.g. 3/day) to check the carry-over.

## 9. Findings from the sample file (officer's non-filer workbook)

| Sheet | Layout | Rows | Mobile? | STATUS |
|---|---|---|---|---|
| Sheet 1 ("… Non-Filers") | Row 1 = title (officer, office, **Return Period: 05/2026**), row 2 = summary, **row 3 = header** | 98 | ❌ none | `FILED` 36 / blank 62 |
| `Sheet1` | Row 1 = header | 62 (all also in sheet 1) | ✅ `MOBILE NO.` (stored as numbers) | `FILED` 29 / blank 33 |

Columns: `# / SRN, GSTIN, Trade Name, Office, Jurisdiction, Turnover, STATUS, MOBILE NO.`

Changes to the plan:
- **The header row is found automatically**: the app scans the first 10 rows of each sheet for a row containing "GSTIN", so title and summary rows are skipped.
- **Sheets are merged by GSTIN**: the mobile number is taken from whichever sheet has one, and `STATUS` comes from the sheet that has the mobile numbers (assumed to be the latest). The two sheets disagree for some dealers, e.g. one dealer is blank in one sheet and FILED in the other. A dealer with no mobile number is shown as **"No mobile"** (36 dealers in this file) and is never queued.
- **One STATUS column, not separate GSTR-1 / 3B columns**: `FILED` means filed, and blank or anything else means not filed. If a future sheet has separate GSTR-1 and 3B columns, the three message versions from 4.2 are used instead.
- **Period** is read from the title text (`Return Period: 05/2026`). If it isn't found, the app asks during import.
- **Mobile numbers** are read whether stored as numbers or text, and normalised to 10 digits.
- Extra columns (Office, Jurisdiction, Turnover) are kept and shown on the dealer detail screen. Jurisdiction (State / Centre) can be used as a filter.
- Trade names are at most 46 characters in this file. The default message is **152 characters, 1 SMS**:
  `Dear Sample Infra Developers Pvt Ltd, GSTIN 23ABCDE1234F1Z5: GST return for 05/2026 not filed. Please file urgently to avoid late fee. -AC Indore-5`

With this file: 33 dealers have not filed and have a mobile number, so all the SMS go out in **1 day** within the 100 limit.

## 10. Open questions (please confirm)

_Resolved: Sheet1 (with mobile numbers) is the final list; future files will have one sheet. Implemented in v1 — see README._

1. **Excel format** – what are the exact column names? Could you share a sample with dummy data? Is GSTR-1 / 3B status in two columns, or are there separate "filed" and "not filed" sheets?
2. **Mobile number** – is it in the same sheet? Could one dealer have more than one number?
3. **Message language** – English only (recommended for the 160-character limit), or Hindi or Marathi as well?
4. **Sender signature** – e.g. "-ABC & Co, Tax Consultants"?
5. **Is the 100/day limit** set by your SIM plan (per SIM)? Should a dual-SIM phone use 2 SIMs to get 200/day?
6. **Due dates** – should the SMS include the due date or late fee per day, or keep it generic?
