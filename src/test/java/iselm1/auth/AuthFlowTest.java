package iselm1.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VerificationMailer mailer;

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 로그인하지_않으면_콘텐츠_API_는_401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/docker/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws/terminal")).andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF_토큰이_없으면_거부() throws Exception {
        mvc.perform(json(post("/api/auth/login"), "{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 비밀번호_규칙에_맞지_않으면_가입_거부() throws Exception {
        for (String password : new String[]{"short1!A", "alllowercase1!", "NoSpecial123", "NoDigits!!Aa", "Has Space1!A"}) {
            int expected = password.equals("short1!A") ? 200 : 400; // short1!A 는 8자로 규칙에 맞음
            mvc.perform(json(post("/api/auth/signup").with(csrf()),
                            "{\"username\":\"pw" + Math.abs(password.hashCode() % 100000) + "\",\"password\":\"" + password
                                    + "\",\"email\":\"pw" + Math.abs(password.hashCode() % 100000) + "@example.com\"}"))
                    .andExpect(status().is(expected));
        }
        mvc.perform(json(post("/api/auth/signup").with(csrf()),
                        "{\"username\":\"toolongpassword\",\"password\":\"Abcdefgh1!Abcdefgh1!X\",\"email\":\"long@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 가입_후_이메일_인증을_마쳐야_이용_가능() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(json(post("/api/auth/signup").with(csrf()).session(session),
                        "{\"username\":\"student1\",\"password\":\"Passw0rd!\",\"email\":\"Student1@Example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me.email").value("student1@example.com"))
                .andExpect(jsonPath("$.me.active").value(false))
                .andExpect(jsonPath("$.mailSent").value(true));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendCode(eq("student1@example.com"), code.capture(), anyLong());
        assertThat(code.getValue()).matches("\\d{6}");

        // 인증 전: 로그인은 되었지만 콘텐츠는 403
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/docker/health").session(session)).andExpect(status().isForbidden());

        // 바로 다시 보내기는 대기 시간 때문에 거부
        mvc.perform(post("/api/auth/email/send").with(csrf()).session(session)).andExpect(status().isTooManyRequests());

        // 틀린 번호
        String wrong = code.getValue().equals("000000") ? "111111" : "000000";
        mvc.perform(json(post("/api/auth/email/verify").with(csrf()).session(session), "{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.remainingAttempts").value(4));

        // 맞는 번호 → 이용 가능
        mvc.perform(json(post("/api/auth/email/verify").with(csrf()).session(session),
                        "{\"code\":\"" + code.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        int health = mvc.perform(get("/api/docker/health").session(session)).andReturn().getResponse().getStatus();
        assertThat(health).isNotIn(401, 403);

        // 같은 아이디·이메일로는 다시 가입할 수 없음
        mvc.perform(json(post("/api/auth/signup").with(csrf()),
                        "{\"username\":\"student1\",\"password\":\"Passw0rd!\",\"email\":\"other@example.com\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/auth/signup").with(csrf()),
                        "{\"username\":\"student2\",\"password\":\"Passw0rd!\",\"email\":\"STUDENT1@example.com\"}"))
                .andExpect(status().isConflict());

        // 로그아웃 후에는 다시 401
        mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/docker/health").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void 연속으로_틀리면_로그인_잠금() throws Exception {
        mvc.perform(json(post("/api/auth/signup").with(csrf()),
                        "{\"username\":\"locktest\",\"password\":\"Passw0rd!\",\"email\":\"locktest@example.com\"}"))
                .andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/auth/login").with(csrf()), "{\"username\":\"locktest\",\"password\":\"Wrong!234\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // 잠긴 동안에는 맞는 비밀번호도 거부
        mvc.perform(json(post("/api/auth/login").with(csrf()), "{\"username\":\"locktest\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 관리자_계정은_설정으로_만들어지고_바로_이용_가능() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(json(post("/api/auth/login").with(csrf()).session(session),
                        "{\"username\":\"admin_test\",\"password\":\"Admin!2345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.active").value(true));
        int health = mvc.perform(get("/api/docker/health").session(session)).andReturn().getResponse().getStatus();
        assertThat(health).isNotIn(401, 403);
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.googleEnabled").value(false));
    }
}
