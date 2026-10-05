/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Unit tests for {@link MultisetCountPlan}.
 *
 * @author Guava Authors
 */
@NullMarked
public final class MultisetCountPlanTest extends TestCase {

  /** Two distinct elements that deliberately share a hash code. */
  private static final class CollidingKey {
    private final String id;

    CollidingKey(String id) {
      this.id = id;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof CollidingKey && ((CollidingKey) obj).id.equals(id);
    }

    @Override
    public int hashCode() {
      return 42; // deliberate collision: equal hash codes must not imply equal elements
    }

    @Override
    public String toString() {
      return "key(" + id + ")";
    }
  }

  public void testTwoElementBatchSucceeds() {
    Multiset<String> source = HashMultiset.create();
    source.add("a");
    source.add("b", 2);

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder()
            .add("a", 2, 1)
            .setCount("b", 5, 2)
            .build();

    ImmutableMultiset<String> result = plan.apply(source);

    assertThat(result.count("a")).isEqualTo(3);
    assertThat(result.count("b")).isEqualTo(5);
    assertThat(result.size()).isEqualTo(8);
    // The source is untouched and the result is separated from it.
    assertThat(source.count("a")).isEqualTo(1);
    assertThat(source.count("b")).isEqualTo(2);
    source.add("a", 10);
    assertThat(result.count("a")).isEqualTo(3);
  }

  public void testThreeOperationExample() {
    Multiset<String> source = HashMultiset.create();
    source.add("apple");
    source.add("cherry", 4);

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder()
            .add("apple", 2, 1) // apple: 1 -> 3
            .setCount("banana", 5, 0) // banana: 0 -> 5
            .remove("cherry", 1, 4) // cherry: 4 -> 3
            .build();

    ImmutableMultiset<String> result = plan.apply(source);

    assertThat(result.count("apple")).isEqualTo(3);
    assertThat(result.count("banana")).isEqualTo(5);
    assertThat(result.count("cherry")).isEqualTo(3);
    assertThat(source.count("apple")).isEqualTo(1);
    assertThat(source.count("cherry")).isEqualTo(4);
  }

