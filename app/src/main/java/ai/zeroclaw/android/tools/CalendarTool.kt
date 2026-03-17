package ai.zeroclaw.android.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * CalendarTool — read/create Android calendar events.
 *
 * Inspired by OpenClaw's calendar skill. Uses Android CalendarProvider.
 * Actions: list (default), create, today, week, search.
 */
class CalendarTool(private val context: Context) : Tool {

    override val name = "calendar"

    override val description = "Read and create Android calendar events. " +
            "Actions: 'list' (events for a date/range), 'today' (today's events), " +
            "'week' (this week's events), 'create' (new event), 'search' (find by keyword). " +
            "Requires calendar permission on the device."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: list, today, week, create, search. Default: today.", required = false),
        ToolParam("title", "string", "Event title (required for 'create')", required = false),
        ToolParam("date", "string", "Date in YYYY-MM-DD format (for 'list' or 'create'). Default: today.", required = false),
        ToolParam("time", "string", "Start time in HH:mm 24h format (for 'create'). Default: 09:00.", required = false),
        ToolParam("duration", "string", "Duration in minutes (for 'create'). Default: 60.", required = false),
        ToolParam("description", "string", "Event description (for 'create')", required = false),
        ToolParam("location", "string", "Event location (for 'create')", required = false),
        ToolParam("query", "string", "Search keyword (for 'search')", required = false),
        ToolParam("days", "string", "Number of days to look ahead (for 'list'). Default: 1.", required = false)
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val displayFormat = SimpleDateFormat("EEE, MMM d yyyy", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("h:mm a", Locale.US)

    override suspend fun execute(args: Map<String, String>): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult(false, "", "Calendar permission not granted. " +
                    "Please grant READ_CALENDAR and WRITE_CALENDAR permissions in device settings.")
        }

