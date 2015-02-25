/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading;

import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_CHANGE_FAVORITE_CHANGED;
import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_CHANGE_FLAGS;
import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_CHANGE_UNREAD_CHANGED;
import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_STATUS;
import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_STATUS_MODIFIED;
import static org.mozilla.gecko.db.BrowserContract.ReadingListItems.SYNC_STATUS_NEW;

import java.util.ArrayList;

import org.mozilla.gecko.db.BrowserContract;
import org.mozilla.gecko.db.BrowserContract.ReadingListItems;
import org.mozilla.gecko.sync.repositories.android.RepoUtils;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;

public class LocalReadingListStorage implements ReadingListStorage {

  final class LocalReadingListChangeAccumulator implements ReadingListChangeAccumulator {
    private final ArrayList<ClientReadingListRecord> changes;
    private final ArrayList<ClientReadingListRecord> deletions;

    LocalReadingListChangeAccumulator() {
      this.changes = new ArrayList<>();
      this.deletions = new ArrayList<>();
    }

    @Override
    public boolean flushDeletions() {
      if (deletions.isEmpty()) {
        return true;
      }

      long[] ids = new long[deletions.size()];
      String[] guids = new String[deletions.size()];
      int iID = 0;
      int iGUID = 0;
      for (ClientReadingListRecord record : deletions) {
        if (record.clientMetadata.id > -1L) {
          ids[iID++] = record.clientMetadata.id;
        } else {
          final String guid = record.getGUID();
          if (guid == null) {
            continue;
          }
          guids[iGUID++] = guid;
        }
      }

      try {
        if (iID > 0) {
          client.delete(URI_WITH_DELETED, RepoUtils.computeSQLLongInClause(ids, ReadingListItems._ID), null);
        }

        if (iGUID > 0) {
          client.delete(URI_WITH_DELETED, RepoUtils.computeSQLInClause(iGUID, ReadingListItems.GUID), guids);
        }
      } catch (RemoteException e) {
        // Not much we can do here.
        return false;
      }

      deletions.clear();
      return true;
    }

    public boolean flushRecordChanges() {
      if (changes.isEmpty()) {
        return true;
      }

      // For each returned record, apply it to the local store and clear all sync flags.
      // We can do this because the server always returns the entire record.
      //
      // <https://github.com/mozilla-services/readinglist/issues/138> tracks not doing so
      // for certain patches, which allows us to optimize here.
      ArrayList<ContentProviderOperation> operations = new ArrayList<>(changes.size());
      for (ClientReadingListRecord rec : changes) {
        operations.add(makeOp(rec));
      }
      return true;
    }

    private ContentProviderOperation makeOp(ClientReadingListRecord rec) {
      final String selection;
      final String[] selectionArgs;

      if (rec.clientMetadata.id > -1L) {
        selection = ReadingListItems._ID + " = " + rec.clientMetadata.id;
        selectionArgs = null;
      } else if (rec.serverMetadata.guid != null) {
        selection = ReadingListItems.GUID + " ? ";
        selectionArgs = new String[] { rec.serverMetadata.guid };
      } else {
        final String url = rec.fields.getString("url");
        final String resolvedURL = rec.fields.getString("resolved_url");

        if (url == null && resolvedURL == null) {
          // We're outta luck.
          return null;
        }

        selection = "(" + ReadingListItems.URL + " = ?) OR (" + ReadingListItems.RESOLVED_URL + " = ?)";
        if (url != null && resolvedURL != null) {
          selectionArgs = new String[] { url, resolvedURL };
        } else {
          final String arg = url == null ? resolvedURL : url;
          selectionArgs = new String[] { arg, arg };
        }
      }

      final ContentValues values = ReadingListClientContentValuesFactory.fromClientRecord(rec);
      return ContentProviderOperation.newUpdate(URI_WITHOUT_DELETED)
                                     .withSelection(selection, selectionArgs)
                                     .withValues(values)
                                     .build();
    }

    @Override
    public void finish() {
      flushDeletions();
      flushRecordChanges();
    }

    @Override
    public void addDeletion(ClientReadingListRecord record) {
      deletions.add(record);
    }

    @Override
    public void addChangedRecord(ClientReadingListRecord record) {
      changes.add(record);
    }

    @Override
    public void addUploadedRecord(ClientReadingListRecord up,
                                  ServerReadingListRecord down) {
      // TODO
    }
  }

