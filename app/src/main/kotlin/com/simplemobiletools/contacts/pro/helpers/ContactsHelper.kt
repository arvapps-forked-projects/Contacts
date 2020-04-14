package com.simplemobiletools.contacts.pro.helpers

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.*
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.MediaStore
import android.text.TextUtils
import android.util.SparseArray
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.contacts.pro.R
import com.simplemobiletools.contacts.pro.extensions.*
import com.simplemobiletools.contacts.pro.models.*
import com.simplemobiletools.contacts.pro.models.Email
import com.simplemobiletools.contacts.pro.models.Event
import com.simplemobiletools.contacts.pro.models.Organization
import com.simplemobiletools.contacts.pro.overloads.times
import java.util.*
import kotlin.collections.ArrayList

class ContactsHelper(val context: Context) {
    private val BATCH_SIZE = 50
    private var displayContactSources = ArrayList<String>()

    fun getContacts(getAll: Boolean = false, ignoredContactSources: HashSet<String> = HashSet(), callback: (ArrayList<Contact>) -> Unit) {
        ensureBackgroundThread {
            val contacts = SparseArray<Contact>()
            displayContactSources = context.getVisibleContactSources()

            if (getAll) {
                displayContactSources = if (ignoredContactSources.isEmpty()) {
                    context.getAllContactSources().map { it.name }.toMutableList() as ArrayList
                } else {
                    context.getAllContactSources().filter {
                        it.getFullIdentifier().isNotEmpty() && !ignoredContactSources.contains(it.getFullIdentifier())
                    }.map { it.name }.toMutableList() as ArrayList
                }
            }

            getDeviceContacts(contacts, ignoredContactSources)

            if (displayContactSources.contains(SMT_PRIVATE)) {
                LocalContactsHelper(context).getAllContacts().forEach {
                    contacts.put(it.id, it)
                }
            }

            val contactsSize = contacts.size()
            val showOnlyContactsWithNumbers = context.config.showOnlyContactsWithNumbers
            val tempContacts = ArrayList<Contact>(contactsSize)
            val resultContacts = ArrayList<Contact>(contactsSize)

            (0 until contactsSize).filter {
                if (ignoredContactSources.isEmpty() && showOnlyContactsWithNumbers) {
                    contacts.valueAt(it).phoneNumbers.isNotEmpty()
                } else {
                    true
                }
            }.mapTo(tempContacts) {
                contacts.valueAt(it)
            }

            if (ignoredContactSources.isEmpty() && !getAll) {
                tempContacts.filter { displayContactSources.contains(it.source) }.groupBy { it.getNameToDisplay().toLowerCase() }.values.forEach { it ->
                    if (it.size == 1) {
                        resultContacts.add(it.first())
                    } else {
                        val sorted = it.sortedByDescending { it.getStringToCompare().length }
                        resultContacts.add(sorted.first())
                    }
                }
            } else {
                resultContacts.addAll(tempContacts)
            }

            // groups are obtained with contactID, not rawID, so assign them to proper contacts like this
            val groups = getContactGroups(getStoredGroupsSync())
            val size = groups.size()
            for (i in 0 until size) {
                val key = groups.keyAt(i)
                resultContacts.firstOrNull { it.contactId == key }?.groups = groups.valueAt(i)
            }

            Contact.sorting = context.config.sorting
            Contact.startWithSurname = context.config.startNameWithSurname
            resultContacts.sort()

            Handler(Looper.getMainLooper()).post {
                callback(resultContacts)
            }
        }
    }

    private fun getContentResolverAccounts(): HashSet<ContactSource> {
        val sources = HashSet<ContactSource>()
        arrayOf(Groups.CONTENT_URI, Settings.CONTENT_URI, RawContacts.CONTENT_URI).forEach {
            fillSourcesFromUri(it, sources)
        }

        return sources
    }

    private fun fillSourcesFromUri(uri: Uri, sources: HashSet<ContactSource>) {
        val projection = arrayOf(
                RawContacts.ACCOUNT_NAME,
                RawContacts.ACCOUNT_TYPE
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val name = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                    val type = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
                    var publicName = name
                    if (type == TELEGRAM_PACKAGE) {
                        publicName += " (${context.getString(R.string.telegram)})"
                    }

                    val source = ContactSource(name, type, publicName)
                    sources.add(source)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
        } finally {
            cursor?.close()
        }
    }

