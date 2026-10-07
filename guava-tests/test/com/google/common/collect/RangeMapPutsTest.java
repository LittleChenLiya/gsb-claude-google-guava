/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.collect;

import static org.junit.Assert.assertThrows;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.collect.RangeMapPuts.ConflictType;
import com.google.common.collect.RangeMapPuts.PutCheck;
import com.google.common.collect.RangeMapPuts.PutPolicy;
import com.google.common.collect.RangeMapPuts.RangeIntersectionException;
import junit.framework.TestCase;
import org.jspecify.annotations.NullUnmarked;

/**
 * Tests for {@link RangeMapPuts}, covering three scenarios with small in-memory {@code
 * TreeRangeMap} fixtures: adjacent ranges with varied open/closed endpoints, ranges that contain
 * (or are contained by) existing entries, and empty ranges.
 */
@GwtIncompatible // NavigableMap
@NullUnmarked
public class RangeMapPutsTest extends TestCase {

  // === Section 1: adjacent ranges with varied open/closed endpoints ===

  public void testAdjacent_openClosedEndpointsOnlyTouch() {
    // [0,2) then [2,4] share the point 2, but 2 is excluded from the first range: no overwrite.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(0, 2), "a");

    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closed(2, 4));
    assertEquals(ConflictType.TOUCHING, check.conflictType());
    assertFalse(check.isIntersection());
    assertEquals(ImmutableList.of(Range.closedOpen(0, 2)), check.touchingRanges());
    assertTrue(check.intersectingRanges().isEmpty());

