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
package org.apache.aurora.common.util.templating;

import java.io.StringWriter;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StringTemplateHelperTest {

  private StringTemplateHelper templateHelper;

  @Before
  public void setUp() {
    templateHelper = new StringTemplateHelper(getClass(), "template", false);
  }

  private static class Item {
    final String name;
    final int price;

    private Item(String name, int price) {
      this.name = name;
      this.price = price;
    }

    public String getName() {
      return name;
    }

    public int getPrice() {
      return price;
    }
  }

  @Test
  public void testFillTemplate() throws Exception {
    StringWriter output = new StringWriter();
    templateHelper.writeTemplate(output, template -> {
      template.setAttribute("header", "Prices");
      template.setAttribute("items", Arrays.asList(
          new Item("banana", 50),
          new Item("car", 2),
          new Item("jupiter", 200)
      ));
      template.setAttribute("footer", "The End");
    });
    String expected = "Prices\n"
        + "\n  The banana costs $50."
        + "\n  The car costs $2."
        + "\n  The jupiter costs $200.\n"
        + "\n\nThe End";
    assertEquals(expected, output.toString());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMissingTemplate() throws Exception {
    new StringTemplateHelper(getClass(), "missing_template", false);
  }

  private static class CustomException extends RuntimeException {
  }

  @Test(expected = CustomException.class)
  public void testClosureError() throws Exception {
    templateHelper.writeTemplate(new StringWriter(), template -> {
      throw new CustomException();
    });
  }
}
