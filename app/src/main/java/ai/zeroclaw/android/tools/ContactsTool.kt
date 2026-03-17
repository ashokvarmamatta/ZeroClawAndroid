package ai.zeroclaw.android.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ContactsTool — search and read Android contacts.
 *
 * Inspired by OpenClaw's contacts skill. Uses Android ContactsProvider.
 * Actions: search (default), list, details.
 */
class ContactsTool(private val context: Context) : Tool {

    override val name = "contacts"

    override val description = "Search and read contacts from the device. " +
            "Actions: 'search' (find by name/number), 'list' (recent contacts), " +
            "'details' (full info for a contact). Requires contacts permission."

    override val parameters = listOf(
        ToolParam("action", "string", "One of: search, list, details. Default: search.", required = false),
        ToolParam("query", "string", "Name or number to search for (for 'search' and 'details')", required = false),
        ToolParam("limit", "string", "Max results to return. Default: 10.", required = false)
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        if (!hasContactsPermission()) {
            return ToolResult(false, "", "Contacts permission not granted. " +
                    "Please grant READ_CONTACTS permission in device settings.")
        }

        val action = args["action"]?.trim()?.lowercase() ?: "search"
        val limit = args["limit"]?.trim()?.toIntOrNull() ?: 10

        return withContext(Dispatchers.IO) {
            try {
                when (action) {
                    "search" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' parameter for search")
                        searchContacts(query, limit)
                    }
                    "list" -> listContacts(limit)
                    "details" -> {
                        val query = args["query"]?.trim()
                            ?: return@withContext ToolResult(false, "", "Missing 'query' parameter for details")
                        contactDetails(query)
                    }
                    else -> ToolResult(false, "", "Unknown action: $action. Use: search, list, details.")
                }
            } catch (e: SecurityException) {
                ToolResult(false, "", "Contacts permission denied: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "", "Contacts error: ${e.message}")
            }
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun searchContacts(query: String, limit: Int): ToolResult {
        if (query.isBlank()) return ToolResult(false, "", "Search query cannot be empty")

        val contacts = mutableListOf<String>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.STARRED
        )

        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val idxName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val idxHasPhone = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val idxStarred = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)

            while (cursor.moveToNext() && contacts.size < limit) {
                val contactId = cursor.getString(idxId)
                val name = cursor.getString(idxName) ?: "(No name)"
                val hasPhone = if (idxHasPhone >= 0) cursor.getInt(idxHasPhone) > 0 else false
                val starred = if (idxStarred >= 0) cursor.getInt(idxStarred) > 0 else false

                val sb = StringBuilder()
                sb.append(if (starred) "⭐ " else "• ")
                sb.append(name)

                // Get phone numbers
                if (hasPhone) {
                    val phones = getPhoneNumbers(contactId)
                    if (phones.isNotEmpty()) {
                        sb.append("\n  📱 ${phones.joinToString(", ")}")
                    }
                }

                // Get email
                val emails = getEmails(contactId)
                if (emails.isNotEmpty()) {
                    sb.append("\n  ✉️ ${emails.joinToString(", ")}")
                }

                contacts.add(sb.toString())
            }
        }

        if (contacts.isEmpty()) {
            return ToolResult(true, "No contacts found matching \"$query\".")
        }

