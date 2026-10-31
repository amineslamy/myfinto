package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.icu.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimeTrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.dao()
    private val repository = TimeTrackingRepository(dao)

    val allRecords = repository.allRecords.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allReports = repository.allReports.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allLeaves = repository.allLeaves.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _activeRecord = MutableStateFlow<TimeRecord?>(null)
    val activeRecord: StateFlow<TimeRecord?> = _activeRecord

    private val _currentMonthObligatoryHours = MutableStateFlow<Double>(0.0)
    val currentMonthObligatoryHours: StateFlow<Double> = _currentMonthObligatoryHours

    // Reporting States
    private val _reportWorkedHours = MutableStateFlow(0.0)
    val reportWorkedHours: StateFlow<Double> = _reportWorkedHours

    private val _reportObligatoryHours = MutableStateFlow(0.0)
    val reportObligatoryHours: StateFlow<Double> = _reportObligatoryHours

    private val _reportLeaveDays = MutableStateFlow<List<LeaveDay>>(emptyList())
    val reportLeaveDays: StateFlow<List<LeaveDay>> = _reportLeaveDays

    init {
        checkActiveSession()
        loadCurrentMonthObligatoryHours()
    }

    fun checkActiveSession() {
        viewModelScope.launch {
            _activeRecord.value = repository.getActiveRecord()
        }
    }

    private fun loadCurrentMonthObligatoryHours() {
        viewModelScope.launch {
            val today = JalaliCalendarHelper.fromEpochMillis(System.currentTimeMillis())
            _currentMonthObligatoryHours.value = monthlyObligatoryHours(today.year, today.month)
        }
    }

    /**
     * ساعت موظفی ماه = مقدار دستی ذخیره شده (اگر باشد) یا مجموع استاندارد ماه،
     * منهای ساعت روزهایی که مرخصی ثبت شده‌اند.
     */
    suspend fun monthlyObligatoryHours(year: Int, month: Int): Double {
        val savedTarget = repository.getObligatoryHours(year, month)
        val base = savedTarget?.requiredHours ?: standardMonthlyHours(year, month)
        val leaveDeduction = repository.getLeavesForMonth(year, month).sumOf { standardDailyHours(it) }
        return maxOf(0.0, base - leaveDeduction)
    }

    fun standardMonthlyHours(year: Int, month: Int): Double {
        val daysInMonth = JalaliCalendarHelper.getDaysInMonth(year, month)
        var total = 0.0
        for (day in 1..daysInMonth) {
            val dayOfWeek = JalaliCalendarHelper.getDayOfWeekInt(year, month, day)
            if (dayOfWeek == Calendar.FRIDAY) continue
            total += if (dayOfWeek == Calendar.THURSDAY) 5.0 else 7.0
        }
        return total
    }

    fun standardDailyHours(leave: LeaveDay): Double {
        val dayOfWeek = JalaliCalendarHelper.getDayOfWeekInt(
            leave.jalaliYear,
            leave.jalaliMonth,
            leave.jalaliDay
        )
        return when (dayOfWeek) {
            Calendar.FRIDAY -> 0.0
            Calendar.THURSDAY -> 5.0
            else -> 7.0
        }
    }

    fun toggleShift() {
        viewModelScope.launch {
            val active = repository.getActiveRecord()
            val now = System.currentTimeMillis()
            val jDate = JalaliCalendarHelper.fromEpochMillis(now)

            if (active == null) {
                // Start Shift
                val newRecord = TimeRecord(
                    jalaliYear = jDate.year,
                    jalaliMonth = jDate.month,
                    jalaliDay = jDate.day,
                    startTimeMillis = now,
                    endTimeMillis = null
                )
                repository.insertRecord(newRecord)
            } else {
                // End Shift (End shift cannot be before start shift)
                val updated = active.copy(endTimeMillis = maxOf(active.startTimeMillis + 1000, now))
                repository.updateRecord(updated)
            }
            checkActiveSession()
        }
    }

    fun saveObligatoryHours(year: Int, month: Int, hours: Double) {
        viewModelScope.launch {
            repository.insertObligatoryHours(MonthlyObligatoryHours(year, month, hours))
            loadCurrentMonthObligatoryHours()
        }
    }

    fun addManualRecord(year: Int, month: Int, day: Int, startH: Int, startM: Int, endH: Int, endM: Int) {
        viewModelScope.launch {
            val startMillis = JalaliCalendarHelper.toEpochMillis(year, month, day, startH, startM)
            val endMillis = JalaliCalendarHelper.toEpochMillis(year, month, day, endH, endM)
            
            // Adjust end time to be after start time if needed
            val finalEndMillis = if (endMillis <= startMillis) startMillis + 60 * 1000 else endMillis

            val record = TimeRecord(
                jalaliYear = year,
                jalaliMonth = month,
                jalaliDay = day,
                startTimeMillis = startMillis,
                endTimeMillis = finalEndMillis,
                isManual = true
            )
            repository.insertRecord(record)
            checkActiveSession()
        }
    }

    fun updateRecord(record: TimeRecord) {
        viewModelScope.launch {
            repository.updateRecord(record)
            checkActiveSession()
        }
    }

    fun deleteRecord(record: TimeRecord) {
        viewModelScope.launch {
            repository.deleteRecord(record)
            checkActiveSession()
        }
    }

    fun generateReport(sYear: Int, sMonth: Int, sDay: Int, eYear: Int, eMonth: Int, eDay: Int) {
        viewModelScope.launch {
            val startMillis = JalaliCalendarHelper.toEpochMillis(sYear, sMonth, sDay, 0, 0)
            val endMillis = JalaliCalendarHelper.toEpochMillis(eYear, eMonth, eDay, 23, 59)
            val list = repository.getRecordsInRange(startMillis, endMillis)

            val totalWorked = list.sumOf { record ->
                val duration = (record.endTimeMillis ?: System.currentTimeMillis()) - record.startTimeMillis
                duration.toDouble() / (1000 * 60 * 60)
            }

            val leaveDays = leavesInRange(startMillis, endMillis)

            // Calculate standard calendar required hours dynamically for the chosen range
            var standardSum = 0.0
            var currentDayMillis = startMillis
            // Prevent infinite loop if endMillis < startMillis
            val safeEndMillis = maxOf(startMillis, endMillis)
            while (currentDayMillis <= safeEndMillis) {
                val jDate = JalaliCalendarHelper.fromEpochMillis(currentDayMillis)
                val dayOfWeek = JalaliCalendarHelper.getDayOfWeekInt(jDate.year, jDate.month, jDate.day)
                if (dayOfWeek != Calendar.FRIDAY) {
                    standardSum += if (dayOfWeek == Calendar.THURSDAY) 5.0 else 7.0
                }
                currentDayMillis += 24 * 60 * 60 * 1000 // increment one calendar day
            }

            // If start/end month are the same, we check if there is a manual obligatory setting
            val savedTarget = if (sYear == eYear && sMonth == eMonth) {
                repository.getObligatoryHours(sYear, sMonth)
            } else {
                null
            }
            val baseHours = savedTarget?.requiredHours ?: standardSum
            val leaveDeduction = leaveDays.sumOf { standardDailyHours(it) }

            _reportWorkedHours.value = totalWorked
            _reportObligatoryHours.value = maxOf(0.0, baseHours - leaveDeduction)
            _reportLeaveDays.value = leaveDays
        }
    }

    private suspend fun leavesInRange(startMillis: Long, endMillis: Long): List<LeaveDay> {
        val start = JalaliCalendarHelper.fromEpochMillis(startMillis)
        val end = JalaliCalendarHelper.fromEpochMillis(maxOf(startMillis, endMillis))
        val result = mutableListOf<LeaveDay>()
        var year = start.year
        var month = start.month
        while (year < end.year || (year == end.year && month <= end.month)) {
            result += repository.getLeavesForMonth(year, month)
            if (month == 12) {
                year += 1
                month = 1
            } else {
                month += 1
            }
        }
        return result
    }

    fun exportMonthCsv(context: Context, year: Int, month: Int, onComplete: (Uri?) -> Unit) {
        viewModelScope.launch {
            val records = repository.getRecordsForMonth(year, month)
            val totalWorked = records.sumOf { record ->
                val duration = (record.endTimeMillis ?: record.startTimeMillis) - record.startTimeMillis
                duration.toDouble() / (1000 * 60 * 60)
            }

            val daysInMonth = JalaliCalendarHelper.getDaysInMonth(year, month)
            val monthLeaves = repository.getLeavesForMonth(year, month).associateBy { it.jalaliDay }
            var countSatWed = 0
            var countThu = 0
            for (day in 1..daysInMonth) {
                if (monthLeaves.containsKey(day)) continue
                val dayOfWeek = JalaliCalendarHelper.getDayOfWeekInt(year, month, day)
                if (dayOfWeek == Calendar.FRIDAY) continue
                if (dayOfWeek == Calendar.THURSDAY) countThu++ else countSatWed++
            }

            val numWorkingDays = countSatWed + countThu
            
            // Calculate standard base required hours
            val totalStandardHours = (countSatWed * 7.0) + (countThu * 5.0)
            
            // Overtime or Undertime difference
            val diff = totalWorked - totalStandardHours
            val adjustmentPerHour = if (numWorkingDays > 0) diff / numWorkingDays else 0.0

            val csvBuilder = StringBuilder()
            // Excel UTF-8 with BOM standard
            csvBuilder.append("روز,تاریخ,ورود,خروج,کارکرد (ساعت),وضعیت,توضیحات\n")

            for (day in 1..daysInMonth) {
                val dayOfWeekStr = JalaliCalendarHelper.getDayOfWeekPersian(year, month, day)
                val dayOfWeekInt = JalaliCalendarHelper.getDayOfWeekInt(year, month, day)
                val dateStr = String.format(Locale.US, "%04d/%02d/%02d", year, month, day)
                val leave = monthLeaves[day]

                if (leave != null) {
                    csvBuilder.append(
                        "$dayOfWeekStr,$dateStr,-,-,0.00,${csvField(leave.leaveType)},${csvField(leave.note)}\n"
                    )
                } else if (dayOfWeekInt == Calendar.FRIDAY) {
                    csvBuilder.append("$dayOfWeekStr,$dateStr,تعطیل,تعطیل,0.00,تعطیل,\n")
                } else {
                    val standardDuration = if (dayOfWeekInt == Calendar.THURSDAY) 5.0 else 7.0
                    val adjustedDuration = maxOf(0.0, standardDuration + adjustmentPerHour)
                    val entryTime = "07:00"
                    val exitTime = formatExitTime(adjustedDuration)
                    val workedFormatted = String.format(Locale.US, "%.2f", adjustedDuration)
                    csvBuilder.append("$dayOfWeekStr,$dateStr,$entryTime,$exitTime,$workedFormatted,,\n")
                }
            }

            val uri = saveCsvToDownloads(context, year, month, csvBuilder.toString())
            onComplete(uri)
        }
    }

    private fun formatExitTime(durationHours: Double): String {
        val totalTimeInHours = 7.0 + durationHours
        var hours = totalTimeInHours.toInt()
        var minutes = Math.round((totalTimeInHours - hours) * 60).toInt()
        if (minutes >= 60) {
            hours += 1
            minutes -= 60
        }
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    private fun saveCsvToDownloads(context: Context, year: Int, month: Int, csvContent: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "گزارش_کارکرد_${year}_${month}.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                // Write standard UTF-8 BOM bytes
                outputStream.write(0xEF)
                outputStream.write(0xBB)
                outputStream.write(0xBF)
                val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
                writer.write(csvContent)
                writer.flush()
            }
        }
        return uri
    }

    // ---------------------------------------------------------------------
    // گزارش‌های ثبت شده برای هر سابقه کارکرد
    // ---------------------------------------------------------------------

    fun addTextReport(recordId: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.insertReport(
                WorkReport(
                    recordId = recordId,
                    reportType = REPORT_TYPE_TEXT,
                    textContent = trimmed
                )
            )
        }
    }

    fun deleteReport(report: WorkReport) {
        viewModelScope.launch {
            repository.deleteReport(report)
        }
    }

    /** گزارش متنی: ارسال نسخه txt به گوگل درایو. */
    suspend fun sendTextReportToGoogleDrive(record: TimeRecord, report: WorkReport): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val delivery = ReportDelivery(getApplication())
                val fileName = "report_${recordLabel(record).replace('/', '-')}_${report.id}.txt"
                val link = delivery.uploadTextToGoogleDrive(
                    fileName = fileName,
                    text = buildTextReportBody(record, report),
                    description = buildRecordDescription(record)
                )
                repository.updateReport(report.copy(driveUrl = link))
                link
            }
        }

    /** گزارش متنی: ارسال متن به تلگرام. */
    suspend fun sendTextReportToTelegram(record: TimeRecord, report: WorkReport): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val delivery = ReportDelivery(getApplication())
                val reference = delivery.sendTextToTelegram(buildTextReportBody(record, report))
                repository.updateReport(report.copy(telegramRef = reference))
                reference
            }
        }

    /**
     * گزارش صوتی: فایل صوتی فقط به گوگل درایو و تلگرام ارسال می‌شود و پس از ارسال
     * موفق از حافظه برنامه پاک می‌گردد؛ در دیتابیس تنها نشانی ارسال‌ها نگه داشته می‌شود.
     */
    suspend fun archiveVoiceReport(record: TimeRecord, audioFile: File): Result<WorkReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val delivery = ReportDelivery(getApplication())
                val settings = delivery.currentSettings()
                if (!settings.isAudioArchiveReady) {
                    throw DeliveryException(
                        "برای گزارش صوتی باید هم گوگل درایو و هم تلگرام در تنظیمات کامل شود"
                    )
                }
                val bytes = audioFile.readBytes()
                val fileName = "voice_${recordLabel(record).replace('/', '-')}_${System.currentTimeMillis()}.m4a"
                val description = buildRecordDescription(record)
                val driveLink = delivery.uploadToGoogleDrive(fileName, "audio/mp4", bytes, description)
                val telegramRef = delivery.sendFileToTelegram(
                    fileName = fileName,
                    mimeType = "audio/mp4",
                    bytes = bytes,
                    caption = description,
                    preferAudio = true
                )
                val report = WorkReport(
                    recordId = record.id,
                    reportType = REPORT_TYPE_AUDIO,
                    audioFileName = fileName,
                    driveUrl = driveLink,
                    telegramRef = telegramRef
                )
                val newId = repository.insertReport(report)
                audioFile.delete()
                report.copy(id = newId)
            }
        }

    // ---------------------------------------------------------------------
    // مرخصی
    // ---------------------------------------------------------------------

    fun saveLeave(year: Int, month: Int, day: Int, leaveType: String, note: String) {
        viewModelScope.launch {
            repository.insertLeave(
                LeaveDay(
                    jalaliYear = year,
                    jalaliMonth = month,
                    jalaliDay = day,
                    leaveType = leaveType,
                    note = note.trim().ifEmpty { null }
                )
            )
            refreshMonthlyObligatoryHours(year, month)
        }
    }

    fun deleteLeave(leave: LeaveDay) {
        viewModelScope.launch {
            repository.deleteLeave(leave)
            refreshMonthlyObligatoryHours(leave.jalaliYear, leave.jalaliMonth)
        }
    }

    private fun refreshMonthlyObligatoryHours(year: Int, month: Int) {
        val today = JalaliCalendarHelper.fromEpochMillis(System.currentTimeMillis())
        if (year == today.year && month == today.month) {
            loadCurrentMonthObligatoryHours()
        }
    }

    private fun recordLabel(record: TimeRecord): String =
        String.format(Locale.US, "%04d/%02d/%02d", record.jalaliYear, record.jalaliMonth, record.jalaliDay)

    private fun recordTimeLabel(record: TimeRecord): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.US)
        val start = formatter.format(Date(record.startTimeMillis))
        val end = record.endTimeMillis?.let { formatter.format(Date(it)) } ?: "در حال ثبت"
        return "$start تا $end"
    }

    private fun buildRecordDescription(record: TimeRecord): String =
        "گزارش سابقه کارکرد ${recordLabel(record)} - ساعت ${recordTimeLabel(record)}"

    private fun buildTextReportBody(record: TimeRecord, report: WorkReport): String =
        buildRecordDescription(record) + "\n\n" + report.textContent.orEmpty()

    private fun csvField(value: String?): String {
        val safe = (value ?: "").replace("\"", "\"\"")
        return "\"$safe\""
    }
}
