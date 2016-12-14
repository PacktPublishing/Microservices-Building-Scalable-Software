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

import java.io.File;
import java.lang.annotation.Annotation;

import org.apache.aurora.common.args.Verifier;
import org.apache.aurora.common.args.VerifierFor;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Verifier to ensure that a file is a directory.
 *
 * @author William Farner
 */
@VerifierFor(IsDirectory.class)
public class IsDirectoryFileVerifier implements Verifier<File> {
  @Override
  public void verify(File value, Annotation annotation) {
    checkArgument(value.isDirectory(), "Must be a directory.");
  }

  @Override
  public String toString(Class<? extends File> argType, Annotation annotation) {
    return "file must be a directory";
  }
}
