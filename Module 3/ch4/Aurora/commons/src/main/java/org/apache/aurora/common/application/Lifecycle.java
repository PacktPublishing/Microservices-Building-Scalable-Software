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
package org.apache.aurora.common.application;

import com.google.inject.Inject;

import org.apache.aurora.common.base.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application lifecycle manager, which coordinates orderly shutdown of an application.  This class
 * is responsible for executing shutdown commands, and can also be used to allow threads to await
 * application shutdown.
 *
 * @author William Farner
 */
public class Lifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(Lifecycle.class);

  // Monitor and state for suspending and terminating execution.
  private final Object waitMonitor = new Object();
  private boolean destroyed = false;

  private final Command shutdownRegistry;

  @Inject
  public Lifecycle(@ShutdownStage Command shutdownRegistry) {
    this.shutdownRegistry = shutdownRegistry;
  }

  /**
   * Checks whether this lifecycle is still considered alive.  The lifecycle is still alive until
   * {@link #shutdown()} has been called and all of the actions registered with the shutdown
   * controller have completed.
   *
   * @return {@code true} if the lifecycle is alive, {@code false} otherwise.
   *
   */
  public final boolean isAlive() {
    synchronized (waitMonitor) {
      return !destroyed;
    }
  }

  /**
   * Allows a caller to wait forever; typically used when all work is done in daemon threads.
   * Will exit on interrupts.
   */
  public final void awaitShutdown() {
    LOG.info("Awaiting shutdown");
    synchronized (waitMonitor) {
      while (!destroyed) {
        try {
          waitMonitor.wait();
        } catch (InterruptedException e) {
          LOG.info("Exiting on interrupt");
          shutdown();
          return;
        }
      }
    }
  }

  /**
   * Initiates an orderly shutdown of the lifecycle's registered shutdown hooks.
   */
  public final void shutdown() {
    synchronized (waitMonitor) {
      if (!destroyed) {
        destroyed = true;
        LOG.info("Shutting down application");
        shutdownRegistry.execute();
        waitMonitor.notifyAll();
      }
    }
  }
}
