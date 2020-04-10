package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.model.AlanException;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class GroupManager {

  private static final String TAG = Log.tag(GroupManager.class);

  @WorkerThread
  public static @NonNull GroupActionResult createGroup(@NonNull  Context        context,
                                                       @NonNull  Set<Recipient> members,
                                                       @Nullable Bitmap         avatar,
                                                       @Nullable String         name,
                                                                 boolean        mms)
  {
    boolean shouldAttemptToCreateV2 = !mms && FeatureFlags.CREATE_V2_GROUPS;

    if (shouldAttemptToCreateV2) {
      try {
        return V2GroupManager.createGroup(context, members, name, avatar);
      } catch (IOException e) {
        //TODO: GV2 We need to cope with failure to update group, network is new to GV2 AND-212
        Log.w(TAG, e);
        return null;
      } catch (MembershipNotSuitableForV2Exception e) {
        Log.w(TAG, "Attempted to make a GV2, but membership was not suitable, falling back to GV1", e);
        Set<RecipientId> ids = getMemberIds(members);

        return V1GroupManager.createGroup(context, ids, avatar, name, false);
      }
    } else {
      Set<RecipientId> ids = getMemberIds(members);

      return V1GroupManager.createGroup(context, ids, avatar, name, mms);
    }
  }

  @WorkerThread
  public static @Nullable GroupActionResult updateGroup(@NonNull  Context        context,
                                                        @NonNull  GroupId        groupId,
                                                        @NonNull  Set<Recipient> members,
                                                        @Nullable Bitmap         avatar,
                                                        @Nullable String         name)
  {
    if (groupId.isV2()) {
      try {
        return V2GroupManager.updateGroup(context, groupId.requireV2(), members, name, avatar);
      } catch (IOException | VerificationFailedException | InvalidGroupStateException | MembershipNotSuitableForV2Exception e) {
        //TODO: GV2 We need to cope with failure to update group, network is new to GV2 AND-212
        Log.w(TAG, e);
        return null;
      }
    } else {
      Set<RecipientId> addresses = getMemberIds(members);
      return V1GroupManager.updateGroup(context, groupId, addresses, avatar, name);
    }
  }

  private static Set<RecipientId> getMemberIds(Collection<Recipient> recipients) {
    final Set<RecipientId> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.getId());
    }

    return results;
  }

  @WorkerThread
  public static boolean leaveGroup(@NonNull Context context, @NonNull GroupId.Push groupId) {
    if (groupId.isV2()) {
      try {
        return V2GroupManager.leaveGroup(context, groupId.requireV2());
      } catch (VerificationFailedException | IOException | InvalidGroupStateException e) {
        Log.w(TAG, e);
        return false;
      }
    } else {
      return V1GroupManager.leaveGroup(context, groupId.requireV1());
    }
  }

  @WorkerThread
  public static void updateGroupTimer(@NonNull Context context, @NonNull GroupId.Push groupId, int expirationTime)
      throws IOException, VerificationFailedException, InvalidGroupStateException
  {
    if (groupId.isV2()) {
      V2GroupManager.updateGroupTimer(context, groupId.requireV2(), expirationTime);
    } else {
      V1GroupManager.updateGroupTimer(context, groupId.requireV1(), expirationTime);
    }
  }

  @WorkerThread
  public static void updateProfileKey(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    V2GroupManager.updateProfileKey(context, groupId);
  }

  public static void acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    V2GroupManager.acceptInvite(context, groupId);
  }

  @WorkerThread
  public static void cancelInvites(@NonNull Context context,
                                   @NonNull GroupId.V2 groupId,
                                   @NonNull Collection<UuidCiphertext> uuidCipherTexts)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    V2GroupManager.cancelInvites(context, groupId.requireV2(), uuidCipherTexts);
  }

  @WorkerThread
  public static void applyAttributesRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
      throws VerificationFailedException, InvalidGroupStateException, IOException
  {
    V2GroupManager.applyAttributesRightsChange(context, groupId, newRights);
  }

  public static class GroupActionResult {
    private final Recipient groupRecipient;
    private final long      threadId;

    public GroupActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
