package org.thoughtcrime.securesms.recipients;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GroupRecordContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.SystemContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.TransparentContactPhoto;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.phonenumbers.NumberUtil;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.libsignal.util.guava.Preconditions;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class Recipient {

  public static final Recipient UNKNOWN = new Recipient(RecipientId.UNKNOWN);

  private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final RecipientId            id;
  private final boolean                resolving;
  private final Address                address;
  private final String                 uuid;
  private final String                 e164;
  private final String                 email;
  private final String                 groupId;
  private final List<Recipient>        participants;
  private final Optional<Long>         groupAvatarId;
  private final boolean                localNumber;
  private final boolean                blocked;
  private final long                   muteUntil;
  private final VibrateState           messageVibrate;
  private final VibrateState           callVibrate;
  private final Uri                    messageRingtone;
  private final Uri                    callRingtone;
  private final MaterialColor          color;
  private final boolean                seenInviteReminder;
  private final Optional<Integer>      defaultSubscriptionId;
  private final int                    expireMessages;
  private final RegisteredState        registered;
  private final byte[]                 profileKey;
  private final String                 name;
  private final Uri                    systemContactPhoto;
  private final String                 customLabel;
  private final Uri                    contactUri;
  private final String                 profileName;
  private final String                 profileAvatar;
  private final boolean                profileSharing;
  private final String                 notificationChannel;
  private final UnidentifiedAccessMode unidentifiedAccessMode;
  private final boolean                forceSmsSelection;
  private final boolean                uuidSupported;


  /**
   * Returns a {@link LiveRecipient}, which contains a {@link Recipient} that may or may not be
   * populated with data. However, you can observe the value that's returned to be notified when the
   * {@link Recipient} changes.
   */
  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull RecipientId id) {
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  /**
   * Returns a fully-populated {@link Recipient}. May hit the disk, and therefore should be
   * called on a background thread.
   */
  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull RecipientId id) {
    return live(id).resolve();
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. Convenience overload of
   * {@link #externalPush(Context, String, String)}
   */
  @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull Context context, @NonNull SignalServiceAddress signalServiceAddress) {
    return externalPush(context, signalServiceAddress.getUuid().orNull(), signalServiceAddress.getNumber().orNull());
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a UUID and phone number, creating one
   * in the database if necessary. We want both piece of information so we're able to associate them
   * both together, depending on which are available.
   *
   * In particular, while we'll eventually get the UUID of a user created via a phone number
   * (through a directory sync), the only way we can store the phone number is by retrieving it from
   * sent messages and whatnot. So we should store it when available.
   */
  @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull Context context, @Nullable String uuid, @Nullable String e164) {
    RecipientDatabase     db       = DatabaseFactory.getRecipientDatabase(context);
    Optional<RecipientId> uuidUser = uuid != null ? db.getByUuid(uuid) : Optional.absent();
    Optional<RecipientId> e164User = e164 != null ? db.getByE164(e164) : Optional.absent();

    if (uuidUser.isPresent()) {
      Recipient resolved = resolved(uuidUser.get());

      if (e164 != null && !resolved.getE164().isPresent()) {
        db.setPhoneNumber(uuidUser.get(), e164);
      }

      return resolved;
    } else if (e164User.isPresent()) {
      Recipient resolved = resolved(e164User.get());

      if (uuid != null && !resolved.getUuid().isPresent()) {
        db.markRegistered(e164User.get(), uuid);
      }

      return resolved;
    } else if (uuid != null) {
      RecipientId id = db.getOrInsertFromUuid(uuid);

      if (e164 != null) {
        db.setPhoneNumber(id, e164);
      }

      return resolved(id);
    } else if (e164 != null) {
      return resolved(db.getOrInsertFromE164(e164));
    } else {
      throw new AssertionError("You must provide either a UUID or phone number!");
    }
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a string identifier, creating one in
   * the database if necessary. The identifier may be a uuid, phone number, email,
   * or serialized groupId.
   *
   * If the identifier is a UUID of a Signal user, prefer using
   * {@link #externalPush(Context, String, String)} or its overload, as this will let us associate
   * the phone number with the recipient.
   */
  @WorkerThread
  public static @NonNull Recipient external(@NonNull Context context, @NonNull String identifier) {
    Preconditions.checkNotNull(identifier, "Address cannot be null!");

    RecipientDatabase db = DatabaseFactory.getRecipientDatabase(context);
    RecipientId       id = null;

    if (UUID_PATTERN.matcher(identifier).matches()) {
      id = db.getOrInsertFromUuid(identifier);
    } else if (GroupUtil.isEncodedGroup(identifier)) {
      id = db.getOrInsertFromGroupId(identifier);
    } else if (NumberUtil.isValidEmail(identifier)) {
      id = db.getOrInsertFromEmail(identifier);
    } else {
      String e164 = PhoneNumberFormatter.get(context).format(identifier);
      id = db.getOrInsertFromE164(e164);
    }

    return Recipient.resolved(id);
  }

  public static @NonNull Recipient self() {
    return ApplicationDependencies.getRecipientCache().getSelf();
  }

  Recipient(@NonNull RecipientId id) {
    this.id                     = id;
    this.resolving              = true;
    this.address                = null;
    this.uuid                   = null;
    this.participants           = Collections.emptyList();
    this.groupAvatarId          = Optional.absent();
    this.localNumber            = false;
    this.blocked                = false;
    this.muteUntil              = 0;
    this.messageVibrate         = VibrateState.DEFAULT;
    this.callVibrate            = VibrateState.DEFAULT;
    this.messageRingtone        = null;
    this.callRingtone           = null;
    this.color                  = null;
    this.seenInviteReminder     = true;
    this.defaultSubscriptionId  = Optional.absent();
    this.expireMessages         = 0;
    this.registered             = RegisteredState.UNKNOWN;
    this.profileKey             = null;
    this.name                   = null;
    this.systemContactPhoto     = null;
    this.customLabel            = null;
    this.contactUri             = null;
    this.profileName            = null;
    this.profileAvatar          = null;
    this.profileSharing         = false;
    this.notificationChannel    = null;
    this.unidentifiedAccessMode = UnidentifiedAccessMode.DISABLED;
    this.forceSmsSelection      = false;
    this.uuidSupported          = false;
  }

  Recipient(@NonNull RecipientId id, @NonNull RecipientDetails details) {
    this.id                     = id;
    this.resolving              = false;
    this.address                = details.address;
    this.uuid                   = details.uuid;
    this.e164                   = details.address.isPhone() ? details.address.toPhoneString() : null;
    this.email                  = details.address.isEmail() ? details.address.toEmailString() : null;
    this.groupId                = details.address.isGroup() ? details.address.toGroupString() : null;
    this.participants           = details.participants;
    this.groupAvatarId          = details.groupAvatarId;
    this.localNumber            = details.isLocalNumber;
    this.blocked                = details.blocked;
    this.muteUntil              = details.mutedUntil;
    this.messageVibrate         = details.messageVibrateState;
    this.callVibrate            = details.callVibrateState;
    this.messageRingtone        = details.messageRingtone;
    this.callRingtone           = details.callRingtone;
    this.color                  = details.color;
    this.seenInviteReminder     = details.seenInviteReminder;
    this.defaultSubscriptionId  = details.defaultSubscriptionId;
    this.expireMessages         = details.expireMessages;
    this.registered             = details.registered;
    this.profileKey             = details.profileKey;
    this.name                   = details.name;
    this.systemContactPhoto     = details.systemContactPhoto;
    this.customLabel            = details.customLabel;
    this.contactUri             = details.contactUri;
    this.profileName            = details.profileName;
    this.profileAvatar          = details.profileAvatar;
    this.profileSharing         = details.profileSharing;
    this.notificationChannel    = details.notificationChannel;
    this.unidentifiedAccessMode = details.unidentifiedAccessMode;
    this.forceSmsSelection      = details.forceSmsSelection;
    this.uuidSupported          = details.uuidSuported;
  }

  public @NonNull RecipientId getId() {
    return id;
  }

  public boolean isLocalNumber() {
    return localNumber;
  }

  public @Nullable Uri getContactUri() {
    return contactUri;
  }

  public @Nullable String getName() {
    if (this.name == null && isMmsGroup()) {
      List<String> names = new LinkedList<>();

      for (Recipient recipient : participants) {
        names.add(recipient.toShortString());
      }

      return Util.join(names, ", ");
    }

    return this.name;
  }

  public @NonNull String getDisplayName() {
    String name = getName();
    if (!TextUtils.isEmpty(name)) {
      return name;
    }

    String profileName = getProfileName();
    if (!TextUtils.isEmpty(profileName)) {
      return profileName;
    }

    return requireAddress().serialize();
  }

  public @NonNull MaterialColor getColor() {
    if      (isGroup())     return MaterialColor.GROUP;
    else if (color != null) return color;
    else if (name != null)  return ContactColors.generateFor(name);
    else                    return ContactColors.UNKNOWN_COLOR;
  }

  public @NonNull Address requireAddress() {

    if (resolvedAddress == null) {
      throw new MissingAddressError();
    }

    return resolvedAddress;
  }

  public @NonNull Optional<String> getUuid() {
    return Optional.fromNullable(uuid);
  }

  public @NonNull Optional<String> getE164() {
    return Optional.fromNullable(e164);
  }

  public @NonNull Optional<String> getEmail() {
    return Optional.fromNullable(email);
  }

  public @NonNull Optional<String> getGroupId() {
    return Optional.fromNullable(groupId);
  }

  public @NonNull String requireServiceIdentifier() {
    Recipient resolved = resolving ? resolve() : this;
    return resolved.getUuid().or(requireAddress().serialize());
  }

  public @Nullable String getCustomLabel() {
    return customLabel;
  }

  public Optional<Integer> getDefaultSubscriptionId() {
    return defaultSubscriptionId;
  }

  public @Nullable String getProfileName() {
    return profileName;
  }

  public @Nullable String getProfileAvatar() {
    return profileAvatar;
  }

  public boolean isProfileSharing() {
    return profileSharing;
  }

  public boolean isGroup() {
    return groupId != null;
  }

  public boolean isMmsGroup() {
    return groupId != null && GroupUtil.isMmsGroup(groupId);
  }

  public boolean isPushGroup() {
    return groupId != null && !GroupUtil.isMmsGroup(groupId);
  }

  public @NonNull List<Recipient> getParticipants() {
    return new ArrayList<>(participants);
  }

  public @NonNull String toShortString() {
    return getName() == null ? address == null ? ""
                                               : address.serialize()
                             : getName();
  }

  public @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getFallbackContactPhoto().asDrawable(context, getColor().toAvatarColor(context), inverted);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto() {
    if      (localNumber)              return new ResourceContactPhoto(R.drawable.ic_note_to_self);
    if      (isResolving())            return new TransparentContactPhoto();
    else if (isGroup())                return new ResourceContactPhoto(R.drawable.ic_group_white_24dp, R.drawable.ic_group_large);
    else if (!TextUtils.isEmpty(name)) return new GeneratedContactPhoto(name, R.drawable.ic_profile_default);
    else                               return new ResourceContactPhoto(R.drawable.ic_profile_default, R.drawable.ic_person_large);
  }

  public @Nullable ContactPhoto getContactPhoto() {
    if      (localNumber)                            return null;
    else if (isGroup() && groupAvatarId.isPresent()) return new GroupRecordContactPhoto(address, groupAvatarId.get());
    else if (systemContactPhoto != null)             return new SystemContactPhoto(address, systemContactPhoto, 0);
    else if (profileAvatar != null)                  return new ProfileContactPhoto(id, profileAvatar);
    else                                             return null;
  }

  public @Nullable Uri getMessageRingtone() {
    if (messageRingtone != null && messageRingtone.getScheme() != null && messageRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return messageRingtone;
  }

  public @Nullable Uri getCallRingtone() {
    if (callRingtone != null && callRingtone.getScheme() != null && callRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return callRingtone;
  }

  public boolean isMuted() {
    return System.currentTimeMillis() <= muteUntil;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public @NonNull VibrateState getMessageVibrate() {
    return messageVibrate;
  }

  public @NonNull VibrateState getCallVibrate() {
    return callVibrate;
  }

  public int getExpireMessages() {
    return expireMessages;
  }

  public boolean hasSeenInviteReminder() {
    return seenInviteReminder;
  }

  public @NonNull RegisteredState getRegistered() {
    if      (isPushGroup()) return RegisteredState.REGISTERED;
    else if (isMmsGroup())  return RegisteredState.NOT_REGISTERED;

    return registered;
  }

  public @Nullable String getNotificationChannel() {
    return !NotificationChannels.supported() ? null : notificationChannel;
  }

  public boolean isForceSmsSelection() {
    return forceSmsSelection;
  }

  /**
   * @return True if this recipient can support receiving UUID-only messages, otherwise false.
   */
  public boolean isUuidSupported() {
    // TODO [greyson] Don't merge this
//    return uuidSupported;
    return true;
  }

  public @Nullable byte[] getProfileKey() {
    return profileKey;
  }

  public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
    return unidentifiedAccessMode;
  }

  public boolean isSystemContact() {
    return contactUri != null;
  }

  public Recipient resolve() {
    if (resolving) {
      return live().resolve();
    } else {
      return this;
    }
  }

  public boolean isResolving() {
    return resolving;
  }

  public @NonNull LiveRecipient live() {
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Recipient recipient = (Recipient) o;
    return id.equals(recipient.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  private static class MissingAddressError extends AssertionError {
  }
}
