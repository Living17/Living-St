package org.thoughtcrime.securesms.groups;

public final class GroupChangeFailedException extends Exception {

  GroupChangeFailedException() {
  }

  GroupChangeFailedException(String message) {
    super(message);
  }

  GroupChangeFailedException(Throwable throwable) {
    super(throwable);
  }
}
