package com.github.thomasdarimont.keycloak.embedded;

import com.github.thomasdarimont.keycloak.embedded.support.InfinispanCacheManagerProvider;
import com.github.thomasdarimont.keycloak.embedded.support.SpringBootConfigProvider;
import com.github.thomasdarimont.keycloak.embedded.support.SpringBootPlatform;
import lombok.RequiredArgsConstructor;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher;
import org.jboss.resteasy.plugins.server.servlet.ResteasyContextParameters;
import org.keycloak.services.filters.KeycloakSessionServletFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.naming.CompositeName;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingException;
import javax.naming.spi.NamingManager;
import javax.sql.DataSource;
import java.io.InputStream;

@Configuration
@RequiredArgsConstructor
public class EmbeddedKeycloakConfig {

    private final KeycloakCustomProperties customProperties;

    @Bean
    @Lazy
    protected SpringBootPlatform springBootPlatform() {
        return new SpringBootPlatform();
    }

    @Bean
    protected SpringBootConfigProvider springBootConfigProvider(KeycloakProperties keycloakProperties) {
        return new SpringBootConfigProvider(keycloakProperties);
    }

    @Bean
    protected DefaultCacheManager keycloakInfinispanCacheManager() throws Exception {

        KeycloakCustomProperties.Infinispan infinispan = customProperties.getInfinispan();
        try (InputStream inputStream = infinispan.getConfigLocation().getInputStream()) {
            ConfigurationBuilderHolder builder = new ParserRegistry().parse(inputStream);
            return new DefaultCacheManager(builder, true);
        }
    }

    @Autowired
    protected void mockJndiEnvironment(DataSource dataSource, DefaultCacheManager infinispanCacheManager) throws NamingException {

        NamingManager.setInitialContextFactoryBuilder((env) -> (environment) -> new InitialContext() {

            @Override
            public Object lookup(Name name) {
                return lookup(name.toString());
            }

            @Override
            public Object lookup(String name) {

                if ("spring/datasource".equals(name)) {
                    return dataSource;
                }

                if (InfinispanCacheManagerProvider.JNDI_NAME.equals(name)) {
                    return infinispanCacheManager;
                }

                return null;
            }

            @Override
            public NameParser getNameParser(String name) {
                return CompositeName::new;
            }

            @Override
            public void close() {
                //NOOP
            }
        });
    }

    @Bean
    protected ServletRegistrationBean<HttpServlet30Dispatcher> keycloakJaxRsApplication(SpringBootConfigProvider configProvider) {

        //FIXME: hack to propagate Spring Boot ConfigProvider to Keycloak Application
        EmbeddedKeycloakApplication.configProvider = configProvider;

        ServletRegistrationBean<HttpServlet30Dispatcher> servlet = new ServletRegistrationBean<>(new HttpServlet30Dispatcher());
        servlet.addInitParameter("javax.ws.rs.Application", EmbeddedKeycloakApplication.class.getName());

        servlet.addInitParameter("resteasy.allowGzip", "true");
        servlet.addInitParameter("keycloak.embedded", "true");
        servlet.addInitParameter("resteasy.document.expand.entity.references", "false");
        servlet.addInitParameter("resteasy.document.secure.processing.feature", "true");
        servlet.addInitParameter("resteasy.document.secure.disableDTDs", "true");

        servlet.addInitParameter(ResteasyContextParameters.RESTEASY_SERVLET_MAPPING_PREFIX, "/");
        servlet.addInitParameter(ResteasyContextParameters.RESTEASY_USE_CONTAINER_FORM_PARAMS, "false");
        servlet.addUrlMappings("/*");
        servlet.setLoadOnStartup(1);
        servlet.setAsyncSupported(true);

        return servlet;
    }

    @Bean
    protected FilterRegistrationBean<KeycloakSessionServletFilter> keycloakSessionManagement() {

        FilterRegistrationBean<KeycloakSessionServletFilter> filter = new FilterRegistrationBean<>();
        filter.setName("Keycloak Session Management");
        filter.setFilter(new KeycloakSessionServletFilter());
        filter.addUrlPatterns("/*");

        return filter;
    }
}
