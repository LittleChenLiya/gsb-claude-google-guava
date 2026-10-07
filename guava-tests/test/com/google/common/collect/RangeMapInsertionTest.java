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

import static com.google.common.collect.RangeMapInsertion.ConflictPolicy.ALLOW_OVERWRITE;
import static com.google.common.collect.RangeMapInsertion.ConflictPolicy.REJECT;
import static com.google.common.collect.RangeMapInsertion.Overlap.COVERING;
import static com.google.common.collect.RangeMapInsertion.Overlap.INTERSECTING;
import static com.google.common.collect.RangeMapInsertion.Overlap.NONE;
import static com.google.common.collect.RangeMapInsertion.Overlap.TOUCHING;

import com.google.common.annotations.GwtIncompatible;
import java.util.HashMap;
import java.util.Map;
import junit.framework.TestCase;

/**
 * Tests for {@link RangeMapInsertion}.
 *
 * <p>The fixtures are small in-memory {@link TreeRangeMap} instances covering three range
 * scenarios: adjacent open/closed endpoints, containing (enclosing) ranges and empty ranges.
 */
@GwtIncompatible // RangeMap
public class RangeMapInsertionTest extends TestCase {

  private static Map<Range<Integer>, String> snapshot(RangeMap<Integer, String> rangeMap) {
    return new HashMap<>(rangeMap.asMapOfRanges());
  }

