/*
 * Copyright (C) 2012 Moxie Marlinpsike
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database.model;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;

import org.GV2DebugFlags;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class MessageRecord extends DisplayRecord {

  private static final String TAG = Log.tag(MessageRecord.class);

  private final Recipient                 individualRecipient;
  private final int                       recipientDeviceId;
  private final long                      id;
  private final List<IdentityKeyMismatch> mismatches;
  private final List<NetworkFailure>      networkFailures;
  private final int                       subscriptionId;
  private final long                      expiresIn;
  private final long                      expireStarted;
  private final boolean                   unidentified;
  private final List<ReactionRecord>      reactions;
  private final long                      serverTimestamp;
  private final boolean                   remoteDelete;

  MessageRecord(long id, String body, Recipient conversationRecipient,
                Recipient individualRecipient, int recipientDeviceId,
                long dateSent, long dateReceived, long dateServer, long threadId,
                int deliveryStatus, int deliveryReceiptCount, long type,
                List<IdentityKeyMismatch> mismatches,
                List<NetworkFailure> networkFailures,
                int subscriptionId, long expiresIn, long expireStarted,
                int readReceiptCount, boolean unidentified,
                @NonNull List<ReactionRecord> reactions, boolean remoteDelete)
  {
    super(body, conversationRecipient, dateSent, dateReceived,
          threadId, deliveryStatus, deliveryReceiptCount, type, readReceiptCount);
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.recipientDeviceId   = recipientDeviceId;
    this.mismatches          = mismatches;
    this.networkFailures     = networkFailures;
    this.subscriptionId      = subscriptionId;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.unidentified        = unidentified;
    this.reactions           = reactions;
    this.serverTimestamp     = dateServer;
    this.remoteDelete        = remoteDelete;
  }

  public abstract boolean isMms();
  public abstract boolean isMmsNotification();

  public boolean isSecure() {
    return MmsSmsColumns.Types.isSecureType(type);
  }

  public boolean isLegacyMessage() {
    return MmsSmsColumns.Types.isLegacyType(type);
  }

  @Override
  public SpannableString getDisplayBody(@NonNull Context context) {
    if (isGroupUpdate() && isGroupV2()) {
      return new SpannableString(getGv2Description(context));
    } else if (isGroupUpdate() && isOutgoing()) {
      return new SpannableString(context.getString(R.string.MessageRecord_you_updated_group));
    } else if (isGroupUpdate()) {
      return new SpannableString(GroupUtil.getDescription(context, getBody(), false).toString(getIndividualRecipient()));
    } else if (isGroupQuit() && isOutgoing()) {
      return new SpannableString(context.getString(R.string.MessageRecord_left_group));
    } else if (isGroupQuit()) {
      return new SpannableString(context.getString(R.string.ConversationItem_group_action_left, getIndividualRecipient().toShortString(context)));
    } else if (isIncomingCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_s_called_you, getIndividualRecipient().toShortString(context)));
    } else if (isOutgoingCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_you_called));
    } else if (isMissedCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_missed_call));
    } else if (isJoined()) {
      return new SpannableString(context.getString(R.string.MessageRecord_s_joined_signal, getIndividualRecipient().toShortString(context)));
    } else if (isExpirationTimerUpdate()) {
      int seconds = (int)(getExpiresIn() / 1000);
      if (seconds <= 0) {
        return isOutgoing() ? new SpannableString(context.getString(R.string.MessageRecord_you_disabled_disappearing_messages))
                            : new SpannableString(context.getString(R.string.MessageRecord_s_disabled_disappearing_messages, getIndividualRecipient().toShortString(context)));
      }
      String time = ExpirationUtil.getExpirationDisplayValue(context, seconds);
      return isOutgoing() ? new SpannableString(context.getString(R.string.MessageRecord_you_set_disappearing_message_time_to_s, time))
                          : new SpannableString(context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, getIndividualRecipient().toShortString(context), time));
    } else if (isIdentityUpdate()) {
      return new SpannableString(context.getString(R.string.MessageRecord_your_safety_number_with_s_has_changed, getIndividualRecipient().toShortString(context)));
    } else if (isIdentityVerified()) {
      if (isOutgoing()) return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified, getIndividualRecipient().toShortString(context)));
      else              return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified_from_another_device, getIndividualRecipient().toShortString(context)));
    } else if (isIdentityDefault()) {
      if (isOutgoing()) return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified, getIndividualRecipient().toShortString(context)));
      else              return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified_from_another_device, getIndividualRecipient().toShortString(context)));
    }

    return new SpannableString(getBody());
  }

  private String getGv2Description(@NonNull Context context) {
    try {
      byte[]                  decoded                 = Base64.decode(getBody());
      DecryptedGroupV2Context decryptedGroupV2Context = DecryptedGroupV2Context.parseFrom(decoded);

      if (decryptedGroupV2Context.hasChange() && decryptedGroupV2Context.getGroupState().getVersion() > 0) {
        DecryptedGroupChange change = decryptedGroupV2Context.getChange();

        return formatGv2Change(context, change);
      } else {
        return formatNewGv2Group(context, decryptedGroupV2Context.getGroupState());
      }
    } catch (IOException e) {
      Log.w(TAG, "GV2 Message update detail could not be read", e);
      return context.getString(R.string.MessageRecord_group_updated);
    }
  }

  /**
   * Typically this can happen only at invite time or new groups.
   */
  private String formatNewGv2Group(@NonNull Context context, @NonNull DecryptedGroup group) {
    UUID       self           = Recipient.self().getUuid().get();
    ByteString selfByteString = UuidUtil.toByteString(self);

    if (group.getVersion() == 0) {
      Optional<DecryptedMember> foundingMember = DecryptedGroupUtil.firstMember(group.getMembersList());
      if (foundingMember.isPresent()) {
        if (selfByteString.equals(foundingMember.get().getUuid())) {
          return context.getString(R.string.MessageRecord_you_created_the_group);
        } else {
          if (DecryptedGroupUtil.findMemberByUuid(group.getMembersList(), self).isPresent()) {
            return context.getString(R.string.MessageRecord_s_added_you, formatWho(context, foundingMember.get().getUuid()));
          } else {
            Optional<DecryptedPendingMember> selfPending = DecryptedGroupUtil.findPendingByUuid(group.getPendingMembersList(), Recipient.self().getUuid().get());

            if (selfPending.isPresent()) {
              return context.getString(R.string.MessageRecord_s_invited_you_to_the_group, formatWho(context, selfPending.get().getAddedByUuid()));
            } else {
              Log.w(TAG, "GV2 Message record not a member or invitee");
              return context.getString(R.string.MessageRecord_group_updated);
            }
          }
        }
      } else {
        Log.w(TAG, "No founding member found on revision 0");
        return context.getString(R.string.MessageRecord_group_updated);
      }
    } else {
      Optional<DecryptedPendingMember> selfPending = DecryptedGroupUtil.findPendingByUuid(group.getPendingMembersList(), Recipient.self().getUuid().get());

      if (selfPending.isPresent()) {
        return context.getString(R.string.MessageRecord_s_invited_you_to_the_group, formatWho(context, selfPending.get().getAddedByUuid()));
      }

      Log.w(TAG, "GV2 Message record seen without change at version > 0");

      if (DecryptedGroupUtil.findMemberByUuid(group.getMembersList(), self).isPresent()) {
        return context.getString(R.string.MessageRecord_you_joined_the_group);
      }

      Log.w(TAG, "GV2 Message record not a member or invitee");
      return context.getString(R.string.MessageRecord_group_updated);
    }
  }

  private String formatWho(Context context, ByteString uuidBytes) {
    UUID uuid = UuidUtil.fromByteString(uuidBytes);

    return formatWho(context, uuid);
  }

  private String formatWho(Context context, UUID uuid) {
    return DatabaseFactory.getRecipientDatabase(context)
                          .getByUuid(uuid)
                          .transform(Recipient::resolved)
                          .transform(recipient -> {
                            if (recipient.equals(Recipient.self())) {
                              return "you!";
                            } else {
                              return recipient.toShortString(context);
                            }
                          })
                          .or(() -> getIndividualRecipient().toShortString(context));
  }

  /**
   * Describes a UUID by it's corresponding recipient's {@link Recipient#toShortString}.
   */
  private static class ShortStringDescriptionStrategy implements GroupsV2UpdateMessageProducer.DescribeMemberStrategy {

    private final Context           context;
    private final RecipientDatabase recipientDatabase;

   ShortStringDescriptionStrategy(@NonNull Context context) {
      this.context           = context;
      this.recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
   }

   @NonNull
   @Override
   public String describe(@NonNull UUID uuid) {
     return recipientDatabase.getByUuid(uuid)
                             .transform(rc -> Recipient.resolved(rc).toShortString(context))
                             .or(context.getString(R.string.MessageRecord_unknown));
   }
 }

 private String formatGv2Change(Context context, DecryptedGroupChange change) {
    List<String>  strings = new GroupsV2UpdateMessageProducer(context, new ShortStringDescriptionStrategy(context), Recipient.self().getUuid().get()).describeChange(change);
    StringBuilder sb      = new StringBuilder();

    if (GV2DebugFlags.EXTRA_VISUAL_LOGGING_AND199) {
      sb.append(String.format("GV2 revision " + change.getVersion() + "%n"));
    }

    for (int i = 0; i < strings.size(); i++) {
      if (i > 0) sb.append('\n');
      sb.append(strings.get(i));
    }

    return sb.toString();
  }

  public long getId() {
    return id;
  }

  public boolean isPush() {
    return SmsDatabase.Types.isPushType(type) && !SmsDatabase.Types.isForcedSms(type);
  }

  public long getTimestamp() {
    if (isPush() && getDateSent() < getDateReceived()) {
      return getDateSent();
    }
    return getDateReceived();
  }

  public long getServerTimestamp() {
    return serverTimestamp;
  }

  public boolean isForcedSms() {
    return SmsDatabase.Types.isForcedSms(type);
  }

  public boolean isIdentityVerified() {
    return SmsDatabase.Types.isIdentityVerified(type);
  }

  public boolean isIdentityDefault() {
    return SmsDatabase.Types.isIdentityDefault(type);
  }

  public boolean isIdentityMismatchFailure() {
    return mismatches != null && !mismatches.isEmpty();
  }

  public boolean isBundleKeyExchange() {
    return SmsDatabase.Types.isBundleKeyExchange(type);
  }

  public boolean isContentBundleKeyExchange() {
    return SmsDatabase.Types.isContentBundleKeyExchange(type);
  }

  public boolean isIdentityUpdate() {
    return SmsDatabase.Types.isIdentityUpdate(type);
  }

  public boolean isCorruptedKeyExchange() {
    return SmsDatabase.Types.isCorruptedKeyExchange(type);
  }

  public boolean isInvalidVersionKeyExchange() {
    return SmsDatabase.Types.isInvalidVersionKeyExchange(type);
  }

  public boolean isUpdate() {
    return isGroupAction() || isJoined() || isExpirationTimerUpdate() || isCallLog() ||
           isEndSession()  || isIdentityUpdate() || isIdentityVerified() || isIdentityDefault();
  }

  public boolean isMediaPending() {
    return false;
  }

  public Recipient getIndividualRecipient() {
    return individualRecipient.live().get();
  }

  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  public long getType() {
    return type;
  }

  public List<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return mismatches;
  }

  public List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public boolean hasNetworkFailures() {
    return networkFailures != null && !networkFailures.isEmpty();
  }

  protected SpannableString emphasisAdded(String sequence) {
    SpannableString spannable = new SpannableString(sequence);
    spannable.setSpan(new RelativeSizeSpan(0.9f), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return spannable;
  }

  public boolean equals(Object other) {
    return other != null                              &&
           other instanceof MessageRecord             &&
           ((MessageRecord) other).getId() == getId() &&
           ((MessageRecord) other).isMms() == isMms();
  }

  public int hashCode() {
    return (int)getId();
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public long getExpireStarted() {
    return expireStarted;
  }

  public boolean isUnidentified() {
    return unidentified;
  }

  public boolean isViewOnce() {
    return false;
  }

  public boolean isRemoteDelete() {
    return remoteDelete;
  }

  public @NonNull List<ReactionRecord> getReactions() {
    return reactions;
  }
}
