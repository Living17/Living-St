package org.thoughtcrime.securesms.groups.ui.managegroup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import com.annimon.stream.Stream;

import org.signal.zkgroup.VerificationFailedException;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.ui.GroupMemberEntry;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

final class ManageGroupRepository {

  private static final String TAG = Log.tag(ManageGroupRepository.class);

  private final Context         context;
  private final GroupId         groupId;
  private final ExecutorService executor;
  private final GroupDatabase   groupDatabase;

  ManageGroupRepository(@NonNull Context context, @NonNull GroupId groupId) {
    this.context       = context;
    this.executor      = SignalExecutors.BOUNDED;
    this.groupId       = groupId;
    this.groupDatabase = DatabaseFactory.getGroupDatabase(context);
  }

  void getGroupState(@NonNull Consumer<GroupStateResult> onGroupStateLoaded) {
    executor.execute(() -> onGroupStateLoaded.accept(getGroupState()));
  }

  @WorkerThread
  private GroupStateResult getGroupState() {
    ThreadDatabase            threadDatabase = DatabaseFactory.getThreadDatabase(context);
    GroupDatabase.GroupRecord groupRecord    = groupDatabase.requireGroup(groupId);
    boolean                   isAdmin        = groupRecord.isAdmin(Recipient.self());

    List<GroupMemberEntry.FullMember> fullMembers = Stream.of(groupDatabase.getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_INCLUDING_SELF))
                                                          .map((Recipient member) -> new GroupMemberEntry.FullMember(member, isAdmin && !member.isLocalNumber()))
                                                          .toList();

    Recipient groupRecipient = Recipient.externalGroup(context, groupId);
    long threadId = threadDatabase.getThreadIdFor(groupRecipient);


    int invitedCount = groupRecord.isV2Group() ? groupRecord.requireV2GroupProperties().getDecryptedGroup().getPendingMembersCount() : 0;

    String memberCountSummary = invitedCount > 0 ? context.getResources().getQuantityString(R.plurals.MessageRequestProfileView_members_and_invited, fullMembers.size(),
                                                                                            fullMembers.size(), invitedCount)
                                                 : context.getResources().getQuantityString(R.plurals.MessageRequestProfileView_members, fullMembers.size(),
                                                                                            fullMembers.size());


    return new GroupStateResult(groupRecord.getTitle(),
                                fullMembers,
                                invitedCount,
                                memberCountSummary,
                                threadId,
                                groupRecipient,
                                groupRecord.getMembershipAccessControl(),
                                groupRecord.getAttributesAccessControl(),
                                isAdmin);
  }

  void applyMembershipRightsChange(@NonNull GroupAccessControl newRights, @NonNull Consumer<GroupAccessControl> after) {
    Log.d("ALAN", "Membership RIGHTS CHANGE TO " + newRights);
    SignalExecutors.BOUNDED.execute(() -> {
      try {
        GroupManager.applyMembershipRightsChange(context, groupId.requireV2(), newRights);
      } catch (VerificationFailedException | InvalidGroupStateException | IOException e) {
        Log.w(TAG, e);
        //TODO: GV2 TOAST?
      }
      after.accept(groupDatabase.requireGroup(groupId).getMembershipAccessControl());
    });
  }

  void applyAttributesRightsChange(@NonNull GroupAccessControl newRights, @NonNull Consumer<GroupAccessControl> after) {
    Log.d("ALAN", "attributes RIGHTS CHANGE TO " + newRights);
    SignalExecutors.BOUNDED.execute(() -> {
      try {
        GroupManager.applyAttributesRightsChange(context, groupId.requireV2(), newRights);
      } catch (VerificationFailedException | InvalidGroupStateException | IOException e) {
        Log.w(TAG, e);
        //TODO: GV2 TOAST?
      }
      after.accept(groupDatabase.requireGroup(groupId).getAttributesAccessControl());
    });
  }

  void removeMember(@NonNull GroupMemberEntry.FullMember fullMember, @NonNull Consumer<GroupStateResult> onGroupStateLoaded) {
    executor.execute(() -> {
      fullMember.setBusy(true);
      try {
        if (GroupManager.ejectFromGroup(context, groupId.requireV2(), fullMember.getMember())) {
          onGroupStateLoaded.accept(getGroupState());
        }
      } finally {
        fullMember.setBusy(false);
      }
    });
  }

  static final class GroupStateResult {

    private final String title;
    private final List<GroupMemberEntry.FullMember> fullMembers;
    private final int invitedCount;
    private final String memberCountSummary;
    private final long threadId;
    private final Recipient recipient;
    private final GroupAccessControl membershipAccessControl;
    private final GroupAccessControl attributesAccessControl;
    private final boolean isAdmin;

    private GroupStateResult(String title,
                             List<GroupMemberEntry.FullMember> fullMembers,
                             int invitedCount,
                             String memberCountSummary,
                             long threadId,
                             Recipient recipient,
                             GroupAccessControl membershipAccessControl,
                             GroupAccessControl attributesAccessControl,
                             boolean isAdmin)
    {
      this.title                   = title;
      this.fullMembers             = fullMembers;
      this.invitedCount            = invitedCount;
      this.memberCountSummary      = memberCountSummary;
      this.threadId                = threadId;
      this.recipient               = recipient;
      this.membershipAccessControl = membershipAccessControl;
      this.attributesAccessControl = attributesAccessControl;
      this.isAdmin                 = isAdmin;
    }

    String getTitle() {
      return title;
    }

    List<GroupMemberEntry.FullMember> getFullMembers() {
      return fullMembers;
    }

    int getInvitedCount() {
      return invitedCount;
    }

    String getMemberCountSummary() {
      return memberCountSummary;
    }

    long getThreadId() {
      return threadId;
    }

    Recipient getRecipient() {
      return recipient;
    }

    GroupAccessControl getEditMembershipRights() {
      return membershipAccessControl;
    }

    GroupAccessControl getEditGroupAttributesRights() {
      return attributesAccessControl;
    }

    boolean isAdmin() {
      return isAdmin;
    }
  }
}
