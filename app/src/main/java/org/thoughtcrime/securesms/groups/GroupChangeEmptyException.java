package org.thoughtcrime.securesms.groups;

import androidx.annotation.NonNull;

public final class GroupChangeEmptyException extends Exception {

  GroupChangeEmptyException() {
  }

  GroupChangeEmptyException(@NonNull Throwable throwable) {
    super(throwable);
  }

  GroupChangeEmptyException(@NonNull String message) {
    super(message);
  }
}
