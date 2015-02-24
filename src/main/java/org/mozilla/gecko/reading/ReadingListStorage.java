/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading;

import java.util.Collection;

import android.database.Cursor;

public interface ReadingListStorage {
  Cursor getModified();
  Cursor getStatusChanges();
  Cursor getNew();
  ReadingListChangeAccumulator getChangeAccumulator();

  void clearStatusChanges(Collection<String> uploaded);
}
