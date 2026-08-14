package bg.spotyourslot.identity.configuration;

import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.domain.TokenCodec;
import bg.spotyourslot.identity.infrastructure.DatabaseSessionFilter;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    private static final String AUTHENTICATION_REQUIRED_PROBLEM =
            "{\"type\":\"about:blank\","
                    + "\"title\":\"Необходим е вход.\","
                    + "\"status\":401,"
                    + "\"code\":\"AUTH_REQUIRED\"}";
    private static final String ACCESS_DENIED_PROBLEM =
            "{\"type\":\"about:blank\","
                    + "\"title\":\"Заявката не може да бъде изпълнена.\","
                    + "\"status\":403,"
                    + "\"detail\":\"Нямате достъп до тази операция.\","
                    + "\"code\":\"ACCESS_DENIED\"}";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TokenCodec tokenCodec() {
        return new TokenCodec(new SecureRandom());
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        return new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${spotyourslot.security.allowed-origin}") String origin) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DatabaseSessionFilter sessions,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies)
            throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        csrf.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secureCookies));
        return http
                .cors(Customizer.withDefaults())
                .csrf(config -> config.csrfTokenRepository(csrf))
                .addFilterBefore(sessions, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**", "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/password/forgot",
                                "/api/auth/password/reset",
                                "/api/auth/invitations/accept")
                        .permitAll()
                        .requestMatchers("/api/platform/**", "/api/dev/**")
                        .hasAuthority("PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(config -> config.disable())
                .formLogin(config -> config.disable())
                .logout(config -> config.disable())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/problem+json");
                            response.getWriter().write(AUTHENTICATION_REQUIRED_PROBLEM);
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/problem+json");
                            response.getWriter().write(ACCESS_DENIED_PROBLEM);
                        }))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'")))
                .build();
    }
}