  /** Adjacent ranges: open/closed endpoints decide whether ranges touch or truly intersect. */
  public void testAdjacentOpenClosedEndpoints() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(1, 3), "a"); // [1, 3)

    // [1,3) and [3,5) share endpoint 3, but [1,3) excludes it: touching, not overlapping.
    assertEquals(TOUCHING, RangeMapInsertion.check(map, Range.closedOpen(3, 5)));
    // (3,5] excludes 3 as well, so the two ranges are genuinely disjoint (gap at point 3).
    assertEquals(NONE, RangeMapInsertion.check(map, Range.openClosed(3, 5)));
    // A fully disjoint range is not even connected.
    assertEquals(NONE, RangeMapInsertion.check(map, Range.closedOpen(5, 7)));
    // With the existing entry closed at 3, the open/closed endpoints flip the classification:
    // [1,3] and (3,5] touch at 3 without sharing it, while [1,3] and [3,5] both contain 3.
    map.put(Range.closed(1, 3), "b"); // replaces [1,3)
    assertEquals(TOUCHING, RangeMapInsertion.check(map, Range.openClosed(3, 5)));
    assertEquals(INTERSECTING, RangeMapInsertion.check(map, Range.closed(3, 5)));
    assertEquals(INTERSECTING, RangeMapInsertion.check(map, Range.closedOpen(2, 5)));

    // Touching ranges may be inserted under the strict policy.
    RangeMap<Integer, String> map2 = TreeRangeMap.create();
    map2.put(Range.closedOpen(1, 3), "a");
    Map<Range<Integer>, String> before = snapshot(map2);
    assertTrue(RangeMapInsertion.put(map2, Range.closedOpen(3, 5), "b", REJECT));
    assertEquals("a", map2.get(2));
    assertEquals("b", map2.get(4));
    assertEquals(2, map2.asMapOfRanges().size());
    // A disjoint insertion under the strict policy works as well.
    assertTrue(RangeMapInsertion.put(map2, Range.closedOpen(8, 9), "c", REJECT));
    assertEquals(3, map2.asMapOfRanges().size());
    assertFalse(before.equals(snapshot(map2))); // sanity: the map really grew
  }

  /** Containing ranges: a candidate that encloses existing entries is classified COVERING. */
  public void testContainingRanges() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(2, 4), "a");
    map.put(Range.closedOpen(7, 9), "b");

    // [1,5) encloses [2,4) and touches nothing else.
    assertEquals(COVERING, RangeMapInsertion.check(map, Range.closedOpen(1, 5)));
    // [0,10) encloses both entries.
    assertEquals(COVERING, RangeMapInsertion.check(map, Range.closedOpen(0, 10)));
    // An equal range is also an overwrite of the whole entry.
    assertEquals(COVERING, RangeMapInsertion.check(map, Range.closedOpen(2, 4)));
    // [1,3) only partially overlaps [2,4): a true intersection.
    assertEquals(INTERSECTING, RangeMapInsertion.check(map, Range.closedOpen(1, 3)));
    // Covering one entry while partially intersecting another is still INTERSECTING.
    assertEquals(INTERSECTING, RangeMapInsertion.check(map, Range.closedOpen(1, 8)));

    // Strict mode rejects the covering candidate and leaves the map unchanged.
    Map<Range<Integer>, String> before = snapshot(map);
    assertFalse(RangeMapInsertion.put(map, Range.closedOpen(1, 5), "x", REJECT));
    assertEquals(before, snapshot(map));
    assertEquals("a", map.get(3));

    // Allow mode delegates to RangeMap.put and overwrites the enclosed entry.
    assertTrue(RangeMapInsertion.put(map, Range.closedOpen(1, 5), "x", ALLOW_OVERWRITE));
    assertEquals("x", map.get(3));
    assertEquals("b", map.get(8));
    assertEquals(2, map.asMapOfRanges().size());
  }

  /** Empty ranges: classified NONE and inserted as a no-op, exactly like RangeMap.put. */
  public void testEmptyRanges() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(1, 5), "a");

    // The empty range [3,3) lies "inside" [1,5) but can never conflict.
    assertEquals(NONE, RangeMapInsertion.check(map, Range.closedOpen(3, 3)));
    assertEquals(NONE, RangeMapInsertion.check(map, Range.openClosed(3, 3)));

    Map<Range<Integer>, String> before = snapshot(map);
    assertTrue(RangeMapInsertion.put(map, Range.closedOpen(3, 3), "x", REJECT));
    assertEquals(before, snapshot(map)); // no-op, as RangeMap.put ignores empty ranges
    assertTrue(RangeMapInsertion.put(map, Range.closedOpen(3, 3), "x", ALLOW_OVERWRITE));
    assertEquals(before, snapshot(map));

    // Empty candidate on an empty map.
    RangeMap<Integer, String> empty = TreeRangeMap.create();
    assertEquals(NONE, RangeMapInsertion.check(empty, Range.closedOpen(0, 0)));
    assertTrue(RangeMapInsertion.put(empty, Range.closedOpen(0, 0), "x", REJECT));
    assertTrue(empty.asMapOfRanges().isEmpty());
  }

  /** A true intersection is strictly rejected and the original map is preserved. */
  public void testIntersectionRejectedAndMapUnchanged() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(1, 5), "a");
    map.put(Range.closedOpen(9, 12), "b");
    Map<Range<Integer>, String> before = snapshot(map);

    assertEquals(INTERSECTING, RangeMapInsertion.check(map, Range.closedOpen(3, 7)));
    assertFalse(RangeMapInsertion.put(map, Range.closedOpen(3, 7), "x", REJECT));
    assertEquals(before, snapshot(map));
    assertEquals("a", map.get(4));
    assertNull(map.get(6));
    assertEquals("b", map.get(10));
    assertEquals(Range.closedOpen(1, 12), map.span());
  }

  /** Allow mode on a true intersection delegates to the original put semantics. */
  public void testAllowOverwriteOnIntersection() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(1, 5), "a");

    assertTrue(RangeMapInsertion.put(map, Range.closedOpen(3, 7), "x", ALLOW_OVERWRITE));
    // TreeRangeMap.put keeps the non-overlapping prefix [1,3) and inserts [3,7).
    assertEquals("a", map.get(2));
    assertEquals("x", map.get(4));
    assertEquals("x", map.get(6));
    assertEquals(2, map.asMapOfRanges().size());
  }

  /** Without the new helper, the original RangeMap interface keeps its overwrite semantics. */
  public void testOriginalInterfaceUnchanged() {
    RangeMap<Integer, String> map = TreeRangeMap.create();
    map.put(Range.closedOpen(1, 5), "a");
    map.put(Range.closedOpen(3, 7), "x"); // plain put still overwrites, as before
    assertEquals("a", map.get(2));
    assertEquals("x", map.get(4));
    assertEquals("x", map.get(6));
  }
}