  public void testSecondOperationPreconditionFailureLeavesBatchUnapplied() {
    Multiset<String> source = HashMultiset.create();
    source.add("a");
    source.add("b", 2);
    Multiset<String> before = HashMultiset.create(source);

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder()
            .add("a", 2, 1) // valid on its own
            .setCount("b", 5, 3) // wrong: original count of b is 2, not 3
            .build();

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, () -> plan.apply(source));
    // The failure message retains the index of the offending operation.
    assertThat(expected).hasMessageThat().contains("operation[1]");
    assertThat(expected).hasMessageThat().contains("expected original count 3 but found 2");
    // The whole batch is rejected: the source is exactly as it was.
    assertThat(source).isEqualTo(before);
    assertThat(source.count("a")).isEqualTo(1);
    assertThat(source.count("b")).isEqualTo(2);
  }

  public void testMaxCountPlusOneRejectedInsteadOfWrappingToZero() {
    Multiset<String> source = HashMultiset.create();
    source.setCount("big", Integer.MAX_VALUE);

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder().add("big", 1, Integer.MAX_VALUE).build();

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, () -> plan.apply(source));
    assertThat(expected).hasMessageThat().contains("operation[0]");
    assertThat(expected).hasMessageThat().contains("overflow");
    // Rejected, not wrapped: the count is still Integer.MAX_VALUE, not 0 or negative.
    assertThat(source.count("big")).isEqualTo(Integer.MAX_VALUE);
  }

  public void testAccumulatedOverflowAcrossOperationsRejected() {
    Multiset<String> source = HashMultiset.create();

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder()
            .add("a", Integer.MAX_VALUE, 0)
            .add("a", 1, 0) // each precondition holds, but the accumulated count overflows
            .build();

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, () -> plan.apply(source));
    assertThat(expected).hasMessageThat().contains("operation[1]");
    assertThat(expected).hasMessageThat().contains("overflow");
    assertThat(source.count("a")).isEqualTo(0);
  }

  public void testNegativeAmountsRejectedWhenBuildingPlan() {
    MultisetCountPlan.Builder<String> builder = MultisetCountPlan.builder();

    IllegalArgumentException addFailure =
        assertThrows(IllegalArgumentException.class, () -> builder.add("a", -1, 0));
    assertThat(addFailure).hasMessageThat().contains("operation[0]");

    IllegalArgumentException setFailure =
        assertThrows(IllegalArgumentException.class, () -> builder.setCount("a", -1, 0));
    assertThat(setFailure).hasMessageThat().contains("operation[0]");

    IllegalArgumentException removeFailure =
        assertThrows(IllegalArgumentException.class, () -> builder.remove("a", -1, 0));
    assertThat(removeFailure).hasMessageThat().contains("operation[0]");

    IllegalArgumentException expectationFailure =
        assertThrows(IllegalArgumentException.class, () -> builder.add("a", 1, -1));
    assertThat(expectationFailure).hasMessageThat().contains("operation[0]");
  }

  public void testRemoveBelowZeroRejected() {
    Multiset<String> source = HashMultiset.create();
    source.add("a");

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder().remove("a", 2, 1).build();

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, () -> plan.apply(source));
    assertThat(expected).hasMessageThat().contains("operation[0]");
    assertThat(expected).hasMessageThat().contains("negative");
    assertThat(source.count("a")).isEqualTo(1);
  }

  public void testHashCollisionElementsStayDistinct() {
    CollidingKey x = new CollidingKey("x");
    CollidingKey y = new CollidingKey("y");
    assertThat(x.hashCode()).isEqualTo(y.hashCode());
    assertThat(x).isNotEqualTo(y);

    Multiset<CollidingKey> source = HashMultiset.create();
    source.add(x, 3);
    source.add(y, 7);

    MultisetCountPlan<CollidingKey> plan =
        MultisetCountPlan.<CollidingKey>builder()
            .add(x, 1, 3)
            .remove(y, 2, 7)
            .build();

    ImmutableMultiset<CollidingKey> result = plan.apply(source);

    // Equal hash codes did not conflate the two elements.
    assertThat(result.count(x)).isEqualTo(4);
    assertThat(result.count(y)).isEqualTo(5);
  }

  public void testHashCollisionDoesNotSatisfyPreconditionOfOtherElement() {
    CollidingKey x = new CollidingKey("x");
    CollidingKey y = new CollidingKey("y");

    Multiset<CollidingKey> source = HashMultiset.create();
    source.add(x, 3);

    MultisetCountPlan<CollidingKey> plan =
        MultisetCountPlan.<CollidingKey>builder()
            .setCount(y, 9, 3) // y's original count is 0, even though y collides with x
            .build();

    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, () -> plan.apply(source));
    assertThat(expected).hasMessageThat().contains("operation[0]");
    assertThat(source.count(x)).isEqualTo(3);
    assertThat(source.count(y)).isEqualTo(0);
  }

  public void testHugeCountAppliedWithoutRepeatedInsertion() {
    Multiset<String> source = HashMultiset.create();

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder()
            .setCount("big", Integer.MAX_VALUE - 1, 0)
            .add("big", 1, 0) // precondition binds the original count, not the running one
            .build();

    // This returns immediately: counts are written directly, not one insert per occurrence.
    ImmutableMultiset<String> result = plan.apply(source);

    assertThat(result.count("big")).isEqualTo(Integer.MAX_VALUE);
    assertThat(result.entrySet()).hasSize(1);
  }

  public void testRemoveAllOccurrencesDropsElement() {
    Multiset<String> source = HashMultiset.create();
    source.add("a", 2);
    source.add("b", 1);

    MultisetCountPlan<String> plan =
        MultisetCountPlan.<String>builder().remove("a", 2, 2).build();

    ImmutableMultiset<String> result = plan.apply(source);

    assertThat(result.count("a")).isEqualTo(0);
    assertThat(result.contains("a")).isFalse();
    assertThat(result.count("b")).isEqualTo(1);
  }

  public void testEmptyPlanReturnsEqualButSeparateCopy() {
    Multiset<String> source = HashMultiset.create();
    source.add("a", 2);

    ImmutableMultiset<String> result =
        MultisetCountPlan.<String>builder().build().apply(source);

    assertThat(result).isEqualTo(source);
    assertThat(result).isInstanceOf(ImmutableMultiset.class);
    source.add("a");
    assertThat(result.count("a")).isEqualTo(2);
  }

  public void testNullElementRejected() {
    MultisetCountPlan.Builder<String> builder = MultisetCountPlan.builder();
    assertThrows(NullPointerException.class, () -> builder.add(null, 1, 0));
    assertThrows(NullPointerException.class, () -> builder.setCount(null, 1, 0));
    assertThrows(NullPointerException.class, () -> builder.remove(null, 1, 0));
  }
}
