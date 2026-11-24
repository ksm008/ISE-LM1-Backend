package iselm1.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocsControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(get("/api/docs/isec/vulnerability")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/docs/isec/vulnerability/01-first")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "PENDING")
    void 이메일_인증_전이면_403() throws Exception {
        mvc.perform(get("/api/docs/isec/vulnerability")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 목록은_md_파일만_이름순으로_제목과_함께() throws Exception {
        mvc.perform(get("/api/docs/isec/vulnerability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].slug").value("01-first"))
                .andExpect(jsonPath("$[0].title").value("첫 번째 문서"))
                .andExpect(jsonPath("$[1].slug").value("02-no-title"))
                .andExpect(jsonPath("$[1].title").value("02-no-title"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 문서가_없는_항목은_빈_목록() throws Exception {
        mvc.perform(get("/api/docs/isec/trends")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 문서_하나() throws Exception {
        mvc.perform(get("/api/docs/isec/vulnerability/01-first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("01-first"))
                .andExpect(jsonPath("$.title").value("첫 번째 문서"))
                .andExpect(jsonPath("$.markdown").value(containsString("본문입니다.")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 없는_문서나_잘못된_이름은_404() throws Exception {
        for (String path : new String[]{
                "/api/docs/isec/vulnerability/99-none",
                "/api/docs/isec/vulnerability/notes",       // .txt 는 문서가 아님
                "/api/docs/isec/vulnerability/01-FIRST",    // 대문자 불가
                "/api/docs/isec/vulnerability/01-first.md", // 확장자 붙여도 불가
                "/api/docs/isec/vulnerability/..",
        }) {
            mvc.perform(get(path)).andExpect(status().is4xxClientError());
        }
        mvc.perform(get("/api/docs/isec/vulnerability/99-none")).andExpect(status().isNotFound());
        mvc.perform(get("/api/docs/ISEC/vulnerability")).andExpect(status().isNotFound());
    }
}
