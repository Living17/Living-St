package org.thoughtcrime.securesms.groups.v2;

import java.util.Collection;
import java.util.HashSet;

public final class DiffSet<T> {

  private final HashSet<T> added;
  private final HashSet<T> removed;

  private DiffSet(HashSet<T> added, HashSet<T> removed) {
    this.added   = added;
    this.removed = removed;
  }

  public static <T> DiffSet<T> findDiff(Collection<T> from, Collection<T> to) {
    HashSet<T> removed = new HashSet<>(from);
    removed.removeAll(to);

    HashSet<T> added = new HashSet<>(to);
    added.removeAll(from);

    return new DiffSet<>(added, removed);
  }

  public HashSet<T> getAdded() {
    return added;
  }

  public HashSet<T> getRemoved() {
    return removed;
  }
}
