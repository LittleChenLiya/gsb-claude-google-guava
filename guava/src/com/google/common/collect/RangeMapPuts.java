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

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.common.base.MoreObjects;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Pre-insertion conflict checks for {@link RangeMap}.
 *
 * <p>This class classifies the relationship between a range requested for insertion and the
 * ranges already present in a {@code RangeMap}, honoring the open/closed endpoints of every range:
 *
 * <ul>
 *   <li>{@link ConflictType#DISJOINT} &mdash; the range does not connect to any existing range.
 *   <li>{@link ConflictType#TOUCHING} &mdash; the range merely meets an existing range at an
 *       endpoint, e.g. {@code [0, 2)} and {@code [2, 4]}. Their intersection is empty, so nothing
 *       currently stored is overwritten.
 *   <li>{@link ConflictType#COVERING} &mdash; the range fully encloses one or more existing ranges,
 *       e.g. inserting {@code [0, 10]} over {@code [2, 8]}.
 *   <li>{@link ConflictType#INTERSECTING} &mdash; the range shares a nonempty region with an
 *       existing range without enclosing it, so the insertion would split that entry, e.g.
 *       inserting {@code [2, 8]} into a map containing {@code [0, 10]}. A single shared endpoint
 *       that is included on both sides, such as {@code [0, 2]} against {@code [2, 4]}, is also an
 *       intersection.
 * </ul>
 *
 * <p>Classification uses only the public {@link Range} predicates ({@link Range#isConnected},
 * {@link Range#intersection} and {@link Range#encloses}); no interval arithmetic is reimplemented
 * here. Likewise, when an insertion is permitted the actual update is delegated to the backing
 * {@link RangeMap#put} &mdash; this class never rewrites or splits ranges itself.
 *
 * @author Guava Authors
 * @since NEXT
 */
@J2ktIncompatible
@GwtIncompatible // Uses NavigableMap-backed RangeMaps
@SuppressWarnings("rawtypes") // https://github.com/google/guava/issues/989
public final class RangeMapPuts {
  private RangeMapPuts() {}

  /**
   * The kind of relationship an inserted range has against the ranges already present in a {@link
   * RangeMap}.
   *
   * @since NEXT
   */
  public enum ConflictType {
    /** The inserted range does not connect to any existing range. */
    DISJOINT,
    /**
     * The inserted range meets one or more existing ranges at endpoints, but every such
     * intersection is empty, so no existing value is overwritten.
     */
    TOUCHING,
    /** The inserted range fully encloses at least one existing range. */
    COVERING,
    /**
     * The inserted range shares a nonempty region with at least one existing range without
     * enclosing it, so a plain {@code put} would split that entry.
     */
    INTERSECTING;

    /**
     * Returns {@code true} if an insertion with this conflict type would overwrite any existing
     * association, i.e. the conflict is {@link #COVERING} or {@link #INTERSECTING}. A {@link
     * #TOUCHING} range never overwrites anything.
     */
    public boolean isOverwrite() {
      return this == COVERING || this == INTERSECTING;
    }
  }

  /**
   * What a guarded {@link RangeMap#put} should do when the requested range intersects an existing
   * range in a nonempty region.
   *
   * @since NEXT
   */
  public enum PutPolicy {
    /**
     * Performs the plain {@link RangeMap#put} unconditionally, exactly like using the original
     * interface directly. This is the default when the new check is not enabled.
     */
    DEFAULT,
    /**
     * Rejects any insertion that would overwrite existing associations ({@link
     * ConflictType#COVERING} or {@link ConflictType#INTERSECTING}), leaving the original map
     * unchanged. {@link ConflictType#TOUCHING} insertions are still accepted, since they do not
     * overwrite anything.
     */
    REJECT_INTERSECTION,
    /**
     * Delegates to the original {@link RangeMap#put}, allowing existing associations to be
     * overwritten and split. This is equivalent to {@link #DEFAULT} but documents that overwriting
     * was considered and accepted.
     */
    ALLOW_OVERWRITE
  }

  /**
   * The result of an {@link #inspect} call: the overall {@link ConflictType}, ordered by severity
   * ({@link ConflictType#INTERSECTING} &gt; {@link ConflictType#COVERING} &gt; {@link
   * ConflictType#TOUCHING} &gt; {@link ConflictType#DISJOINT}), plus every existing range involved.
   *
   * @since NEXT
   */
  public static final class PutCheck<K extends Comparable, V> {
    private final ConflictType conflictType;
    private final ImmutableList<Range<K>> touchingRanges;
    private final ImmutableList<Range<K>> intersectingRanges;

    private PutCheck(
        ConflictType conflictType,
        ImmutableList<Range<K>> touchingRanges,
        ImmutableList<Range<K>> intersectingRanges) {
      this.conflictType = conflictType;
      this.touchingRanges = touchingRanges;
      this.intersectingRanges = intersectingRanges;
    }

    /** Returns the overall conflict type, the most severe type observed against any entry. */
    public ConflictType conflictType() {
      return conflictType;
    }

    /** Returns the existing ranges only touched at an endpoint, in ascending order. */
    public ImmutableList<Range<K>> touchingRanges() {
      return touchingRanges;
    }

    /**
     * Returns the existing ranges whose intersection with the inserted range is nonempty (both
     * {@link ConflictType#COVERING covering} and {@link ConflictType#INTERSECTING intersecting}
     * entries), in ascending order.
     */
    public ImmutableList<Range<K>> intersectingRanges() {
      return intersectingRanges;
    }

    /**
     * Returns {@code true} if a plain {@code put} would overwrite or split any existing
     * association.
     */
    public boolean isIntersection() {
      return conflictType.isOverwrite();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("conflictType", conflictType)
          .add("touchingRanges", touchingRanges)
          .add("intersectingRanges", intersectingRanges)
          .toString();
    }
  }

  /**
   * Classifies the effect of inserting {@code range} into {@code rangeMap} against the ranges it
   * already contains. The map is never modified. An empty range is always reported as {@link
   * ConflictType#DISJOINT}, consistent with {@link RangeMap#put} treating it as a no-op.
   */
  public static <K extends Comparable, V> PutCheck<K, V> inspect(
      RangeMap<K, V> rangeMap, Range<K> range) {
    checkNotNull(rangeMap);
    checkNotNull(range);
    if (range.isEmpty()) {
      return new PutCheck<>(ConflictType.DISJOINT, ImmutableList.of(), ImmutableList.of());
    }
    ImmutableList.Builder<Range<K>> touching = ImmutableList.builder();
    ImmutableList.Builder<Range<K>> intersecting = ImmutableList.builder();
    ConflictType type = ConflictType.DISJOINT;
    // asMapOfRanges() iterates in ascending order, so the reported ranges stay sorted.
    for (Range<K> existing : rangeMap.asMapOfRanges().keySet()) {
      ConflictType entryType = classify(existing, range);
      if (entryType == ConflictType.DISJOINT) {
        continue;
      }
      if (entryType == ConflictType.TOUCHING) {
        touching.add(existing);
      } else {
        intersecting.add(existing);
      }
      if (entryType.ordinal() > type.ordinal()) {
        type = entryType;
      }
    }
    return new PutCheck<>(type, touching.build(), intersecting.build());
  }

  /**
   * Classifies a single existing range against the range being inserted, honoring open/closed
   * endpoints. Delegates all interval geometry to {@link Range}.
   */
  private static <K extends Comparable> ConflictType classify(
      Range<K> existing, Range<K> inserted) {
    if (!existing.isConnected(inserted)) {
      return ConflictType.DISJOINT;
    }
    Range<K> intersection = existing.intersection(inserted);
    if (intersection.isEmpty()) {
      // Connected but the shared endpoints are not both included: merely touching.
      return ConflictType.TOUCHING;
    }
    // Nonempty overlap. "Covering" means the incoming range wholly replaces an entry; everything
    // else is a genuine partial intersection that a plain put would split.
    return inserted.encloses(existing) ? ConflictType.COVERING : ConflictType.INTERSECTING;
  }

  /**
   * Inserts {@code range} into {@code rangeMap} according to {@code policy}.
   *
   * <p>Under {@link PutPolicy#REJECT_INTERSECTION}, a {@link RangeIntersectionException} is thrown
   * when the range would overwrite an existing association and the map is left untouched (the
   * inspection runs before any mutation). Under {@link PutPolicy#DEFAULT} and {@link
   * PutPolicy#ALLOW_OVERWRITE}, this simply delegates to {@link RangeMap#put}; this method never
   * performs the insertion itself.
   *
   * @throws RangeIntersectionException under {@code REJECT_INTERSECTION} on a nonempty intersection
   */
  public static <K extends Comparable, V> void put(
      RangeMap<K, V> rangeMap, Range<K> range, V value, PutPolicy policy) {
    checkNotNull(rangeMap);
    checkNotNull(range);
    checkNotNull(value);
    checkNotNull(policy);
    if (policy == PutPolicy.REJECT_INTERSECTION) {
      PutCheck<K, V> check = inspect(rangeMap, range);
      if (check.isIntersection()) {
        throw new RangeIntersectionException(range, check);
      }
    }
    rangeMap.put(range, value);
  }

  /**
   * Returns a {@link RangeMap} view backed by {@code delegate} that applies {@code policy} to
   * {@link RangeMap#put}, {@link RangeMap#putCoalescing} and {@link RangeMap#putAll}.
   *
   * <p>With {@link PutPolicy#DEFAULT} (or {@link PutPolicy#ALLOW_OVERWRITE}) the view behaves
   * exactly like the original {@code RangeMap}, so code that does not opt into the new check keeps
   * working through the same {@link RangeMap} interface. Under {@link
   * PutPolicy#REJECT_INTERSECTION}, a rejected call leaves the delegate unchanged.
   */
  public static <K extends Comparable, V> RangeMap<K, V> guarding(
      RangeMap<K, V> delegate, PutPolicy policy) {
    checkNotNull(delegate);
    checkNotNull(policy);
    return new GuardingRangeMap<>(delegate, policy);
  }

  /**
   * Thrown when a guarded insertion is rejected because its range has a nonempty intersection with
   * an existing association.
   *
   * @since NEXT
   */
  public static final class RangeIntersectionException extends IllegalArgumentException {
    @GwtIncompatible @J2ktIncompatible private static final long serialVersionUID = 0;

    private final transient Range<?> requestedRange;
    private final transient ConflictType conflictType;
    private final transient ImmutableList<?> intersectingRanges;

    private <K extends Comparable> RangeIntersectionException(
        Range<K> requestedRange, PutCheck<K, ?> check) {
      super(
          "Refused to insert "
              + requestedRange
              + ": it intersects existing ranges "
              + check.intersectingRanges()
              + " ("
              + check.conflictType()
              + ")");
      this.requestedRange = requestedRange;
      this.conflictType = check.conflictType();
      this.intersectingRanges = check.intersectingRanges();
    }

    /** Returns the range whose insertion was rejected. */
    public Range<?> requestedRange() {
      return requestedRange;
    }

    /**
     * Returns the observed conflict type ({@link ConflictType#COVERING} or {@link
     * ConflictType#INTERSECTING}).
     */
    public ConflictType conflictType() {
      return conflictType;
    }

    /** Returns the existing ranges that caused the rejection, in ascending order. */
    public ImmutableList<?> intersectingRanges() {
      return intersectingRanges;
    }
  }

  /**
   * A forwarding {@link RangeMap} that guards mutating range insertions. All other operations,
   * including the actual update once a request is accepted, are delegated unchanged.
   */
  private static final class GuardingRangeMap<K extends Comparable, V>
      implements RangeMap<K, V> {
    private final RangeMap<K, V> delegate;
    private final PutPolicy policy;

    GuardingRangeMap(RangeMap<K, V> delegate, PutPolicy policy) {
      this.delegate = delegate;
      this.policy = policy;
    }

    @Override
    public @Nullable V get(K key) {
      return delegate.get(key);
    }

    @Override
    public @Nullable Entry<Range<K>, V> getEntry(K key) {
      return delegate.getEntry(key);
    }

    @Override
    public Range<K> span() {
      return delegate.span();
    }

    @Override
    public void put(Range<K> range, V value) {
      RangeMapPuts.put(delegate, range, value, policy);
    }

    @Override
    public void putCoalescing(Range<K> range, V value) {
      checkNotNull(range);
      checkNotNull(value);
      if (policy == PutPolicy.REJECT_INTERSECTION) {
        PutCheck<K, V> check = RangeMapPuts.inspect(delegate, range);
        if (check.isIntersection()) {
          throw new RangeIntersectionException(range, check);
        }
      }
      delegate.putCoalescing(range, value);
    }

    @Override
    public void putAll(RangeMap<K, ? extends V> rangeMap) {
      checkNotNull(rangeMap);
      if (policy == PutPolicy.REJECT_INTERSECTION) {
        // Validate every incoming entry first so a failed putAll does not partially mutate the
        // delegate.
        for (Entry<Range<K>, ? extends V> entry : rangeMap.asMapOfRanges().entrySet()) {
          PutCheck<K, V> check = RangeMapPuts.inspect(delegate, entry.getKey());
          if (check.isIntersection()) {
            throw new RangeIntersectionException(entry.getKey(), check);
          }
        }
      }
      delegate.putAll(rangeMap);
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public void remove(Range<K> range) {
      delegate.remove(range);
    }

    @Override
    public void merge(
        Range<K> range,
        @Nullable V value,
        BiFunction<? super V, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
      delegate.merge(range, value, remappingFunction);
    }

    @Override
    public Map<Range<K>, V> asMapOfRanges() {
      return delegate.asMapOfRanges();
    }

    @Override
    public Map<Range<K>, V> asDescendingMapOfRanges() {
      return delegate.asDescendingMapOfRanges();
    }

    @Override
    public RangeMap<K, V> subRangeMap(Range<K> range) {
      return RangeMapPuts.guarding(delegate.subRangeMap(range), policy);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return delegate.equals(o);
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
