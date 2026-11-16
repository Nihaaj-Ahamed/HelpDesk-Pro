package com.helpdesk;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {

  @Bean
  SecretKeySpec jwtKey(@Value("${app.jwt-secret}") String secret) {
    if (
      secret.getBytes(StandardCharsets.UTF_8).length < 32
    ) throw new IllegalArgumentException(
      "JWT_SECRET must contain at least 32 bytes"
    );
    return new SecretKeySpec(
      secret.getBytes(StandardCharsets.UTF_8),
      "HmacSHA256"
    );
  }

  @Bean
  JwtEncoder encoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder decoder(SecretKeySpec key) {
    var decoder = NimbusJwtDecoder.withSecretKey(key)
      .macAlgorithm(MacAlgorithm.HS256)
      .build();
    decoder.setJwtValidator(
      new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("helpdesk-pro"),
        new JwtClaimValidator<java.util.List<String>>(
          "aud",
          aud -> aud != null && aud.contains("helpdesk-web")
        )
      )
    );
    return decoder;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain security(
    HttpSecurity http,
    @Value("${app.origin}") String origin,
    @Value("${app.auth-rate-limit:20}") int rateLimit
  ) throws Exception {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(origin));
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return http
      .cors(c -> c.configurationSource(source))
      .csrf(c -> c.disable())
      .addFilterBefore(
        new com.helpdesk.identity.AuthRateLimitFilter(rateLimit),
        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class
      )
      .sessionManagement(s ->
        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .authorizeHttpRequests(a ->
        a
          .requestMatchers(
            "/api/auth/**",
            "/ws/conversations",
            "/api/invitations/preview",
            "/api/support/{slug}",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      )
      .oauth2ResourceServer(o ->
        o
          .jwt(j -> {})
          .authenticationEntryPoint((req, res, e) -> {
            res.setStatus(401);
            res.setContentType("application/problem+json");
            res
              .getWriter()
              .write("{\"status\":401,\"title\":\"Authentication required\"}");
          })
      )
      .headers(h ->
        h.contentSecurityPolicy(c ->
          c.policyDirectives("default-src 'self'; frame-ancestors 'none'")
        )
      )
      .build();
  }
}
