package org.thoughtcrime.securesms.groups.ui.managegroup;

import androidx.annotation.NonNull;

public interface ErrorCallback {
  void onError(@NonNull FailureReason failureReason);
}
