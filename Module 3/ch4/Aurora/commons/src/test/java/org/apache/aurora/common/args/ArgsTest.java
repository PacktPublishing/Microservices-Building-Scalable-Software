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

import java.io.File;
import java.io.IOException;

import com.google.common.collect.ImmutableList;

import org.apache.aurora.common.args.constraints.NotEmpty;
import org.apache.aurora.common.args.constraints.Range;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;

public class ArgsTest {
  private static class App {
    @CmdLine(name = "db", help = "help")
    private static final Arg<File> DB = Arg.create();

    @NotEmpty
    @CmdLine(name = "name", help = "help")
    private final Arg<String> name = Arg.create();
  }

  @Test
  public void testMixed() throws IOException {
    App app = new App();

    new ArgScanner().parse(Args.from(ArgFilters.selectClass(App.class), app),
        ImmutableList.of("-name=bob", "-db=fred", "1", "137"));

    assertEquals(new File("fred"), App.DB.get());
    assertEquals("bob", app.name.get());
  }

  @Test
  public void testReentrance() throws IOException {
    class InnerApp {
      @Range(lower = 0.0, upper = 1.0)
      @CmdLine(name = "level", help = "help")
      private final Arg<Double> level = Arg.create();
    }

    InnerApp app1 = new InnerApp();
    InnerApp app2 = new InnerApp();

    new ArgScanner().parse(Args.from(ArgFilters.selectClass(InnerApp.class), app1),
        ImmutableList.of("-level=0.5"));
    new ArgScanner().parse(Args.from(ArgFilters.selectClass(InnerApp.class), app2),
        ImmutableList.of("-level=0.00729"));

    assertEquals(0.5, app1.level.get(), 0.00001);
    assertEquals(0.00729, app2.level.get(), 0.00001);
  }
}
