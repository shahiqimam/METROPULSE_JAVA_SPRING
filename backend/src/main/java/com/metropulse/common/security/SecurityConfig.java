package com.metropulse.common.security;

import com.metropulse.common.config.MetroPulseProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Who may call what.
 *
 * <h2>The request path</h2>
 *
 * <pre>
 *   HTTP request
 *     -> SecurityFilterChain
 *     -> BearerTokenAuthenticationFilter   reads the Authorization header
 *     -> JwtDecoder                        verifies the signature and expiry
 *     -> JwtAuthenticationConverter        turns the role claim into an authority
 *     -> SecurityContext                   holds the authentication for this request
 *     -> authorization rules below
 *     -> controller
 * </pre>
 *
 * <h2>Rules, and why they are here rather than on methods</h2>
 *
 * <p>Authorisation is expressed by URL and HTTP method in one place, so the whole policy can be read
 * at once. Scattering {@code @PreAuthorize} across controllers makes "who can close an alert" a
 * question you answer by grepping.
 *
 * <p>The shape of the policy: reading is open to any authenticated operator, acting on the network
 * needs a CONTROLLER, and administration needs an ADMIN. A PLANNER and a VIEWER can see everything
 * and change nothing, which is what those jobs are.
 *
 * <p>Telemetry ingest is not part of this at all — it is machine-to-machine, authenticated by the
 * ingest key, and a bearer token would mean the simulator holding an operator's credentials.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String CONTROLLER = "CONTROLLER";
    private static final String ADMIN = "ADMIN";
    private static final String FLEET_SUPERVISOR = "FLEET_SUPERVISOR";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MetroPulseProperties properties) throws Exception {
        return http
                // No cookies and no sessions, so there is no CSRF surface: a bearer token is not sent
                // automatically by the browser the way a cookie is.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Public: health, docs, and the endpoints you need before you have a token.
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()

                        // Machine-to-machine, guarded by the ingest key rather than by a token.
                        .requestMatchers(HttpMethod.POST, "/api/v1/telemetry/ingest").permitAll()

                        // Acting on the network.
                        .requestMatchers(HttpMethod.POST, "/api/v1/alerts/**").hasAnyRole(CONTROLLER, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents/**").hasAnyRole(CONTROLLER, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/charging-sessions/**")
                                .hasAnyRole(CONTROLLER, FLEET_SUPERVISOR, ADMIN)

                        // Administration.
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        .requestMatchers("/actuator/**").hasRole(ADMIN)

                        // Everything else: any authenticated operator may read.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Basic auth stays available so curl and the dev tooling keep working against a
                // seeded operator; it is the same users and the same roles, just a different way to
                // present them. A production profile would turn it off.
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * HMAC signing with a shared secret.
     *
     * <p>Symmetric because there is one service issuing and verifying. A second service verifying
     * these tokens would want asymmetric keys, so it can check signatures without being able to mint
     * them.
     */
    @Bean
    JwtEncoder jwtEncoder(MetroPulseProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    /**
     * Verifies signature and expiry.
     *
     * <p>Timestamp validation uses the injected {@link Clock} rather than the library's default of
     * system time, so issuing and verifying always agree on what "now" is. In production both are the
     * system clock; the difference only shows up under a test clock, where a token minted at the test
     * time would otherwise be judged expired against the wall clock.
     */
    @Bean
    JwtDecoder jwtDecoder(MetroPulseProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampValidator));

        return decoder;
    }

    /** Maps the single {@code role} claim onto the authority Spring Security expects. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName("role");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * CORS allowlist.
     *
     * <p>An explicit list of origins, never a wildcard: the API is called with credentials, and
     * a wildcard would let any site on the internet make authenticated calls on a logged-in
     * operator's behalf.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(MetroPulseProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.auth().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Ingest-Key", "X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private SecretKeySpec secretKey(MetroPulseProperties properties) {
        byte[] secret = properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 with a key shorter than its output is a weakness the library will not warn about.
            throw new IllegalStateException(
                    "metropulse.auth.jwt-secret must be at least 32 bytes; it is " + secret.length + ".");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
