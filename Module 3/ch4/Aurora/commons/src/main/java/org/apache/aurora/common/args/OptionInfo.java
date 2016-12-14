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
package org.apache.aurora.common.args;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeToken;

import org.apache.aurora.common.args.apt.Configuration;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Description of a command line option/flag such as -foo=bar.
 */
public final class OptionInfo<T> extends ArgumentInfo<T> {
  static final String ARG_NAME_RE = "[\\w\\-\\.]+";
  static final String ARG_FILE_HELP_TEMPLATE
      = "%s This argument supports @argfile format. See details below.";

  private static final Pattern ARG_NAME_PATTERN = Pattern.compile(ARG_NAME_RE);
  private static final String NEGATE_BOOLEAN = "no_";

  /**
   * Factory method to create a OptionInfo from a field.
   *
   * @param field The field must contain a {@link Arg}.
   * @return an OptionInfo describing the field.
   */
  static OptionInfo<?> createFromField(Field field) {
    return createFromField(field, null);
  }

  /**
   * Factory method to create a OptionInfo from a field.
   *
   * @param field The field must contain a {@link Arg}.
   * @param instance The object containing the non-static Arg instance or else null if the Arg
   *     field is static.
   * @return an OptionInfo describing the field.
   */
  static OptionInfo<?> createFromField(final Field field, @Nullable Object instance) {
    CmdLine cmdLine = field.getAnnotation(CmdLine.class);
    if (cmdLine == null) {
      throw new Configuration.ConfigurationException(
          "No @CmdLine Arg annotation for field " + field);
    }

    String name = cmdLine.name();
    Preconditions.checkNotNull(name);
    checkArgument(!HELP_ARGS.contains(name),
        String.format("Argument name '%s' is reserved for builtin argument help", name));
    checkArgument(ARG_NAME_PATTERN.matcher(name).matches(),
        String.format("Argument name '%s' does not match required pattern %s",
            name, ARG_NAME_RE));

    @SuppressWarnings({"unchecked", "rawtypes"}) // we have no way to know the type here
    OptionInfo<?> optionInfo = new OptionInfo(
        name,
        getCmdLineHelp(cmdLine),
        cmdLine.argFile(),
        getArgForField(field, Optional.fromNullable(instance)),
        TypeUtil.getTypeParamTypeToken(field),
        Arrays.asList(field.getAnnotations()),
        cmdLine.parser());

    return optionInfo;
  }

  private static String getCmdLineHelp(CmdLine cmdLine) {
    String help = cmdLine.help();

    if (cmdLine.argFile()) {
      help = String.format(ARG_FILE_HELP_TEMPLATE, help, cmdLine.name(), cmdLine.name());
    }

    return help;
  }

  private OptionInfo(
      String name,
      String help,
      boolean argFile,
      Arg<T> arg,
      TypeToken<T> type,
      List<Annotation> verifierAnnotations,
      @Nullable Class<? extends Parser<T>> parser) {

    super(name, help, arg, type, verifierAnnotations, parser);
  }

  /**
   * Parses the value and store result in the {@link Arg} contained in this {@code OptionInfo}.
   */
  void load(ParserOracle parserOracle, String optionName, String value) {
    Parser<? extends T> parser = getParser(parserOracle);

    Object result = parser.parse(parserOracle, getType().getType(), value); // [A]

    // If the arg type is boolean, check if the command line uses the negated boolean form.
    if (isBoolean()) {
      if (optionName.equals(getNegatedName())) {
        result = !(Boolean) result; // [B]
      }
    }

    // We know result is T at line [A] but throw this type information away to allow negation if T
    // is Boolean at line [B]
    @SuppressWarnings("unchecked")
    T parsed = (T) result;

    setValue(parsed);
  }

  boolean isBoolean() {
    return getType().getRawType() == Boolean.class;
  }

  /**
   * Similar to the simple name, but with boolean arguments appends "no_", as in:
   * {@code -no_fire=false}
   */
  String getNegatedName() {
    return NEGATE_BOOLEAN + getName();
  }
}
