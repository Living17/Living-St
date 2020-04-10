package org.thoughtcrime.securesms.groups.ui.managegroup;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.annimon.stream.Stream;

import org.signal.storageservice.protos.groups.AccessControl;
import org.signal.zkgroup.VerificationFailedException;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MediaDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.loaders.MediaLoader;
import org.thoughtcrime.securesms.database.loaders.ThreadMediaLoader;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.ui.GroupMemberEntry;
import org.thoughtcrime.securesms.groups.ui.managegroup.dialogs.GroupRightsDialog;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;

import java.io.IOException;
import java.util.List;

public class ManageGroupViewModel extends ViewModel {

  private static final String TAG = Log.tag(ManageGroupViewModel.class);

  private final MutableLiveData<List<GroupMemberEntry.FullMember>> members                   = new MutableLiveData<>(    );
  private final MutableLiveData<String>                            title                     = new MutableLiveData<>(""  );
  private final MutableLiveData<Integer>                           pendingMemberCount        = new MutableLiveData<>(0   );
  private final MutableLiveData<String>                            memberCountSummary        = new MutableLiveData<>(""  );
  private final MutableLiveData<GroupViewState>                    groupViewState            = new MutableLiveData<>(null);
  private final MutableLiveData<GroupAccessControl>                editGroupAttributesRights = new MutableLiveData<>(null);

  public LiveData<List<GroupMemberEntry.FullMember>> getMembers() {
    return members;
  }

  public LiveData<Integer> getPendingMemberCount() {
    return pendingMemberCount;
  }

  public LiveData<String> getMemberCountSummary() {
    return memberCountSummary;
  }

  public LiveData<GroupViewState> getGroupViewState() {
    return groupViewState;
  }

  public LiveData<String> getTitle() {
    return title;
  }

  public LiveData<GroupAccessControl> getEditGroupAttributesRights() {
    return editGroupAttributesRights;
  }

  public void setGroupId(@NonNull Context context, @NonNull GroupId groupId) {
    SignalExecutors.BOUNDED.execute(() -> {
      GroupDatabase  groupDatabase  = DatabaseFactory.getGroupDatabase(context);
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

      List<GroupMemberEntry.FullMember> fullMembers = Stream.of(groupDatabase.getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_INCLUDING_SELF))
                                                            .map(GroupMemberEntry.FullMember::new)
                                                            .toList();

      members.postValue(fullMembers);
      // TODO: GV2 Efficiency, this is an expensive way to find out pending member count
      int invitedCount = groupDatabase.getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_AND_PENDING_INCLUDING_SELF).size() - fullMembers.size();
      pendingMemberCount.postValue(invitedCount);

      memberCountSummary.postValue(invitedCount > 0 ? context.getResources().getQuantityString(R.plurals.MessageRequestProfileView_members_and_invited, fullMembers.size(),
                                                        fullMembers.size(), invitedCount)
                                                    : context.getResources().getQuantityString(R.plurals.MessageRequestProfileView_members, fullMembers.size(),
                                                                      fullMembers.size()));

      Recipient groupRecipient = Recipient.externalGroup(context, groupId);
      long      threadId       = threadDatabase.getThreadIdFor(groupRecipient);
      boolean   isV2           = groupId.isV2();
      GroupDatabase.GroupRecord groupRecord = groupDatabase.requireGroup(groupId);

      title.postValue(groupRecord.getTitle());

      groupViewState.postValue(new GroupViewState(groupId,
                                                  threadId,
                                                  groupRecipient,
                                                  () -> new ThreadMediaLoader(context, threadId, MediaLoader.MediaType.GALLERY, MediaDatabase.Sorting.Newest).getCursor(),
                                                  isV2));

      editGroupAttributesRights.postValue(groupRecord.getAttributesAccessControl());
    });
  }

  void applyAttributesRightsChange(@NonNull Context context, @NonNull GroupAccessControl newRights) {
    // TODO GV2 apply the rights change to the group
    Log.d("ALAN", "RIGHTS CHANGE TO " + newRights);
    SignalExecutors.BOUNDED.execute(() -> {
      //noinspection ConstantConditions
      try {
        GroupManager.applyAttributesRightsChange(context, groupViewState.getValue().groupId.requireV2(), newRights);
      } catch (VerificationFailedException | InvalidGroupStateException | IOException e) {
        Log.w(TAG, e);
        //TODO: GV2 TOAST?
      }
    });
    editGroupAttributesRights.postValue(newRights);
  }

  public static final class GroupViewState {
    @NonNull private final GroupId       groupId;
             private final long          threadId;
    @NonNull private final Recipient     groupRecipient;
    @NonNull private final CursorFactory mediaCursorFactory;
             private final boolean       isV2;

    private GroupViewState(@NonNull GroupId groupId,
                           long threadId,
                           @NonNull Recipient groupRecipient,
                           @NonNull CursorFactory mediaCursorFactory,
                           boolean isV2)
    {
      this.groupId            = groupId;
      this.threadId           = threadId;
      this.groupRecipient     = groupRecipient;
      this.mediaCursorFactory = mediaCursorFactory;
      this.isV2               = isV2;
    }

    @NonNull
    public GroupId getGroupId() {
      return groupId;
    }

    public long getThreadId() {
      return threadId;
    }

    @NonNull
    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public CursorFactory getMediaCursorFactory() {
      return mediaCursorFactory;
    }

    public boolean isV2() {
      return isV2;
    }
  }

  public interface CursorFactory {
    Cursor create();
  }
}
