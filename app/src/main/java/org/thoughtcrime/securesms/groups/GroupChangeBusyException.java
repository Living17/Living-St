package org.thoughtcrime.securesms.groups;

public final class GroupChangeBusyException extends Exception {

  public GroupChangeBusyException(Throwable throwable) {
    super(throwable);
  }

  public GroupChangeBusyException(String message) {
    super(message);
  }

}
