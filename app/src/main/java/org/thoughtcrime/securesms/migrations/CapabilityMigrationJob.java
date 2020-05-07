package org.thoughtcrime.securesms.migrations;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.jobs.RefreshOwnProfileJob;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

/**
 * Use to enable hardcoded app capabilities on an app upgrade.
 * <p>
 * Enqueues a {@link RefreshAttributesJob}.
 */
public final class CapabilityMigrationJob extends MigrationJob {

  private static final String TAG = Log.tag(CapabilityMigrationJob.class);

  public static final String KEY = "CapabilityMigrationJob";

  CapabilityMigrationJob() {
    this(new Parameters.Builder().build());
  }

  private CapabilityMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public boolean isUiBlocking() {
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void performMigration() {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      Log.w(TAG, "Skipping attribute refresh migration while not push registered");
      return;
    }

    ApplicationDependencies.getJobManager()
                           .startChain(new RefreshAttributesJob())
                           .then(new RefreshOwnProfileJob())
                           .enqueue();
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return false;
  }

  public static class Factory implements Job.Factory<CapabilityMigrationJob> {
    @Override
    public @NonNull CapabilityMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new CapabilityMigrationJob(parameters);
    }
  }
}
