package iselm1.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * 구글 OAuth 클라이언트. auth.google.client-id / client-secret 이 있을 때만 만든다
 * (없으면 구글 로그인 기능이 꺼지고 프론트엔드에 구글 버튼이 보이지 않는다).
 * 리디렉션 URI: {서버 주소}/login/oauth2/code/google
 */
@Configuration
public class GoogleOAuthConfig {

    @Bean
    @ConditionalOnExpression("!'${auth.google.client-id:}'.isBlank() and !'${auth.google.client-secret:}'.isBlank()")
    public ClientRegistrationRepository googleClientRegistrationRepository(AuthProperties properties) {
        return new InMemoryClientRegistrationRepository(
                CommonOAuth2Provider.GOOGLE.getBuilder("google")
                        .clientId(properties.google().clientId())
                        .clientSecret(properties.google().clientSecret())
                        .scope("openid", "email", "profile")
                        .build());
    }
}
