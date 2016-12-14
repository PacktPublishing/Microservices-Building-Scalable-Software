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
package org.apache.aurora.scheduler.storage.db.migration;

import java.math.BigDecimal;

import org.apache.ibatis.migration.MigrationScript;

public class V002_CreateDockerImagesTable implements MigrationScript {
  @Override
  public BigDecimal getId() {
    return BigDecimal.valueOf(2L);
  }

  @Override
  public String getDescription() {
    return "Create the task_config_docker_images table.";
  }

  @Override
  public String getUpScript() {
    return "CREATE TABLE IF NOT EXISTS task_config_docker_images("
        + "id IDENTITY,"
        + "task_config_id BIGINT NOT NULL REFERENCES task_configs(id) ON DELETE CASCADE,"
        + "name VARCHAR NOT NULL,"
        + "tag VARCHAR NOT NULL,"
        + "UNIQUE(task_config_id)"
        + ");";
  }

  @Override
  public String getDownScript() {
    return "DROP TABLE IF EXISTS task_config_docker_images;";
  }
}
