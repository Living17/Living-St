package org.thoughtcrime.securesms.groups.ui.managegroup;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.MediaDatabase;
import org.thoughtcrime.securesms.database.loaders.MediaLoader;
import org.thoughtcrime.securesms.database.loaders.ThreadMediaLoader;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.GroupMemberEntry;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.List;

public class ManageGroupViewModel extends ViewModel {

  private static final String TAG = Log.tag(ManageGroupViewModel.class);

  private final Context                                            context;
  private final ManageGroupRepository                              manageGroupRepository;
  private final MutableLiveData<String>                            title                     = new MutableLiveData<>(""   );
  private final MutableLiveData<String>                            memberCountSummary        = new MutableLiveData<>(""   );
  private final MutableLiveData<List<GroupMemberEntry.FullMember>> members                   = new MutableLiveData<>(     );
  private final MutableLiveData<Integer>                           pendingMemberCount        = new MutableLiveData<>(0    );
  private final MutableLiveData<GroupViewState>                    groupViewState            = new MutableLiveData<>(null );
  private final MutableLiveData<GroupAccessControl>                editMembershipRights      = new MutableLiveData<>(null );
  private final MutableLiveData<GroupAccessControl>                editGroupAttributesRights = new MutableLiveData<>(null );
  private final MutableLiveData<Boolean>                           isAdmin                   = new MutableLiveData<>(false);

  private ManageGroupViewModel(@NonNull Context context, @NonNull ManageGroupRepository manageGroupRepository) {
    this.context               = context;
    this.manageGroupRepository = manageGroupRepository;

    manageGroupRepository.getGroupState(this::groupStateLoaded);
  }

  @WorkerThread
  private void groupStateLoaded(@NonNull ManageGroupRepository.GroupStateResult groupStateResult) {
    title.postValue(groupStateResult.getTitle());
    members.postValue(groupStateResult.getFullMembers());
    memberCountSummary.postValue(groupStateResult.getMemberCountSummary());
    pendingMemberCount.postValue(groupStateResult.getInvitedCount());
    groupViewState.postValue(new GroupViewState(groupStateResult.getThreadId(),
                                                groupStateResult.getRecipient(),
                                                () -> new ThreadMediaLoader(context, groupStateResult.getThreadId(), MediaLoader.MediaType.GALLERY, MediaDatabase.Sorting.Newest).getCursor()
                                                 ));
    editMembershipRights.postValue(groupStateResult.getEditMembershipRights());
    editGroupAttributesRights.postValue(groupStateResult.getEditGroupAttributesRights());
    isAdmin.postValue(groupStateResult.isAdmin());
  }

  LiveData<List<GroupMemberEntry.FullMember>> getMembers() {
    return members;
  }

  LiveData<Integer> getPendingMemberCount() {
    return pendingMemberCount;
  }

  LiveData<String> getMemberCountSummary() {
    return memberCountSummary;
  }

  LiveData<GroupViewState> getGroupViewState() {
    return groupViewState;
  }

  LiveData<String> getTitle() {
    return title;
  }

  LiveData<GroupAccessControl> getMembershipRights() {
    return editMembershipRights;
  }

  LiveData<GroupAccessControl> getEditGroupAttributesRights() {
    return editGroupAttributesRights;
  }

  LiveData<Boolean> getIsAdmin() {
    return isAdmin;
  }

  void applyMembershipRightsChange(@NonNull GroupAccessControl newRights) {
    manageGroupRepository.applyMembershipRightsChange(newRights, editMembershipRights::postValue);
  }

  void applyAttributesRightsChange(@NonNull GroupAccessControl newRights) {
    manageGroupRepository.applyAttributesRightsChange(newRights, editGroupAttributesRights::postValue);
  }

  static final class GroupViewState {

             private final long          threadId;
    @NonNull private final Recipient     groupRecipient;
    @NonNull private final CursorFactory mediaCursorFactory;

    private GroupViewState(long threadId,
                           @NonNull Recipient groupRecipient,
                           @NonNull CursorFactory mediaCursorFactory)
    {
      this.threadId           = threadId;
      this.groupRecipient     = groupRecipient;
      this.mediaCursorFactory = mediaCursorFactory;
    }

    public long getThreadId() {
      return threadId;
    }

    @NonNull
    Recipient getGroupRecipient() {
      return groupRecipient;
    }

    CursorFactory getMediaCursorFactory() {
      return mediaCursorFactory;
    }
  }

  interface CursorFactory {
    Cursor create();
  }

  public static class Factory implements ViewModelProvider.Factory {
    private final Context    context;
    private final GroupId.V2 groupId;

    public Factory(@NonNull Context context, @NonNull GroupId.V2 groupId) {
      this.context = context;
      this.groupId = groupId;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection unchecked
      return (T) new ManageGroupViewModel(context, new ManageGroupRepository(context.getApplicationContext(), groupId));
    }
  }
}
