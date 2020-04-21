package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Threadsafe group memory
 */

 // TODO GV2 THIS IS TRASH NOW
public final class GroupMemoryCache {

  private final Map<GroupSecretParams, Group> cache = new HashMap<>();

  private final Provider provider;

  public GroupMemoryCache(@NonNull Provider provider) {
    this.provider = provider;
  }

  /**
   * Finds or creates the cache for the given group id.
   */
  public synchronized Group forGroup(@NonNull GroupMasterKey groupMasterKey) {
    return forGroup(GroupSecretParams.deriveFromMasterKey(groupMasterKey));
  }

  public synchronized Group forGroup(@NonNull GroupSecretParams groupSecretParams) {
    Group group = cache.get(groupSecretParams);
    if (group == null) {
      group = new Group(groupSecretParams);
      cache.put(groupSecretParams, group);
    }

    return group;
  }

  public final class Group {

    private final Map<Integer, DecryptedGroup>       cache       = new HashMap<>();
    private final Map<Integer, DecryptedGroupChange> changeCache = new HashMap<>();

    private final GroupSecretParams groupSecretParams;

    public Group(@NonNull GroupSecretParams groupSecretParams) {
      this.groupSecretParams = groupSecretParams;
    }

    public @NonNull DecryptedGroup getLatest()
      throws IOException, VerificationFailedException, InvalidGroupStateException
    {
      DecryptedGroup decryptedGroup = provider.getCurrentGroupState(groupSecretParams);
      store(decryptedGroup);
      return decryptedGroup;
    }

    public @Nullable DecryptedGroup getGroupState(int revision)
      throws IOException, VerificationFailedException, InvalidGroupStateException
    {
      if (revision < 0) throw new AssertionError();

      DecryptedGroup decryptedGroup = read(revision);

      if (decryptedGroup == null) {
        // we don't know about this revision

        fetchLogs(revision);
        decryptedGroup = read(revision);
      }

      return decryptedGroup;
    }

    public @Nullable DecryptedGroupChange getGroupChange(int revision)
      throws IOException, VerificationFailedException, InvalidGroupStateException
    {
      DecryptedGroupChange decryptedChange = readChange(revision);

      if (decryptedChange == null) {
        fetchLogs(revision);
        decryptedChange = readChange(revision);
      }

      return decryptedChange;
    }

    private void fetchLogs(int revision)
      throws IOException, VerificationFailedException, InvalidGroupStateException
    {
      Collection<DecryptedGroupHistoryEntry> states = provider.getGroupStatesFrom(groupSecretParams, revision);
      for (DecryptedGroupHistoryEntry entry : states) {
        store(entry.getGroup());
        storeChange(entry.getChange());
      }
    }

    private synchronized DecryptedGroup read(int revision) {
      return cache.get(revision);
    }

    private synchronized void store(DecryptedGroup group) {
      if (group != null) {
        cache.put(group.getVersion(), group);
      }
    }

    private synchronized DecryptedGroupChange readChange(int revision) {
      return changeCache.get(revision);
    }

    private synchronized void storeChange(DecryptedGroupChange group) {
      if (group != null) {
        changeCache.put(group.getVersion(), group);
      }
    }
  }

  public interface Provider {
    @NonNull DecryptedGroup getCurrentGroupState(@NonNull GroupSecretParams groupSecretParams) throws IOException, VerificationFailedException, InvalidGroupStateException;

    @NonNull Collection<DecryptedGroupHistoryEntry> getGroupStatesFrom(@NonNull GroupSecretParams groupSecretParams, int from) throws IOException, VerificationFailedException, InvalidGroupStateException;
  }

}
