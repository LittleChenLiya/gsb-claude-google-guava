/*
 * Copyright (C) 2026 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.annotations.GwtCompatible;
import java.util.List;
import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Unit test for {@link MultisetChangePlan}.
 *
 * <p>To run locally:
 *
 * <pre>{@code
 * ./mvnw test -pl guava-tests -Dtest=MultisetChangePlanTest -DfailIfNoTests=false
 * }</pre>
 *
 * @author Guava Authors
 */
@GwtCompatible
@NullMarked
public class MultisetChangePlanTest extends TestCase {

  /**
   * A two-element batch succeeds: both operations are applied and the result preserves the
   * operation index (the previous count of each element before the corresponding operation).
   */
  public void testTwoElementBatchSucceeds() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("a", 2);
    multiset.add("b", 3);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 3); // op 0: a 2 -> 5
    plan.setCount("b", 7); // op 1: b 3 -> 7

    List<Integer> previousCounts = plan.apply(multiset);

    // The result is indexed by operation: [count of a before op 0, count of b before op 1].
    assertArrayEquals(new Integer[] {2, 3}, previousCounts.toArray(new Integer[0]));
    assertEquals(5, multiset.count("a"));
    assertEquals(7, multiset.count("b"));
  }

  /**
   * If the second operation's precondition count is wrong, the original set is unchanged. The
   * precondition is bound to the element's original count, and a failure aborts the whole batch.
   */
  public void testSecondOperationPreconditionWrongLeavesSetUnchanged() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("a", 2);
    multiset.add("b", 3);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 3, 2); // op 0: precondition a == 2 (correct)
    plan.setCount("b", 99, 7); // op 1: precondition b == 99 (wrong; b is 3)

    assertThrows(IllegalStateException.class, () -> plan.apply(multiset));

    // The whole batch is aborted: the original set is unchanged.
    assertEquals(2, multiset.count("a"));
    assertEquals(3, multiset.count("b"));
  }

  /**
   * Adding one occurrence to an element already at Integer.MAX_VALUE is rejected (int overflow)
   * rather than wrapping around to zero.
   */
  public void testMaxCountPlusOneRejectedInsteadOfWrappingToZero() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.setCount("a", Integer.MAX_VALUE);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 1); // would overflow: MAX_VALUE + 1

    assertThrows(IllegalArgumentException.class, () -> plan.apply(multiset));

    // The count stays at MAX_VALUE (not wrapped to zero).
    assertEquals(Integer.MAX_VALUE, multiset.count("a"));
  }

  /**
   * The source (plan) preserves the operation index: the returned list has one entry per operation,
   * in plan order, each entry being the count of the element before that operation.
   */
  public void testSourcePreservesOperationIndex() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("a", 1);
    multiset.add("b", 2);
    multiset.add("c", 3);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 10); // op 0: a 1 -> 11
    plan.remove("b", 1); // op 1: b 2 -> 1
    plan.setCount("c", 5); // op 2: c 3 -> 5

    List<Integer> previousCounts = plan.apply(multiset);

    assertEquals(3, previousCounts.size());
    assertArrayEquals(new Integer[] {1, 2, 3}, previousCounts.toArray(new Integer[0]));
  }

  /**
   * A three-operation example covering add, setCount, and remove in one batch. The result is
   * applied atomically and the previous counts are returned in operation order.
   */
  public void testThreeOperationExample() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("a", 2);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 3); // op 0: a 2 -> 5
    plan.setCount("b", 4); // op 1: b 0 -> 4
    plan.remove("a", 1); // op 2: a 5 -> 4

    List<Integer> previousCounts = plan.apply(multiset);

    // Previous count of each element before the corresponding operation.
    assertArrayEquals(new Integer[] {2, 0, 5}, previousCounts.toArray(new Integer[0]));
    // Final state: a x 4 and b x 4.
    assertEquals(4, multiset.count("a"));
    assertEquals(4, multiset.count("b"));
    assertEquals(2, multiset.entrySet().size());
  }

  /**
   * Negative counts are rejected up front, at plan time, before any operation is applied.
   */
  public void testNegativeCountRejectedAtPlanTime() {
    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    assertThrows(IllegalArgumentException.class, () -> plan.add("a", -1));
    assertThrows(IllegalArgumentException.class, () -> plan.setCount("a", -1));
    assertThrows(IllegalArgumentException.class, () -> plan.remove("a", -1));
  }

  /**
   * A hash collision is not treated as element equality: two distinct elements with the same hash
   * code are tracked independently.
   */
  public void testHashCollisionNotConflated() {
    // Two distinct String values with the same hashCode: "Aa" and "BB" both hash to 2112.
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("Aa", 1);
    multiset.add("BB", 2);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.setCount("Aa", 5);
    plan.setCount("BB", 7);

    plan.apply(multiset);

    // The two elements are tracked independently (equals identity, not hashCode).
    assertEquals(5, multiset.count("Aa"));
    assertEquals(7, multiset.count("BB"));
  }

  /**
   * The ImmutableMultiset result is separated from the input: building an ImmutableMultiset from
   * the multiset after applying the plan yields an independent copy that is unaffected by later
   * mutations.
   */
  public void testImmutableMultisetResultIsSeparatedFromInput() {
    Multiset<String> multiset = HashMultiset.create();
    multiset.add("a", 2);

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.add("a", 3);
    plan.apply(multiset);

    ImmutableMultiset<String> immutable = ImmutableMultiset.copyOf(multiset);

    // Mutating the original multiset does not affect the immutable copy.
    multiset.add("a", 100);
    assertEquals(5, immutable.count("a"));
    assertEquals(105, multiset.count("a"));
  }

  /**
   * Very large counts are applied via setCount, not by repeated inserts: a single operation can
   * set a count of Integer.MAX_VALUE without iterating.
   */
  public void testVeryLargeCountAppliedViaSetCountNotRepeatedInserts() {
    Multiset<String> multiset = HashMultiset.create();

    MultisetChangePlan<String> plan = MultisetChangePlan.create();
    plan.setCount("a", Integer.MAX_VALUE);

    plan.apply(multiset);

    assertEquals(Integer.MAX_VALUE, multiset.count("a"));
  }
}
