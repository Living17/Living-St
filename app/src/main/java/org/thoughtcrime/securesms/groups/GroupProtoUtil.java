package org.thoughtcrime.securesms.groups;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.util.UUIDUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class GroupProtoUtil {

  private GroupProtoUtil() {
  }

  public static int findVersionWeWereAdded(@NonNull DecryptedGroup group, @NonNull UUID uuid) {
    ByteString bytes = UuidUtil.toByteString(uuid);
    for (DecryptedMember decryptedMember : group.getMembersList()) {
      if (decryptedMember.getUuid().equals(bytes)) {
        return decryptedMember.getJoinedAtVersion();
      }
    }
    for (DecryptedPendingMember decryptedMember : group.getPendingMembersList()) {
      if (decryptedMember.getUuid().equals(bytes)) {
        //TODO: GV2 We don't really know, assume latest AND-214
        return group.getVersion();
      }
    }
    //TODO: GV2 Maybe better error AND-213
    throw new AssertionError("Not in the group!");
  }

  /**
   * Returns true iff {@param uuid} is in the {@param list}.
   */
  public static boolean uuidInList(@NonNull UUID uuid, @NonNull Collection<ByteString> list) {
    return list.contains(UuidUtil.toByteString(uuid));
  }


  public static DecryptedGroupV2Context createDecryptedGroupV2Context(@NonNull GroupMasterKey masterKey,
                                                                      @NonNull DecryptedGroup decryptedGroup,
                                                                      @Nullable DecryptedGroupChange plainGroupChange)
  {
    int version = plainGroupChange != null ? plainGroupChange.getVersion() : decryptedGroup.getVersion();
    SignalServiceProtos.GroupContextV2 groupContext = SignalServiceProtos.GroupContextV2.newBuilder()
                                                                         .setMasterKey(ByteString.copyFrom(masterKey.serialize()))
                                                                         .setRevision(version)
                                                                         .build();

    DecryptedGroupV2Context.Builder builder = DecryptedGroupV2Context.newBuilder()
                                                                     .setContext(groupContext);

    if (plainGroupChange != null) {
      if (plainGroupChange.getVersion() != decryptedGroup.getVersion()) {
        //TODO: GV2 You are actually allowed to be one behind when leaving AND-215
        //throw new AssertionError();
      }
      builder.setChange(plainGroupChange);
    }

    builder.setGroupState(decryptedGroup);

    return builder.build();
  }

  @WorkerThread
  public static Recipient pendingMemberToRecipient(@NonNull Context context, @NonNull DecryptedPendingMember pendingMember) {
    return uuidByteStringToRecipient(context, pendingMember.getUuid());
  }

  @WorkerThread
  public static Recipient uuidByteStringToRecipient(@NonNull Context context, @NonNull ByteString uuidByteString) {
    UUID uuid = UUIDUtil.deserialize(uuidByteString.toByteArray());

    if (uuid.equals(GroupsV2Operations.UNKNOWN_UUID)) {
      return Recipient.UNKNOWN;
    }

    return Recipient.externalPush(context, uuid, null);
  }

  public static boolean isMember(@NonNull UUID uuid, @NonNull List<DecryptedMember> membersList) {
    ByteString uuidBytes = ByteString.copyFrom(UUIDUtil.serialize(uuid));

    for (DecryptedMember member : membersList) {
      if (uuidBytes.equals(member.getUuid())) {
        return true;
      }
    }

    return false;
  }

  public static boolean isPendingMember(@NonNull UUID uuid, @NonNull List<DecryptedPendingMember> pendingMembersList) {
    ByteString uuidBytes = ByteString.copyFrom(UUIDUtil.serialize(uuid));

    for (DecryptedPendingMember member : pendingMembersList) {
      if (uuidBytes.equals(member.getUuid())) {
        return true;
      }
    }

    return false;
  }
}
