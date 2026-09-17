package com.metropulse.realtime;

import com.metropulse.common.config.MetroPulseProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The realtime channel.
 *
 * <h2>REST for the baseline, WebSocket for what changed</h2>
 *
 * <p>The dashboard fetches its picture over REST and then subscribes for updates. It does not build
 * its state from the socket alone, because a client that has only ever seen deltas cannot know what
 * it missed while it was disconnected. After a reconnect it refetches the baseline and resumes.
 *
 * <p>That also means the socket is allowed to drop messages. Nothing is acknowledged, nothing is
 * replayed, and no state depends on a delta arriving — losing one costs at most a few seconds of
 * staleness until the next update or the next baseline refresh.
 *
 * <h2>What is broadcast</h2>
 *
 * <pre>
 *   /topic/vehicles   current state of the fleet, one message per projection tick
 *   /topic/alerts     the live alert list, when it changes
 *   /topic/headway    spacing conditions, when they change
 * </pre>
 *
 * <p>Summaries rather than raw telemetry. Sending every observation to every browser would be tens of
 * messages a second per client to say what one message can, and the control centre's question is
 * "what is the network doing", not "what did BUS-042 report at 10:15:02".
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final MetroPulseProperties properties;
    private final WebSocketAuthenticationInterceptor authenticationInterceptor;

    public WebSocketConfig(
            MetroPulseProperties properties,
            WebSocketAuthenticationInterceptor authenticationInterceptor
    ) {
        this.properties = properties;
        this.authenticationInterceptor = authenticationInterceptor;
    }

    /** Every inbound frame passes the authentication interceptor, starting with CONNECT. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // An in-memory broker: fine for one instance. Several instances would each broadcast only to
        // their own subscribers, which is the point at which a real broker relay becomes necessary.
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(properties.auth().allowedOrigins().toArray(String[]::new));
    }
}
