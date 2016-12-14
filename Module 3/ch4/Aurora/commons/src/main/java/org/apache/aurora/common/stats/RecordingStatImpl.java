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
package org.apache.aurora.common.stats;

import com.google.common.base.Preconditions;

/**
 * A convenience class to wrap a {@link RecordingStat}.
 *
 * @author William Farner
 */
class RecordingStatImpl<T extends Number> implements RecordingStat<T> {
  private final Stat<T> recorded;
  private final String name;

  public RecordingStatImpl(Stat<T> recorded) {
    this.recorded = Preconditions.checkNotNull(recorded);
    this.name = Stats.validateName(recorded.getName());
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public T sample() {
    return read();
  }

  @Override
  public T read() {
    return recorded.read();
  }
}
