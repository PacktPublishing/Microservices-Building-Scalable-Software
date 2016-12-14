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
package org.apache.aurora.common.args.argfilterstest.subpackageA.subsubpackage1;

import org.apache.aurora.common.args.Arg;
import org.apache.aurora.common.args.CmdLine;

/**
 * @author John Sirois
 */
public final class ArgsA1 {
  @CmdLine(name = "args_a1", help = "")
  static final Arg<String> ARGS_A1 = Arg.create();

  private ArgsA1() {
    // Test class.
  }
}
