package org.thoughtcrime.securesms.groups;

import androidx.annotation.NonNull;

import org.signal.zkgroup.auth.AuthCredentialResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class GroupsV2AuthorizationMemoryValueCache implements GroupsV2Authorization.ValueCache {

  private final AtomicReference<Map<Integer, AuthCredentialResponse>> values = new AtomicReference<>();

  @Override
  public void clear() {
    values.set(null);
  }

  @Override
  public @NonNull Map<Integer, AuthCredentialResponse> read() {
    Map<Integer, AuthCredentialResponse> map = values.get();
    return map != null ? map : Collections.emptyMap();
  }

  @Override
  public void write(@NonNull Map<Integer, AuthCredentialResponse> values) {
    this.values.set(Collections.unmodifiableMap(new HashMap<>(values)));
  }
}
