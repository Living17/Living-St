package org.thoughtcrime.securesms;

import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;

public final class AppCapabilities {

  private AppCapabilities() {
  }

  private static final boolean UUID_CAPABLE      = true;
  private static final boolean GROUPS_V2_CAPABLE = true;

  /**
   * @param storageCapable Whether or not the user can use storage service. This is another way of
   *                       asking if the user has set a Signal PIN or not.
   */
  public static SignalServiceProfile.Capabilities getCapabilities(boolean storageCapable) {
    return new SignalServiceProfile.Capabilities(UUID_CAPABLE, GROUPS_V2_CAPABLE, storageCapable);
  }
}