        val header = "Contacts matching \"$query\" (${contacts.size} found)\n${"═".repeat(40)}\n\n"
        return ToolResult(true, (header + contacts.joinToString("\n\n")).take(4000))
    }

    private fun listContacts(limit: Int): ToolResult {
        val contacts = mutableListOf<String>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.STARRED
        )

        val sortOrder = "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idxId = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val idxName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val idxHasPhone = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val idxStarred = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)

            while (cursor.moveToNext() && contacts.size < limit) {
                val contactId = cursor.getString(idxId)
                val name = cursor.getString(idxName) ?: continue
                val hasPhone = if (idxHasPhone >= 0) cursor.getInt(idxHasPhone) > 0 else false
                val starred = if (idxStarred >= 0) cursor.getInt(idxStarred) > 0 else false

                val sb = StringBuilder()
                sb.append(if (starred) "⭐ " else "• ")
                sb.append(name)

                if (hasPhone) {
                    val phones = getPhoneNumbers(contactId)
                    if (phones.isNotEmpty()) {
                        sb.append(" — ${phones.first()}")
                    }
                }

                contacts.add(sb.toString())
            }
        }

        if (contacts.isEmpty()) {
            return ToolResult(true, "No contacts found on this device.")
        }

        val header = "Contacts (${contacts.size} shown)\n${"═".repeat(40)}\n\n"
        return ToolResult(true, (header + contacts.joinToString("\n")).take(4000))
    }

    private fun contactDetails(query: String): ToolResult {
        if (query.isBlank()) return ToolResult(false, "", "Query cannot be empty")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.PHOTO_URI
        )

        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return ToolResult(true, "No contact found matching \"$query\".")
            }

            val idxId = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val idxName = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val idxStarred = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)

            val contactId = cursor.getString(idxId)
            val name = cursor.getString(idxName) ?: "(No name)"
            val starred = if (idxStarred >= 0) cursor.getInt(idxStarred) > 0 else false

            val sb = StringBuilder()
            sb.appendLine("Contact Details")
            sb.appendLine("═".repeat(40))
            sb.appendLine()
            sb.appendLine("Name: $name${if (starred) " ⭐" else ""}")

            // Phone numbers
            val phones = getPhoneNumbers(contactId)
            if (phones.isNotEmpty()) {
                sb.appendLine("\nPhone Numbers:")
                phones.forEach { sb.appendLine("  📱 $it") }
            }

            // Emails
            val emails = getEmails(contactId)
            if (emails.isNotEmpty()) {
                sb.appendLine("\nEmail Addresses:")
                emails.forEach { sb.appendLine("  ✉️ $it") }
            }

            // Organization
            val org = getOrganization(contactId)
            if (org != null) sb.appendLine("\nOrganization: $org")

            // Address
            val addresses = getAddresses(contactId)
            if (addresses.isNotEmpty()) {
                sb.appendLine("\nAddresses:")
                addresses.forEach { sb.appendLine("  📍 $it") }
            }

            // Notes
            val notes = getNotes(contactId)
            if (notes != null) sb.appendLine("\nNotes: $notes")

            return ToolResult(true, sb.toString().take(4000))
        }

        return ToolResult(true, "No contact found matching \"$query\".")
    }

    private fun getPhoneNumbers(contactId: String): List<String> {
        val phones = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { cursor ->
            val idxNumber = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idxType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (cursor.moveToNext()) {
                val number = cursor.getString(idxNumber) ?: continue
                val type = if (idxType >= 0) {
                    when (cursor.getInt(idxType)) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Other"
                    }
                } else "Phone"
                phones.add("$number ($type)")
            }
        }
        return phones
    }

    private fun getEmails(contactId: String): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { cursor ->
            val idxAddress = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                val email = cursor.getString(idxAddress) ?: continue
                emails.add(email)
            }
        }
        return emails
    }

    private fun getOrganization(contactId: String): String? {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY, ContactsContract.CommonDataKinds.Organization.TITLE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val company = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY))
                val title = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE))
                val parts = listOfNotNull(title, company).filter { it.isNotBlank() }
                if (parts.isNotEmpty()) return parts.joinToString(" at ")
            }
        }
        return null
    }

    private fun getAddresses(contactId: String): List<String> {
        val addresses = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
            "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { cursor ->
            val idxAddr = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
            while (cursor.moveToNext()) {
                val addr = cursor.getString(idxAddr) ?: continue
                if (addr.isNotBlank()) addresses.add(addr)
            }
        }
        return addresses
    }

    private fun getNotes(contactId: String): String? {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val note = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE))
                if (!note.isNullOrBlank()) return note.take(200)
            }
        }
        return null
    }
}
