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
package org.apache.aurora.scheduler.storage.backup;

import java.util.Set;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;

import org.apache.aurora.common.util.BuildInfo;
import org.apache.aurora.common.util.testing.FakeClock;
import org.apache.aurora.gen.storage.Snapshot;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.storage.SnapshotStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.Storage.MutateWork.NoResult;
import org.apache.aurora.scheduler.storage.db.DbUtil;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.log.SnapshotStoreImpl;

import static org.apache.aurora.common.util.testing.FakeBuildInfo.generateBuildInfo;

/**
 * A short-lived in-memory storage system that can be converted to a {@link Snapshot}.
 */
interface TemporaryStorage {

  /**
   * Deletes all tasks matching a query.  Deleted tasks will not be reflected in the snapshot when
   * {@link #toSnapshot()} is executed.
   *
   * @param query Query builder for tasks to delete.
   */
  void deleteTasks(Query.Builder query);

  /**
   * Fetches tasks matching a query.
   *
   * @param query Query builder for tasks to fetch.
   * @return Matching tasks.
   */
  Iterable<IScheduledTask> fetchTasks(Query.Builder query);

  /**
   * Creates a snapshot of the contents of the temporary storage.
   *
   * @return Temporary storage snapshot.
   */
  Snapshot toSnapshot();

  /**
   * A factory that creates temporary storage instances, detached from the rest of the system.
   */
  class TemporaryStorageFactory implements Function<Snapshot, TemporaryStorage> {
    @Override
    public TemporaryStorage apply(Snapshot snapshot) {
      final Storage storage = DbUtil.createFlaggedStorage();
      final BuildInfo buildInfo = generateBuildInfo();
      FakeClock clock = new FakeClock();
      clock.setNowMillis(snapshot.getTimestamp());
      final SnapshotStore<Snapshot> snapshotStore = new SnapshotStoreImpl(
          buildInfo,
          clock,
          storage,
          // Safe to pass false here to default to the non-experimental task store
          // during restore from backup procedure.
          false /** useDbSnapshotForTaskStore */);
      snapshotStore.applySnapshot(snapshot);

      return new TemporaryStorage() {
        @Override
        public void deleteTasks(final Query.Builder query) {
          storage.write((NoResult.Quiet) storeProvider -> {
            Set<String> ids = FluentIterable.from(storeProvider.getTaskStore().fetchTasks(query))
                .transform(Tasks::id)
                .toSet();
            storeProvider.getUnsafeTaskStore().deleteTasks(ids);
          });
        }

        @Override
        public Iterable<IScheduledTask> fetchTasks(final Query.Builder query) {
          return storage.read(storeProvider -> storeProvider.getTaskStore().fetchTasks(query));
        }

        @Override
        public Snapshot toSnapshot() {
          return snapshotStore.createSnapshot();
        }
      };
    }
  }
}
