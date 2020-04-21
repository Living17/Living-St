package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Authorization;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

public final class NetworkGroupStateProvider implements GroupMemoryCache.Provider {

  private static final String TAG = Log.tag(NetworkGroupStateProvider.class);

  private final GroupsV2Api           groups;
  private final GroupsV2Authorization GroupsV2Authorization;

  public NetworkGroupStateProvider(@NonNull GroupsV2Api groups, @NonNull GroupsV2Authorization GroupsV2Authorization) {
    this.groups                = groups;
    this.GroupsV2Authorization = GroupsV2Authorization;
  }

  @Override
  public @NonNull DecryptedGroup getCurrentGroupState(@NonNull GroupSecretParams groupSecretParams)
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    Log.i(TAG, "Getting current group state");
    return groups.getGroup(groupSecretParams, GroupsV2Authorization);
  }

  @Override
  public @NonNull Collection<DecryptedGroupHistoryEntry> getGroupStatesFrom(@NonNull GroupSecretParams groupSecretParams, int from)
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    Log.i(TAG, String.format(Locale.US, "Getting group logs from revision %d", from));
    return groups.getGroupHistory(groupSecretParams, from, GroupsV2Authorization);
  }
}
