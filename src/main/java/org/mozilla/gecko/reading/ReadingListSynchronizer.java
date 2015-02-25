/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;

import org.json.simple.parser.ParseException;
import org.mozilla.gecko.background.common.PrefsBranch;
import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.db.BrowserContract.ReadingListItems;
import org.mozilla.gecko.reading.ReadingListRecord.ServerMetadata;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.NonObjectJSONException;
import org.mozilla.gecko.sync.net.MozResponse;

import android.database.Cursor;
import android.text.TextUtils;

/**
 * This class implements the multi-phase synchronizing approach described
 * at <https://github.com/mozilla-services/readinglist/wiki/Client-phases>.
 */
public class ReadingListSynchronizer {
  public static final String LOG_TAG = ReadingListSynchronizer.class.getSimpleName();

  private static final int MAX_FAILURES = 5;

  private final PrefsBranch prefs;
  private final ReadingListClient remote;
  private final ReadingListStorage local;

  public ReadingListSynchronizer(final PrefsBranch prefs, final ReadingListClient remote, final ReadingListStorage local) {
    this.prefs = prefs;
    this.remote = remote;
    this.local = local;
  }

  private static final class NewItemUploadDelegate implements ReadingListRecordUploadDelegate {
    final Queue<String> uploaded = new LinkedList<>();
    final Queue<String> failed = new LinkedList<>();
    final Queue<String> toEnsureDownloaded = new LinkedList<>();

    public int failures = 0;
    private final ReadingListChangeAccumulator acc;

    NewItemUploadDelegate(ReadingListChangeAccumulator acc) {
      this.acc = acc;
    }

    /**
     * When an operation implies that a server record is a replacement
     * for a local record, call this to ensure that we have a copy.
     */
    private void ensureDownloaded(String id) {
      toEnsureDownloaded.add(id);
    }

    @Override
    public void onSuccess(ClientReadingListRecord up,
                          ReadingListRecordResponse response,
                          ServerReadingListRecord down) {
      // Apply the resulting record. The server will have populated some fields.
      acc.addChangedRecord(up.givenServerRecord(down));
    }

    @Override
    public void onConflict(ClientReadingListRecord up, ReadingListResponse response) {
      ExtendedJSONObject body;
      try {
        body = response.jsonObjectBody();
        String conflicting = body.getString("id");
        Logger.warn(LOG_TAG, "Conflict detected: remote ID is " + conflicting);
        ensureDownloaded(conflicting);
      } catch (IllegalStateException | NonObjectJSONException | IOException |
               ParseException e) {
        // Oops.
        // But our workaround is the same either way.
      }

      // Either the record exists locally, in which case we need to merge,
      // or it doesn't, and we'll download it shortly.
      // The simplest thing to do in both cases is to simply delete the local
      // record we tried to upload. Yes, we might lose some annotations, but
      // we can leave doing better to a follow-up.
      // Issues here are so unlikely that we don't do anything sophisticated
      // (like moving the record to a holding area) -- just delete it ASAP.
      acc.addDeletion(up);
    }

    @Override
    public void onInvalidUpload(ClientReadingListRecord up, ReadingListResponse response) {
      recordFailed(up);
    }

    @Override
    public void onFailure(ClientReadingListRecord up, MozResponse response) {
      recordFailed(up);
    }

    @Override
    public void onFailure(ClientReadingListRecord up, Exception ex) {
      recordFailed(up);
    }

    @Override
    public void onBadRequest(ClientReadingListRecord up, MozResponse response) {
      recordFailed(up);
    }

    private void recordFailed(ClientReadingListRecord up) {
      failed.add(up.getGUID());
      ++failures;
    }
  }

  private static class StatusUploadDelegate implements ReadingListRecordUploadDelegate {
    final Collection<String> uploaded = new LinkedList<>();
    final Collection<String> failed = new LinkedList<>();
    private final ReadingListChangeAccumulator acc;

    public int failures = 0;

