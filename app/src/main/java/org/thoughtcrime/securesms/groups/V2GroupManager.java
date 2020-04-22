package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.GV2DebugFlags;
import org.signal.storageservice.protos.groups.AccessControl;
import org.signal.storageservice.protos.groups.GroupChange;
import org.signal.storageservice.protos.groups.Member;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.signal.zkgroup.groups.UuidCiphertext;
import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.model.AlanException;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupManager.GroupActionResult;
import org.thoughtcrime.securesms.groups.v2.DiffSet;
import org.thoughtcrime.securesms.groups.v2.GroupMemoryCache;
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor;
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob;
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupCandidate;
import org.whispersystems.signalservice.api.groupsv2.GroupChangeUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Authorization;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.api.push.exceptions.ConflictException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class V2GroupManager {

  private static final String TAG = Log.tag(V2GroupManager.class);

  @WorkerThread
  static @NonNull List<GroupCandidate> recipientsToCandidates(@NonNull Context context,
                                                              @NonNull Collection<Recipient> recipients)
    throws IOException
  {
    List<GroupCandidate> result = new ArrayList<>(recipients.size());

    for (Recipient member : recipients) {
      result.add(recipientToCandidate(context, member));
    }

    return result;
  }

  /**
   * Given a recipient will create a {@link GroupCandidate} which will have either
   */
  @WorkerThread
  private static GroupCandidate recipientToCandidate(@NonNull Context context, @NonNull Recipient recipient)
    throws IOException
  {
    final SignalServiceAccountManager signalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager();
    final RecipientDatabase           recipientDatabase           = DatabaseFactory.getRecipientDatabase(context);

    recipient = recipient.resolve();

    UUID uuid = recipient.getUuid().orNull();
    if (uuid == null) {
      throw new AssertionError("Non UUID members should have need detected by now");
    }

    Optional<ProfileKeyCredential> profileKeyCredential = ProfileKeyUtil.profileKeyCredentialOptional(recipient.getProfileKeyCredential());
    GroupCandidate                 candidate            = new GroupCandidate(uuid, profileKeyCredential);

    if (!candidate.hasProfileKeyCredential()) {
      // we need to try to find the profile key credential
      ProfileKey profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());

      if (profileKey != null) {
        try {
          Optional<ProfileKeyCredential> profileKeyCredentialOptional = signalServiceAccountManager.resolveProfileKeyCredential(uuid, profileKey);

          if (profileKeyCredentialOptional.isPresent()) {
            candidate = candidate.withProfileKeyCredential(profileKeyCredentialOptional);

            recipientDatabase.setProfileKeyCredential(recipient.getId(), profileKey, profileKeyCredentialOptional.get());
          }
        } catch (VerificationFailedException e) {
          Log.w(TAG, e);
          throw new IOException(e);
        }
      }
    }

    return candidate;
  }

  @WorkerThread
  static @NonNull GroupActionResult createGroup(@NonNull Context context,
                                                @NonNull Collection<Recipient> recipients,
                                                @Nullable String name,
                                                @Nullable Bitmap avatar)
      throws IOException, MembershipNotSuitableForV2Exception
  {
    if (!allMembersAndSelfSupportGroupsV2AndUuid(context, recipients)) {
      throw new MembershipNotSuitableForV2Exception("At least one member does not support GV2 or UUID capabilities");
    }

    Set<GroupCandidate> members = new HashSet<>(recipientsToCandidates(context, recipients));
    final SignalServiceAccountManager signalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager();
    final GroupsV2Authorization       groupsV2Authorization       = ApplicationDependencies.getGroupsV2Authorization();
    final GroupsV2Operations          groupsV2Operations          = ApplicationDependencies.getGroupsV2Operations   ();
    final GroupsV2Api                 groupsV2Api                 = signalServiceAccountManager.getGroupsV2Api();
    final byte[]                      avatarBytes                 = BitmapUtil.toByteArray(avatar);
    final GroupDatabase               groupDatabase               = DatabaseFactory.getGroupDatabase(context);

    if (GV2DebugFlags.FORCE_INVITES) {
      // Strip profile key credentials
      members = new HashSet<>(Stream.of(members).map(c -> new GroupCandidate(c.getUuid(), Optional.absent())).toList());
    }

    try {
      GroupCandidate self = recipientToCandidate(context, Recipient.self());

      if (!self.hasProfileKeyCredential()) {
        Log.w(TAG, "Cannot create a V2 group as self does not have a versioned profile");
        throw new MembershipNotSuitableForV2Exception("Cannot create a V2 group as self does not have a versioned profile");
      }

      GroupsV2Operations.NewGroup newGroup = groupsV2Operations.createNewGroup(name,
                                                                               Optional.fromNullable(avatarBytes),
                                                                               self,
                                                                               members);

      groupsV2Api.putNewGroup(newGroup, groupsV2Authorization);

      GroupSecretParams groupSecretParams = newGroup.getGroupSecretParams();
      GroupMasterKey    masterKey         = groupSecretParams.getMasterKey();

      DecryptedGroup decryptedGroup = ApplicationDependencies.getGroupsV2MemoryCache().forGroup(masterKey).getGroupState(0);
      if (decryptedGroup == null) {
        throw new AssertionError();
      }

      GroupId.V2    groupId          = groupDatabase.create(masterKey, decryptedGroup);
      RecipientId   groupRecipientId = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
      Recipient     groupRecipient   = Recipient.resolved(groupRecipientId);

      AvatarHelper.setAvatar(context, groupRecipientId, avatarBytes != null ? new ByteArrayInputStream(avatarBytes) : null);
      groupDatabase.onAvatarUpdated(groupId, avatarBytes != null);
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(groupRecipient.getId(), true);

      return sendGroupUpdate(context, masterKey, decryptedGroup, null, groupId);

    } catch (VerificationFailedException | InvalidGroupStateException | IOException e) {
      //TODO: GV2 We need to propagate an exception, network is new to V2. AND-212
      throw new AssertionError(e);
    }
  }

  @WorkerThread
  static @Nullable GroupActionResult updateGroup(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull Collection<Recipient> recipients,
                                                 @Nullable String name,
                                                 @Nullable Bitmap avatar)
      throws IOException, VerificationFailedException, InvalidGroupStateException, MembershipNotSuitableForV2Exception
  {
    if (!allMembersAndSelfSupportGroupsV2AndUuid(context, recipients)) {
      throw new MembershipNotSuitableForV2Exception("At least one member does not support GV2 or UUID capabilities");
    }

    final GroupDatabase             groupDatabase = DatabaseFactory.getGroupDatabase(context);
    final GroupDatabase.GroupRecord groupRecord   = groupDatabase.requireGroup(groupId);

    DiffSet<RecipientId> diffSet = DiffSet.findDiff(groupRecord.getMembers(), RecipientUtil.toRecipientIdList(recipients));
    HashSet<RecipientId> added   = diffSet.getAdded();
    HashSet<RecipientId> removed = diffSet.getRemoved();

    List<Recipient> addedRecipients   = Stream.of(recipients).filter(r -> added.contains(r.getId())).toList();
    List<UUID>      removedRecipients = Stream.of(recipients).filter(r -> removed.contains(r.getId())).map(r->r.getUuid().get()).toList();

    return updateGroup(context,
                       groupId,
                       new HashSet<>(recipientsToCandidates(context, addedRecipients)),
                       new HashSet<>(removedRecipients),
                       name,
                       avatar);
  }

  @WorkerThread
  private static @Nullable GroupActionResult updateGroup(@NonNull Context context,
                                                         @NonNull GroupId.V2 groupId,
                                                         @NonNull Set<GroupCandidate> newMembers,
                                                         @NonNull Set<UUID> removedMembers,
                                                         @Nullable String name,
                                                         @Nullable Bitmap avatar)
    throws IOException, VerificationFailedException, InvalidGroupStateException
  {
    return genericUpdate(context,
                         groupId,
                         Optional.fromNullable(BitmapUtil.toByteArray(avatar)),
                         updateContext ->
      updateContext.operations.createModifyGroupTitleAndMembershipChange(updateContext.groupRecord.getTitle().equals(name) ? Optional.absent() : Optional.fromNullable(name),
                                                                         newMembers,
                                                                         removedMembers));
  }

  @WorkerThread
  static @Nullable GroupActionResult updateProfileKey(@NonNull Context context, @NonNull GroupId.V2 groupId)
    throws IOException, InvalidGroupStateException, VerificationFailedException
  {
    return genericUpdate(context, groupId, updateContext -> {
      Optional<DecryptedMember> selfInGroup = DecryptedGroupUtil.findMemberByUuid(updateContext.v2GroupProperties.getDecryptedGroup().getMembersList(), Recipient.self().getUuid().get());

      if (!selfInGroup.isPresent()) {
        Log.w(TAG, "Self not in group");
        return null;
      }

      if (Arrays.equals(Recipient.self().getProfileKey(), selfInGroup.get().getProfileKey().toByteArray())) {
        Log.i(TAG, "Own Profile Key is already up to date in group " + groupId);
        return null;
      }

      GroupCandidate groupCandidate = recipientToCandidate(context, Recipient.self());

      if (!groupCandidate.hasProfileKeyCredential()) {
        Log.w(TAG, "No credential available");
        return null;
      }

      return updateContext.operations.createUpdateProfileKeyCredentialChange(groupCandidate.getProfileKeyCredential().get());
    });
  }

  @WorkerThread
  static @Nullable GroupActionResult acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
    throws IOException, InvalidGroupStateException, VerificationFailedException
  {
    return genericUpdate(context, groupId, updateContext -> {
      Optional<DecryptedMember> selfInGroup = DecryptedGroupUtil.findMemberByUuid(updateContext.v2GroupProperties.getDecryptedGroup().getMembersList(), Recipient.self().getUuid().get());

      if (selfInGroup.isPresent()) {
        Log.w(TAG, "Self already in group");
        return null;
      }

      GroupCandidate groupCandidate = recipientToCandidate(context, Recipient.self());

      if (!groupCandidate.hasProfileKeyCredential()) {
        Log.w(TAG, "No credential available");
        return null;
      }

      return updateContext.operations.createAcceptInviteChange(groupCandidate.getProfileKeyCredential().get());
    });
  }

  @WorkerThread
  static @Nullable GroupActionResult cancelInvites(@NonNull Context context,
                                                   @NonNull GroupId.V2 groupId,
                                                   @NonNull Collection<UuidCiphertext> uuidCipherTexts)
    throws IOException, InvalidGroupStateException, VerificationFailedException
  {
    return genericUpdate(context,
                         groupId,
                         updateContext -> updateContext.operations.createRemoveInvitationChange(new HashSet<>(uuidCipherTexts)));
  }

  //TODO: [GV2] what to do with result? 
  @WorkerThread
  @Nullable static GroupActionResult updateGroupTimer(@NonNull Context context,
                                                      @NonNull GroupId.V2 groupId,
                                                      int timerDurationSeconds)
    throws IOException, VerificationFailedException, InvalidGroupStateException
  {
     return genericUpdate(context, groupId, updateContext -> updateContext.operations.createModifyGroupTimerChange(timerDurationSeconds));
  }

  //TODO: [GV2] what to do with result?
  @WorkerThread
  @Nullable static GroupActionResult setMemberAdmin(@NonNull Context context,
                                                    @NonNull GroupId.V2 groupId,
                                                    @NonNull RecipientId recipientId,
                                                    boolean admin)
    throws IOException, VerificationFailedException, InvalidGroupStateException
  {
    Recipient recipient = Recipient.resolved(recipientId);
    return genericUpdate(context, groupId, updateContext -> updateContext.operations.createChangeMemberRole(recipient.getUuid().get(), admin ? Member.Role.ADMINISTRATOR : Member.Role.DEFAULT));
  }

  @WorkerThread
  static GroupActionResult genericUpdate(@NonNull Context context,
                                         @NonNull GroupId.V2 groupId,
                                         @NonNull ChangeConstructor changeConstructor)
    throws IOException, InvalidGroupStateException, VerificationFailedException
  {
    return genericUpdate(context, groupId, Optional.absent(), changeConstructor);
  }

  /**
   * Constructs an {@link UpdateContext} and passes to the supplied {@link ChangeConstructor} to create
   * the change record.
   * <p>
   * Then it will apply that record on the server, update local group state and inform other group members.
   */
  @WorkerThread
  static @Nullable GroupActionResult genericUpdate(@NonNull Context context,
                                                   @NonNull GroupId.V2 groupId,
                                                   @NonNull Optional<byte[]> avatarBytes,
                                                   @NonNull ChangeConstructor changeConstructor)
    throws IOException, InvalidGroupStateException, VerificationFailedException
  {
    final GroupDatabase                      groupDatabase               = DatabaseFactory.getGroupDatabase(context);
    final GroupDatabase.GroupRecord          groupRecord                 = groupDatabase.requireGroup(groupId);
    final SignalServiceAccountManager        signalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager();
    final GroupsV2Operations                 groupsV2Operations          = ApplicationDependencies.getGroupsV2Operations();
    final GroupsV2Api                        groupsV2Api                 = signalServiceAccountManager.getGroupsV2Api();
    final GroupDatabase.V2GroupProperties    v2GroupProperties           = groupRecord.requireV2GroupProperties();
    final GroupMasterKey                     groupMasterKey              = v2GroupProperties.getGroupMasterKey();
    final GroupSecretParams                  groupSecretParams           = GroupSecretParams.deriveFromMasterKey(groupMasterKey);
    final GroupsV2Operations.GroupOperations groupOperations             = groupsV2Operations.forGroup(groupSecretParams);

    final UpdateContext updateContext = new UpdateContext(groupOperations, groupRecord);

    GroupChange.Actions.Builder groupChange = changeConstructor.createChange(updateContext);

    if (groupChange == null) {
      return null;
    }

    UpdateResult result = applyUpdateOnServer(groupsV2Api, avatarBytes, groupMasterKey, groupSecretParams, groupChange, v2GroupProperties.getGroupRevision() + 1);

    if (result == null) {
      return null;
    }

    GroupActionResult groupActionResult = null;
    if (result.changePlainText != null) {
      // TODO: Ideally we won't hit server here
      // Instead of updating from what we know, just go and get the latest
      GroupMemoryCache.Group group            = ApplicationDependencies.getGroupsV2MemoryCache().forGroup(groupSecretParams);
      DecryptedGroup         groupState       = group.getGroupState(result.revision);
      DecryptedGroupChange   plainGroupChange = group.getGroupChange(result.revision);

      if (groupState == null) {
        throw new AssertionError();
      }

      groupDatabase.update(groupMasterKey, groupState);

      if (avatarBytes.isPresent()) {
        AvatarHelper.setAvatar(context, groupRecord.getRecipientId(), new ByteArrayInputStream(avatarBytes.get()));
        groupDatabase.onAvatarUpdated(groupId, true);
      }

      if (result.changePlainText.hasNewTimer()) {
        RecipientId groupRecipientId = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
        DatabaseFactory.getRecipientDatabase(context).setExpireMessages(groupRecipientId, result.changePlainText.getNewTimer().getDuration());
      }

      groupActionResult = sendGroupUpdate(context, groupMasterKey, groupState, plainGroupChange, groupId);
    }
    if (result.conflict) {
      // TODO: GV2 This doesn't make sense here, should have already updated as part of conflict resolution
      ApplicationDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId, result.revision));
    }

    return groupActionResult;
  }

  @WorkerThread
  public static void applyMembershipRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    genericUpdate(context, groupId, updateContext -> updateContext.operations.createChangeMembershipRights(rightsToAccessControl(newRights)));
  }

  @WorkerThread
  public static void applyAttributesRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
    throws InvalidGroupStateException, VerificationFailedException, IOException
  {
    genericUpdate(context, groupId, updateContext -> updateContext.operations.createChangeAttributesRights(rightsToAccessControl(newRights)));
  }

  private static @NonNull AccessControl.AccessRequired rightsToAccessControl(@NonNull GroupAccessControl rights) {
    switch (rights){
      case ALL_MEMBERS:
        return AccessControl.AccessRequired.MEMBER;
      case ONLY_ADMINS:
        return AccessControl.AccessRequired.ADMINISTRATOR;
      default:
      throw new AssertionError();
    }
  }

  public static boolean allMembersAndSelfSupportGroupsV2AndUuid(@NonNull Context context,
                                                                @NonNull Collection<Recipient> membersWithoutSelf)
      throws IOException
  {
    if (GV2DebugFlags.SKIP_CAPABILITY_CHECK) return true;

    final HashSet<Recipient> membersAndSelf = new HashSet<>(membersWithoutSelf);
    membersAndSelf.add(Recipient.self());

    if (GV2DebugFlags.EXTRA_LOGGING_AND199) {
      for (Recipient member : membersAndSelf) {
        Log.d("ALAN", String.format("Capability: %s %s", member.toShortString(context), member.getGroupsV2Capability()));
      }
    }

    for (Recipient member : membersAndSelf) {
      member = member.resolve();
      Recipient.Capability gv2Capability  = member.getGroupsV2Capability();
      Recipient.Capability uuidCapability = member.getUuidCapability();

      if (gv2Capability == Recipient.Capability.UNKNOWN || uuidCapability == Recipient.Capability.UNKNOWN) {
        if (!ApplicationDependencies.getJobManager().runSynchronously(RetrieveProfileJob.forRecipient(member), TimeUnit.SECONDS.toMillis(10)).isPresent()) {
          throw new IOException("Member capability was not retrieved in time");
        }
      }

      if (gv2Capability != Recipient.Capability.SUPPORTED) {
        Log.i(TAG, "At least one member does not support GV2, capability was " + gv2Capability);
        return false;
      }

      if (uuidCapability != Recipient.Capability.SUPPORTED) {
        Log.i(TAG, "At least one member does not support GV2, capability was " + gv2Capability);
        return false;
      }
    }

    return true;
  }

  private static class UpdateResult {
    private final DecryptedGroupChange changePlainText;
    private final boolean              conflict;
    private final int                  revision;

    public UpdateResult(@Nullable DecryptedGroupChange changePlainText, boolean conflict, int revision) {
      this.changePlainText = changePlainText;
      this.conflict        = conflict;
      this.revision        = revision;
    }
  }

  private static @Nullable UpdateResult applyUpdateOnServer(GroupsV2Api groupsV2Api,
                                                            Optional<byte[]> avatarBytes,
                                                            GroupMasterKey groupMasterKey,
                                                            GroupSecretParams groupSecretParams,
                                                            GroupChange.Actions.Builder groupChange,
                                                            int revision)
    throws IOException, VerificationFailedException, InvalidGroupStateException
  {
    final GroupsV2Authorization              groupsV2Authorization  = ApplicationDependencies.getGroupsV2Authorization();
    final GroupsV2StateProcessor groupsV2StateProcessor = ApplicationDependencies.getGroupsV2StateProcessor();
    final GroupsV2Operations                 groupsV2Operations     = ApplicationDependencies.getGroupsV2Operations();
    final GroupsV2Operations.GroupOperations groupOperations        = groupsV2Operations.forGroup(groupSecretParams);

    final int                  revisionLimit   = revision + 3;
          boolean              conflict        = false;
          DecryptedGroupChange changePlainText = null;

    if (avatarBytes.isPresent()) {
      String cdnKey = groupsV2Api.uploadAvatar(avatarBytes.get(), groupSecretParams, groupsV2Authorization);
      groupChange.setModifyAvatar(GroupChange.Actions.ModifyAvatarAction.newBuilder().setAvatar(cdnKey));
    }

    while (revision < revisionLimit) {
      GroupChange.Actions change = groupChange.setVersion(revision).build();
      try {
        if (GroupChangeUtil.changeIsEmpty(change)) {
          Log.d(TAG, "Change is empty");
          return null;
        }
        changePlainText = groupsV2Api.patchGroup(change, groupSecretParams, groupsV2Authorization);
      } catch (ConflictException e) {
        GroupsV2StateProcessor.GroupUpdateResult2 groupUpdateResult2 = groupsV2StateProcessor.forGroup(groupMasterKey)
                                                                                             .updateLocalGroupToRevision(GroupsV2StateProcessor.LATEST, System.currentTimeMillis());

        if (groupUpdateResult2.getGroupUpdateResult() == GroupsV2StateProcessor.GroupUpdateResult.GROUP_LEFT) {
          Log.w(TAG, "You are no longer in the group, or invite was revoked");
          return null;
        }

        if (groupUpdateResult2.getGroupUpdateResult() != GroupsV2StateProcessor.GroupUpdateResult.GROUP_UPDATED || groupUpdateResult2.getLatestServer() == null) {
          throw new AlanException(); //TODO: GV2 failed to apply
        }

        // resolve the conflict
        groupChange = GroupChangeUtil.resolveConflict(groupUpdateResult2.getLatestServer(),
                                                      groupOperations.decryptChange(change),
                                                      change);

        //Group group = null;
        //groupChange = GroupChangeUtil.cleanUpChange(change, group);
        //if(true) throw new AssertionError("TODO GV2");// TODO GV2
        Log.w(TAG, "Conflict on revision " + revision);
        revision++; //TODO!
        conflict = true;
        continue;
      }
      break;
    }
    return new UpdateResult(changePlainText, conflict, revision);
  }

  private static GroupActionResult sendGroupUpdate(@NonNull Context context,
                                                   @NonNull GroupMasterKey masterKey,
                                                   @NonNull DecryptedGroup decryptedGroup,
                                                   @Nullable DecryptedGroupChange plainGroupChange,
                                                   @NonNull GroupId.V2 groupId)
  {
    RecipientId groupRecipientId = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(groupId);
    Recipient   groupRecipient   = Recipient.resolved(groupRecipientId);

    DecryptedGroupV2Context decryptedGroupV2Context = GroupProtoUtil.createDecryptedGroupV2Context(masterKey, decryptedGroup, plainGroupChange);

    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, decryptedGroupV2Context, null, System.currentTimeMillis(), 0, false, null, Collections.emptyList(), Collections.emptyList());
    long                      threadId        = MessageSender.send(context, outgoingMessage, -1, false, null);

    return new GroupActionResult(groupRecipient, threadId);
  }

  static boolean leaveGroup(@NonNull Context context, @NonNull GroupId.V2 groupId)
    throws VerificationFailedException, IOException, InvalidGroupStateException
  {
    if (ejectOneMember(context, groupId, Recipient.self()) != null) {
      DatabaseFactory.getGroupDatabase(context).setActive(groupId, false);
      return true;
    }
    return false;
  }

  static GroupActionResult ejectOneMember(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Recipient toEject)
    throws IOException, VerificationFailedException, InvalidGroupStateException
  {
    final SignalServiceAccountManager     signalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager();
    final GroupsV2Operations              groupsV2Operations          = ApplicationDependencies.getGroupsV2Operations();
    final GroupsV2Api                     groupsV2Api                 = signalServiceAccountManager.getGroupsV2Api();
    final GroupDatabase                   groupDatabase               = DatabaseFactory.getGroupDatabase(context);
    final GroupDatabase.GroupRecord       groupRecord                 = groupDatabase.requireGroup(groupId);
    final GroupDatabase.V2GroupProperties v2GroupProperties           = groupRecord.requireV2GroupProperties();
    final GroupMasterKey                  groupMasterKey              = v2GroupProperties.getGroupMasterKey();
    final GroupSecretParams               groupSecretParams           = GroupSecretParams.deriveFromMasterKey(groupMasterKey);

    GroupChange.Actions.Builder groupChange = groupsV2Operations.forGroup(groupSecretParams)
                                                                .createModifyGroupTitleAndMembershipChange(Optional.absent(),
                                                                                                           Collections.emptySet(),
                                                                                                           Collections.singleton(toEject.getUuid().get()));
    // TODO: Ideally we won't hit server here
    DecryptedGroup group = ApplicationDependencies.getGroupsV2MemoryCache().forGroup(groupSecretParams).getLatest();

    UpdateResult result = applyUpdateOnServer(groupsV2Api, Optional.absent(), groupMasterKey, groupSecretParams, groupChange, v2GroupProperties.getGroupRevision() + 1);

    if (result == null) {
      return null;
    }

    GroupActionResult groupActionResult;
    if (result.changePlainText != null) {
      if (toEject.isLocalNumber()) {
        group = DecryptedGroupUtil.removeMember(group, toEject.getUuid().get(), group.getVersion() + 1);
        groupActionResult = sendGroupUpdate(context, groupMasterKey, group, result.changePlainText, groupId);
        groupDatabase.update(groupMasterKey, group);
        groupDatabase.setActive(groupId, false);
        Log.i(TAG, "Left group");
      } else {
        // TODO GV2: Ideally we won't hit server here
        group = ApplicationDependencies.getGroupsV2MemoryCache().forGroup(groupSecretParams).getLatest();
        groupDatabase.update(groupMasterKey, group);
        groupActionResult = sendGroupUpdate(context, groupMasterKey, group, result.changePlainText, groupId);
      }
    } else {
      throw new IOException();
    }
    if (result.conflict) {
      // TODO GV2: This doesn't make sense here, should have already updated as part of conflict resolution
      ApplicationDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId, result.revision));
    }

    return groupActionResult;
  }

  static class UpdateContext {
    @NonNull final GroupsV2Operations.GroupOperations operations;
    @NonNull final GroupDatabase.GroupRecord          groupRecord;
    @NonNull final GroupDatabase.V2GroupProperties    v2GroupProperties;
    @NonNull final GroupMasterKey                     groupMasterKey;
    @NonNull final GroupSecretParams                  groupSecretParams;

    UpdateContext(@NonNull GroupsV2Operations.GroupOperations operations, @NonNull GroupDatabase.GroupRecord groupRecord) {
      this.operations        = operations;
      this.groupRecord       = groupRecord;
      this.v2GroupProperties = groupRecord.requireV2GroupProperties();
      this.groupMasterKey    = v2GroupProperties.getGroupMasterKey();
      this.groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey);
    }
  }

  interface ChangeConstructor{
    @Nullable GroupChange.Actions.Builder createChange(@NonNull UpdateContext updateContext) throws IOException;
  }
}
