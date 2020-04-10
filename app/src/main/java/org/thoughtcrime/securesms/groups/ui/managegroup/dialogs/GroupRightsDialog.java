package org.thoughtcrime.securesms.groups.ui.managegroup.dialogs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.groups.GroupAccessControl;

public final class GroupRightsDialog {

  private final AlertDialog.Builder builder;

  private @NonNull
  GroupAccessControl rights;

  public GroupRightsDialog(@NonNull Context context, @NonNull GroupAccessControl currentRights, @NonNull GroupRightsDialog.OnChange onChange) {
    rights = currentRights;

    builder = new AlertDialog.Builder(context)
                .setTitle(R.string.GroupManagement_choose_who_can_change_the_group_name_and_photo)
                .setSingleChoiceItems(R.array.GroupManagement_edit_group_info_choices, currentRights.ordinal(), (dialog, which) -> rights = GroupAccessControl.values()[which])
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                  GroupAccessControl newGroupAccessControl = rights;

                  if (newGroupAccessControl != currentRights) {
                    onChange.changed(currentRights, newGroupAccessControl);
                  }
                });
  }

  public void show() {
    builder.show();
  }

  public interface OnChange {
    void changed(@NonNull GroupAccessControl from, @NonNull GroupAccessControl to);
  }
}
