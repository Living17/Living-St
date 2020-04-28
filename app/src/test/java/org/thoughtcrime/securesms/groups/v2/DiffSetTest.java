package org.thoughtcrime.securesms.groups.v2;

import org.junit.Test;

import static com.google.common.collect.Sets.newHashSet;
import static org.junit.Assert.assertEquals;

public final class DiffSetTest {

  @Test
  public void findDiff_empty_sets() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet(), newHashSet());

    assertEquals(newHashSet(), diff.getAdded());
    assertEquals(newHashSet(), diff.getRemoved());
  }

  @Test
  public void findDiff_no_change() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet("1", "2", "3"), newHashSet("1", "2", "3"));

    assertEquals(newHashSet(), diff.getAdded());
    assertEquals(newHashSet(), diff.getRemoved());
  }

  @Test
  public void findDiff_add_one() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet("1", "2", "3"), newHashSet("1", "1b", "2", "3"));

    assertEquals(newHashSet("1b"), diff.getAdded());
    assertEquals(newHashSet(), diff.getRemoved());
  }

  @Test
  public void findDiff_remove_one() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet("1", "2", "3"), newHashSet("1", "3"));

    assertEquals(newHashSet(), diff.getAdded());
    assertEquals(newHashSet("2"), diff.getRemoved());
  }

  @Test
  public void findDiff_add_one_and_remove_one() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet("1", "2", "3"), newHashSet("1", "2", "4"));

    assertEquals(newHashSet("4"), diff.getAdded());
    assertEquals(newHashSet("3"), diff.getRemoved());
  }

  @Test
  public void findDiff_remove_all() {
    DiffSet<String> diff = DiffSet.findDiff(newHashSet("1", "2", "3"), newHashSet());

    assertEquals(newHashSet(), diff.getAdded());
    assertEquals(newHashSet("1", "2", "3"), diff.getRemoved());
  }
}