    private fun getDeviceContacts(contacts: SparseArray<Contact>, ignoredContactSources: HashSet<String>?) {
        if (!context.hasContactPermissions()) {
            return
        }

        val ignoredSources = ignoredContactSources ?: context.config.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        val selection = "${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        val sortOrder = getSortString()

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            if (cursor?.moveToFirst() == true) {
                do {
                    val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                    val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
                    if (ignoredSources.contains("$accountName:$accountType")) {
                        continue
                    }

                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    var prefix = ""
                    var firstName = ""
                    var middleName = ""
                    var surname = ""
                    var suffix = ""

                    // ignore names at Organization type contacts
                    if (cursor.getStringValue(Data.MIMETYPE) == StructuredName.CONTENT_ITEM_TYPE) {
                        prefix = cursor.getStringValue(StructuredName.PREFIX) ?: ""
                        firstName = cursor.getStringValue(StructuredName.GIVEN_NAME) ?: ""
                        middleName = cursor.getStringValue(StructuredName.MIDDLE_NAME) ?: ""
                        surname = cursor.getStringValue(StructuredName.FAMILY_NAME) ?: ""
                        suffix = cursor.getStringValue(StructuredName.SUFFIX) ?: ""
                    }

                    val nickname = ""
                    val photoUri = cursor.getStringValue(StructuredName.PHOTO_URI) ?: ""
                    val numbers = ArrayList<PhoneNumber>()          // proper value is obtained below
                    val emails = ArrayList<Email>()
                    val addresses = ArrayList<Address>()
                    val events = ArrayList<Event>()
                    val starred = cursor.getIntValue(StructuredName.STARRED)
                    val contactId = cursor.getIntValue(Data.CONTACT_ID)
                    val thumbnailUri = cursor.getStringValue(StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                    val notes = ""
                    val groups = ArrayList<Group>()
                    val organization = Organization("", "")
                    val websites = ArrayList<String>()
                    val ims = ArrayList<IM>()
                    val contact = Contact(id, prefix, firstName, middleName, surname, suffix, nickname, photoUri, numbers, emails, addresses,
                            events, accountName, starred, contactId, thumbnailUri, null, notes, groups, organization, websites, ims)

                    contacts.put(id, contact)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        val phoneNumbers = getPhoneNumbers(null)
        var size = phoneNumbers.size()
        for (i in 0 until size) {
            val key = phoneNumbers.keyAt(i)
            if (contacts[key] != null) {
                val numbers = phoneNumbers.valueAt(i)
                contacts[key].phoneNumbers = numbers
            }
        }

        val nicknames = getNicknames()
        size = nicknames.size()
        for (i in 0 until size) {
            val key = nicknames.keyAt(i)
            contacts[key]?.nickname = nicknames.valueAt(i)
        }

        val emails = getEmails()
        size = emails.size()
        for (i in 0 until size) {
            val key = emails.keyAt(i)
            contacts[key]?.emails = emails.valueAt(i)
        }

        val addresses = getAddresses()
        size = addresses.size()
        for (i in 0 until size) {
            val key = addresses.keyAt(i)
            contacts[key]?.addresses = addresses.valueAt(i)
        }

        val IMs = getIMs()
        size = IMs.size()
        for (i in 0 until size) {
            val key = IMs.keyAt(i)
            contacts[key]?.IMs = IMs.valueAt(i)
        }

        val events = getEvents()
        size = events.size()
        for (i in 0 until size) {
            val key = events.keyAt(i)
            contacts[key]?.events = events.valueAt(i)
        }

        val notes = getNotes()
        size = notes.size()
        for (i in 0 until size) {
            val key = notes.keyAt(i)
            contacts[key]?.notes = notes.valueAt(i)
        }

        val organizations = getOrganizations()
        size = organizations.size()
        for (i in 0 until size) {
            val key = organizations.keyAt(i)
            contacts[key]?.organization = organizations.valueAt(i)
        }

        val websites = getWebsites()
        size = websites.size()
        for (i in 0 until size) {
            val key = websites.keyAt(i)
            contacts[key]?.websites = websites.valueAt(i)
        }
    }

    private fun getPhoneNumbers(contactId: Int? = null): SparseArray<ArrayList<PhoneNumber>> {
        val phoneNumbers = SparseArray<ArrayList<PhoneNumber>>()
        val uri = Phone.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                Phone.NUMBER,
                Phone.NORMALIZED_NUMBER,
                Phone.TYPE,
                Phone.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val number = cursor.getStringValue(Phone.NUMBER) ?: continue
                    val normalizedNumber = cursor.getStringValue(Phone.NORMALIZED_NUMBER) ?: number.normalizeNumber()
                    val type = cursor.getIntValue(Phone.TYPE)
                    val label = cursor.getStringValue(Phone.LABEL) ?: ""

                    if (phoneNumbers[id] == null) {
                        phoneNumbers.put(id, ArrayList())
                    }

                    val phoneNumber = PhoneNumber(number, type, label, normalizedNumber)
                    phoneNumbers[id].add(phoneNumber)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return phoneNumbers
    }

    private fun getNicknames(contactId: Int? = null): SparseArray<String> {
        val nicknames = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                Nickname.NAME
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(Nickname.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val nickname = cursor.getStringValue(Nickname.NAME) ?: continue
                    nicknames.put(id, nickname)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return nicknames
    }

    private fun getEmails(contactId: Int? = null): SparseArray<ArrayList<Email>> {
        val emails = SparseArray<ArrayList<Email>>()
        val uri = CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                CommonDataKinds.Email.DATA,
                CommonDataKinds.Email.TYPE,
                CommonDataKinds.Email.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val email = cursor.getStringValue(CommonDataKinds.Email.DATA) ?: continue
                    val type = cursor.getIntValue(CommonDataKinds.Email.TYPE)
                    val label = cursor.getStringValue(CommonDataKinds.Email.LABEL) ?: ""

                    if (emails[id] == null) {
                        emails.put(id, ArrayList())
                    }

                    emails[id]!!.add(Email(email, type, label))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return emails
    }

    private fun getAddresses(contactId: Int? = null): SparseArray<ArrayList<Address>> {
        val addresses = SparseArray<ArrayList<Address>>()
        val uri = StructuredPostal.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                StructuredPostal.FORMATTED_ADDRESS,
                StructuredPostal.TYPE,
                StructuredPostal.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val address = cursor.getStringValue(StructuredPostal.FORMATTED_ADDRESS) ?: continue
                    val type = cursor.getIntValue(StructuredPostal.TYPE)
                    val label = cursor.getStringValue(StructuredPostal.LABEL) ?: ""

                    if (addresses[id] == null) {
                        addresses.put(id, ArrayList())
                    }

                    addresses[id]!!.add(Address(address, type, label))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return addresses
    }

    private fun getIMs(contactId: Int? = null): SparseArray<ArrayList<IM>> {
        val IMs = SparseArray<ArrayList<IM>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                Im.DATA,
                Im.PROTOCOL,
                Im.CUSTOM_PROTOCOL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(Im.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val IM = cursor.getStringValue(Im.DATA) ?: continue
                    val type = cursor.getIntValue(Im.PROTOCOL)
                    val label = cursor.getStringValue(Im.CUSTOM_PROTOCOL) ?: ""

                    if (IMs[id] == null) {
                        IMs.put(id, ArrayList())
                    }

                    IMs[id]!!.add(IM(IM, type, label))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return IMs
    }

    private fun getEvents(contactId: Int? = null): SparseArray<ArrayList<Event>> {
        val events = SparseArray<ArrayList<Event>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                CommonDataKinds.Event.START_DATE,
                CommonDataKinds.Event.TYPE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Event.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val startDate = cursor.getStringValue(CommonDataKinds.Event.START_DATE) ?: continue
                    val type = cursor.getIntValue(CommonDataKinds.Event.TYPE)

                    if (events[id] == null) {
                        events.put(id, ArrayList())
                    }

                    events[id]!!.add(Event(startDate, type))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return events
    }

    private fun getNotes(contactId: Int? = null): SparseArray<String> {
        val notes = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                Note.NOTE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(Note.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val note = cursor.getStringValue(Note.NOTE) ?: continue
                    notes.put(id, note)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return notes
    }

    private fun getOrganizations(contactId: Int? = null): SparseArray<Organization> {
        val organizations = SparseArray<Organization>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                CommonDataKinds.Organization.COMPANY,
                CommonDataKinds.Organization.TITLE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val company = cursor.getStringValue(CommonDataKinds.Organization.COMPANY) ?: ""
                    val title = cursor.getStringValue(CommonDataKinds.Organization.TITLE) ?: ""
                    if (company.isEmpty() && title.isEmpty()) {
                        continue
                    }

                    val organization = Organization(company, title)
                    organizations.put(id, organization)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return organizations
    }

    private fun getWebsites(contactId: Int? = null): SparseArray<ArrayList<String>> {
        val websites = SparseArray<ArrayList<String>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.RAW_CONTACT_ID,
                Website.URL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(Website.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val url = cursor.getStringValue(Website.URL) ?: continue

                    if (websites[id] == null) {
                        websites.put(id, ArrayList())
                    }

                    websites[id]!!.add(url)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return websites
    }

    private fun getContactGroups(storedGroups: ArrayList<Group>, contactId: Int? = null): SparseArray<ArrayList<Group>> {
        val groups = SparseArray<ArrayList<Group>>()
        if (!context.hasContactPermissions()) {
            return groups
        }

        val uri = Data.CONTENT_URI
        val projection = arrayOf(
                Data.CONTACT_ID,
                Data.DATA1
        )

        val selection = getSourcesSelection(true, contactId != null, false)
        val selectionArgs = getSourcesSelectionArgs(GroupMembership.CONTENT_ITEM_TYPE, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getIntValue(Data.CONTACT_ID)
                    val newRowId = cursor.getLongValue(Data.DATA1)

                    val groupTitle = storedGroups.firstOrNull { it.id == newRowId }?.title ?: continue
                    val group = Group(newRowId, groupTitle)
                    if (groups[id] == null) {
                        groups.put(id, ArrayList())
                    }
                    groups[id]!!.add(group)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        return groups
    }

    private fun getQuestionMarks() = "?,".times(displayContactSources.filter { it.isNotEmpty() }.size).trimEnd(',')

    private fun getSourcesSelection(addMimeType: Boolean = false, addContactId: Boolean = false, useRawContactId: Boolean = true): String {
        val strings = ArrayList<String>()
        if (addMimeType) {
            strings.add("${Data.MIMETYPE} = ?")
        }

        if (addContactId) {
            strings.add("${if (useRawContactId) Data.RAW_CONTACT_ID else Data.CONTACT_ID} = ?")
        } else {
            // sometimes local device storage has null account_name, handle it properly
            val accountnameString = StringBuilder()
            if (displayContactSources.contains("")) {
                accountnameString.append("(")
            }
            accountnameString.append("${RawContacts.ACCOUNT_NAME} IN (${getQuestionMarks()})")
            if (displayContactSources.contains("")) {
                accountnameString.append(" OR ${RawContacts.ACCOUNT_NAME} IS NULL)")
            }
            strings.add(accountnameString.toString())
        }

        return TextUtils.join(" AND ", strings)
    }

    private fun getSourcesSelectionArgs(mimetype: String? = null, contactId: Int? = null): Array<String> {
        val args = ArrayList<String>()

        if (mimetype != null) {
            args.add(mimetype)
        }

        if (contactId != null) {
            args.add(contactId.toString())
        } else {
            args.addAll(displayContactSources.filter { it.isNotEmpty() })
        }

        return args.toTypedArray()
    }

    fun getStoredGroups(callback: (ArrayList<Group>) -> Unit) {
        ensureBackgroundThread {
            val groups = getStoredGroupsSync()
            Handler(Looper.getMainLooper()).post {
                callback(groups)
            }
        }
    }

    fun getStoredGroupsSync(): ArrayList<Group> {
        val groups = getDeviceStoredGroups()
        groups.addAll(context.groupsDB.getGroups())
        return groups
    }

    private fun getDeviceStoredGroups(): ArrayList<Group> {
        val groups = ArrayList<Group>()
        if (!context.hasContactPermissions()) {
            return groups
        }

        val uri = Groups.CONTENT_URI
        val projection = arrayOf(
                Groups._ID,
                Groups.TITLE,
                Groups.SYSTEM_ID
        )

        val selection = "${Groups.AUTO_ADD} = ? AND ${Groups.FAVORITES} = ?"
        val selectionArgs = arrayOf("0", "0")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    val id = cursor.getLongValue(Groups._ID)
                    val title = cursor.getStringValue(Groups.TITLE) ?: continue

                    val systemId = cursor.getStringValue(Groups.SYSTEM_ID)
                    if (groups.map { it.title }.contains(title) && systemId != null) {
                        continue
                    }

                    groups.add(Group(id, title))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            context.showErrorToast(e)
        } finally {
            cursor?.close()
        }
        return groups
    }

    fun createNewGroup(title: String, accountName: String, accountType: String): Group? {
        if (accountType == SMT_PRIVATE) {
            val newGroup = Group(null, title)
            val id = context.groupsDB.insertOrUpdate(newGroup)
            newGroup.id = id
            return newGroup
        }

        val operations = ArrayList<ContentProviderOperation>()
        ContentProviderOperation.newInsert(Groups.CONTENT_URI).apply {
            withValue(Groups.TITLE, title)
            withValue(Groups.GROUP_VISIBLE, 1)
            withValue(Groups.ACCOUNT_NAME, accountName)
            withValue(Groups.ACCOUNT_TYPE, accountType)
            operations.add(build())
        }

        try {
            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawId = ContentUris.parseId(results[0].uri)
            return Group(rawId, title)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
        return null
    }

    fun renameGroup(group: Group) {
        val operations = ArrayList<ContentProviderOperation>()
        ContentProviderOperation.newUpdate(Groups.CONTENT_URI).apply {
            val selection = "${Groups._ID} = ?"
            val selectionArgs = arrayOf(group.id.toString())
            withSelection(selection, selectionArgs)
            withValue(Groups.TITLE, group.title)
            operations.add(build())
        }

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun deleteGroup(id: Long) {
        val operations = ArrayList<ContentProviderOperation>()
        val uri = ContentUris.withAppendedId(Groups.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
                .build()

        operations.add(ContentProviderOperation.newDelete(uri).build())

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun getContactWithId(id: Int, isLocalPrivate: Boolean): Contact? {
        if (id == 0) {
            return null
        } else if (isLocalPrivate) {
            return LocalContactsHelper(context).getContactWithId(id)
        }

        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, id.toString())
        return parseContactCursor(selection, selectionArgs)
    }

    fun getContactWithLookupKey(key: String): Contact? {
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.LOOKUP_KEY} = ?"
        val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, key)
        return parseContactCursor(selection, selectionArgs)
    }

    private fun parseContactCursor(selection: String, selectionArgs: Array<String>): Contact? {
        val storedGroups = getStoredGroupsSync()
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)

                var prefix = ""
                var firstName = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (cursor.getStringValue(Data.MIMETYPE) == StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(StructuredName.PREFIX) ?: ""
                    firstName = cursor.getStringValue(StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(StructuredName.SUFFIX) ?: ""
                }

                val nickname = getNicknames(id)[id] ?: ""
                val photoUri = cursor.getStringValue(Phone.PHOTO_URI) ?: ""
                val number = getPhoneNumbers(id)[id] ?: ArrayList()
                val emails = getEmails(id)[id] ?: ArrayList()
                val addresses = getAddresses(id)[id] ?: ArrayList()
                val events = getEvents(id)[id] ?: ArrayList()
                val notes = getNotes(id)[id] ?: ""
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val starred = cursor.getIntValue(StructuredName.STARRED)
                val contactId = cursor.getIntValue(Data.CONTACT_ID)
                val groups = getContactGroups(storedGroups, contactId)[contactId] ?: ArrayList()
                val thumbnailUri = cursor.getStringValue(StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                val organization = getOrganizations(id)[id] ?: Organization("", "")
                val websites = getWebsites(id)[id] ?: ArrayList()
                val ims = getIMs(id)[id] ?: ArrayList()
                return Contact(id, prefix, firstName, middleName, surname, suffix, nickname, photoUri, number, emails, addresses, events,
                        accountName, starred, contactId, thumbnailUri, null, notes, groups, organization, websites, ims)
            }
        } finally {
            cursor?.close()
        }

        return null
    }

    fun getContactSources(callback: (ArrayList<ContactSource>) -> Unit) {
        ensureBackgroundThread {
            callback(getContactSourcesSync())
        }
    }

    private fun getContactSourcesSync(): ArrayList<ContactSource> {
        val sources = getDeviceContactSources()
        sources.add(context.getPrivateContactSource())
        return ArrayList(sources)
    }

    fun getDeviceContactSources(): LinkedHashSet<ContactSource> {
        val sources = LinkedHashSet<ContactSource>()
        if (!context.hasContactPermissions()) {
            return sources
        }

        val accounts = AccountManager.get(context).accounts
        accounts.forEach {
            if (ContentResolver.getIsSyncable(it, AUTHORITY) == 1) {
                var publicName = it.name
                if (it.type == TELEGRAM_PACKAGE) {
                    publicName += " (${context.getString(R.string.telegram)})"
                }
                val contactSource = ContactSource(it.name, it.type, publicName)
                sources.add(contactSource)
            }
        }

        val contentResolverAccounts = getContentResolverAccounts().filter {
            it.name.isNotEmpty() && it.type.isNotEmpty() && !accounts.contains(Account(it.name, it.type))
        }
        sources.addAll(contentResolverAccounts)

        return sources
    }

    private fun getContactSourceType(accountName: String) = getDeviceContactSources().firstOrNull { it.name == accountName }?.type ?: ""

    private fun getContactProjection() = arrayOf(
            Data.MIMETYPE,
            Data.CONTACT_ID,
            Data.RAW_CONTACT_ID,
            StructuredName.PREFIX,
            StructuredName.GIVEN_NAME,
            StructuredName.MIDDLE_NAME,
            StructuredName.FAMILY_NAME,
            StructuredName.SUFFIX,
            StructuredName.PHOTO_URI,
            StructuredName.PHOTO_THUMBNAIL_URI,
            StructuredName.STARRED,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
    )

    private fun getSortString(): String {
        val sorting = context.config.sorting
        return when {
            sorting and SORT_BY_FIRST_NAME != 0 -> "${StructuredName.GIVEN_NAME} COLLATE NOCASE"
            sorting and SORT_BY_MIDDLE_NAME != 0 -> "${StructuredName.MIDDLE_NAME} COLLATE NOCASE"
            sorting and SORT_BY_SURNAME != 0 -> "${StructuredName.FAMILY_NAME} COLLATE NOCASE"
            else -> Phone.NUMBER
        }
    }

    private fun getRealContactId(id: Long): Int {
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, id.toString())
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                return cursor.getIntValue(Data.CONTACT_ID)
            }
        } finally {
            cursor?.close()
        }

        return 0
    }

    fun updateContact(contact: Contact, photoUpdateStatus: Int): Boolean {
        context.toast(R.string.updating)
        if (contact.isPrivate()) {
            return LocalContactsHelper(context).insertOrUpdateContact(contact)
        }

        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newUpdate(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND (${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?)"
                val selectionArgs = arrayOf(contact.id.toString(), StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                withValue(StructuredName.PREFIX, contact.prefix)
                withValue(StructuredName.GIVEN_NAME, contact.firstName)
                withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                withValue(StructuredName.FAMILY_NAME, contact.surname)
                withValue(StructuredName.SUFFIX, contact.suffix)
                operations.add(build())
            }

            // delete nickname
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), Nickname.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add nickname
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                withValue(Nickname.NAME, contact.nickname)
                operations.add(build())
            }

            // delete phone numbers
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), Phone.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add phone numbers
            contact.phoneNumbers.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    withValue(Phone.NUMBER, it.value)
                    withValue(Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                    withValue(Phone.TYPE, it.type)
                    withValue(Phone.LABEL, it.label)
                    operations.add(build())
                }
            }

            // delete emails
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add emails
            contact.emails.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Email.DATA, it.value)
                    withValue(CommonDataKinds.Email.TYPE, it.type)
                    withValue(CommonDataKinds.Email.LABEL, it.label)
                    operations.add(build())
                }
            }

