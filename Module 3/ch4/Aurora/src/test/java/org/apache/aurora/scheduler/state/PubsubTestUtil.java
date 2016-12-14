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

import java.util.Set;

import com.google.common.util.concurrent.Service;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import org.apache.aurora.scheduler.AppStartup;
import org.apache.aurora.scheduler.events.EventSink;

/**
 * A convenience utility for unit tests that which to verify pubsub wiring.
 * TODO(wfarner): Clean this up - make it integrate more cleanly with callers and LifecycleModule.
 */
public final class PubsubTestUtil {

  private PubsubTestUtil() {
    // Utility class.
  }

  /**
   * Starts the pubsub system and gets a handle to the event sink where pubsub events may be sent.
   *
   * @param injector Injector where the pubsub system was installed.
   * @return The pubsub event sink.
   * @throws Exception If the pubsub system failed to start.
   */
  public static EventSink startPubsub(Injector injector) throws Exception {
    // TODO(wfarner): Make it easier to write a unit test wired for pubsub events.
    // In this case, a trade-off was made to avoid installing several distant modules and providing
    // required bindings that seem unrelated from this code.
    Set<Service> services = injector.getInstance(
        Key.get(new TypeLiteral<Set<Service>>() { }, AppStartup.class));

    for (Service service : services) {
      service.startAsync().awaitRunning();
    }
    return injector.getInstance(EventSink.class);
  }
}