    // Strict mode accepts touching insertions and delegates to the original put.
    RangeMapPuts.put(map, Range.closed(2, 4), "b", PutPolicy.REJECT_INTERSECTION);
    assertEquals("{[0..2)=a, [2..4]=b}", map.asMapOfRanges().toString());
  }

  public void testAdjacent_sharedClosedEndpointIsRealIntersection() {
    // [0,2] and [2,4] both contain 2: that single point is a genuine intersection, not a touch.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 2), "a");

    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closed(2, 4));
    assertEquals(ConflictType.INTERSECTING, check.conflictType());
    assertTrue(check.isIntersection());
    assertEquals(ImmutableList.of(Range.closed(0, 2)), check.intersectingRanges());

    RangeMap<Integer, String> guarded = RangeMapPuts.guarding(map, PutPolicy.REJECT_INTERSECTION);
    RangeIntersectionException expected =
        assertThrows(
            RangeIntersectionException.class, () -> guarded.put(Range.closed(2, 4), "b"));
    assertEquals(ConflictType.INTERSECTING, expected.conflictType());
    // Rejected: the original map is preserved exactly.
    assertEquals("{[0..2]=a}", map.asMapOfRanges().toString());

    // The same insertion under ALLOW_OVERWRITE delegates to the original put and splits at 2.
    RangeMapPuts.put(map, Range.closed(2, 4), "b", PutPolicy.ALLOW_OVERWRITE);
    assertEquals("{[0..2)=a, [2..4]=b}", map.asMapOfRanges().toString());
  }

  public void testAdjacent_openEndAndOpenStartOnlyTouch() {
    // (1,5) and [5,9) touch at 5 (excluded from both); (1,5] and [5,9) overlap at exactly 5.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.open(1, 5), "a");

    assertEquals(
        ConflictType.TOUCHING,
        RangeMapPuts.inspect(map, Range.closedOpen(5, 9)).conflictType());

    TreeRangeMap<Integer, String> includingFive = TreeRangeMap.create();
    includingFive.put(Range.openClosed(1, 5), "a");
    assertEquals(
        ConflictType.INTERSECTING,
        RangeMapPuts.inspect(includingFive, Range.closedOpen(5, 9)).conflictType());
  }

  // === Section 2: containing / enclosed ranges and full overwrites ===

  public void testContaining_insertedRangeEnclosesExistingEntries() {
    // [1,2] and [4,6] are both wholly enclosed by [0,10]: a covering overwrite.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(1, 2), "a");
    map.put(Range.closed(4, 6), "b");

    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closed(0, 10));
    assertEquals(ConflictType.COVERING, check.conflictType());
    assertEquals(
        ImmutableList.of(Range.closed(1, 2), Range.closed(4, 6)), check.intersectingRanges());

    RangeMap<Integer, String> strict = RangeMapPuts.guarding(map, PutPolicy.REJECT_INTERSECTION);
    assertThrows(
        RangeIntersectionException.class, () -> strict.put(Range.closed(0, 10), "c"));
    assertEquals("{[1..2]=a, [4..6]=b}", map.asMapOfRanges().toString());

    // ALLOW_OVERWRITE delegates to the original put, which replaces both entries.
    RangeMap<Integer, String> lenient = RangeMapPuts.guarding(map, PutPolicy.ALLOW_OVERWRITE);
    lenient.put(Range.closed(0, 10), "c");
    assertEquals("{[0..10]=c}", map.asMapOfRanges().toString());
  }

  public void testContaining_insertedRangeInsideExistingEntryIsPartialIntersection() {
    // Inserting [2,8] inside [0,10] is a genuine partial intersection that splits the entry.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 10), "a");

    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closed(2, 8));
    assertEquals(ConflictType.INTERSECTING, check.conflictType());
    assertEquals(ImmutableList.of(Range.closed(0, 10)), check.intersectingRanges());

    assertThrows(
        RangeIntersectionException.class,
        () -> RangeMapPuts.put(map, Range.closed(2, 8), "b", PutPolicy.REJECT_INTERSECTION));
  }

  public void testContaining_rejectLeavesMapUntouchedThenAllowSplits() {
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 10), "a");
    String before = map.asMapOfRanges().toString();

    try {
      RangeMapPuts.put(map, Range.closed(2, 8), "b", PutPolicy.REJECT_INTERSECTION);
      fail("partial intersection should be rejected");
    } catch (RangeIntersectionException expected) {
      // Exception or not, the map must be identical to before.
    }
    assertEquals(before, map.asMapOfRanges().toString());
    assertEquals("a", map.get(5));

    RangeMapPuts.put(map, Range.closed(2, 8), "b", PutPolicy.ALLOW_OVERWRITE);
    assertEquals("{[0..2)=a, [2..8]=b, (8..10]=a}", map.asMapOfRanges().toString());
  }

  public void testContaining_mixedCoveringAndIntersectingReportsWorst() {
    // [0,4] wholly covers the first entry but only crosses the second: worst type INTERSECTING.
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(1, 2), "a");
    map.put(Range.closed(3, 6), "b");

    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closed(0, 4));
    assertEquals(ConflictType.INTERSECTING, check.conflictType());
    assertEquals(
        ImmutableList.of(Range.closed(1, 2), Range.closed(3, 6)), check.intersectingRanges());
  }

  // === Section 3: empty ranges ===

  public void testEmptyRange_isDisjointAndNoOpEvenInStrictMode() {
    // openClosed(3,3) is a valid empty range; TreeRangeMap.put treats it as a no-op.
    Range<Integer> empty = Range.openClosed(3, 3);
    assertTrue(empty.isEmpty());

    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 10), "a");

    PutCheck<Integer, String> check = RangeMapPuts.inspect(map, empty);
    assertEquals(ConflictType.DISJOINT, check.conflictType());
    assertFalse(check.isIntersection());

    RangeMapPuts.put(map, empty, "x", PutPolicy.REJECT_INTERSECTION);
    assertEquals("{[0..10]=a}", map.asMapOfRanges().toString());
  }

  public void testEmptyRange_againstEmptyMapIsDisjoint() {
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    PutCheck<Integer, String> check =
        RangeMapPuts.inspect(map, Range.closedOpen(2, 2));
    assertEquals(ConflictType.DISJOINT, check.conflictType());
    RangeMapPuts.put(map, Range.closedOpen(2, 2), "x", PutPolicy.REJECT_INTERSECTION);
    assertTrue(map.asMapOfRanges().isEmpty());
  }

  // === New option disabled: the original interface keeps working ===

  public void testDefaultPolicyDelegatesToOriginalPut() {
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 10), "a");

    // DEFAULT behaves exactly like calling RangeMap.put directly, including overwriting.
    RangeMap<Integer, String> guarded = RangeMapPuts.guarding(map, PutPolicy.DEFAULT);
    guarded.put(Range.closed(2, 8), "b");
    assertEquals("{[0..2)=a, [2..8]=b, (8..10]=a}", map.asMapOfRanges().toString());

    // Reads and other RangeMap operations pass through unchanged.
    assertEquals("b", guarded.get(5));
    guarded.remove(Range.closed(2, 8));
    assertEquals("{[0..2)=a, (8..10]=a}", map.asMapOfRanges().toString());
  }

  public void testRejectPolicy_putAllIsAtomic() {
    TreeRangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closed(0, 10), "a");

    TreeRangeMap<Integer, String> incoming = TreeRangeMap.create();
    incoming.put(Range.closed(20, 30), "ok"); // disjoint
    incoming.put(Range.closed(5, 6), "bad"); // intersecting

    RangeMap<Integer, String> strict = RangeMapPuts.guarding(map, PutPolicy.REJECT_INTERSECTION);
    assertThrows(RangeIntersectionException.class, () -> strict.putAll(incoming));
    // Neither entry landed: the delegate is unchanged.
    assertEquals("{[0..10]=a}", map.asMapOfRanges().toString());
  }
}