            // delete addresses
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), StructuredPostal.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add addresses
            contact.addresses.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                    withValue(StructuredPostal.FORMATTED_ADDRESS, it.value)
                    withValue(StructuredPostal.TYPE, it.type)
                    withValue(StructuredPostal.LABEL, it.label)
                    operations.add(build())
                }
            }

            // delete IMs
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), Im.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add IMs
            contact.IMs.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                    withValue(Im.DATA, it.value)
                    withValue(Im.PROTOCOL, it.type)
                    withValue(Im.CUSTOM_PROTOCOL, it.label)
                    operations.add(build())
                }
            }

            // delete events
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add events
            contact.events.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, it.type)
                    operations.add(build())
                }
            }

            // delete notes
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), Note.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add notes
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                withValue(Note.NOTE, contact.notes)
                operations.add(build())
            }

            // delete organization
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add organization
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
                withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
                withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                operations.add(build())
            }

            // delete websites
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), Website.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add websites
            contact.websites.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                    withValue(Website.URL, it)
                    withValue(Website.TYPE, DEFAULT_WEBSITE_TYPE)
                    operations.add(build())
                }
            }

            // delete groups
            val relevantGroupIDs = getStoredGroupsSync().map { it.id }
            if (relevantGroupIDs.isNotEmpty()) {
                val IDsString = TextUtils.join(",", relevantGroupIDs)
                ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                    val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} IN ($IDsString)"
                    val selectionArgs = arrayOf(contact.contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE)
                    withSelection(selection, selectionArgs)
                    operations.add(build())
                }
            }

            // add groups
            contact.groups.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    withValue(GroupMembership.GROUP_ROW_ID, it.id)
                    operations.add(build())
                }
            }

            // favorite
            try {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, contact.contactId.toString())
                val contentValues = ContentValues(1)
                contentValues.put(Contacts.STARRED, contact.starred)
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (e: Exception) {
                context.showErrorToast(e)
            }

            // photo
            when (photoUpdateStatus) {
                PHOTO_ADDED, PHOTO_CHANGED -> addPhoto(contact, operations)
                PHOTO_REMOVED -> removePhoto(contact, operations)
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            return false
        }
    }

    private fun addPhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        if (contact.photoUri.isNotEmpty()) {
            val photoUri = Uri.parse(contact.photoUri)
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, photoUri)

            val thumbnailSize = context.getPhotoThumbnailSize()
            val scaledPhoto = Bitmap.createScaledBitmap(bitmap, thumbnailSize, thumbnailSize, false)
            val scaledSizePhotoData = scaledPhoto.getByteArray()
            scaledPhoto.recycle()

            val fullSizePhotoData = bitmap.getByteArray()
            bitmap.recycle()

            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                withValue(Photo.PHOTO, scaledSizePhotoData)
                operations.add(build())
            }

            addFullSizePhoto(contact.id.toLong(), fullSizePhotoData)
        }
        return operations
    }

    private fun removePhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), Photo.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        return operations
    }

    fun addContactsToGroup(contacts: ArrayList<Contact>, groupId: Long) {
        val operations = ArrayList<ContentProviderOperation>()
        contacts.forEach {
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, it.id)
                withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                withValue(GroupMembership.GROUP_ROW_ID, groupId)
                operations.add(build())
            }

            if (operations.size % BATCH_SIZE == 0) {
                context.contentResolver.applyBatch(AUTHORITY, operations)
                operations.clear()
            }
        }

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun removeContactsFromGroup(contacts: ArrayList<Contact>, groupId: Long) {
        val operations = ArrayList<ContentProviderOperation>()
        contacts.forEach {
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} = ?"
                val selectionArgs = arrayOf(it.contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            if (operations.size % BATCH_SIZE == 0) {
                context.contentResolver.applyBatch(AUTHORITY, operations)
                operations.clear()
            }
        }
        context.contentResolver.applyBatch(AUTHORITY, operations)
    }

    fun insertContact(contact: Contact): Boolean {
        if (contact.isPrivate()) {
            return LocalContactsHelper(context).insertOrUpdateContact(contact)
        }

        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                withValue(RawContacts.ACCOUNT_NAME, contact.source)
                withValue(RawContacts.ACCOUNT_TYPE, getContactSourceType(contact.source))
                operations.add(build())
            }

            // names
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                withValue(StructuredName.PREFIX, contact.prefix)
                withValue(StructuredName.GIVEN_NAME, contact.firstName)
                withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                withValue(StructuredName.FAMILY_NAME, contact.surname)
                withValue(StructuredName.SUFFIX, contact.suffix)
                operations.add(build())
            }

            // nickname
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                withValue(Nickname.NAME, contact.nickname)
                operations.add(build())
            }

            // phone numbers
            contact.phoneNumbers.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    withValue(Phone.NUMBER, it.value)
                    withValue(Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                    withValue(Phone.TYPE, it.type)
                    withValue(Phone.LABEL, it.label)
                    operations.add(build())
                }
            }

            // emails
            contact.emails.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Email.DATA, it.value)
                    withValue(CommonDataKinds.Email.TYPE, it.type)
                    withValue(CommonDataKinds.Email.LABEL, it.label)
                    operations.add(build())
                }
            }

            // addresses
            contact.addresses.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                    withValue(StructuredPostal.FORMATTED_ADDRESS, it.value)
                    withValue(StructuredPostal.TYPE, it.type)
                    withValue(StructuredPostal.LABEL, it.label)
                    operations.add(build())
                }
            }

            // IMs
            contact.IMs.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                    withValue(Im.DATA, it.value)
                    withValue(Im.PROTOCOL, it.type)
                    withValue(Im.CUSTOM_PROTOCOL, it.label)
                    operations.add(build())
                }
            }

            // events
            contact.events.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, it.type)
                    operations.add(build())
                }
            }

            // notes
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                withValue(Note.NOTE, contact.notes)
                operations.add(build())
            }

            // organization
            if (contact.organization.isNotEmpty()) {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
                    withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                    withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
                    withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                    operations.add(build())
                }
            }

            // websites
            contact.websites.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                    withValue(Website.URL, it)
                    withValue(Website.TYPE, DEFAULT_WEBSITE_TYPE)
                    operations.add(build())
                }
            }

            // groups
            contact.groups.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    withValue(GroupMembership.GROUP_ROW_ID, it.id)
                    operations.add(build())
                }
            }

            // photo (inspired by https://gist.github.com/slightfoot/5985900)
            var fullSizePhotoData: ByteArray? = null
            var scaledSizePhotoData: ByteArray?
            if (contact.photoUri.isNotEmpty()) {
                val photoUri = Uri.parse(contact.photoUri)
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, photoUri)

                val thumbnailSize = context.getPhotoThumbnailSize()
                val scaledPhoto = Bitmap.createScaledBitmap(bitmap, thumbnailSize, thumbnailSize, false)
                scaledSizePhotoData = scaledPhoto.getByteArray()

                fullSizePhotoData = bitmap.getByteArray()
                scaledPhoto.recycle()
                bitmap.recycle()

                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    withValue(Photo.PHOTO, scaledSizePhotoData)
                    operations.add(build())
                }
            }

            val results: Array<ContentProviderResult>
            try {
                results = context.contentResolver.applyBatch(AUTHORITY, operations)
            } finally {
                scaledSizePhotoData = null
            }

            // fullsize photo
            val rawId = ContentUris.parseId(results[0].uri)
            if (contact.photoUri.isNotEmpty() && fullSizePhotoData != null) {
                addFullSizePhoto(rawId, fullSizePhotoData)
            }

            // favorite
            val userId = getRealContactId(rawId)
            if (userId != 0 && contact.starred == 1) {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, userId.toString())
                val contentValues = ContentValues(1)
                contentValues.put(Contacts.STARRED, contact.starred)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            return false
        }
    }

    private fun addFullSizePhoto(contactId: Long, fullSizePhotoData: ByteArray) {
        val baseUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, contactId)
        val displayPhotoUri = Uri.withAppendedPath(baseUri, RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        val fileDescriptor = context.contentResolver.openAssetFileDescriptor(displayPhotoUri, "rw")
        val photoStream = fileDescriptor!!.createOutputStream()
        photoStream.write(fullSizePhotoData)
        photoStream.close()
        fileDescriptor.close()
    }

    fun getContactLookupKey(contactId: String): String {
        val uri = Data.CONTENT_URI
        val projection = arrayOf(Data.CONTACT_ID, Data.LOOKUP_KEY)
        val selection = "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, contactId)
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                val id = cursor.getIntValue(Data.CONTACT_ID)
                val lookupKey = cursor.getStringValue(Data.LOOKUP_KEY)
                return "$lookupKey/$id"
            }
        } finally {
            cursor?.close()
        }
        return ""
    }

    fun getContactMimeTypeId(contactId: String, mimeType: String): String {
        val uri = Data.CONTENT_URI
        val projection = arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE)
        val selection = "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(mimeType, contactId)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                return cursor.getStringValue(Data._ID)
            }
        } finally {
            cursor?.close()
        }
        return ""
    }

    fun addFavorites(contacts: ArrayList<Contact>) {
        ensureBackgroundThread {
            toggleLocalFavorites(contacts, true)
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, true)
            }
        }
    }

    fun removeFavorites(contacts: ArrayList<Contact>) {
        ensureBackgroundThread {
            toggleLocalFavorites(contacts, false)
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, false)
            }
        }
    }

    private fun toggleFavorites(contacts: ArrayList<Contact>, addToFavorites: Boolean) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.filter { !it.isPrivate() }.map { it.contactId.toString() }.forEach {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, it)
                ContentProviderOperation.newUpdate(uri).apply {
                    withValue(Contacts.STARRED, if (addToFavorites) 1 else 0)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    private fun toggleLocalFavorites(contacts: ArrayList<Contact>, addToFavorites: Boolean) {
        val localContacts = contacts.filter { it.isPrivate() }.map { it.id }.toTypedArray()
        LocalContactsHelper(context).toggleFavorites(localContacts, addToFavorites)
    }

    fun deleteContact(originalContact: Contact, deleteClones: Boolean = false, callback: (success: Boolean) -> Unit) {
        ensureBackgroundThread {
            if (deleteClones) {
                getDuplicatesOfContact(originalContact, true) { contacts ->
                    ensureBackgroundThread {
                        if (deleteContacts(contacts)) {
                            callback(true)
                        }
                    }
                }
            } else {
                if (deleteContacts(arrayListOf(originalContact))) {
                    callback(true)
                }
            }
        }
    }

    fun deleteContacts(contacts: ArrayList<Contact>): Boolean {
        val localContacts = contacts.filter { it.isPrivate() }.map { it.id.toLong() }.toMutableList()
        LocalContactsHelper(context).deleteContactIds(localContacts)

        return try {
            val operations = ArrayList<ContentProviderOperation>()
            val selection = "${RawContacts._ID} = ?"
            contacts.filter { !it.isPrivate() }.forEach {
                ContentProviderOperation.newDelete(RawContacts.CONTENT_URI).apply {
                    val selectionArgs = arrayOf(it.id.toString())
                    withSelection(selection, selectionArgs)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }

            if (context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
                context.contentResolver.applyBatch(AUTHORITY, operations)
            }
            true
        } catch (e: Exception) {
            context.showErrorToast(e)
            false
        }
    }

    fun getDuplicatesOfContact(contact: Contact, addOriginal: Boolean, callback: (ArrayList<Contact>) -> Unit) {
        ensureBackgroundThread {
            getContacts(true) { contacts ->
                val duplicates = contacts.filter { it.id != contact.id && it.getHashToCompare() == contact.getHashToCompare() }.toMutableList() as ArrayList<Contact>
                if (addOriginal) {
                    duplicates.add(contact)
                }
                callback(duplicates)
            }
        }
    }
}
