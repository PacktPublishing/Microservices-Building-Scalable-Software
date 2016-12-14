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
package org.apache.aurora.scheduler.updater;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.AbstractModule;
import com.google.inject.PrivateModule;

import org.apache.aurora.scheduler.SchedulerServicesModule;
import org.apache.aurora.scheduler.base.AsyncUtil;
import org.apache.aurora.scheduler.events.PubsubEventModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binding module for scheduling logic and higher-level state management.
 */
public class UpdaterModule extends AbstractModule {
  private static final Logger LOG = LoggerFactory.getLogger(UpdaterModule.class);

  private final ScheduledExecutorService executor;

  public UpdaterModule() {
    this(AsyncUtil.singleThreadLoggingScheduledExecutor("updater-%d", LOG));
  }

  @VisibleForTesting
  UpdaterModule(ScheduledExecutorService executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  protected void configure() {
    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(ScheduledExecutorService.class).toInstance(executor);
        bind(UpdateFactory.class).to(UpdateFactory.UpdateFactoryImpl.class);
        bind(UpdateFactory.UpdateFactoryImpl.class).in(Singleton.class);
        bind(JobUpdateController.class).to(JobUpdateControllerImpl.class);
        bind(JobUpdateControllerImpl.class).in(Singleton.class);
        expose(JobUpdateController.class);
        bind(JobUpdateEventSubscriber.class);
        expose(JobUpdateEventSubscriber.class);
      }
    });

    PubsubEventModule.bindSubscriber(binder(), JobUpdateEventSubscriber.class);
    SchedulerServicesModule.addSchedulerActiveServiceBinding(binder())
        .to(JobUpdateEventSubscriber.class);
  }
}
