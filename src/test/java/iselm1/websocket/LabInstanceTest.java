package iselm1.websocket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabInstanceTest {

    @Test
    void 설정값은_라벨에_쓸_수_있는_형태로() {
        assertThat(TerminalWebSocketHandler.resolveInstance("prod")).isEqualTo("prod");
        assertThat(TerminalWebSocketHandler.resolveInstance(" My PC#1 ")).isEqualTo("my-pc-1");
        assertThat(TerminalWebSocketHandler.resolveInstance("서버")).isEqualTo("--");
    }

    @Test
    void 비우면_컴퓨터_이름() {
        String name = TerminalWebSocketHandler.resolveInstance("");
        assertThat(name).isNotBlank().matches("[a-z0-9._-]+");
        assertThat(TerminalWebSocketHandler.resolveInstance(null)).isEqualTo(name);
    }
}
