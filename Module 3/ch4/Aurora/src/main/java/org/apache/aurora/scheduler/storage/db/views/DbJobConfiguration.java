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
package org.apache.aurora.scheduler.storage.db.views;

import org.apache.aurora.gen.CronCollisionPolicy;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.JobConfiguration;
import org.apache.aurora.gen.JobKey;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;

public final class DbJobConfiguration {
  private JobKey key;
  private Identity owner;
  private String cronSchedule;
  private CronCollisionPolicy cronCollisionPolicy;
  private DbTaskConfig taskConfig;
  private int instanceCount;

  private DbJobConfiguration() {
  }

  public IJobConfiguration toImmutable() {
    return IJobConfiguration.build(
        new JobConfiguration()
        .setKey(key)
        .setOwner(owner)
        .setCronSchedule(cronSchedule)
        .setCronCollisionPolicy(cronCollisionPolicy)
        .setTaskConfig(taskConfig.toThrift())
        .setInstanceCount(instanceCount));
  }
}
