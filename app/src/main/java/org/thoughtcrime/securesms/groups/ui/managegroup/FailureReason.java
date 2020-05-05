package org.thoughtcrime.securesms.groups.ui.managegroup;

import androidx.annotation.StringRes;

import org.thoughtcrime.securesms.R;

public enum FailureReason {
  NO_RIGHTS(R.string.ManageGroupActivity_you_dont_have_the_rights_to_do_this),
  NOT_CAPABLE(R.string.ManageGroupActivity_not_capable),
  NOT_A_MEMBER(R.string.ManageGroupActivity_youre_not_a_member_of_the_group),
  OTHER(R.string.ManageGroupActivity_failed_to_update_the_group);

  private final @StringRes
  int toastMessage;

  FailureReason(@StringRes int toastMessage) {
    this.toastMessage = toastMessage;
  }

  public @StringRes int getToastMessage() {
    return toastMessage;
  }
}
