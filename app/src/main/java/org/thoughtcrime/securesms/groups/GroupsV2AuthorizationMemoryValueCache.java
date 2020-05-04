package org.thoughtcrime.securesms.groups;

import androidx.annotation.NonNull;

import org.signal.zkgroup.auth.AuthCredentialResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class GroupsV2AuthorizationMemoryValueCache implements GroupsV2Authorization.ValueCache {

  private final AtomicReference<Map<Integer, AuthCredentialResponse>> values = new AtomicReference<>();
  private final GroupsV2Authorization.ValueCache                      inner;

  public GroupsV2AuthorizationMemoryValueCache(@NonNull GroupsV2Authorization.ValueCache inner) {
    this.inner = inner;
  }

  @Override
  public void clear() {
    inner.clear();
    values.set(null);
  }

  @Override
  public @NonNull Map<Integer, AuthCredentialResponse> read() {
    Map<Integer, AuthCredentialResponse> map = values.get();

    if (map == null) {
      map = inner.read();
      values.set(map);
    }

    return map;
  }

  @Override
  public void write(@NonNull Map<Integer, AuthCredentialResponse> values) {
    inner.write(values);
    this.values.set(Collections.unmodifiableMap(new HashMap<>(values)));
  }
}
