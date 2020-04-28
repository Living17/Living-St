package org;

public final class GV2DebugFlags {
  public static final boolean DO_NOT_CHANGE_PROFILE_KEY_ON_ROTATE = false;

  public static final boolean EXTRA_LOGGING_AND199 = false; // TODO GV2 AND-199
  public static final boolean SKIP_CAPABILITY_CHECK = false;
  public static final boolean INCLUDE_SIGNED_GROUP_CHANGE = false;

  private GV2DebugFlags() {
  }

  /**
   * If true, only invite members, even if you know their profilekeycredential
   */
  public static final boolean FORCE_INVITES = false;
}
