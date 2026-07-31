package com.feng.dsagent.security;

import com.feng.dsagent.auth.RolePolicy;
import com.feng.dsagent.common.ApiError;
import com.feng.dsagent.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    JwtTokenService jwtTokenService(SecurityProperties properties, ObjectMapper objectMapper, Clock clock) {
        return new JwtTokenService(
            properties.jwtSecret(),
            properties.tokenTtl(),
            properties.issuer(),
            objectMapper,
            clock
        );
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    RolePolicy rolePolicy(SecurityProperties properties) {
        return new RolePolicy(properties.bootstrapAdminEmail(), properties.teacherEmails());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseOrigins(properties.corsAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtFilter,
        ObjectMapper objectMapper
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/chapters/**", "/api/v1/resources/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/chat", "/api/v1/chat/stream").permitAll()
                .requestMatchers("/api/v1/code/**", "/api/v1/animations/generate").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeError(
                    request,
                    response,
                    objectMapper,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "AUTH_REQUIRED",
                    "请先登录"
                ))
                .accessDeniedHandler((request, response, exception) -> writeError(
                    request,
                    response,
                    objectMapper,
                    HttpServletResponse.SC_FORBIDDEN,
                    "AUTH_FORBIDDEN",
                    "当前账号无权执行该操作"
                ))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    private List<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .distinct()
            .toList();
    }

    private void writeError(
        HttpServletRequest request,
        HttpServletResponse response,
        ObjectMapper objectMapper,
        int status,
        String code,
        String message
    ) throws java.io.IOException {
        Object requestIdValue = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = requestIdValue == null ? "" : requestIdValue.toString();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(
            code,
            message,
            requestId,
            List.of()
        )));
    }
}
