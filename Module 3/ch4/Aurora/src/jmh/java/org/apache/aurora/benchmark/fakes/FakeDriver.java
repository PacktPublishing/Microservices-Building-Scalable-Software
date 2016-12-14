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
package org.apache.aurora.benchmark.fakes;

import java.util.Collection;

import com.google.common.util.concurrent.AbstractIdleService;

import org.apache.aurora.scheduler.mesos.Driver;
import org.apache.mesos.Protos;

public class FakeDriver extends AbstractIdleService implements Driver {
  @Override
  public void blockUntilStopped() {
    // no-op
  }

  @Override
  public void launchTask(Protos.OfferID offerId, Protos.TaskInfo task) {
    // no-op
  }

  @Override
  public void declineOffer(Protos.OfferID offerId) {
    // no-op
  }

  @Override
  public void killTask(String taskId) {
    // no-op
  }

  @Override
  public void acknowledgeStatusUpdate(Protos.TaskStatus status) {
    // no-op
  }

  @Override
  public void abort() {
    // no-op
  }

  @Override
  protected void startUp() throws Exception {
    // no-op
  }

  @Override
  protected void shutDown() throws Exception {
    // no-op
  }

  @Override
  public void reconcileTasks(Collection<Protos.TaskStatus> statuses) {
    // no-op
  }
}
