package iselm1.websocket;

import iselm1.capture.CaptureWebSocketHandler;
import iselm1.config.LabProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final CaptureWebSocketHandler captureWebSocketHandler;
    private final LabProperties labProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = labProperties.cors().allowedOrigins().toArray(String[]::new);
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal").setAllowedOrigins(origins);
        registry.addHandler(captureWebSocketHandler, "/ws/capture").setAllowedOrigins(origins);
    }
}
