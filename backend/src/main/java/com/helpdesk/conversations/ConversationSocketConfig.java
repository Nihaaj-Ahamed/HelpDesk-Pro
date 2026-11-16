package com.helpdesk.conversations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
@EnableScheduling
public class ConversationSocketConfig implements WebSocketConfigurer {

  private final ConversationSocket socket;
  private final String origin;

  public ConversationSocketConfig(
    ConversationSocket socket,
    @Value("${app.origin}") String origin
  ) {
    this.socket = socket;
    this.origin = origin;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(socket, "/ws/conversations").setAllowedOrigins(origin);
  }
}
