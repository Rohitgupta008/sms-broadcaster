# GST Reminder – SMS broadcaster

A lightweight Android app that takes the monthly GST non-filer Excel sheet and sends each dealer
who has not filed a short, personalised reminder SMS from your own SIM. It stays within a daily
SMS limit (default 100) and carries the rest over to the next day automatically.

## Features
- **Import `.xlsx` / `.csv`**: finds the header row by itself (title rows above it are fine),
  picks the sheet that has mobile numbers, and reads `GSTIN`, `Trade Name`, `STATUS`, `MOBILE NO.`
  (also supports separate `GSTR-1` / `GSTR-3B` columns).
- **Return period** is read from the title (e.g. `Return Period: 05/2026`) and can be edited before importing.
- **Short, personalised SMS**, kept to one SMS (≤160 characters). Long names are shortened automatically
  ("M/S X Private Limited" → "X Pvt Ltd").
- **Daily limit and carry-over**: sends up to the limit, and the rest go out at the next daily run (default 10:00 AM).
- **Tracking**: each SMS shows Queued → Sent → Delivered, or Failed with the reason. Failures are retried
  up to 3 times, and sending stops after 3 failures in a row (no signal, no balance, or operator limit).
- **Dashboard**: dealers / filed / not filed, SMS delivered / sent / queued / failed / no mobile,
  today's usage and an estimated finish date. Filter chips and search help you find dealers.
- **Monthly**: importing a new month starts a new list. Re-importing an updated sheet for the
  **same** month updates dealers by GSTIN, and anyone who has filed since is removed from the queue.
- **Dealer page**: shows the message and history, with buttons to send or resend, call, mark as filed or edit the mobile number.
- **Export** a CSV status report.

## Install
1. On the phone, open **https://github.com/Rohitgupta008/sms-broadcaster/releases/latest/download/GST-Reminder.apk**
   (sign in to GitHub in the browser first if the repo is private).
2. Open the downloaded file and allow "Install unknown apps" for your browser once.
3. Later builds are signed with the same key, so they install as updates and keep your data.

> The app is installed directly (not from Play Store), because Google Play only allows SMS-sending
> permission for default SMS apps.

## Monthly use
1. Tap **+** and choose the month's Excel file. Check the preview (sheet, columns, period, sample SMS) and tap **Import**.
2. Tap **Send now**, or let the automatic daily run send it.
3. The first time, Android asks for SMS permission. It may also ask about *"sending a large number of
   SMS messages"*: tick **Remember my choice** and tap **Allow**.
4. Check the list for Delivered / Failed. Use **Export report** to share the status.

## Excel format
One sheet, header row with at least:

| GSTIN | Trade Name | STATUS | MOBILE NO. |
|---|---|---|---|
| 23AAAAA0000A1Z5 | ABC Traders | FILED / blank | 98xxxxxxxx |

`STATUS` = `FILED` (or Yes / Y / ✓) means filed. Blank or anything else means not filed.
Other columns (Office, Jurisdiction, Turnover…) are kept and shown on the dealer page.

## Project layout
- `core/`: pure Kotlin. Contains the xlsx/csv reader, column detection, status, phone and period parsing,
  SMS length rules and the message builder. Unit tested (`./gradlew -PcoreOnly :core:test`, no Android SDK needed).
- `app/`: the Android app (Kotlin, Jetpack Compose, Room, WorkManager).
- `PLAN.md`: design notes.
