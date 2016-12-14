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
package org.apache.aurora.scheduler.http.api.security;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import javax.servlet.Filter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.AnnotatedBindingBuilder;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Names;
import com.google.inject.servlet.RequestScoped;
import com.google.inject.servlet.ServletModule;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.aurora.GuiceUtils;
import org.apache.aurora.common.args.Arg;
import org.apache.aurora.common.args.CmdLine;
import org.apache.aurora.gen.AuroraAdmin;
import org.apache.aurora.gen.AuroraSchedulerManager;
import org.apache.aurora.scheduler.app.MoreModules;
import org.apache.aurora.scheduler.thrift.aop.AnnotatedAuroraAdmin;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.guice.aop.ShiroAopModule;
import org.apache.shiro.guice.web.ShiroWebModule;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.subject.Subject;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.scheduler.http.H2ConsoleModule.H2_PATH;
import static org.apache.aurora.scheduler.http.H2ConsoleModule.H2_PERM;
import static org.apache.aurora.scheduler.http.api.ApiModule.API_PATH;
import static org.apache.aurora.scheduler.spi.Permissions.Domain.THRIFT_AURORA_ADMIN;
import static org.apache.shiro.guice.web.ShiroWebModule.guiceFilterModule;
import static org.apache.shiro.web.filter.authc.AuthenticatingFilter.PERMISSIVE;

/**
 * Provides HTTP Basic Authentication using Apache Shiro. When enabled, prevents unauthenticated
 * access to write APIs and configured servlets. Write API access must also be authorized, with
 * permissions configured in a shiro.ini file. For an example of this file, see the test resources
 * included with this package.
 */
public class HttpSecurityModule extends ServletModule {
  public static final String HTTP_REALM_NAME = "Apache Aurora Scheduler";

  private static final String H2_PATTERN = H2_PATH + "/**";
  private static final String ALL_PATTERN = "/**";
  private static final Key<? extends Filter> K_STRICT =
      Key.get(ShiroKerberosAuthenticationFilter.class);
  private static final Key<? extends Filter> K_PERMISSIVE =
      Key.get(ShiroKerberosPermissiveAuthenticationFilter.class);

  @CmdLine(name = "shiro_realm_modules",
      help = "Guice modules for configuring Shiro Realms.")
  private static final Arg<Set<Module>> SHIRO_REALM_MODULE = Arg.create(
      ImmutableSet.of(MoreModules.lazilyInstantiated(IniShiroRealmModule.class)));

  @CmdLine(name = "shiro_after_auth_filter",
      help = "Fully qualified class name of the servlet filter to be applied after the"
          + " shiro auth filters are applied.")
  private static final Arg<Class<? extends Filter>> SHIRO_AFTER_AUTH_FILTER = Arg.create();

  @VisibleForTesting
  static final Matcher<Method> AURORA_SCHEDULER_MANAGER_SERVICE =
      GuiceUtils.interfaceMatcher(AuroraSchedulerManager.Iface.class, true);

  @VisibleForTesting
  static final Matcher<Method> AURORA_ADMIN_SERVICE =
      GuiceUtils.interfaceMatcher(AuroraAdmin.Iface.class, true);

  public enum HttpAuthenticationMechanism {
    /**
     * No security.
     */
    NONE,

    /**
     * HTTP Basic Authentication, produces {@link org.apache.shiro.authc.UsernamePasswordToken}s.
     */
    BASIC,

    /**
     * Use GSS-Negotiate. Only Kerberos and SPNEGO-with-Kerberos GSS mechanisms are supported.
     */
    NEGOTIATE,
  }

  @CmdLine(name = "http_authentication_mechanism", help = "HTTP Authentication mechanism to use.")
  private static final Arg<HttpAuthenticationMechanism> HTTP_AUTHENTICATION_MECHANISM =
      Arg.create(HttpAuthenticationMechanism.NONE);

  private final HttpAuthenticationMechanism mechanism;
  private final Set<Module> shiroConfigurationModules;
  private final Optional<Key<? extends Filter>> shiroAfterAuthFilterKey;

  public HttpSecurityModule() {
    this(
        HTTP_AUTHENTICATION_MECHANISM.get(),
        SHIRO_REALM_MODULE.get(),
        SHIRO_AFTER_AUTH_FILTER.hasAppliedValue() ? Key.get(SHIRO_AFTER_AUTH_FILTER.get()) : null);
  }

  @VisibleForTesting
  HttpSecurityModule(
      Module shiroConfigurationModule,
      Key<? extends Filter> shiroAfterAuthFilterKey) {

    this(HttpAuthenticationMechanism.BASIC,
        ImmutableSet.of(shiroConfigurationModule),
        shiroAfterAuthFilterKey);
  }

