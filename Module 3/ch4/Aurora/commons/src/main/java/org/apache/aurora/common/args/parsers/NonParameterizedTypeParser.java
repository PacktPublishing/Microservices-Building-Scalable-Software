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

import java.lang.reflect.Type;

import org.apache.aurora.common.args.Parser;
import org.apache.aurora.common.args.ParserOracle;

/**
 * Base class for parsers of types that are not parameterized.
 *
 * @author William Farner
 */
public abstract class NonParameterizedTypeParser<T> implements Parser<T> {

  /**
   * Performs the parsing of the raw string.
   *
   * @param raw Value to parse.
   * @return The parsed value.
   * @throws IllegalArgumentException If the value could not be parsed into the target type.
   */
  public abstract T doParse(String raw) throws IllegalArgumentException;

  @Override
  public T parse(ParserOracle parserOracle, Type type, String raw) throws IllegalArgumentException {
    return doParse(raw);
  }
}
