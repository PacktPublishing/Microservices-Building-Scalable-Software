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
package org.apache.aurora.scheduler.storage.db.typehandlers;

import java.util.List;

import com.google.common.collect.ImmutableList;

import org.apache.ibatis.type.TypeHandler;

/**
 * Utility class to access the available type handler classes.
 */
public final class TypeHandlers {
  private TypeHandlers() {
    // Utility class.
  }

  public static List<Class<? extends TypeHandler<?>>> getAll() {
    return ImmutableList.<Class<? extends TypeHandler<?>>>builder()
        .add(CronCollisionPolicyTypeHandler.class)
        .add(JobUpdateActionTypeHandler.class)
        .add(JobUpdateStatusTypeHandler.class)
        .add(MaintenanceModeTypeHandler.class)
        .add(ScheduleStatusTypeHandler.class)
        .build();
  }
}
