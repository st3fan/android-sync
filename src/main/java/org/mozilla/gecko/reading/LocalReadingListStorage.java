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
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;

public class LocalReadingListStorage implements ReadingListStorage {
  private final ContentProviderClient client;
  private final Uri URI_WITHOUT_DELETED = BrowserContract.READING_LIST_AUTHORITY_URI
      .buildUpon()
      .appendQueryParameter(BrowserContract.PARAM_IS_SYNC, "1")
      .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "0")
      .build();

  private final Uri URI_WITH_DELETED = BrowserContract.READING_LIST_AUTHORITY_URI
      .buildUpon()
      .appendQueryParameter(BrowserContract.PARAM_IS_SYNC, "1")
      .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "1")
      .build();

  public LocalReadingListStorage(final ContentProviderClient client) {
    this.client = client;
  }

  @Override
  public Cursor getModified() {
    final String[] projection = new String[] {
      ReadingListItems.GUID,
      ReadingListItems.IS_FAVORITE,
      ReadingListItems.RESOLVED_TITLE,
      ReadingListItems.RESOLVED_URL,
      ReadingListItems.EXCERPT,
    };

    final String selection = ReadingListItems.SYNC_STATUS + " = " + ReadingListItems.SYNC_STATUS_MODIFIED;;

    try {
      return client.query(URI_WITHOUT_DELETED, projection, selection, null, null);
    } catch (RemoteException e) {
      throw new IllegalStateException(e);
    }
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
    final String selection = SYNC_STATUS + " = " + SYNC_STATUS_NEW + " OR " + ReadingListItems.GUID + " IS NULL";

    try {
      return client.query(URI_WITHOUT_DELETED, null, selection, null, null);
    } catch (RemoteException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public ReadingListChangeAccumulator getChangeAccumulator() {
    final ArrayList<ClientReadingListRecord> delete = new ArrayList<>();
    final ArrayList<ReadingListRecord> records = new ArrayList<>();

    return new ReadingListChangeAccumulator() {
      @Override
      public boolean flushDeletions() {
        if (delete.size() == 0) {
          return true;
        }

        long[] ids = new long[delete.size()];
        String[] guids = new String[delete.size()];
        int iID = 0;
        int iGUID = 0;
        for (ClientReadingListRecord record : delete) {
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

        delete.clear();
        return true;
      }

      @Override
      public void finish() {
        flushDeletions();
        // TODO
      }

      @Override
      public void addDeletion(ClientReadingListRecord record) {
        delete.add(record);
      }

      @Override
      public void addChangedRecord(ReadingListRecord record) {
        records.add(record);
      }
    };
  }
}
