package org.thoughtcrime.securesms.groups.v2;

import org.junit.Test;
import org.mockito.Mockito;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public final class GroupMemoryCacheTest {

  public class TestContext {
    final GroupMemoryCache.Provider provider = mock(GroupMemoryCache.Provider.class);
    final GroupMemoryCache sut = new GroupMemoryCache(provider);

    void whenRequestCurrentStateReturn(GroupMasterKey groupMasterKey, DecryptedGroup group) throws Exception {
      GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey);

      when(provider.getCurrentGroupState(groupSecretParams)).thenReturn(group);
    }

    void whenRequestGroupStateAfterReturn(GroupMasterKey groupMasterKey, int from, Collection<DecryptedGroupHistoryEntry> groupStates) throws Exception {
      GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey);

      when(provider.getGroupStatesFrom(groupSecretParams, from)).thenReturn(groupStates);
    }

    void verifySingleCallToGetCurrentGroupState() throws Exception {
      verify(provider, times(1)).getCurrentGroupState(any());
      verifyNoMoreInteractions(provider);
    }
  }

  private final TestContext testContext = new TestContext();

  @Test
  public void groups_are_cached() {
    GroupMemoryCache.Group groupA = testContext.sut.forGroup(masterKey(1));
    GroupMemoryCache.Group groupB = testContext.sut.forGroup(masterKey(1));
    GroupMemoryCache.Group groupC = testContext.sut.forGroup(masterKey(2));

    assertSame(groupA, groupB);
    assertNotSame(groupB, groupC);
  }

  @Test
  public void get_first_group_state() throws Exception {
    DecryptedGroup groupState0 = mockGroupState(0);

    testContext.whenRequestCurrentStateReturn(masterKey(1), groupState0);

    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertSame(groupState0, group.getGroupState(0));

    testContext.verifySingleCallToGetCurrentGroupState();
  }

  @Test
  public void latest_is_cached_for_1() throws Exception {
    DecryptedGroup groupState0 = mockGroupState(0);

    testContext.whenRequestCurrentStateReturn(masterKey(1), groupState0);

    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertSame(groupState0, group.getGroupState(0));
    assertSame(groupState0, group.getGroupState(0));

    testContext.verifySingleCallToGetCurrentGroupState();
  }

  @Test
  public void get_later_group_state() throws Exception {
    DecryptedGroupHistoryEntry groupState1 = mockGroupHistory(1);
    DecryptedGroupHistoryEntry groupState2 = mockGroupHistory(2);

    testContext.whenRequestGroupStateAfterReturn(masterKey(1), 1, listOf(groupState1, groupState2));

    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertSame(groupState1.getGroup(), group.getGroupState(1));
    assertSame(groupState2.getGroup(), group.getGroupState(2));

    verify(testContext.provider, times(1)).getGroupStatesFrom(any(), eq(1));
    verifyNoMoreInteractions(testContext.provider);
  }

  @Test
  public void get_later_group_state_overlaps() throws Exception {
    DecryptedGroupHistoryEntry groupState1 = mockGroupHistory(1);
    DecryptedGroupHistoryEntry groupState2 = mockGroupHistory(2);
    DecryptedGroupHistoryEntry groupState3 = mockGroupHistory(3);

    testContext.whenRequestGroupStateAfterReturn(masterKey(1), 1, listOf(groupState1, groupState2));
    testContext.whenRequestGroupStateAfterReturn(masterKey(1), 3, listOf(groupState3));

    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertSame(groupState1.getGroup(), group.getGroupState(1));
    assertSame(groupState2.getGroup(), group.getGroupState(2));
    assertSame(groupState3.getGroup(), group.getGroupState(3));

    verify(testContext.provider, times(1)).getGroupStatesFrom(any(), eq(1));
    verify(testContext.provider, times(1)).getGroupStatesFrom(any(), eq(3));
    verifyNoMoreInteractions(testContext.provider);
  }

  @Test
  public void cant_get_change_for_0() throws Exception {
    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertNull(group.getGroupChange(0));

    verifyZeroInteractions(testContext.provider);
  }

  @Test
  public void get_later_group_changes_overlaps() throws Exception {
    DecryptedGroupHistoryEntry groupState1 = mockGroupHistory(1);
    DecryptedGroupHistoryEntry groupState2 = mockGroupHistory(2);
    DecryptedGroupHistoryEntry groupState3 = mockGroupHistory(3);

    testContext.whenRequestGroupStateAfterReturn(masterKey(1), 1, listOf(groupState1, groupState2));
    testContext.whenRequestGroupStateAfterReturn(masterKey(1), 3, listOf(groupState3));

    GroupMemoryCache.Group group = testContext.sut.forGroup(masterKey(1));

    assertSame(groupState1.getChange(), group.getGroupChange(1));
    //TODO: GV2, cannot test this, these all return null
    //  assertSame(groupState2.getChange(), group.getGroupChange(2)); AND-221
    assertSame(groupState3.getChange(), group.getGroupChange(3));

    verify(testContext.provider, times(1)).getGroupStatesFrom(any(), eq(1));
    verify(testContext.provider, times(1)).getGroupStatesFrom(any(), eq(3));
    verifyNoMoreInteractions(testContext.provider);
  }

  @SafeVarargs
  private static <T> List<T> listOf(T... ts) {
    ArrayList<T> ts1 = new ArrayList<>(ts.length);
    ts1.addAll(Arrays.asList(ts));
    return ts1;
  }

  private DecryptedGroup mockGroupState(int revision) {
    DecryptedGroup decryptedGroup = mock(DecryptedGroup.class);
    when(decryptedGroup.getVersion()).thenReturn(revision);
    return decryptedGroup;
  }

  private DecryptedGroupHistoryEntry mockGroupHistory(int revision) {
    DecryptedGroup groupState = mockGroupState(revision);
    DecryptedGroupHistoryEntry decryptedGroup = mock(DecryptedGroupHistoryEntry.class);
    when(decryptedGroup.getGroup()).thenReturn(groupState);
    return decryptedGroup;
  }

  private static GroupMasterKey masterKey(int value) {
    byte[] bytes = new byte[32];
    bytes[31] = (byte) value;
    if (bytes[31] != value) {
      throw new IllegalArgumentException("Value must be 1 byte");
    }
    try {
      return new GroupMasterKey(bytes);
    } catch (InvalidInputException e) {
      throw new AssertionError(e);
    }
  }


}