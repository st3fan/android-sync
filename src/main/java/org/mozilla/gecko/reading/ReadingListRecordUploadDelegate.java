/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.reading;

import org.mozilla.gecko.sync.net.MozResponse;

public interface ReadingListRecordUploadDelegate {
  public void onInvalidUpload(ClientReadingListRecord up, ReadingListResponse response);
  public void onConflict(ClientReadingListRecord up, ReadingListResponse response);
  public void onSuccess(ClientReadingListRecord up, ReadingListRecordResponse response, ServerReadingListRecord down);
  public void onBadRequest(ClientReadingListRecord up, MozResponse response);
  public void onFailure(ClientReadingListRecord up, Exception ex);
  public void onFailure(ClientReadingListRecord up, MozResponse response);
}
