package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.Manifest
import android.os.Bundle
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.flipos.launcher.ui.CallLogItem
import com.flipos.launcher.ui.CallLogRowAdapter
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.PermissionGate
import com.flipos.launcher.util.openAppPermissionSettings
import com.flipos.launcher.util.placeCall
import com.flipos.launcher.util.sendMessage

/**
 * The KaiOS "Recent Calls" action for the Send key: our own list screen over
 * `CallLog.Calls`, not the system dialer's call-log tab, which isn't reliably
 * reachable by intent across OEM dialers (especially on Kyocera-style
 * devices) - the same reasoning that produced [NoticesActivity].
 */
class CallLogActivity : BaseListActivity() {

    private lateinit var adapter: CallLogRowAdapter
    private lateinit var emptyView: TextView
    private val loader = BackgroundLoader()

    private val readCallLogPermission = PermissionGate(this, Manifest.permission.READ_CALL_LOG)
    private val callPermission = PermissionGate(this, Manifest.permission.CALL_PHONE)
    private val contactsPermission = PermissionGate(this, Manifest.permission.READ_CONTACTS)

    /** Contacts access is asked for at most once per screen visit, never in a loop on denial. */
    private var contactsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView.text = getString(R.string.title_call_log)
        emptyView = findViewById(R.id.empty_view)

        adapter = CallLogRowAdapter(onClick = { callEntry(it) })
        listView.adapter = adapter

        softKeys.setLabels(
            getString(R.string.softkey_back),
            getString(R.string.softkey_call),
            getString(R.string.softkey_options),
        )
        softKeys.setOnLeftClick { finish() }
        softKeys.setOnCenterClick { onCenterPressed() }
        softKeys.setOnRightClick { adapter.itemAt(focusedPosition())?.let { showOptions(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (isRecreatingForAccent) return
        refresh()
        if (!readCallLogPermission.isGranted()) {
            requestCallLogAccess()
        } else {
            maybeRequestContactsAccess()
        }
    }

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        if (!readCallLogPermission.isGranted()) {
            adapter.submit(emptyList())
            emptyView.text = getString(R.string.calllog_access_required)
            emptyView.visibility = TextView.VISIBLE
            return
        }
        loader.load(
            produce = { queryCallLog() },
            consume = { items ->
                if (isDestroyed) return@load
                adapter.submit(items)
                if (items.isEmpty()) {
                    emptyView.text = getString(R.string.calllog_empty)
                    emptyView.visibility = TextView.VISIBLE
                } else {
                    emptyView.visibility = TextView.GONE
                    focusFirst()
                }
            },
        )
    }

    private fun queryCallLog(): List<CallLogItem> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val items = ArrayList<CallLogItem>()
        val lookupContacts = contactsPermission.isGranted()
        val namesByNumber = HashMap<String, String?>()
        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT $QUERY_LIMIT",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberCol).orEmpty()
                // CACHED_NAME is only what the dialer recorded at call time -
                // often null/blank (contact saved later, OEM dialers that never
                // fill it, number-format mismatches) - so the live Contacts
                // match wins whenever there is one.
                val liveName = if (lookupContacts && number.isNotBlank()) {
                    namesByNumber.getOrPut(number) { contactName(number) }
                } else {
                    null
                }
                items.add(
                    CallLogItem(
                        id = cursor.getLong(idCol),
                        number = number,
                        displayName = liveName ?: cursor.getString(nameCol)?.takeIf { it.isNotBlank() },
                        type = cursor.getInt(typeCol),
                        date = cursor.getLong(dateCol),
                        duration = cursor.getLong(durationCol),
                    ),
                )
            }
        }
        return items
    }

    /** The Contacts display name for [number] (PhoneLookup handles formatting differences), or null. */
    private fun contactName(number: String): String? = try {
        contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun onCenterPressed() {
        if (!readCallLogPermission.isGranted()) {
            requestCallLogAccess()
            return
        }
        adapter.itemAt(focusedPosition())?.let { callEntry(it) }
    }

    private fun requestCallLogAccess() {
        readCallLogPermission.run(
            onDenied = {
                // shouldShowRequestPermissionRationale is false both before the
                // first ask and after a permanent denial; inside this callback
                // we've just been denied, so false here means permanent - the
                // system won't show its dialog again, so send the user to the
                // app's permission settings page instead of a dead end.
                if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
                    openAppPermissionSettings()
                }
            },
            action = {
                refresh()
                maybeRequestContactsAccess()
            },
        )
    }

    /**
     * Names come from a live Contacts lookup (see [contactName]), which needs
     * READ_CONTACTS; without it the list still works, just falling back to
     * the call log's own cached names.
     */
    private fun maybeRequestContactsAccess() {
        if (contactsRequested || contactsPermission.isGranted()) return
        contactsRequested = true
        contactsPermission.run(action = { refresh() })
    }

    private fun callEntry(item: CallLogItem) {
        if (item.number.isBlank()) return
        placeCall(callPermission, item.number)
    }

    private fun showOptions(item: CallLogItem) {
        if (item.number.isBlank()) return
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(getString(R.string.calllog_send_message)),
            ) { _, which ->
                when (which) {
                    0 -> sendMessage(item.number)
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SOFT_LEFT -> { finish(); return true }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                adapter.itemAt(focusedPosition())?.let { showOptions(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val QUERY_LIMIT = 100
    }
}
