package org.thoughtcrime.securesms.recipients.ui.bottomsheet;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;

import java.util.Objects;

public final class RecipientBottomSheetDialog extends BottomSheetDialogFragment {

  private static final String ARGS_RECIPIENT_ID = "RECIPIENT_ID";
  private static final String ARGS_GROUP_ID     = "GROUP_ID";

  private RecipientDialogViewModel viewModel;
  private AvatarImageView          avatar;
  private TextView                 fullName;
  private Button                   messageButton;
  private Button                   callButton;
  private Button                   blockButton;
  private Button                   viewSafetyNumberButton;
  private Button                   makeGroupAdminButton;
  private Button                   removeAdminButton;
  private Button                   removeFromGroupButton;

  public static BottomSheetDialogFragment create(@NonNull RecipientId recipientId,
                                                 @Nullable GroupId groupId)
  {
    Bundle                     args     = new Bundle();
    RecipientBottomSheetDialog fragment = new RecipientBottomSheetDialog();

    args.putString(ARGS_RECIPIENT_ID, recipientId.serialize());
    if (groupId != null) {
      args.putString(ARGS_GROUP_ID, groupId.toString());
    }

    fragment.setArguments(args);

    return fragment;
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.recipient_bottom_sheet, container, false);

    avatar                 = view.findViewById(R.id.recipient_avatar);
    fullName               = view.findViewById(R.id.full_name);
    messageButton          = view.findViewById(R.id.message_button);
    callButton             = view.findViewById(R.id.call_button);
    blockButton            = view.findViewById(R.id.block_button);
    viewSafetyNumberButton = view.findViewById(R.id.view_safety_number_button);
    makeGroupAdminButton   = view.findViewById(R.id.make_group_admin_button);
    removeAdminButton      = view.findViewById(R.id.remove_group_admin_button);
    removeFromGroupButton  = view.findViewById(R.id.remove_from_group_button);

    return view;
  }

  @Override
  public void onAttachFragment(@NonNull Fragment childFragment) {
    super.onAttachFragment(childFragment);
  }

  @Override
  public void onResume() {
    super.onResume();

    Bundle      arguments   = requireArguments();
    RecipientId recipientId = RecipientId.from(Objects.requireNonNull(arguments.getString(ARGS_RECIPIENT_ID)));
    GroupId     groupId     = GroupId.parseNullableOrThrow(arguments.getString(ARGS_GROUP_ID));

    RecipientDialogViewModel.Factory factory = new RecipientDialogViewModel.Factory(requireContext(),recipientId, groupId);

    viewModel = ViewModelProviders.of(requireActivity(), factory).get(RecipientDialogViewModel.class);

    Recipient.live(recipientId).observe(getViewLifecycleOwner(), recipient -> {
        avatar.setRecipient(recipient);
        String name = recipient.getProfileName().toString();
        fullName.setText(name);
        fullName.setVisibility(TextUtils.isEmpty(name) ? View.INVISIBLE : View.VISIBLE);
      }
    );

    viewModel.getAdmin().observe(getViewLifecycleOwner(), admin -> {
      boolean locallyAdmin     = admin.first();
      boolean recipientIsAdmin = admin.second();

      if (locallyAdmin) {
        makeGroupAdminButton.setVisibility(recipientIsAdmin ? View.GONE : View.VISIBLE);
        removeAdminButton.setVisibility(recipientIsAdmin ? View.VISIBLE : View.GONE);
      } else {
        makeGroupAdminButton.setVisibility(View.GONE);
        removeAdminButton.setVisibility(View.GONE);
      }

      removeFromGroupButton.setVisibility(locallyAdmin ? View.VISIBLE : View.GONE);
    });

    viewModel.getIdentity().observe(getViewLifecycleOwner(), identityRecord -> {
      viewSafetyNumberButton.setVisibility(identityRecord != null ? View.VISIBLE : View.GONE);

      if (identityRecord != null) {
        viewSafetyNumberButton.setOnClickListener(view -> viewModel.viewSafetyNumber(identityRecord));
      }
    });

    messageButton.setOnClickListener(view -> viewModel.message());
    callButton.setOnClickListener(view -> viewModel.call());
    blockButton.setOnClickListener(view -> viewModel.block());
    makeGroupAdminButton.setOnClickListener(view -> viewModel.makeGroupAdmin());
    removeAdminButton.setOnClickListener(view -> viewModel.removeGroupAdmin());
    removeFromGroupButton.setOnClickListener(view -> viewModel.removeFromGroup());

  }
}
