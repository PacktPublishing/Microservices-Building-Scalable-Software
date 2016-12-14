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
package org.apache.aurora.scheduler.sla;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.TaskEvent;
import org.apache.aurora.scheduler.base.TaskTestUtil;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskEvent;

final class SlaTestUtil {

  private SlaTestUtil() {
    // Utility class.
  }

  static IScheduledTask makeTask(Map<Long, ScheduleStatus> events, int instanceId) {
    return makeTask(events, instanceId, true);
  }

  static IScheduledTask makeTask(Map<Long, ScheduleStatus> events, int instanceId, boolean isProd) {
    List<ITaskEvent> taskEvents = makeEvents(events);
    ScheduledTask builder = TaskTestUtil.makeTask("task_id", TaskTestUtil.JOB).newBuilder()
        .setStatus(Iterables.getLast(taskEvents).getStatus())
        .setTaskEvents(ITaskEvent.toBuildersList(taskEvents));
    builder.getAssignedTask().setInstanceId(instanceId);
    builder.getAssignedTask().getTask().setProduction(isProd);
    return IScheduledTask.build(builder);
  }

  private static List<ITaskEvent> makeEvents(Map<Long, ScheduleStatus> events) {
    ImmutableList.Builder<ITaskEvent> taskEvents = ImmutableList.builder();
    for (Map.Entry<Long, ScheduleStatus> entry : events.entrySet()) {
      taskEvents.add(ITaskEvent.build(new TaskEvent(entry.getKey(), entry.getValue())));
    }

    return taskEvents.build();
  }
}
