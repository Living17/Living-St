package org.thoughtcrime.securesms.groups;

public final class GroupChangeFailedException extends Exception {

  GroupChangeFailedException() {
  }

  GroupChangeFailedException(Throwable throwable) {
    super(throwable);
  }

  GroupChangeFailedException(String message) {
    super(message);
  }
}