    StatusUploadDelegate(ReadingListChangeAccumulator acc) {
      this.acc = acc;
    }

    @Override
    public void onInvalidUpload(ClientReadingListRecord up,
                                ReadingListResponse response) {
      recordFailed(up);
    }

    @Override
    public void onConflict(ClientReadingListRecord up,
                           ReadingListResponse response) {
      // This should never happen for a status-only change.
      // TODO: mark this record as requiring a full upload or download.
      failed.add(up.getGUID());
    }

    @Override
    public void onSuccess(ClientReadingListRecord up,
                          ReadingListRecordResponse response,
                          ServerReadingListRecord down) {
      if (!TextUtils.equals(up.getGUID(), down.getGUID())) {
        // Uh oh!
      }

      uploaded.add(up.getGUID());
      acc.addChangedRecord(up.givenServerRecord(down));
    }

    @Override
    public void onBadRequest(ClientReadingListRecord up, MozResponse response) {
      recordFailed(up);
    }

    @Override
    public void onFailure(ClientReadingListRecord up, Exception ex) {
      recordFailed(up);
    }

    @Override
    public void onFailure(ClientReadingListRecord up, MozResponse response) {
      recordFailed(up);
    }

    private void recordFailed(ClientReadingListRecord up) {
      failed.add(up.getGUID());
      ++failures;
    }
  }

  private Queue<ClientReadingListRecord> accumulateStatusChanges(final Cursor cursor) {
    try {
      final Queue<ClientReadingListRecord> toUpload = new LinkedList<>();

      // The columns should come in this order, FWIW.
      final int columnGUID = cursor.getColumnIndexOrThrow(ReadingListItems.GUID);
      final int columnIsUnread = cursor.getColumnIndexOrThrow(ReadingListItems.IS_UNREAD);
      final int columnIsFavorite = cursor.getColumnIndexOrThrow(ReadingListItems.IS_FAVORITE);
      final int columnMarkedReadBy = cursor.getColumnIndexOrThrow(ReadingListItems.MARKED_READ_BY);
      final int columnMarkedReadOn = cursor.getColumnIndexOrThrow(ReadingListItems.MARKED_READ_ON);
      final int columnChangeFlags = cursor.getColumnIndexOrThrow(ReadingListItems.SYNC_CHANGE_FLAGS);

      while (cursor.moveToNext()) {
        final String guid = cursor.getString(columnGUID);
        if (guid == null) {
          // Nothing we can do here.
          continue;
        }

        final ExtendedJSONObject o = new ExtendedJSONObject();
        o.put("id", guid);

        final int changeFlags = cursor.getInt(columnChangeFlags);
        if ((changeFlags & ReadingListItems.SYNC_CHANGE_FAVORITE_CHANGED) > 0) {
          o.put("favorite", cursor.getInt(columnIsFavorite) == 1);
        }

        if ((changeFlags & ReadingListItems.SYNC_CHANGE_UNREAD_CHANGED) > 0) {
          final boolean isUnread = cursor.getInt(columnIsUnread) == 1;
          o.put("unread", isUnread);
          if (!isUnread) {
            o.put("marked_read_by", cursor.getString(columnMarkedReadBy));
            o.put("marked_read_on", cursor.getLong(columnMarkedReadOn));
          }
        }

        final ClientMetadata cm = null;
        final ServerMetadata sm = new ServerMetadata(guid, -1L);
        final ClientReadingListRecord record = new ClientReadingListRecord(sm, cm, o);
        toUpload.add(record);
      }

      return toUpload;
    } finally {
      cursor.close();
    }
  }

  private Queue<ClientReadingListRecord> accumulateNewItems(Cursor cursor) {
    try {
      final Queue<ClientReadingListRecord> toUpload = new LinkedList<>();
      final ReadingListClientRecordFactory factory = new ReadingListClientRecordFactory(cursor);

      ClientReadingListRecord record;
      while ((record = factory.getNext()) != null) {
        toUpload.add(record);
      }
      return toUpload;
    } finally {
      cursor.close();
    }
  }

