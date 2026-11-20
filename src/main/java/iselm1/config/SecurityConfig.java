package iselm1.config;

import iselm1.auth.AccountRepository;
import iselm1.auth.AuthUser;
import iselm1.auth.GoogleLoginHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 접근 규칙
 * <ul>
 *   <li>누구나: 로그인 상태 확인, 로그인, 회원가입, 구글 로그인</li>
 *   <li>로그인(이메일 인증 전 포함): 인증번호 보내기/확인, 로그아웃</li>
 *   <li>이메일 인증을 마친 회원·관리자: 나머지 전부 (실습 WebSocket, SSE, REST)</li>
 * </ul>
 * 세션 쿠키(JSESSIONID) 로그인이며, 상태를 바꾸는 요청은 CSRF 토큰(쿠키 XSRF-TOKEN → 헤더 X-XSRF-TOKEN)이 필요하다.
 * 인증되지 않은 요청에는 로그인 페이지로 보내지 않고 401 을 돌려준다 (화면 이동은 프론트엔드가 한다).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ObjectProvider<ClientRegistrationRepository> googleClients,
                                                   GoogleLoginHandler googleLoginHandler) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // 쿠키 값을 그대로 헤더로 보내는 SPA 방식 (BREACH 대비 XOR 인코딩을 쓰지 않음)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/me", "/api/auth/config", "/api/auth/csrf",
                                "/api/auth/login", "/api/auth/signup",
                                "/api/auth/signup/google", "/api/auth/google/pending",
                                "/oauth2/**", "/login/oauth2/**", "/error").permitAll()
                        .requestMatchers("/api/auth/email/**").authenticated()
                        .anyRequest().hasAnyRole("USER", "ADMIN"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        if (googleClients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    // 기본 로그인 페이지를 만들지 않게 한다 (화면은 프론트엔드)
                    .loginPage("/oauth2/authorization/google")
                    .successHandler(googleLoginHandler)
                    .failureHandler(googleLoginHandler));
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 아이디 로그인은 AuthController 가 직접 확인한다. 이 빈은 Spring Boot 가 임시 사용자(generated password)를
     * 만들지 않게 하는 용도다.
     */
    @Bean
    public UserDetailsService userDetailsService(AccountRepository accounts) {
        return username -> accounts.findByUsername(username)
                .map(AuthUser::of)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    /** /api/** CORS. 세션 쿠키를 함께 보내므로 Origin 을 명시하고 allowCredentials 를 켠다. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(LabProperties labProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(labProperties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
