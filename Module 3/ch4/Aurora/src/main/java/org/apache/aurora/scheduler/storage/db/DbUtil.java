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

import com.google.common.base.Optional;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.common.util.Clock;
import org.apache.aurora.common.util.testing.FakeClock;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.testing.FakeStatsProvider;

import static org.apache.aurora.common.inject.Bindings.KeyFactory.PLAIN;
import static org.apache.aurora.scheduler.storage.db.DbModule.testModuleWithWorkQueue;

/**
 * Utility class for creating ad-hoc storage instances.
 */
public final class DbUtil {

  private DbUtil() {
    // Utility class.
  }

  /**
   * Creates a new, empty storage system with bindings for the new storage.
   *
   * @param dbModule {@link DbModule} to install.
   * @return An injector with bindings necessary for a storage system.
   */
  public static Injector createStorageInjector(Module dbModule) {
    return createStorageInjector(dbModule, new DbModule.MigrationManagerModule());
  }

  public static Injector createStorageInjector(Module dbModule, Module migrationModule) {
    Injector injector = Guice.createInjector(
        migrationModule,
        dbModule,
        new AbstractModule() {
          @Override
          protected void configure() {
            FakeStatsProvider stats = new FakeStatsProvider();
            bind(StatsProvider.class).toInstance(stats);
            bind(FakeStatsProvider.class).toInstance(stats);
            bind(Clock.class).toInstance(new FakeClock());
          }
        });
    Storage storage = injector.getInstance(Storage.class);
    storage.prepare();
    return injector;
  }

  /**
   * Creates a new, empty test storage system.
   * <p>
   * TODO(wfarner): Rename this to createTestStorage() to avoid misuse.
   *
   * @return A new storage instance.
   */
  public static Storage createStorage() {
    return createStorageInjector(testModuleWithWorkQueue()).getInstance(Storage.class);
  }

  /**
   * Creates a new, empty storage system with a task store defined by the command line flag.
   *
   * @return A new storage instance.
   */
  public static Storage createFlaggedStorage() {
    return createStorageInjector(testModuleWithWorkQueue(PLAIN, Optional.absent()))
        .getInstance(Storage.class);
  }
}