  private HttpSecurityModule(
      HttpAuthenticationMechanism mechanism,
      Set<Module> shiroConfigurationModules,
      Key<? extends Filter> shiroAfterAuthFilterKey) {

    this.mechanism = requireNonNull(mechanism);
    this.shiroConfigurationModules = requireNonNull(shiroConfigurationModules);
    this.shiroAfterAuthFilterKey = Optional.ofNullable(shiroAfterAuthFilterKey);
  }

  @Override
  protected void configureServlets() {
    if (mechanism == HttpAuthenticationMechanism.NONE) {
      // TODO(ksweeney): Use an OptionalBinder here once we're on Guice 4.0.
      bind(new TypeLiteral<Optional<Subject>>() { }).toInstance(Optional.empty());
    } else {
      doConfigureServlets();
    }
  }

  private void doConfigureServlets() {
    bind(Subject.class).toProvider(SecurityUtils::getSubject).in(RequestScoped.class);
    install(new AbstractModule() {
      @Override
      protected void configure() {
        // Provides-only module to provide Optional<Subject>.
        // TODO(ksweeney): Use an OptionalBinder here once we're on Guice 4.0.
      }

      @Provides
      Optional<Subject> provideOptionalSubject(Subject subject) {
        return Optional.of(subject);
      }
    });
    install(guiceFilterModule(API_PATH));
    install(guiceFilterModule(H2_PATH));
    install(guiceFilterModule(H2_PATH + "/*"));
    install(new ShiroWebModule(getServletContext()) {

      // Replace the ServletContainerSessionManager which causes subject.runAs(...) in a
      // downstream user-defined filter to fail. See also: SHIRO-554
      @Override
      protected void bindSessionManager(AnnotatedBindingBuilder<SessionManager> bind) {
        bind.to(DefaultSessionManager.class).asEagerSingleton();
      }

      @Override
      @SuppressWarnings("unchecked")
      protected void configureShiroWeb() {
        for (Module module : shiroConfigurationModules) {
          // We can't wrap this in a PrivateModule because Guice Multibindings don't work with them
          // and we need a Set<Realm>.
          install(module);
        }

        // Filter registration order is important here and is defined by the matching pattern:
        // more specific pattern first.
        switch (mechanism) {
          case BASIC:
            addFilterChain(H2_PATTERN, NO_SESSION_CREATION, AUTHC_BASIC, config(PERMS, H2_PERM));
            addFilterChainWithAfterAuthFilter(config(AUTHC_BASIC, PERMISSIVE));
            break;

          case NEGOTIATE:
            addFilterChain(H2_PATTERN, NO_SESSION_CREATION, K_STRICT, config(PERMS, H2_PERM));
            addFilterChainWithAfterAuthFilter(K_PERMISSIVE);
            break;

          default:
            addError("Unrecognized HTTP authentication mechanism: " + mechanism);
            break;
        }
      }

      private void addFilterChainWithAfterAuthFilter(Key<? extends Filter> filter) {
        if (shiroAfterAuthFilterKey.isPresent()) {
          addFilterChain(filter, shiroAfterAuthFilterKey.get());
        } else {
          addFilterChain(filter);
        }
      }

      @SuppressWarnings("unchecked")
      private void addFilterChain(Key<? extends Filter> filter) {
        addFilterChain(ALL_PATTERN, NO_SESSION_CREATION, filter);
      }

      @SuppressWarnings("unchecked")
      private void addFilterChain(Key<? extends Filter> filter1, Key<? extends Filter> filter2) {
        addFilterChain(ALL_PATTERN, NO_SESSION_CREATION, filter1, filter2);
      }
    });

    bindConstant().annotatedWith(Names.named("shiro.applicationName")).to(HTTP_REALM_NAME);

    // TODO(ksweeney): Disable session cookie.
    // TODO(ksweeney): Disable RememberMe cookie.

    install(new ShiroAopModule());

    // It is important that authentication happen before authorization is attempted, otherwise
    // the authorizing interceptor will always fail.
    MethodInterceptor authenticatingInterceptor = new ShiroAuthenticatingThriftInterceptor();
    requestInjection(authenticatingInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AuroraSchedulerManager.Iface.class),
        AURORA_SCHEDULER_MANAGER_SERVICE.or(AURORA_ADMIN_SERVICE),
        authenticatingInterceptor);

    MethodInterceptor apiInterceptor = new ShiroAuthorizingParamInterceptor();
    requestInjection(apiInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AuroraSchedulerManager.Iface.class),
        AURORA_SCHEDULER_MANAGER_SERVICE,
        apiInterceptor);

    MethodInterceptor adminInterceptor = new ShiroAuthorizingInterceptor(THRIFT_AURORA_ADMIN);
    requestInjection(adminInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AnnotatedAuroraAdmin.class),
        AURORA_ADMIN_SERVICE,
        adminInterceptor);
  }
}
