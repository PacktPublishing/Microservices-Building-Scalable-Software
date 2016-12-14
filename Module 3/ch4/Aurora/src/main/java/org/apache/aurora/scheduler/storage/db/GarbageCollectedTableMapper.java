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
package org.apache.aurora.scheduler.storage.db;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * Base interface for a table mapper whose rows should be garbage-collected when unreferenced.
 */
interface GarbageCollectedTableMapper {
  /**
   * Selects the IDs of all rows in the table.
   */
  List<Long> selectAllRowIds();

  /**
   * Attempts to delete a row from the table.
   */
  void deleteRow(@Param("rowId") long rowId);
}
