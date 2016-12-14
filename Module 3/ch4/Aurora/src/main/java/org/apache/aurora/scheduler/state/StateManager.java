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
package org.apache.aurora.scheduler.state;

import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;

import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.mesos.Protos.SlaveID;

import static org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;

/**
 * A manager for the state of tasks.  Most modifications to tasks should be made here, especially
 * those that alter the {@link ScheduleStatus} of tasks.
 */
public interface StateManager {

  /**
   * Attempts to alter a task from its existing state to {@code newState}. If a {@code casState}
   * (compare and swap) is provided, the transition will only performed if the task is currently
   * in the state.
   *
   * @param taskId ID of the task to transition.
   * @param casState State that the task must be in for the operation to proceed.  If the task
   *                 is found to not be in {@code casState}, no action is performed and
   *                 {@code false} is returned.  This can be useful when deferring asynchronous
   *                 work, to perform a follow-up action iff the task has not changed since the
   *                 decision to defer the action was mde.
   * @param newState State to move the task to.
   * @param auditMessage Message to include with the transition.
   * @return {@link StateChangeResult}.
   *
   */
  StateChangeResult changeState(
      MutableStoreProvider storeProvider,
      String taskId,
      Optional<ScheduleStatus> casState,
      ScheduleStatus newState,
      Optional<String> auditMessage);

  /**
   * Assigns a task to a specific slave.
   * This will modify the task record to reflect the host assignment and return the updated record.
   *
   * @param storeProvider Storage provider.
   * @param taskId ID of the task to mutate.
   * @param slaveHost Host name that the task is being assigned to.
   * @param slaveId ID of the slave that the task is being assigned to.
   * @param assignedPorts Ports on the host that are being assigned to the task.
   * @return The updated task record, or {@code null} if the task was not found.
   */
  IAssignedTask assignTask(
      MutableStoreProvider storeProvider,
      String taskId,
      String slaveHost,
      SlaveID slaveId,
      Map<String, Integer> assignedPorts);

  /**
   * Inserts pending instances using {@code task} as their configuration. Tasks will immediately
   * move into PENDING and will be eligible for scheduling.
   *
   * @param storeProvider Storage provider.
   * @param task Task template.
   * @param instanceIds Instance IDs to assign to new PENDING tasks.
   */
  void insertPendingTasks(
      MutableStoreProvider storeProvider,
      ITaskConfig task,
      Set<Integer> instanceIds);

  /**
   * Attempts to delete tasks from the task store.
   * If the task is not currently in a state that is considered safe for deletion,
   * side-effect actions will be performed to reconcile the state conflict.
   *
   * @param storeProvider Storage provider.
   * @param taskIds IDs of tasks to delete.
   */
  void deleteTasks(MutableStoreProvider storeProvider, Set<String> taskIds);
}