        val action = args["action"]?.trim()?.lowercase() ?: "today"

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "today" -> listEvents(todayStart(), todayEnd())
                    "week" -> listEvents(todayStart(), weekEnd())
                    "list" -> {
                        val date = parseDate(args["date"]) ?: todayStart()
                        val days = args["days"]?.trim()?.toIntOrNull() ?: 1
                        val endDate = Calendar.getInstance().apply {
                            time = Date(date)
                            add(Calendar.DAY_OF_YEAR, days)
                        }.timeInMillis
                        listEvents(date, endDate)
                    }
                    "create" -> createEvent(args)
                    "search" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' parameter for search")
                        searchEvents(query)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: today, week, list, create, search.")
                }
            } catch (e: SecurityException) {
                ToolResult(false, "", "Calendar permission denied: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "", "Calendar error: ${e.message}")
            }
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun todayStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun todayEnd(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun weekEnd(): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 7)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            dateFormat.parse(dateStr.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun listEvents(startMillis: Long, endMillis: Long): ToolResult {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idxTitle = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val idxStart = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val idxEnd = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val idxLocation = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val idxDesc = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val idxAllDay = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val idxCalendar = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val title = cursor.getString(idxTitle) ?: "(No title)"
                val start = cursor.getLong(idxStart)
                val end = if (idxEnd >= 0) cursor.getLong(idxEnd) else 0L
                val location = if (idxLocation >= 0) cursor.getString(idxLocation) else null
                val desc = if (idxDesc >= 0) cursor.getString(idxDesc) else null
                val allDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false
                val calName = if (idxCalendar >= 0) cursor.getString(idxCalendar) else null

                val sb = StringBuilder()
                sb.append("• $title")
                if (allDay) {
                    sb.append(" (All Day)")
                } else {
                    sb.append("\n  Time: ${displayTimeFormat.format(Date(start))}")
                    if (end > 0) sb.append(" – ${displayTimeFormat.format(Date(end))}")
                }
                sb.append("\n  Date: ${displayFormat.format(Date(start))}")
                if (!location.isNullOrBlank()) sb.append("\n  Location: $location")
                if (!desc.isNullOrBlank()) sb.append("\n  Notes: ${desc.take(100)}")
                if (!calName.isNullOrBlank()) sb.append("\n  Calendar: $calName")
                events.add(sb.toString())
            }
        }

        if (events.isEmpty()) {
            val startStr = displayFormat.format(Date(startMillis))
            val endStr = displayFormat.format(Date(endMillis))
            return ToolResult(true, "No events found from $startStr to $endStr.")
        }

        val startStr = displayFormat.format(Date(startMillis))
        val endStr = displayFormat.format(Date(endMillis))
        val header = "Calendar Events ($startStr – $endStr)\n${"═".repeat(40)}\n\n"
        val content = header + events.joinToString("\n\n")
        return ToolResult(true, content.take(4000))
    }

    private fun createEvent(args: Map<String, String>): ToolResult {
        if (!hasWritePermission()) {
            return ToolResult(false, "", "WRITE_CALENDAR permission not granted.")
        }

        val title = args["title"]?.trim()
            ?: return ToolResult(false, "", "Missing 'title' parameter for creating an event")

        if (title.isBlank()) {
            return ToolResult(false, "", "Event title cannot be blank")
        }

        // Parse date (default: today)
        val dateStr = args["date"]?.trim()
        val calendar = Calendar.getInstance()
        if (!dateStr.isNullOrBlank()) {
            val parsed = dateFormat.parse(dateStr)
            if (parsed != null) {
                val parsedCal = Calendar.getInstance().apply { time = parsed }
                calendar.set(Calendar.YEAR, parsedCal.get(Calendar.YEAR))
                calendar.set(Calendar.MONTH, parsedCal.get(Calendar.MONTH))
                calendar.set(Calendar.DAY_OF_MONTH, parsedCal.get(Calendar.DAY_OF_MONTH))
            }
        }

        // Parse time (default: 09:00)
        val timeStr = args["time"]?.trim() ?: "09:00"
        val timeParts = timeStr.split(":")
        if (timeParts.size == 2) {
            calendar.set(Calendar.HOUR_OF_DAY, timeParts[0].toIntOrNull() ?: 9)
            calendar.set(Calendar.MINUTE, timeParts[1].toIntOrNull() ?: 0)
        }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startMillis = calendar.timeInMillis
        val durationMins = args["duration"]?.trim()?.toIntOrNull() ?: 60
        val endMillis = startMillis + durationMins * 60 * 1000L

        // Get default calendar ID
        val calendarId = getDefaultCalendarId()
            ?: return ToolResult(false, "", "No calendar found on this device. Please add a Google account or create a calendar first.")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args["description"]?.trim()?.let {
                if (it.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, it)
            }
            args["location"]?.trim()?.let {
                if (it.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, it)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolResult(false, "", "Failed to create calendar event")

        val eventId = uri.lastPathSegment
        val sb = StringBuilder("✅ Event created successfully!\n\n")
        sb.appendLine("Title: $title")
        sb.appendLine("Date: ${displayFormat.format(Date(startMillis))}")
        sb.appendLine("Time: ${displayTimeFormat.format(Date(startMillis))} – ${displayTimeFormat.format(Date(endMillis))}")
        sb.appendLine("Duration: $durationMins minutes")
        args["location"]?.trim()?.let { if (it.isNotBlank()) sb.appendLine("Location: $it") }
        args["description"]?.trim()?.let { if (it.isNotBlank()) sb.appendLine("Notes: $it") }
        sb.appendLine("Event ID: $eventId")

        return ToolResult(true, sb.toString())
    }

    private fun searchEvents(query: String): ToolResult {
        if (query.isBlank()) {
            return ToolResult(false, "", "Search query cannot be empty")
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )

        // Search in past 30 days and future 90 days
        val now = System.currentTimeMillis()
        val searchStart = now - 30L * 24 * 60 * 60 * 1000
        val searchEnd = now + 90L * 24 * 60 * 60 * 1000

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? " +
                "AND ${CalendarContract.Events.TITLE} LIKE ?"
        val selectionArgs = arrayOf(searchStart.toString(), searchEnd.toString(), "%$query%")
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<String>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idxTitle = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val idxStart = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val idxEnd = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val idxLocation = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val idxAllDay = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)

            while (cursor.moveToNext() && events.size < 20) {
                val title = cursor.getString(idxTitle) ?: "(No title)"
                val start = cursor.getLong(idxStart)
                val end = if (idxEnd >= 0) cursor.getLong(idxEnd) else 0L
                val location = if (idxLocation >= 0) cursor.getString(idxLocation) else null
                val allDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false

                val sb = StringBuilder("• $title")
                sb.append("\n  Date: ${displayFormat.format(Date(start))}")
                if (!allDay) {
                    sb.append("\n  Time: ${displayTimeFormat.format(Date(start))}")
                    if (end > 0) sb.append(" – ${displayTimeFormat.format(Date(end))}")
                } else {
                    sb.append(" (All Day)")
                }
                if (!location.isNullOrBlank()) sb.append("\n  Location: $location")
                events.add(sb.toString())
            }
        }

        if (events.isEmpty()) {
            return ToolResult(true, "No events found matching \"$query\" in the past 30 days or next 90 days.")
        }

        val header = "Search Results for \"$query\" (${events.size} found)\n${"═".repeat(40)}\n\n"
        return ToolResult(true, (header + events.joinToString("\n\n")).take(4000))
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val idxPrimary = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

            // Prefer primary calendar
            var firstId: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                if (firstId == null) firstId = id
                val isPrimary = if (idxPrimary >= 0) cursor.getInt(idxPrimary) == 1 else false
                if (isPrimary) return id
            }
            return firstId
        }
        return null
    }
}
