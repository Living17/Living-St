package org;

public final class GV2DebugFlags {
  public static final boolean DO_NOT_CHANGE_PROFILE_KEY_ON_ROTATE = false;

  public static final boolean EXTRA_LOGGING_AND199        = true;  // TODO GV2 AND-199
  public static final boolean EXTRA_VISUAL_LOGGING_AND199 = false; // TODO GV2 AND-199
  public static final boolean SKIP_CAPABILITY_CHECK       = false;
  public static final boolean SKIP_UUID_CAPABILITY_CHECK  = true;
  public static final boolean INCLUDE_SIGNED_GROUP_CHANGE = false;
  public static final boolean FORCE_SELF_CAPABILITIES     = true;

  private GV2DebugFlags() {
  }

  /**
   * If true, only invite members, even if you know their profilekeycredential
   */
  public static final boolean FORCE_INVITES = false;
}
