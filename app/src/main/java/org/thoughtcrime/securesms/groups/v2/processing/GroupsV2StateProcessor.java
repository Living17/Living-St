package org.thoughtcrime.securesms.groups.v2.processing;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupProtoUtil;
import org.thoughtcrime.securesms.groups.v2.NetworkGroupStateProvider2;
import org.thoughtcrime.securesms.groups.v2.ProfileKeySet;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.AvatarGroupsV2DownloadJob;
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * This class advances a groups state.
 */
public final class GroupsV2StateProcessor {

  private static final String TAG = Log.tag(GroupsV2StateProcessor.class);

  public static final int LATEST = GroupStateMapper.LATEST;

  private final Context           context;
  private final JobManager        jobManager;
  private final RecipientDatabase recipientDatabase;
  private final GroupDatabase     groupDatabase;

  public GroupsV2StateProcessor(@NonNull Context context) {
    this.context           = context.getApplicationContext();
    this.jobManager        = ApplicationDependencies.getJobManager();
    this.recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    this.groupDatabase     = DatabaseFactory.getGroupDatabase(context);
  }

  public StateProcessorForGroup forGroup(@NonNull GroupMasterKey groupMasterKey) {
    return new StateProcessorForGroup(groupMasterKey);
  }

  public enum GroupUpdateResult {
    /**
     * The message revision was inconsistent with server revision, should ignore
     */
    INCONSISTENT,

    /**
     * The local group was successfully updated to be consistent with the message revision
     */
    GROUP_UPDATED,

    /**
     * The local group is already consistent with the message revision
     */
    GROUP_CONSISTENT,

    /**
     * The local group was ahead of the message revision
     */
    GROUP_AHEAD,

    /**
     * The group was determined to have been left
     */
    GROUP_LEFT
  }

  public static class GroupUpdateResult2 {
              private final GroupUpdateResult groupUpdateResult;
    @Nullable private       DecryptedGroup    latestServer;

    GroupUpdateResult2(@NonNull GroupUpdateResult groupUpdateResult, @Nullable DecryptedGroup latestServer) {
      this.groupUpdateResult = groupUpdateResult;
      this.latestServer      = latestServer;
    }

    public GroupUpdateResult getGroupUpdateResult() {
      return groupUpdateResult;
    }

    public @Nullable DecryptedGroup getLatestServer() {
      return latestServer;
    }
  }

  public final class StateProcessorForGroup {

    private final GroupMasterKey             masterKey;
    private final GroupId.V2                 groupId;
    private final NetworkGroupStateProvider2 networkGroupStateProvider;

    private StateProcessorForGroup(@NonNull GroupMasterKey groupMasterKey) {
      this.masterKey                 = groupMasterKey;
      this.groupId                   = GroupId.v2(masterKey);
      this.networkGroupStateProvider = new NetworkGroupStateProvider2(ApplicationDependencies.getSignalServiceAccountManager().getGroupsV2Api(),
                                                                      ApplicationDependencies.getGroupsV2Authorization(),
                                                                      GroupSecretParams.deriveFromMasterKey(groupMasterKey));
    }

