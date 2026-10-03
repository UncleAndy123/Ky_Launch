package com.flipos.launcher.util

import android.content.Context
import android.net.Uri

/** A contact bound to a speed-dial digit key in the phone's own dialer. */
data class SystemSpeedDialEntry(val number: String, val label: String)

/**
 * Reads speed dial slot [digit] directly from the phone's own dialer data
 * (content://speed_dial/speed_dial, backed by com.android.providers.contacts
 * - confirmed via adb on this Kyocera hardware), instead of keeping a
 * separate copy of the same assignment in our own storage. Requires
 * READ_CONTACTS; returns null on any failure (permission denied, provider
 * unavailable/different on another OEM, slot unassigned) - callers treat
 * that uniformly as "not set."
 *
 * Projection is deliberately null (select everything) rather than naming
 * "data1"/"display_name" up front: SpeedDialProvider's internal
 * SQLiteQueryBuilder has a projection allowlist that rejects those column
 * names outright (IllegalArgumentException: Invalid column data1), even
 * though the very same names come back fine as columns in an unrestricted
 * query - confirmed both via adb and via a real on-device crash log.
 *
 * The selection column is qualified as "speed_table._id" (the exact alias
 * the provider's own internal query uses for its base speed_dial table,
 * also seen in that same crash log) rather than a bare "_id" - the
 * provider's query joins five tables, several of which have their own
 * _id column, so an unqualified "_id = ?" fails with
 * "ambiguous column name: _id".
 */
fun systemSpeedDial(context: Context, digit: Int): SystemSpeedDialEntry? = try {
    context.contentResolver.query(
        Uri.parse("content://speed_dial/speed_dial"),
        null,
        "speed_table._id = ?",
        arrayOf(digit.toString()),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val numberCol = cursor.getColumnIndex("data1")
            val nameCol = cursor.getColumnIndex("display_name")
            val number = if (numberCol >= 0) cursor.getString(numberCol) else null
            val name = if (nameCol >= 0) cursor.getString(nameCol) else null
            if (number.isNullOrBlank()) null else SystemSpeedDialEntry(number, name ?: number)
        } else {
            null
        }
    }
} catch (e: Exception) {
    null
}
