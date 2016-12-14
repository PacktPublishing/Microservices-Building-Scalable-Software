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

import com.google.inject.Inject;

import org.apache.aurora.scheduler.storage.entities.ILockKey;

import static java.util.Objects.requireNonNull;

/**
 * Mapper for LockKeys. Not a MyBatis mapper, this just encapsulates the logic for writing
 * union types so it does not leak into the related object's implementation.
 *
 * TODO(davmclau):
 * Consider creating these classes with code generation since something like this will be needed
 * for all union field relationships. Might not be possible unless the code generator also defines
 * a mapper interface for every field in the union as well as the associated XML mapper config
 * with the SQL to satisfy the interface.
 *
 */
class LockKeyMapper {

  private final JobKeyMapper jobKeyMapper;

  @Inject
  LockKeyMapper(JobKeyMapper jobKeyMapper) {
    this.jobKeyMapper = requireNonNull(jobKeyMapper);
  }

  public void insert(ILockKey key) {
    if (key.isSetJob()) {
      jobKeyMapper.merge(requireNonNull(key.getJob()));
    } else {
      throw new IllegalArgumentException("Unsupported lock type on LockKey.");
    }
  }
}
