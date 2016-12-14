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
package org.apache.aurora.common.args.constraints;

import java.lang.annotation.Annotation;

import org.apache.aurora.common.args.Verifier;
import org.apache.aurora.common.args.VerifierFor;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Verifier to ensure that a string is not empty.
 *
 * @author William Farner
 */
@VerifierFor(NotEmpty.class)
public class NotEmptyStringVerifier implements Verifier<String> {
  @Override
  public void verify(String s, Annotation annotation) {
    checkArgument(!s.isEmpty(), "Value must not be empty.");
  }

  @Override
  public String toString(Class<? extends String> argType, Annotation annotation) {
    return "must be non-empty";
  }
}
