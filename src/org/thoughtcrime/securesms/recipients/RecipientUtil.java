package org.thoughtcrime.securesms.recipients;

import androidx.annotation.NonNull;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

public class RecipientUtil {
  public static @NonNull SignalServiceAddress toSignalServiceAddress(@NonNull Recipient recipient) {
    return new SignalServiceAddress(Optional.of(recipient.requireUuid()), Optional.fromNullable(recipient.resolve().getE164().orNull()));
  }
}
