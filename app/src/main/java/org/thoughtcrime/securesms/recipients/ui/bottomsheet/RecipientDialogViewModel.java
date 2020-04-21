package org.thoughtcrime.securesms.recipients.ui.bottomsheet;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.thoughtcrime.securesms.VerifyIdentityActivity;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DefaultValueLiveData;
import org.thoughtcrime.securesms.util.livedata.LiveDataPair;

public final class RecipientDialogViewModel extends ViewModel {

  private final MutableLiveData<Boolean>                         localIsAdmin     = new DefaultValueLiveData<>(false);
  private final MutableLiveData<Boolean>                         recipientIsAdmin = new DefaultValueLiveData<>(false);
  private final LiveDataPair<Boolean, Boolean>                   admin            = new LiveDataPair<>(localIsAdmin, recipientIsAdmin, false, false);
  private final MutableLiveData<IdentityDatabase.IdentityRecord> identity         = new MutableLiveData<>();
  @NonNull
  private final Context context;
  @NonNull
  private final RecipientId recipientId;

  private RecipientDialogViewModel(@NonNull Context context,
                                   @NonNull RecipientId recipientId,
                                   @Nullable GroupId groupId,
                                   @NonNull RecipientDialogRepository recipientDialogRepository)
  {
    this.context = context;
    this.recipientId = recipientId;
    if (groupId != null) {
      recipientDialogRepository.isAdminOfGroup(Recipient.self().getId(), localIsAdmin::setValue);
      recipientDialogRepository.isAdminOfGroup(recipientId, recipientIsAdmin::setValue);
    }

    recipientDialogRepository.getIdentity(identity::setValue);
  }

  /**
   * Local and remote admin status.
   */
  LiveDataPair<Boolean, Boolean> getAdmin() {
    return admin;
  }

  LiveData<IdentityDatabase.IdentityRecord> getIdentity() {
    return identity;
  }

  void message() {
    throw new AssertionError("NYI");
  }

  void call() {
    throw new AssertionError("NYI");
  }

  void block() {
    throw new AssertionError("NYI");
  }

  void viewSafetyNumber(IdentityDatabase.IdentityRecord identityRecord) {
    context.startActivity(VerifyIdentityActivity.newIntent(context, identityRecord));
  }

  void makeGroupAdmin() {
    throw new AssertionError("NYI");
  }

  void removeGroupAdmin() {
    throw new AssertionError("NYI");
  }

  void removeFromGroup() {
    throw new AssertionError("NYI");
  }

  public static class Factory implements ViewModelProvider.Factory {

    private final Context context;
    private final RecipientId recipientId;
    private final GroupId groupId;

    public Factory(@NonNull Context context, @NonNull RecipientId recipientId, @Nullable GroupId groupId) {
      this.context = context;
      this.recipientId = recipientId;
      this.groupId = groupId;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection unchecked
      return (T) new RecipientDialogViewModel(context, recipientId, groupId, new RecipientDialogRepository(context, recipientId, groupId));
    }
  }
}
