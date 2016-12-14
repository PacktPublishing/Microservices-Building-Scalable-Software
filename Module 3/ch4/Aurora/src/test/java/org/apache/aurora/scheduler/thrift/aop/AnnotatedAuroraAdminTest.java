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
package org.apache.aurora.scheduler.thrift.aop;

import java.lang.reflect.Method;
import java.util.Collection;

import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.reflect.Invokable;
import com.google.common.reflect.Parameter;

import org.apache.aurora.gen.AuroraSchedulerManager;
import org.apache.aurora.scheduler.http.api.security.AuthorizingParam;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class AnnotatedAuroraAdminTest {
  @Test
  public void testAllAuroraSchedulerManagerIfaceMethodsHaveAuthorizingParam() throws Exception {
    for (Method declaredMethod : AuroraSchedulerManager.Iface.class.getDeclaredMethods()) {
      Invokable<?, ?> invokable = Invokable.from(declaredMethod);
      Collection<Parameter> parameters = invokable.getParameters();
      Invokable<?, ?> annotatedInvokable = Invokable.from(
          AnnotatedAuroraAdmin.class.getDeclaredMethod(
              invokable.getName(),
              FluentIterable.from(parameters)
                  .transform(input -> input.getType().getRawType())
                  .toList()
                  .toArray(new Class<?>[0])));

      Collection<Parameter> annotatedParameters = Collections2.filter(
          annotatedInvokable.getParameters(),
          input -> input.getAnnotation(AuthorizingParam.class) != null);

      assertFalse(
          "Method " + invokable + " should have at least 1 " + AuthorizingParam.class.getName()
              + " annotation but none were found.",
          annotatedParameters.isEmpty());
    }
  }
}
