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

import java.io.IOException;
import java.lang.reflect.Field;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.apache.aurora.common.args.apt.Configuration;
import org.apache.aurora.common.args.argfilterstest.ArgsRoot;
import org.apache.aurora.common.args.argfilterstest.subpackageA.ArgsA;
import org.apache.aurora.common.args.argfilterstest.subpackageA.subsubpackage1.ArgsA1;
import org.apache.aurora.common.args.argfilterstest.subpackageB.ArgsB;
import org.apache.aurora.common.args.argfilterstest.subpackageBwithSuffix.ArgsBWithSuffix;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * @author John Sirois
 */
public class ArgFiltersTest {

  @CmdLine(name = "a", help = "")
  static final Arg<String> A = Arg.create();

  @CmdLine(name = "b", help = "")
  static final Arg<String> B = Arg.create();

  @Test
  public void testpackage() throws IOException {
    testFilter(ArgFilters.selectPackage(ArgsRoot.class.getPackage()),
        fieldInfo(ArgsRoot.class, "ARGS_ROOT"));
  }

  @Test
  public void testAllPackagesUnderHere() throws IOException {
    testFilter(ArgFilters.selectAllPackagesUnderHere(ArgsRoot.class.getPackage()),
        fieldInfo(ArgsRoot.class, "ARGS_ROOT"),
        fieldInfo(ArgsA.class, "ARGS_A"),
        fieldInfo(ArgsB.class, "ARGS_B"),
        fieldInfo(ArgsA1.class, "ARGS_A1"),
        fieldInfo(ArgsBWithSuffix.class, "ARGS_B_WITH_SUFFIX"));

    testFilter(ArgFilters.selectAllPackagesUnderHere(ArgsB.class.getPackage()),
        fieldInfo(ArgsB.class, "ARGS_B"));
  }

  @Test
  public void testClass() throws IOException {
    testFilter(ArgFilters.selectClass(ArgFiltersTest.class),
        fieldInfo(ArgFiltersTest.class, "A"), fieldInfo(ArgFiltersTest.class, "B"));
  }

  @Test
  public void testClasses() throws IOException {
    testFilter(ArgFilters.selectClasses(ArgFiltersTest.class, ArgsA.class),
        fieldInfo(ArgFiltersTest.class, "A"),
        fieldInfo(ArgFiltersTest.class, "B"),
        fieldInfo(ArgsA.class, "ARGS_A"));
  }

  @Test
  public void testArg() throws IOException {
    testFilter(ArgFilters.selectCmdLineArg(ArgFiltersTest.class, "b"),
        fieldInfo(ArgFiltersTest.class, "B"));
  }

  private static Configuration.ArgInfo fieldInfo(Class<?> declaringClass, String fieldName) {
    return new Configuration.ArgInfo(declaringClass.getName(), fieldName);
  }

  private void testFilter(final Predicate<Field> filter, Configuration.ArgInfo... expected)
      throws IOException {

    Predicate<Optional<Field>> fieldFilter = maybeField -> maybeField.isPresent() && filter.apply(maybeField.get());

    assertEquals(ImmutableSet.copyOf(expected),
        ImmutableSet.copyOf(Iterables.filter(Configuration.load().optionInfo(),
            Predicates.compose(fieldFilter, Args.TO_FIELD))));
  }
}
