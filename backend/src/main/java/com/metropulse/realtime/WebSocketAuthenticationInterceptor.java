package com.metropulse.realtime;

import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates the STOMP connection.
 *
 * <p>The socket needs its own check. The HTTP filter chain runs on the handshake, but a browser
 * cannot set an {@code Authorization} header on a WebSocket handshake, so the token arrives in the
 * STOMP CONNECT frame and has to be verified here instead.
 *
 * <p>Without this, an unauthenticated client could open a socket and receive every broadcast — a leak
 * no amount of REST authorisation would catch, because that data never travels over REST.
 */
@Component
public class WebSocketAuthenticationInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public WebSocketAuthenticationInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = bearerToken(accessor);
        if (token == null) {
            throw new IllegalArgumentException("A STOMP connection requires an access token.");
        }

        // Throws if the signature is wrong or the token has expired, which refuses the connection.
        Jwt jwt = jwtDecoder.decode(token);

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                jwt.getSubject(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        accessor.setUser(authentication);

        return message;
    }

    private String bearerToken(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }

        String value = values.getFirst();
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : null;
    }
}
