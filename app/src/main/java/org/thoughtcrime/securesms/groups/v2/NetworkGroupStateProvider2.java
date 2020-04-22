package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Authorization;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

public final class NetworkGroupStateProvider2 {

  private static final String TAG = Log.tag(NetworkGroupStateProvider2.class);

  @NonNull private final GroupsV2Api           groups;
  @NonNull private final GroupsV2Authorization GroupsV2Authorization;
  @NonNull private final GroupSecretParams     groupSecretParams;

  public NetworkGroupStateProvider2(@NonNull GroupsV2Api groups,
                                    @NonNull GroupsV2Authorization GroupsV2Authorization,
                                    @NonNull GroupSecretParams groupSecretParams)
  {
    this.groups                = groups;
    this.GroupsV2Authorization = GroupsV2Authorization;
    this.groupSecretParams     = groupSecretParams;
  }

  public @NonNull DecryptedGroup getCurrentGroupState()
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    Log.i(TAG, "Getting current group state");
    return groups.getGroup(groupSecretParams, GroupsV2Authorization);
  }

  public @NonNull Collection<DecryptedGroupHistoryEntry> getGroupStatesFromRevision(int fromRevision)
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    Log.i(TAG, String.format(Locale.US, "Getting group logs from revision %d", fromRevision));
    return groups.getGroupHistory(groupSecretParams, fromRevision, GroupsV2Authorization);
  }
}
