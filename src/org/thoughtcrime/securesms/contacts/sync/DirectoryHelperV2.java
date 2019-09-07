package org.thoughtcrime.securesms.contacts.sync;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.contacts.ContactsDatabase;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.BulkOperationsHandle;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.push.IasTrustStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingJoinedMessage;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.internal.contacts.crypto.Quote;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedQuoteException;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class DirectoryHelperV2 {

  private static final String TAG = Log.tag(DirectoryHelperV2.class);

  @WorkerThread
  static void refreshDirectory(@NonNull Context context, boolean notifyOfNewUsers) throws IOException {
    if (!Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) {
      Log.w(TAG, "We don't have contact permissions! Can't refresh directory!");
      return;
    }

    RecipientDatabase recipientDatabase                       = DatabaseFactory.getRecipientDatabase(context);
    Stream<String>    eligibleRecipientDatabaseContactNumbers = Stream.of(recipientDatabase.getAllAddresses()).filter(Address::isPhone).map(Address::toPhoneString);
    Stream<String>    eligibleSystemDatabaseContactNumbers    = Stream.of(ContactAccessor.getInstance().getAllContactsWithNumbers(context)).map(Address::serialize);
    Set<String>       eligibleContactNumbers                  = Stream.concat(eligibleRecipientDatabaseContactNumbers, eligibleSystemDatabaseContactNumbers).collect(Collectors.toSet());
    Set<String>       sanitizedNumbers                        = sanitizeNumbers(eligibleContactNumbers);

    SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
    KeyStore                    iasKeyStore    = getIasKeyStore(context);

    try {
      Map<String, UUID>        results   = accountManager.getRegisteredUsers(iasKeyStore, sanitizedNumbers, BuildConfig.MRENCLAVE);
      Map<RecipientId, String> uuidMap   = new HashMap<>();

      for (Map.Entry<String, UUID> entry : results.entrySet()) {
        RecipientId id   = recipientDatabase.getOrInsertFromE164(entry.getKey());
        String      uuid = entry.getValue().toString();

        uuidMap.put(id, uuid);
      }

      Set<String>      activeNumbers = results.keySet();
      Set<RecipientId> activeIds     = uuidMap.keySet();
      Set<RecipientId> inactiveIds   = Stream.of(sanitizedNumbers)
                                             .filterNot(activeNumbers::contains)
                                             .map(recipientDatabase::getOrInsertFromE164)
                                             .collect(Collectors.toSet());

      recipientDatabase.bulkUpdatedRegisteredStatus(uuidMap, inactiveIds);

      updateContactsDatabase(context, activeIds, true);

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationContext.getInstance(context).getJobManager().add(new MultiDeviceContactUpdateJob());
      }

      if (TextSecurePreferences.hasSuccessfullyRetrievedDirectory(context) && notifyOfNewUsers) {
        Set<RecipientId>  existingSignalIds = new HashSet<>(recipientDatabase.getRegistered());
        Set<RecipientId>  existingSystemIds = new HashSet<>(recipientDatabase.getSystemContacts());
        List<RecipientId> newlyActiveIds    = Stream.of(activeIds)
                                                    .filterNot(existingSignalIds::contains)
                                                    .filter(existingSystemIds::contains)
                                                    .toList();

        notifyNewUsers(context, newlyActiveIds);
      } else {
        TextSecurePreferences.setHasSuccessfullyRetrievedDirectory(context, true);
      }
    } catch (SignatureException | UnauthenticatedQuoteException | UnauthenticatedResponseException | Quote.InvalidQuoteFormatException e) {
      Log.w(TAG, "Attestation error.", e);
      throw new IOException(e);
    }
  }

  @WorkerThread
  static RegisteredState refreshDirectoryFor(@NonNull Context context, @NonNull Recipient recipient) throws IOException {
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    Set<String>       sanitizedNumbers  = sanitizeNumbers(Collections.singleton(recipient.requireAddress().serialize()));

    if (sanitizedNumbers.isEmpty()) {
      Log.w(TAG, "Contact had invalid address. Marking as not registered.");
      recipientDatabase.markUnregistered(recipient.getId());
      return RegisteredState.NOT_REGISTERED;
    }

    boolean                     activeUser        = recipient.resolve().getRegistered() == RegisteredState.REGISTERED;
    String                      sanitized         = sanitizedNumbers.iterator().next();
    KeyStore                    iasKeyStore       = getIasKeyStore(context);
    SignalServiceAccountManager accountManager    = ApplicationDependencies.getSignalServiceAccountManager();

    try {
      Map<String, UUID> result = accountManager.getRegisteredUsers(iasKeyStore, Collections.singleton(sanitized), BuildConfig.MRENCLAVE);

      if (result.size() == 1) {
        recipientDatabase.markRegistered(recipient.getId(), result.values().iterator().next().toString());

        if (!Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) {
          updateContactsDatabase(context, Collections.singletonList(recipient.getId()), false);
        }

        if (!activeUser && TextSecurePreferences.isMultiDevice(context)) {
          ApplicationContext.getInstance(context).getJobManager().add(new MultiDeviceContactUpdateJob());
        }

        if (!activeUser && recipient.resolve().isSystemContact()) {
          notifyNewUsers(context, Collections.singletonList(recipient.getId()));
        }

        return RegisteredState.REGISTERED;
      } else {
        recipientDatabase.setRegistered(recipient.getId(), RegisteredState.NOT_REGISTERED);
        return RegisteredState.NOT_REGISTERED;
      }
    } catch (SignatureException | UnauthenticatedQuoteException | UnauthenticatedResponseException | Quote.InvalidQuoteFormatException e) {
      Log.w(TAG, "Attestation error.", e);
      throw new IOException(e);
    }
  }

  private static void updateContactsDatabase(@NonNull Context context, @NonNull Collection<RecipientId> activeIds, boolean removeMissing) {
    AccountHolder account = getOrCreateSystemAccount(context);

    if (account == null) {
      Log.w(TAG, "Failed to create an account!");
      return;
    }

    try {
      List<Address>     activeAddresses   = Stream.of(activeIds).map(Recipient::resolved).map(Recipient::requireAddress).toList();
      RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
      ContactsDatabase  contactsDatabase  = DatabaseFactory.getContactsDatabase(context);

      contactsDatabase.removeDeletedRawContacts(account.getAccount());
      contactsDatabase.setRegisteredUsers(account.getAccount(), activeAddresses, removeMissing);

      Cursor               cursor = ContactAccessor.getInstance().getAllSystemContacts(context);
      BulkOperationsHandle handle = recipientDatabase.resetAllSystemContactInfo();

      try {
        while (cursor != null && cursor.moveToNext()) {
          String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));

          if (!TextUtils.isEmpty(number)) {
            RecipientId recipientId     = Recipient.external(context, number).getId();
            String      displayName     = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
            String      contactPhotoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI   ));
            String      contactLabel    = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL       ));
            int         phoneType       = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE));
            Uri         contactUri      = ContactsContract.Contacts.getLookupUri(cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)),
                                                                                 cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)));

            handle.setSystemContactInfo(recipientId, displayName, contactPhotoUri, contactLabel, phoneType, contactUri.toString());
          }
        }
      } finally {
        handle.finish();
      }

      if (NotificationChannels.supported()) {
        try (RecipientDatabase.RecipientReader recipients = DatabaseFactory.getRecipientDatabase(context).getRecipientsWithNotificationChannels()) {
          Recipient recipient;
          while ((recipient = recipients.getNext()) != null) {
            NotificationChannels.updateContactChannelName(context, recipient);
          }
        }
      }
    } catch (RemoteException | OperationApplicationException e) {
      Log.w(TAG, "Failed to update contacts.", e);
    }
  }

  private static void notifyNewUsers(@NonNull  Context context,
                                     @NonNull  List<RecipientId> newUsers)
  {
    if (!TextSecurePreferences.isNewContactsNotificationEnabled(context)) return;

    for (RecipientId newUser: newUsers) {
      Recipient recipient = Recipient.resolved(newUser);
      if (!SessionUtil.hasSession(context, recipient.requireAddress()) && !recipient.isLocalNumber()) {
        IncomingJoinedMessage message      = new IncomingJoinedMessage(newUser);
        Optional<InsertResult> insertResult = DatabaseFactory.getSmsDatabase(context).insertMessageInbox(message);

        if (insertResult.isPresent()) {
          int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
          if (hour >= 9 && hour < 23) {
            MessageNotifier.updateNotification(context, insertResult.get().getThreadId(), true);
          } else {
            MessageNotifier.updateNotification(context, insertResult.get().getThreadId(), false);
          }
        }
      }
    }
  }

  private static @Nullable AccountHolder getOrCreateSystemAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account[]      accounts       = accountManager.getAccountsByType("org.thoughtcrime.securesms");

    AccountHolder account;

    if (accounts.length == 0) account = createAccount(context);
    else                      account = new AccountHolder(accounts[0], false);

    if (account != null && !ContentResolver.getSyncAutomatically(account.getAccount(), ContactsContract.AUTHORITY)) {
      ContentResolver.setSyncAutomatically(account.getAccount(), ContactsContract.AUTHORITY, true);
    }

    return account;
  }

  private static @Nullable AccountHolder createAccount(Context context) {
    AccountManager accountManager = AccountManager.get(context);
    Account        account        = new Account(context.getString(R.string.app_name), "org.thoughtcrime.securesms");

    if (accountManager.addAccountExplicitly(account, null, null)) {
      Log.i(TAG, "Created new account...");
      ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
      return new AccountHolder(account, true);
    } else {
      Log.w(TAG, "Failed to create account!");
      return null;
    }
  }

  private static Set<String> sanitizeNumbers(@NonNull Set<String> numbers) {
    return Stream.of(numbers).filter(number -> {
      try {
        return number.startsWith("+") && number.length() > 1 && number.charAt(1) != '0' && Long.parseLong(number.substring(1)) > 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }).collect(Collectors.toSet());
  }

  private static boolean isAttestationError(Throwable e) {
    return e instanceof CertificateException             ||
           e instanceof SignatureException               ||
           e instanceof UnauthenticatedQuoteException    ||
           e instanceof UnauthenticatedResponseException ||
           e instanceof Quote.InvalidQuoteFormatException;
  }

  private static KeyStore getIasKeyStore(@NonNull Context context) {
    try {
      TrustStore contactTrustStore = new IasTrustStore(context);

      KeyStore keyStore = KeyStore.getInstance("BKS");
      keyStore.load(contactTrustStore.getKeyStoreInputStream(), contactTrustStore.getKeyStorePassword().toCharArray());

      return keyStore;
    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static String buildErrorReason(@Nullable Throwable t) {
    if (t == null) {
      return "null";
    }

    String       rawString = android.util.Log.getStackTraceString(t);
    List<String> lines     = Arrays.asList(rawString.split("\\n"));

    String errorString;

    if (lines.size() > 1) {
      errorString = t.getClass().getName() + "\n" + Util.join(lines.subList(1, lines.size()), "\n");
    } else {
      errorString = t.getClass().getName();
    }

    if (errorString.length() > 1000) {
      return errorString.substring(0, 1000);
    } else {
      return errorString;
    }
  }

  private static class AccountHolder {

    private final boolean fresh;
    private final Account account;

    private AccountHolder(Account account, boolean fresh) {
      this.fresh   = fresh;
      this.account = account;
    }

    @SuppressWarnings("unused")
    public boolean isFresh() {
      return fresh;
    }

    public Account getAccount() {
      return account;
    }

  }
}