  private final ContentProviderClient client;
  private final Uri URI_WITHOUT_DELETED = BrowserContract.READING_LIST_AUTHORITY_URI
      .buildUpon()
      .appendPath("items")
      .appendQueryParameter(BrowserContract.PARAM_IS_SYNC, "1")
      .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "0")
      .build();

  private final Uri URI_WITH_DELETED = BrowserContract.READING_LIST_AUTHORITY_URI
      .buildUpon()
      .appendPath("items")
      .appendQueryParameter(BrowserContract.PARAM_IS_SYNC, "1")
      .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "1")
      .build();

  public LocalReadingListStorage(final ContentProviderClient client) {
    this.client = client;
  }

  public Cursor getModifiedWithSelection(final String selection) {
    final String[] projection = new String[] {
      ReadingListItems.GUID,
      ReadingListItems.IS_FAVORITE,
      ReadingListItems.RESOLVED_TITLE,
      ReadingListItems.RESOLVED_URL,
      ReadingListItems.EXCERPT,
    };


    try {
      return client.query(URI_WITHOUT_DELETED, projection, selection, null, null);
    } catch (RemoteException e) {
      throw new IllegalStateException(e);
    }
  }
  @Override
  public Cursor getModified() {
    final String selection = ReadingListItems.SYNC_STATUS + " = " + ReadingListItems.SYNC_STATUS_MODIFIED;
    return getModifiedWithSelection(selection);
  }

  // Return changed items that aren't just status changes.
  // This isn't necessary because we insist on processing status changes before modified items.
  // Currently we only need this for tests...
  public Cursor getNonStatusModified() {
    final String selection = ReadingListItems.SYNC_STATUS + " = " + ReadingListItems.SYNC_STATUS_MODIFIED +
                             " AND ((" + ReadingListItems.SYNC_CHANGE_FLAGS + " & " + ReadingListItems.SYNC_CHANGE_RESOLVED + ") > 0)";

    return getModifiedWithSelection(selection);
  }

  // These will never conflict (in the case of unread status changes), or
  // we don't care if they overwrite the server value (in the case of favorite changes).
  // N.B., don't actually send each field if the appropriate change flag isn't set!
  @Override
  public Cursor getStatusChanges() {
    final String[] projection = new String[] {
      ReadingListItems.GUID,
      ReadingListItems.IS_FAVORITE,
      ReadingListItems.IS_UNREAD,
      ReadingListItems.MARKED_READ_BY,
      ReadingListItems.MARKED_READ_ON,
      ReadingListItems.SYNC_CHANGE_FLAGS,
    };

    final String selection =
        SYNC_STATUS + " = " + SYNC_STATUS_MODIFIED + " AND " +
        "((" + SYNC_CHANGE_FLAGS + " & (" + SYNC_CHANGE_UNREAD_CHANGED + " | " + SYNC_CHANGE_FAVORITE_CHANGED + ")) > 0)";

    try {
      return client.query(URI_WITHOUT_DELETED, projection, selection, null, null);
    } catch (RemoteException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Cursor getNew() {
    // N.B., query for items that have no GUID, regardless of status.
    // They should all be marked as NEW, but belt and braces.
    final String selection = "(" + SYNC_STATUS + " = " + SYNC_STATUS_NEW + ") OR (" + ReadingListItems.GUID + " IS NULL)";

    try {
      return client.query(URI_WITHOUT_DELETED, null, selection, null, null);
    } catch (RemoteException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public ReadingListChangeAccumulator getChangeAccumulator() {
    return new LocalReadingListChangeAccumulator();
  }

  /**
   * Unused: we implicitly do this when we apply the server record.
   */
  /*
  public void markStatusChangedItemsAsSynced(Collection<String> uploaded) {
    ContentValues values = new ContentValues();
    values.put(ReadingListItems.SYNC_CHANGE_FLAGS, ReadingListItems.SYNC_CHANGE_NONE);
    values.put(ReadingListItems.SYNC_STATUS, ReadingListItems.SYNC_STATUS_SYNCED);
    final String where = RepoUtils.computeSQLInClause(uploaded.size(), ReadingListItems.GUID);
    final String[] args = uploaded.toArray(new String[uploaded.size()]);
    try {
      client.update(URI_WITHOUT_DELETED, values, where, args);
    } catch (RemoteException e) {
      // Nothing we can do.
    }
  }
  */
}