    /**
     * Using network where required, will attempt to bring the local copy of the group up to the revision specified.
     *
     * @param revision use {@link #LATEST} to get latest.
     */
    @WorkerThread
    public GroupUpdateResult2 updateLocalGroupToRevision(final int revision,
                                                         final long timestamp)
       throws IOException
    {
      // Pre-check, unless we're getting the latest, or group is unknown, then we'll check the group needs updating.
      // This saves network calls.
      if (!groupDatabase.isUnknownGroup(groupId) && revision != LATEST) {
        final int dbRevision = groupDatabase.getGroup(groupId).get().requireV2GroupProperties().getGroupRevision();
        if (revision <= dbRevision) {
          return new GroupUpdateResult2(revision == dbRevision ? GroupUpdateResult.GROUP_CONSISTENT : GroupUpdateResult.GROUP_AHEAD, null);
        }
      }

      // Query server for latest group state
      GlobalGroupState inputGroupState;
      try {
        inputGroupState = queryServer();
      } catch (NotInGroupException e) {
        Log.w(TAG, "Not a member of this group");
        return new GroupUpdateResult2(GroupUpdateResult.GROUP_LEFT, null);
      } catch (VerificationFailedException | IOException | InvalidGroupStateException e) {
        throw new IOException(e);
      }

      // Process the result, method is pure, no server or database interactions
      AdvanceGroupStateResult advanceGroupStateResult = GroupStateMapper.partiallyAdvanceGroupState(inputGroupState, revision);

      // Apply the changes to the database and produce update messages
      DecryptedGroup newLocalState = advanceGroupStateResult.getNewGlobalGroupState().getLocalState();
      if (newLocalState == null || newLocalState == inputGroupState.getLocalState()) {
        return new GroupUpdateResult2(GroupUpdateResult.GROUP_CONSISTENT, null);
      } else {
        if (inputGroupState.getLocalState() == null) {
          groupDatabase.create(masterKey, newLocalState);
        } else {
          groupDatabase.update(masterKey, newLocalState);
        }

        String avatar = newLocalState.getAvatar();
        if (avatar != null) {
          jobManager.add(new AvatarGroupsV2DownloadJob(groupId, avatar));
        }

        // insert messages for every applied update
        for (GroupLogEntry entry : advanceGroupStateResult.getProcessedLogEntries()) {
          storeMessage(GroupProtoUtil.createDecryptedGroupV2Context(masterKey, entry.getGroup(), entry.getChange()), timestamp);
        }

        final boolean fullMemberPostUpdate = GroupProtoUtil.isMember(Recipient.self().getUuid().get(), newLocalState.getMembersList());
        if (fullMemberPostUpdate) {
          recipientDatabase.setProfileSharing(Recipient.externalGroup(context, groupId).getId(), true);
        }
      }

      persistLearnedProfileKeys(inputGroupState);

      GlobalGroupState remainingWork = advanceGroupStateResult.getNewGlobalGroupState();
      if (remainingWork.getHistory().size() > 0) {
        // Enqueue a task to update to the latest revision we see at this point
        // It's injected into the message queue
        Log.i(TAG, String.format(Locale.US, "Enqueuing processing of more versions V[%d..%d]", newLocalState.getVersion() + 1, remainingWork.getLatestVersionNumber()));
        jobManager.add(new RequestGroupV2InfoJob(groupId, remainingWork.getLatestVersionNumber()));
      }

      return new GroupUpdateResult2(GroupUpdateResult.GROUP_UPDATED, newLocalState);
    }

    private void persistLearnedProfileKeys(GlobalGroupState globalGroupState) {
      final ProfileKeySet profileKeys = new ProfileKeySet();

      for (GroupLogEntry entry : globalGroupState.getHistory()) {
        profileKeys.addKeysFromGroupState(entry.getGroup(), DecryptedGroupUtil.editorUuid(entry.getChange()));
      }

      profileKeys.persistLearnedProfileKeys(recipientDatabase, jobManager);
    }

    private GlobalGroupState queryServer()
      throws VerificationFailedException, IOException, InvalidGroupStateException
    {
      UUID selfUuid = Recipient.self().getUuid().get();
      DecryptedGroup localState         = groupDatabase.getGroup(groupId)
                                                       .transform(g -> g.requireV2GroupProperties().getDecryptedGroup())
                                                       .orNull();
      DecryptedGroup latestServerGroup  = networkGroupStateProvider.getCurrentGroupState();
      int            versionWeWereAdded = GroupProtoUtil.findVersionWeWereAdded(latestServerGroup, selfUuid);
      int            logsNeededFrom     = localState != null ? Math.max(localState.getVersion(), versionWeWereAdded) : versionWeWereAdded;

      List<GroupLogEntry> history;
      if (GroupProtoUtil.isMember(selfUuid, latestServerGroup.getMembersList())) {
        // only full members can query history
        Collection<DecryptedGroupHistoryEntry> groupStatesFromRevision = networkGroupStateProvider.getGroupStatesFromRevision(logsNeededFrom);
        history = new ArrayList<>(groupStatesFromRevision.size());
        for (DecryptedGroupHistoryEntry entry: groupStatesFromRevision) {
          history.add(new GroupLogEntry(entry.getGroup(), entry.getChange()));
        }
      } else {
        // pending members only have the latest
        history = Collections.singletonList(new GroupLogEntry(latestServerGroup, null));
      }

      return new GlobalGroupState(localState, history);
    }

    private void storeMessage(@NonNull DecryptedGroupV2Context decryptedGroupV2Context, long timestamp) {
      try {
        MmsDatabase               mmsDatabase     = DatabaseFactory.getMmsDatabase(context);
        RecipientId               recipientId     = recipientDatabase.getOrInsertFromGroupId(groupId);
        Recipient                 recipient       = Recipient.resolved(recipientId);
        OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipient, decryptedGroupV2Context, null, timestamp, 0, false, null, Collections.emptyList(), Collections.emptyList());
        long                      threadId        = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
        long                      messageId       = mmsDatabase.insertMessageOutbox(outgoingMessage, threadId, false, null);

        mmsDatabase.markAsSent(messageId, true);
      } catch (MmsException e) {
        Log.w(TAG, e);
      }
    }
  }
}
