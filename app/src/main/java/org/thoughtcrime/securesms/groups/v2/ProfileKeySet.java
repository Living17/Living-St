package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ProfileKeySet {

  private static final String TAG = Log.tag(ProfileKeySet.class);

  private final Map<UUID, ProfileKey> profileKeys              = new LinkedHashMap<>();
  private final Map<UUID, ProfileKey> authoritativeProfileKeys = new LinkedHashMap<>();

  /**
   * Add new profile keys from the group state.
   */
  public void addKeysFromGroupState(@NonNull DecryptedGroup group,
                                    @Nullable UUID changeSource)
  {
    for (DecryptedMember member : group.getMembersList()) {
      UUID       memberUuid  = UuidUtil.fromByteString(member.getUuid());
      ProfileKey profileKey;
      try {
        profileKey = new ProfileKey(member.getProfileKey().toByteArray());
      } catch (InvalidInputException e) {
        Log.w(TAG, "Bad profile key in group");
        continue;
      }

      if (changeSource != null) {
        Log.d(TAG, String.format("Change %s by %s", memberUuid, changeSource));

        if (changeSource.equals(memberUuid)) {
          authoritativeProfileKeys.put(memberUuid, profileKey);
          profileKeys.remove(memberUuid);
        } else {
          if (!authoritativeProfileKeys.containsKey(memberUuid)) {
            profileKeys.put(memberUuid, profileKey);
          }
        }
      }
    }
  }

  /**
   * Fills in gaps (nulls) in profile key knowledge from new profile keys.
   * <p>
   * If from authoritative source, this will overwrite local, otherwise it will only write to the
   * database if missing.
   * <p>
   * Fires new fetch profile jobs for each updated profile key.
   */
  @WorkerThread
  public void persistLearnedProfileKeys(@NonNull RecipientDatabase recipientDatabase,
                                        @NonNull JobManager jobManager)
  {
    Log.i(TAG, String.format(Locale.US, "persistLearnedProfileKeys %d/%d keys", profileKeys.size(), authoritativeProfileKeys.size()));

    HashSet<RecipientId> updated = new HashSet<>(profileKeys.size() + authoritativeProfileKeys.size());
    RecipientId selfId  = Recipient.self().getId();

    for (Map.Entry<UUID, ProfileKey> entry : profileKeys.entrySet()) {
      RecipientId recipientId = recipientDatabase.getOrInsertFromUuid(entry.getKey());
      if (recipientDatabase.setProfileKeyIfAbsent(recipientId, entry.getValue())) {
        Log.i(TAG, "Learned new profile key");
        updated.add(recipientId);
      }
    }

    for (Map.Entry<UUID, ProfileKey> entry : authoritativeProfileKeys.entrySet()) {
      RecipientId recipientId = recipientDatabase.getOrInsertFromUuid(entry.getKey());

      if (selfId.equals(recipientId)) {
        Log.i(TAG, "Seen authoritative update for self");
        if (!entry.getValue().equals(ProfileKeyUtil.getSelfProfileKey())) {
          // TODO: GV2 We don't want to trust this, but might want to do a social graph sync AND-220
          Log.w(TAG, "Seen authoritative update for self that didn't match local");
        }
      } else {
        Log.i(TAG, String.format("Profile key from owner %s %s", recipientId, Arrays.toString(entry.getValue().serialize())));
        if (recipientDatabase.setProfileKey(recipientId, entry.getValue())) {
          Log.i(TAG, "Learned new profile key from owner");
          updated.add(recipientId);
        }
      }
    }

    if (!updated.isEmpty()) {
      Log.i(TAG, String.format(Locale.US, "Retrieving %d profiles", updated.size()));
      for (RecipientId recipient: updated) {
        jobManager.add(RetrieveProfileJob.forRecipient(recipient));
      }
    }
  }
}
