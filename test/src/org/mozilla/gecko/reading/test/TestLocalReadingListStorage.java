/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading.test;

import org.mozilla.gecko.background.helpers.AndroidSyncTestCase;
import org.mozilla.gecko.db.BrowserContract;
import org.mozilla.gecko.db.BrowserContract.ReadingListItems;
import org.mozilla.gecko.reading.ClientReadingListRecord;
import org.mozilla.gecko.reading.LocalReadingListStorage;
import org.mozilla.gecko.reading.ReadingListClientRecordFactory;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.repositories.domain.ClientRecordFactory;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;

// TODO: this class needs to make sure Gecko isn't running, else it'll try fetching the items!
public class TestLocalReadingListStorage extends AndroidSyncTestCase {
    private static final Uri CONTENT_URI = ReadingListItems.CONTENT_URI;
    private static final Uri CONTENT_URI_IS_SYNC = CONTENT_URI.buildUpon()
                                                              .appendQueryParameter(BrowserContract.PARAM_IS_SYNC, "1")
                                                              .build();

    private ContentProviderClient getClient() {
        final ContentResolver contentResolver = getApplicationContext().getContentResolver();
        final ContentProviderClient client = contentResolver.acquireContentProviderClient(ReadingListItems.CONTENT_URI);
        return client;
    }

    private ContentProviderClient getWipedClient() throws RemoteException {
        final ContentProviderClient client = getClient();
        client.delete(CONTENT_URI_IS_SYNC, null, null);
        assertTrue(0 == getCount(client));
        return client;
    }


    private int getCount(ContentProviderClient client) throws RemoteException {
        Cursor cursor = client.query(CONTENT_URI_IS_SYNC, null, null, null, null);
        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }

    // Closes cursor.
    private void assertCursorCount(int expected, Cursor cursor) {
        try {
            assertTrue(expected == cursor.getCount());
        } finally {
            cursor.close();
        }
    }

    private void assertIsEmpty(LocalReadingListStorage storage) throws Exception {
        Cursor modified = storage.getModified();
        try {
            assertTrue(0 == modified.getCount());
        } finally {
            modified.close();
        }
    }

    private static ContentValues getRecordA() {
        final ContentValues values = new ContentValues();
        values.put("url", "http://example.org/");
        values.put("title", "Example");
        values.put("content_status", ReadingListItems.STATUS_UNFETCHED);
        values.put(ReadingListItems.ADDED_ON, System.currentTimeMillis());
        values.put(ReadingListItems.ADDED_BY, "$local");
        return values;
    }

    // This is how ReadingListHelper does it.
    private long addRecordA(ContentProviderClient client) throws Exception {
        Uri inserted = client.insert(CONTENT_URI, getRecordA());
        assertNotNull(inserted);
        return ContentUris.parseId(inserted);
    }

    private long addRecordASynced(ContentProviderClient client) throws Exception {
        ContentValues values = getRecordA();
        values.put(ReadingListItems.SYNC_STATUS, ReadingListItems.SYNC_STATUS_SYNCED);
        values.put(ReadingListItems.SYNC_CHANGE_FLAGS, ReadingListItems.SYNC_CHANGE_NONE);
        values.put(ReadingListItems.GUID, "abcdefghi");
        Uri inserted = client.insert(CONTENT_URI_IS_SYNC, values);
        assertNotNull(inserted);
        return ContentUris.parseId(inserted);
    }

    public final void testGetModified() throws Exception {
        final ContentProviderClient client = getWipedClient();
        try {
            final LocalReadingListStorage storage = new LocalReadingListStorage(client);
            assertIsEmpty(storage);

            addRecordA(client);
            assertTrue(1 == getCount(client));
            assertCursorCount(0, storage.getModified());
            assertCursorCount(1, storage.getNew());
        } finally {
            client.release();
        }
    }

    public final void testGetStatusChanges() throws Exception {
        final ContentProviderClient client = getWipedClient();
        try {
            final LocalReadingListStorage storage = new LocalReadingListStorage(client);
            assertIsEmpty(storage);

            long id = addRecordASynced(client);
            assertTrue(1 == getCount(client));

            assertCursorCount(0, storage.getModified());
            assertCursorCount(0, storage.getNew());

            // Make a change.
            ContentValues v = new ContentValues();
            v.put(ReadingListItems.SYNC_CHANGE_FLAGS, ReadingListItems.SYNC_CHANGE_UNREAD_CHANGED);
            v.put(ReadingListItems.MARKED_READ_ON, System.currentTimeMillis());
            v.put(ReadingListItems.MARKED_READ_BY, "$this");        // TODO: test this substitution.
            v.put(ReadingListItems.IS_UNREAD, 0);
            assertEquals(1, client.update(CONTENT_URI, v, ReadingListItems._ID + " = " + id, null));
            assertCursorCount(0, storage.getNew());
            assertCursorCount(1, storage.getStatusChanges());
            assertCursorCount(0, storage.getNonStatusModified());
            assertCursorCount(1, storage.getModified());           // Modified includes status.
        } finally {
            client.release();
        }
    }

    public final void testGetNew() {
    }
}
