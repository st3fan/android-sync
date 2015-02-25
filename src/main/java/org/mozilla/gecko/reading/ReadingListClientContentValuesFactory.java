/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading;

import java.util.Map.Entry;
import java.util.Set;

import org.mozilla.gecko.db.BrowserContract.ReadingListItems;
import org.mozilla.gecko.reading.ReadingListRecord.ServerMetadata;
import org.mozilla.gecko.sync.ExtendedJSONObject;

import android.content.ContentValues;

public class ReadingListClientContentValuesFactory {
  public static ContentValues fromClientRecord(ClientReadingListRecord record) {
    // Do each of these.
    ExtendedJSONObject fields = record.fields;
    ServerMetadata sm = record.serverMetadata;

    final ContentValues values = new ContentValues();

    if (sm.guid != null) {
      values.put(ReadingListItems.GUID, sm.guid);
    }

    if (sm.lastModified > -1L) {
      values.put(ReadingListItems.SERVER_LAST_MODIFIED, sm.lastModified);
    }

    final Set<Entry<String, Object>> entries = fields.entrySet();

    for (Entry<String,Object> entry : entries) {
      final String key = entry.getKey();
      if (key.equals("status")) {
        final int status = ((Long) entry.getValue()).intValue();
        values.put(ReadingListItems.IS_DELETED, (status == 2) ? 1 : 0);
        values.put(ReadingListItems.IS_ARCHIVED, (status == 1) ? 1 : 0);
        continue;
      }

      final String field = mapField(key);
      if (field == null) {
        continue;
      }

      final Object v = entry.getValue();
      if (v instanceof Boolean) {
        values.put(field, ((Boolean) v) ? 1 : 0);
      } else if (v instanceof Long) {
        values.put(field, (Long) v);
      } else if (v instanceof Integer) {
        values.put(field, (Integer) v);
      } else if (v instanceof String) {
        values.put(field, (String) v);
      } else if (v instanceof Double) {
        values.put(field, (Double) v);
      } else {
        throw new IllegalArgumentException("Unknown value " + v + " of type " + v.getClass().getSimpleName());
      }
    }

    return values;
  }

  /**
   * Only returns valid columns.
   */
  private static String mapField(String key) {
    if (key == null) {
      return null;
    }

    switch (key) {
    case "unread":
      return "is_unread";
    case "favorite":
      return "is_favorite";
    }

    // Validation.
    for (int i = 0; i < ReadingListItems.ALL_FIELDS.length; ++i) {
      if (key.equals(ReadingListItems.ALL_FIELDS[i])) {
        return key;
      }
    }

    return null;
  }
}
