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
package org.apache.aurora.common.args.parsers;

import org.apache.aurora.common.args.ArgParser;

/**
 * Float parser.
 *
 * @author William Farner
 */
@ArgParser
public class FloatParser extends NumberParser<Float> {
  @Override
  Float parseNumber(String raw) {
    return Float.parseFloat(raw);
  }
}
