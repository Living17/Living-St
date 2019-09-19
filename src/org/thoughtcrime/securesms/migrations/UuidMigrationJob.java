package org.thoughtcrime.securesms.migrations;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.contacts.sync.DirectoryHelper;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;

import java.io.IOException;

/**
 * Couple migrations steps need to happen after we move to UUIDS.
 *  - We need to get our own UUID.
 *  - We need to fetch the new UUID sealed sender cert.
 *  - We need to do a directory sync so we can guarantee that all active users have UUIDs.
 */
public class UuidMigrationJob extends MigrationJob {

  public static final String KEY = "UuidMigrationJob";

  UuidMigrationJob() {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY).build());
  }

  private UuidMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  boolean isUiBlocking() {
    return false;
  }

  @Override
  void performMigration() throws Exception {
    fetchOwnUuid(context);
    rotateSealedSenderCerts(context);
    // TODO [greyson] Do self profile fetch to determine if linked devices support UUID so we know if we can use the new UD cert or not
    DirectoryHelper.refreshDirectory(context, false);
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return e instanceof IOException;
  }

  private static void fetchOwnUuid(@NonNull Context context) throws IOException {
    String localUuid = ApplicationDependencies.getSignalServiceAccountManager().getOwnUuid();
    DatabaseFactory.getRecipientDatabase(context).markRegistered(Recipient.self().getId(), localUuid);
  }

  private static void rotateSealedSenderCerts(@NonNull Context context) throws IOException {
    SignalServiceAccountManager accountManager    = ApplicationDependencies.getSignalServiceAccountManager();
    byte[]                      certificate       = accountManager.getSenderCertificate();
    byte[]                      legacyCertificate = accountManager.getSenderCertificateLegacy();

    TextSecurePreferences.setUnidentifiedAccessCertificate(context, certificate);
    TextSecurePreferences.setUnidentifiedAccessCertificateLegacy(context, legacyCertificate);
  }


  public static class Factory implements Job.Factory<UuidMigrationJob> {
    @Override
    public @NonNull UuidMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new UuidMigrationJob(parameters);
    }
  }
}
