package org.thoughtcrime.securesms.sms;

import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.mms.MessageGroupContext;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

public class IncomingGroupMessage extends IncomingTextMessage {

  private final MessageGroupContext groupContext;

  public IncomingGroupMessage(IncomingTextMessage base, GroupContext groupContext, String body) {
    this(base, new MessageGroupContext(groupContext));
  }

  public IncomingGroupMessage(IncomingTextMessage base, DecryptedGroupV2Context groupV2Context) {
    this(base, new MessageGroupContext(groupV2Context));
  }

  public IncomingGroupMessage(IncomingTextMessage base, MessageGroupContext groupContext) {
    super(base, groupContext.getEncodedGroupContext());
    this.groupContext = groupContext;
  }

  @Override
  public IncomingGroupMessage withMessageBody(String body) {
    throw new AssertionError();
  }

  @Override
  public boolean isGroup() {
    return true;
  }

  public boolean isUpdate() {
    return groupContext.isV2Group() || groupContext.requireGroupV1Properties().isUpdate();
  }

  public boolean isGroupV2() {
    return groupContext.isV2Group();
  }

  public boolean isQuit() {
    return !groupContext.isV2Group() && groupContext.requireGroupV1Properties().isQuit();
  }

}
