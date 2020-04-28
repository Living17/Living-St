package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.util.InvalidNumberException;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
        return V2GroupManagerOld.createGroup(context, members, name, avatar);
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
  public static GroupActionResult updateGroup(@NonNull  Context context,
                                              @NonNull  GroupId groupId,
                                              @Nullable byte[]  avatar,
                                              @Nullable String  name)
      throws InvalidNumberException
  {

    List<Recipient> members = DatabaseFactory.getGroupDatabase(context)
                                             .getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

    return updateGroup(context, groupId, new HashSet<>(members), avatar, name);
  }

  public static @Nullable GroupActionResult updateGroup(@NonNull  Context        context,
                                                        @NonNull  GroupId        groupId,
                                                        @NonNull  Set<Recipient> members,
                                                        @Nullable byte[]         avatar,
                                                        @Nullable String         name)
  {
    if (groupId.isV2()) {
      try {
        return V2GroupManagerOld.updateGroup(context, groupId.requireV2(), members, name, avatar);
      } catch (IOException | VerificationFailedException | InvalidGroupStateException | MembershipNotSuitableForV2Exception | GroupNotAMemberException e) {
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
        return V2GroupManagerOld.leaveGroup(context, groupId.requireV2());
      } catch (VerificationFailedException | IOException | InvalidGroupStateException | GroupNotAMemberException e) {
        Log.w(TAG, e);
        return false;
      }
    } else {
      return V1GroupManager.leaveGroup(context, groupId.requireV1());
    }
  }

  @WorkerThread
  public static boolean ejectFromGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Recipient recipient) {
    if (groupId.isV2()) {
      try {
        return V2GroupManagerOld.ejectOneMember(context, groupId.requireV2(), recipient) != null;
      } catch (VerificationFailedException | IOException | InvalidGroupStateException | GroupNotAMemberException e) {
        Log.w(TAG, e);
        return false;
      }
    } else {
      return V1GroupManager.leaveGroup(context, groupId.requireV1());
    }
  }

  @WorkerThread
  public static boolean setMemberAdmin(@NonNull Context context,
                                       @NonNull GroupId.V2 groupId,
                                       @NonNull RecipientId recipientId,
                                       boolean admin)
  {
    try {
      V2GroupManagerOld.setMemberAdmin(context, groupId, recipientId, admin);
      return true;
    } catch (IOException | VerificationFailedException | InvalidGroupStateException | GroupNotAMemberException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  @WorkerThread
  public static void updateProfileKey(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    try {
      V2GroupManagerOld.updateProfileKey(context, groupId);
    } catch (GroupNotAMemberException e) {
      e.printStackTrace();
    }
  }

  public static void acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    try {
      V2GroupManagerOld.acceptInvite(context, groupId);
    } catch (GroupNotAMemberException e) {
      e.printStackTrace();
    }
  }

  @WorkerThread
  public static void updateGroupTimer(@NonNull Context context, @NonNull GroupId.Push groupId, int expirationTime)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException
  {
    if (groupId.isV2()) {
      new V2GroupManager(context).edit(groupId.requireV2())
                                 .updateGroupTimer(expirationTime);
    } else {
      V1GroupManager.updateGroupTimer(context, groupId.requireV1(), expirationTime);
    }
  }

  @WorkerThread
  public static void cancelInvites(@NonNull Context context,
                                   @NonNull GroupId.V2 groupId,
                                   @NonNull Collection<UuidCiphertext> uuidCipherTexts)
      throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    try {
      V2GroupManagerOld.cancelInvites(context, groupId.requireV2(), uuidCipherTexts);
    } catch (GroupNotAMemberException e) {
      e.printStackTrace();
    }
  }

  @WorkerThread
  public static void applyMembershipAdditionRightsChange(@NonNull Context context,
                                                         @NonNull GroupId.V2 groupId,
                                                         @NonNull GroupAccessControl newRights)
       throws GroupChangeFailedException, GroupInsufficientRightsException
  {
    try {
      V2GroupManagerOld.applyMembershipRightsChange(context, groupId, newRights);
    } catch (AuthorizationFailedException e) {
      Log.w(TAG, e);
      throw new GroupInsufficientRightsException(e);
    } catch (InvalidGroupStateException | VerificationFailedException | IOException | GroupNotAMemberException e) {
      Log.w(TAG, e);
      throw new GroupChangeFailedException(e);
    }
  }

  @WorkerThread
  public static void applyAttributesRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
      throws GroupChangeFailedException, GroupInsufficientRightsException
  {
    try {
      V2GroupManagerOld.applyAttributesRightsChange(context, groupId, newRights);
    } catch (AuthorizationFailedException e) {
      Log.w(TAG, e);
      throw new GroupInsufficientRightsException(e);
    } catch (InvalidGroupStateException | VerificationFailedException | IOException | GroupNotAMemberException e) {
      Log.w(TAG, e);
      throw new GroupChangeFailedException(e);
    }
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
