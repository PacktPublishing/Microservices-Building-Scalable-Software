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
package org.apache.aurora.scheduler.updater;

import com.google.common.collect.ImmutableSet;

import org.apache.aurora.common.testing.easymock.EasyMockTest;
import org.apache.aurora.gen.InstanceKey;
import org.apache.aurora.gen.InstanceTaskConfig;
import org.apache.aurora.gen.JobUpdateInstructions;
import org.apache.aurora.gen.JobUpdateSettings;
import org.apache.aurora.gen.JobUpdateStatus;
import org.apache.aurora.gen.Range;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.state.StateManager;
import org.apache.aurora.scheduler.storage.entities.IInstanceKey;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateInstructions;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.junit.Before;
import org.junit.Test;

public class AddTaskTest extends EasyMockTest {
  private static final IJobUpdateInstructions INSTRUCTIONS = IJobUpdateInstructions.build(
      new JobUpdateInstructions()
          .setDesiredState(new InstanceTaskConfig()
              .setTask(new TaskConfig())
              .setInstances(ImmutableSet.of(new Range(0, 0))))
          .setSettings(
              new JobUpdateSettings()
                  .setMinWaitInInstanceRunningMs(1000)));
  private static final IInstanceKey INSTANCE =
      IInstanceKey.build(new InstanceKey(JobKeys.from("role", "env", "job").newBuilder(), 0));

  private StorageTestUtil storageUtil;
  private StateManager stateManager;
  private InstanceActionHandler handler;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectOperations();
    stateManager = createMock(StateManager.class);
    handler = new InstanceActionHandler.AddTask();
  }

  @Test
  public void testAddInstance() throws Exception {
    storageUtil.expectTaskFetch(Query.instanceScoped(INSTANCE).active());

    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        INSTRUCTIONS.getDesiredState().getTask(),
        ImmutableSet.of(0));

    control.replay();

    handler.getReevaluationDelay(
        INSTANCE,
        INSTRUCTIONS,
        storageUtil.mutableStoreProvider,
        stateManager,
        JobUpdateStatus.ROLLING_FORWARD);
  }

  @Test
  public void testAddInstanceCollisionDoesNotThrow() throws Exception {
    storageUtil.expectTaskFetch(
        Query.instanceScoped(INSTANCE).active(),
        TaskTestUtil.makeTask("id", INSTANCE.getJobKey()));

    control.replay();

    handler.getReevaluationDelay(
        INSTANCE,
        INSTRUCTIONS,
        storageUtil.mutableStoreProvider,
        stateManager,
        JobUpdateStatus.ROLLING_FORWARD);
  }

  @Test(expected = IllegalStateException.class)
  public void testInstanceNotFound() throws Exception {
    storageUtil.expectTaskFetch(Query.instanceScoped(INSTANCE).active());

    control.replay();

    handler.getReevaluationDelay(
        INSTANCE,
        INSTRUCTIONS,
        storageUtil.mutableStoreProvider,
        stateManager,
        JobUpdateStatus.ROLLING_BACK);
  }
}
