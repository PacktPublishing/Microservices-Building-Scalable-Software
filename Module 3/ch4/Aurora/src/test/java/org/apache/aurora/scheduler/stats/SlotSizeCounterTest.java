/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.stats;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.gen.ResourceAggregate;
import org.apache.aurora.scheduler.resources.ResourceAggregates;
import org.apache.aurora.scheduler.stats.SlotSizeCounter.MachineResource;
import org.apache.aurora.scheduler.stats.SlotSizeCounter.MachineResourceProvider;
import org.apache.aurora.scheduler.storage.entities.IResourceAggregate;
import org.junit.Before;
import org.junit.Test;

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

public class SlotSizeCounterTest extends EasyMockTest {

  private static final IResourceAggregate SMALL =
      IResourceAggregate.build(new ResourceAggregate(1.0, 1024, 4096));
  private static final IResourceAggregate LARGE = ResourceAggregates.scale(SMALL, 4);

  private static final Map<String, IResourceAggregate> SLOT_SIZES = ImmutableMap.of(
      "small", SMALL,
      "large", LARGE);

  private MachineResourceProvider slotProvider;
  private StatsProvider statsProvider;
  private Runnable slotCounter;

  private final AtomicLong smallCounter = new AtomicLong();
  private final AtomicLong smallDedicatedCounter = new AtomicLong();
  private final AtomicLong smallRevocableCounter = new AtomicLong();
  private final AtomicLong smallDedicatedRevocableCounter = new AtomicLong();
  private final AtomicLong largeCounter = new AtomicLong();
  private final AtomicLong largeDedicatedCounter = new AtomicLong();
  private final AtomicLong largeRevocableCounter = new AtomicLong();
  private final AtomicLong largeDedicatedRevocableCounter = new AtomicLong();

  @Before
  public void setUp() {
    slotProvider = createMock(MachineResourceProvider.class);
    statsProvider = createMock(StatsProvider.class);
    slotCounter = new SlotSizeCounter(SLOT_SIZES, slotProvider, new CachedCounters(statsProvider));
  }

  private void expectStatExport() {
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("small", false, false)))
        .andReturn(smallCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("small", true, false)))
        .andReturn(smallDedicatedCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("small", false, true)))
        .andReturn(smallRevocableCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("small", true, true)))
        .andReturn(smallDedicatedRevocableCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("large", false, false)))
        .andReturn(largeCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("large", true, false)))
        .andReturn(largeDedicatedCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("large", false, true)))
        .andReturn(largeRevocableCounter);
    expect(statsProvider.makeCounter(SlotSizeCounter.getStatName("large", true, true)))
        .andReturn(largeDedicatedRevocableCounter);
  }

  private void expectGetSlots(MachineResource... returned) {
    expect(slotProvider.get()).andReturn(ImmutableList.copyOf(returned));
  }

  @Test
  public void testNoOffers() {
    expectStatExport();
    expectGetSlots();

    control.replay();

    slotCounter.run();
    assertEquals(0, smallCounter.get());
    assertEquals(0, smallDedicatedCounter.get());
    assertEquals(0, smallRevocableCounter.get());
    assertEquals(0, smallDedicatedRevocableCounter.get());
    assertEquals(0, largeCounter.get());
    assertEquals(0, largeDedicatedCounter.get());
    assertEquals(0, largeRevocableCounter.get());
    assertEquals(0, largeDedicatedRevocableCounter.get());
  }

  @Test
  public void testTinyOffers() {
    expectStatExport();
    expectGetSlots(new MachineResource(
        IResourceAggregate.build(new ResourceAggregate(0.1, 1, 1)), false, false));

    control.replay();

    slotCounter.run();
    assertEquals(0, smallCounter.get());
    assertEquals(0, smallDedicatedCounter.get());
    assertEquals(0, smallRevocableCounter.get());
    assertEquals(0, smallDedicatedRevocableCounter.get());
    assertEquals(0, largeCounter.get());
    assertEquals(0, largeDedicatedCounter.get());
    assertEquals(0, largeRevocableCounter.get());
    assertEquals(0, largeDedicatedRevocableCounter.get());
  }

  @Test
  public void testStarvedResourceVector() {
    expectStatExport();
    expectGetSlots(
        new MachineResource(
            IResourceAggregate.build(new ResourceAggregate(1000, 16384, 1)), false, false));

    control.replay();

    slotCounter.run();
    assertEquals(0, smallCounter.get());
    assertEquals(0, smallDedicatedCounter.get());
    assertEquals(0, smallRevocableCounter.get());
    assertEquals(0, smallDedicatedRevocableCounter.get());
    assertEquals(0, largeCounter.get());
    assertEquals(0, largeDedicatedCounter.get());
    assertEquals(0, largeRevocableCounter.get());
    assertEquals(0, largeDedicatedRevocableCounter.get());
  }

  @Test
  public void testCountSlots() {
    expectStatExport();
    expectGetSlots(
        new MachineResource(SMALL, false, false),
        new MachineResource(SMALL, false, false),
        new MachineResource(LARGE, false, false),
        new MachineResource(LARGE, false, true),
        new MachineResource(LARGE, true, true),
        new MachineResource(ResourceAggregates.scale(LARGE, 4), false, false),
        new MachineResource(IResourceAggregate.build(new ResourceAggregate(1, 1, 1)), false, false),
        new MachineResource(SMALL, true, false),
        new MachineResource(SMALL, true, false),
        new MachineResource(ResourceAggregates.scale(SMALL, 2), true, false));

    control.replay();

    slotCounter.run();
    assertEquals(22, smallCounter.get());
    assertEquals(4, smallDedicatedCounter.get());
    assertEquals(4, smallRevocableCounter.get());
    assertEquals(4, smallDedicatedRevocableCounter.get());
    assertEquals(5, largeCounter.get());
    assertEquals(0, largeDedicatedCounter.get());
    assertEquals(1, largeRevocableCounter.get());
    assertEquals(1, largeDedicatedRevocableCounter.get());
  }
}
