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
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;

import org.apache.aurora.common.args.ArgParser;
import org.apache.aurora.common.args.Parser;
import org.apache.aurora.common.args.ParserOracle;
import org.apache.aurora.common.args.Parsers;

/**
 * Set parser.
 *
 * @author William Farner
 */
@ArgParser
public class SetParser extends TypeParameterizedParser<Set<?>> {

  public SetParser() {
    super(1);
  }

  @Override
  Set<?> doParse(final ParserOracle parserOracle, String raw, List<Type> typeParams) {
    final Type setType = typeParams.get(0);
    final Parser<?> parser = parserOracle.get(TypeToken.of(setType));

    return ImmutableSet.copyOf(Iterables.transform(Parsers.MULTI_VALUE_SPLITTER.split(raw),
        raw1 -> parser.parse(parserOracle, setType, raw1)));
  }
}
