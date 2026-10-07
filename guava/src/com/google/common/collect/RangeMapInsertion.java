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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtIncompatible;
import java.util.Map.Entry;

/**
 * Pre-insertion conflict checking for local updates to a {@link RangeMap}.
 *
 * <p>This helper classifies how a candidate range relates to the entries already present in a
 * {@code RangeMap} — distinguishing {@linkplain Overlap#COVERING covering}, {@linkplain
 * Overlap#TOUCHING touching-but-not-overlapping} and {@linkplain Overlap#INTERSECTING truly
 * intersecting} relationships, computed from the open/closed endpoints of the ranges — and offers a
 * {@linkplain ConflictPolicy#REJECT strict} insertion mode that refuses to modify the map when the
 * candidate truly intersects existing entries, as well as an {@linkplain
 * ConflictPolicy#ALLOW_OVERWRITE allow} mode that simply delegates to {@link RangeMap#put}.
 *
 * <p>All relationship computation is delegated to {@link Range#isConnected}, {@link
 * Range#intersection} and {@link Range#encloses}; no range arithmetic is reimplemented here. The
 * helper is purely additive: callers that do not use it observe the unchanged {@link RangeMap}
 * behavior.
 *
 * @since NEXT
 */
@Beta
@GwtIncompatible // NavigableMap (via RangeMap implementations)
@SuppressWarnings("rawtypes") // https://github.com/google/guava/issues/989
public final class RangeMapInsertion {

  /**
   * Classification of how a candidate range relates to the existing entries of a {@link RangeMap}.
   */
  public enum Overlap {
    /** No existing entry is even {@linkplain Range#isConnected connected} to the candidate range. */
    NONE,

    /**
     * At least one existing entry is {@linkplain Range#isConnected connected} to the candidate
     * range, but every intersection is empty. Whether two ranges merely touch is decided by their
     * open/closed endpoints: {@code [1, 3)} and {@code [3, 5)} touch, while {@code [1, 3]} and
     * {@code [3, 5]} intersect in the single point {@code 3}.
     */
    TOUCHING,

    /**
     * The candidate range has a nonempty intersection with at least one existing entry, and every
     * intersected entry is {@linkplain Range#encloses enclosed} by the candidate range: a {@code
     * put} would completely replace those entries.
     */
    COVERING,

    /**
     * The candidate range has a nonempty intersection with at least one existing entry that it does
     * not enclose: a {@code put} would truncate or split existing entries.
     */
    INTERSECTING
  }

  /** How {@link #put} handles a candidate range that conflicts with existing entries. */
  public enum ConflictPolicy {
    /**
     * Refuse to insert when the candidate range would modify any existing entry (that is, when
     * {@link #check} reports {@link Overlap#COVERING} or {@link Overlap#INTERSECTING}). The map is
     * left completely unchanged and {@code put} returns {@code false}.
     */
    REJECT,

    /**
     * Always delegate to {@link RangeMap#put}, overwriting existing entries exactly as the
     * underlying {@code RangeMap} implementation does.
     */
    ALLOW_OVERWRITE
  }

  /**
   * Classifies the relationship between {@code range} and the entries currently present in {@code
   * rangeMap}, without modifying the map.
   *
   * <p>An {@linkplain Range#isEmpty empty} candidate range is always classified {@link
   * Overlap#NONE}, matching the fact that {@link RangeMap#put} ignores empty ranges.
   */
  public static <K extends Comparable, V> Overlap check(RangeMap<K, V> rangeMap, Range<K> range) {
    checkNotNull(rangeMap);
    checkNotNull(range);
    if (range.isEmpty()) {
      return Overlap.NONE;
    }
    boolean touching = false;
    boolean intersecting = false;
    boolean partial = false;
    for (Entry<Range<K>, V> entry : rangeMap.asMapOfRanges().entrySet()) {
      Range<K> existing = entry.getKey();
      if (!existing.isConnected(range)) {
        continue;
      }
      if (existing.intersection(range).isEmpty()) {
        touching = true;
      } else {
        intersecting = true;
        if (!range.encloses(existing)) {
          partial = true;
        }
      }
    }
    if (partial) {
      return Overlap.INTERSECTING;
    }
    if (intersecting) {
      return Overlap.COVERING;
    }
    return touching ? Overlap.TOUCHING : Overlap.NONE;
  }

  /**
   * Attempts to insert {@code (range, value)} into {@code rangeMap} according to {@code policy}.
   *
   * <p>Under {@link ConflictPolicy#REJECT}, if {@link #check} reports {@link Overlap#COVERING} or
   * {@link Overlap#INTERSECTING}, the map is left unchanged and this method returns {@code false};
   * otherwise the call is delegated to {@link RangeMap#put} and this method returns {@code true}.
   *
   * <p>Under {@link ConflictPolicy#ALLOW_OVERWRITE}, the call is always delegated to {@link
   * RangeMap#put} and this method returns {@code true}.
   *
   * <p>Note that, as with {@link RangeMap#put} itself, inserting an {@linkplain Range#isEmpty
   * empty} range is a no-op even when this method returns {@code true}.
   *
   * @return {@code true} if the insertion was delegated to {@link RangeMap#put}; {@code false} if
   *     it was rejected, in which case the map is unchanged
   */
  public static <K extends Comparable, V> boolean put(
      RangeMap<K, V> rangeMap, Range<K> range, V value, ConflictPolicy policy) {
    checkNotNull(rangeMap);
    checkNotNull(range);
    checkNotNull(value);
    checkNotNull(policy);
    if (policy == ConflictPolicy.REJECT) {
      Overlap overlap = check(rangeMap, range);
      if (overlap == Overlap.COVERING || overlap == Overlap.INTERSECTING) {
        return false;
      }
    }
    rangeMap.put(range, value);
    return true;
  }

  private RangeMapInsertion() {}
}