  // N.B., status changes for items that haven't been uploaded yet are dealt with in
  // uploadNewItems.
  public ReadingListChangeAccumulator uploadUnreadChanges(final ReadingListSynchronizerDelegate delegate) {
    try {
      final Cursor cursor = local.getStatusChanges();

      if (cursor == null) {
        delegate.onUnableToSync(new RuntimeException("Unable to get unread item cursor."));
        return null;
      }

      final Queue<ClientReadingListRecord> toUpload = accumulateStatusChanges(cursor);

      // Nothing to do.
      if (toUpload.isEmpty()) {
        delegate.onStatusUploadComplete(null, null);
        return null;
      }

      // Upload each record.
      final ReadingListChangeAccumulator acc = this.local.getChangeAccumulator();
      final StatusUploadDelegate uploadDelegate = new StatusUploadDelegate(acc);
      ClientReadingListRecord rec;

      while ((rec = toUpload.poll()) != null) {
        if (uploadDelegate.failures > MAX_FAILURES) {
          // Abort the rest.
          break;
        }
        // Don't send I-U-S; in the case of favorites we're
        // happy to overwrite the server value, and in the case of unread status
        // the server will reconcile for us.
        this.remote.patch(rec, uploadDelegate);
      }
      delegate.onStatusUploadComplete(uploadDelegate.uploaded, uploadDelegate.failed);
      return acc;
    } catch (IllegalStateException e) {
      delegate.onUnableToSync(e);
      return null;
    }
  }

  public ReadingListChangeAccumulator uploadNewItems(final ReadingListSynchronizerDelegate delegate) {
    try {
      final Cursor cursor = this.local.getNew();

      if (cursor == null) {
        delegate.onUnableToSync(new RuntimeException("Unable to get new item cursor."));
        return null;
      }

      Queue<ClientReadingListRecord> toUpload = accumulateNewItems(cursor);

      // Nothing to do.
      if (toUpload.isEmpty()) {
        delegate.onNewItemUploadComplete(null, null);
        return null;
      }

      final ReadingListChangeAccumulator acc = this.local.getChangeAccumulator();
      final NewItemUploadDelegate uploadDelegate = new NewItemUploadDelegate(acc);
      ClientReadingListRecord rec;

      while ((rec = toUpload.poll()) != null) {
        if (uploadDelegate.failures > MAX_FAILURES) {
          // Abort the rest.
          break;
        }

        // Handle 201 for success, 400 for invalid, 303 for redirect.
        this.remote.add(rec, uploadDelegate);
      }

      addEnsureDownloadedToPrefs(uploadDelegate.toEnsureDownloaded);

      // We mark uploaded records as synced when we apply the server record with the
      // GUID -- we don't know the GUID yet!
      return acc;
    } catch (IllegalStateException e) {
      delegate.onUnableToSync(e);
      return null;
    }
  }

  private void addEnsureDownloadedToPrefs(Queue<String> toEnsureDownloaded) {
    if (toEnsureDownloaded.isEmpty()) {
      return;
    }
    // TODO
  }

  public void uploadModified(final ReadingListSynchronizerDelegate delegate) {
    ReadingListChangeAccumulator postUnread = uploadUnreadChanges(delegate);
    postUnread.finish();
  }

  public void uploadOutgoing(final ReadingListSynchronizerDelegate delegate) {
    uploadModified(delegate);
    ReadingListChangeAccumulator postUpload = uploadNewItems(delegate);
    postUpload.finish();
  }

  public void sync(final ReadingListSynchronizerDelegate delegate) {
    uploadOutgoing(delegate);

    // N.B., we apply the downloaded versions of all uploaded records.
    // That means the DB server timestamp matches the server's current
    // timestamp when we do a fetch; we skip records in this way.
    // We can also optimize by keeping the (guid, server timestamp) pair
    // in memory, but of course this runs into invalidation issues if
    // concurrent writes are occurring.
    // TODO: download.
    // TODO: ensure downloaded.
  }
}
