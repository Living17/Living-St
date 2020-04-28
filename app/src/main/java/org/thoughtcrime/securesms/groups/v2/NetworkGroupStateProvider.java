package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.groups.GroupNotAMemberException;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Authorization;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

public final class NetworkGroupStateProvider {

  private static final String TAG = Log.tag(NetworkGroupStateProvider.class);

  @NonNull private final GroupsV2Api           groups;
  @NonNull private final GroupsV2Authorization groupsV2Authorization;
  @NonNull private final GroupSecretParams     groupSecretParams;

  public NetworkGroupStateProvider(@NonNull GroupsV2Api groups,
                                   @NonNull GroupsV2Authorization GroupsV2Authorization,
                                   @NonNull GroupSecretParams groupSecretParams)
  {
    this.groups                = groups;
    this.groupsV2Authorization = GroupsV2Authorization;
    this.groupSecretParams     = groupSecretParams;
  }

  public @NonNull DecryptedGroup getCurrentGroupState()
      throws InvalidGroupStateException, VerificationFailedException, IOException, GroupNotAMemberException
  {
    Log.i(TAG, "Getting current group state");
    try {
      return groups.getGroup(groupSecretParams, groupsV2Authorization);
    } catch (NotInGroupException e) {
      throw new GroupNotAMemberException(e);
    }
  }

  public @NonNull Collection<DecryptedGroupHistoryEntry> getGroupStatesFromRevision(int fromRevision)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    Log.i(TAG, String.format(Locale.US, "Getting group logs from revision %d", fromRevision));
    return groups.getGroupHistory(groupSecretParams, fromRevision, groupsV2Authorization);
  }
}
