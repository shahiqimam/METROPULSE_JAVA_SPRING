package com.metropulse.realtime;

import com.metropulse.auth.application.AuthenticationService;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The realtime channel, over a real WebSocket.
 *
 * <p>Runs the application on a real port and connects a STOMP client to it, because what is under
 * test is the handshake and the CONNECT frame — the parts a mocked messaging template would skip
 * entirely. The question these answer is "can an unauthenticated client receive operational data",
 * and only a real connection can answer it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "metropulse.outbox.enabled=false",
                "metropulse.operations.consumer-enabled=false",
                "metropulse.scheduling-enabled=false"
        }
)
class WebSocketIntegrationTest extends PostgisIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthenticationService authenticationService;

    private WebSocketStompClient client;

    @org.junit.jupiter.api.BeforeEach
    void clearAlerts() {
        jdbcTemplate.update("DELETE FROM alert");
        // The broadcaster remembers what it last sent, and it is a singleton shared by every test in
        // this context. Settling it here keeps each test independent of the order they run in.
        realtimeBroadcaster.broadcast();
    }

    @AfterEach
    void stopClient() {
        if (client != null) {
            client.stop();
        }
    }

    @Test
    void anAuthenticatedClientCanConnectAndSubscribe() throws Exception {
        StompSession session = connect(controllerToken());

        BlockingQueue<Object> received = subscribe(session, "/topic/vehicles");

        assertThat(session.isConnected()).isTrue();
        // Nothing is broadcast until something changes, so an empty queue here is correct.
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void aClientWithoutATokenIsRefused() {
        assertThatThrownBy(() -> connect(null))
                .as("an unauthenticated socket would receive every broadcast")
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aClientWithAGarbageTokenIsRefused() {
        assertThatThrownBy(() -> connect("not-a-real-token"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void aViewerMayConnectBecauseReadingIsAllowedForEveryRole() throws Exception {
        String token = authenticationService
                .login("viewer@metropulse.test", "viewer-dev-password")
                .accessToken();

        StompSession session = connect(token);

        assertThat(session.isConnected()).isTrue();
    }

    @Test
    void aBroadcastReachesASubscribedClient() throws Exception {
        StompSession session = connect(controllerToken());
        BlockingQueue<Object> received = subscribe(session, "/topic/alerts");

        openAlert("ROUTE_DEVIATION|BUS-042");
        // Drive the broadcaster directly; the scheduler is off in tests.
        broadcaster().broadcast();

        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void nothingIsBroadcastWhenNothingChanged() throws Exception {
        StompSession session = connect(controllerToken());
        BlockingQueue<Object> received = subscribe(session, "/topic/alerts");

        openAlert("ROUTE_DEVIATION|BUS-042");
        broadcaster().broadcast();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();

        broadcaster().broadcast();

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as("a screen should only change when the network does")
                .isNull();
    }

    @Test
    void clientsAreToldWhenTheLastAlertGoesAway() throws Exception {
        StompSession session = connect(controllerToken());
        BlockingQueue<Object> received = subscribe(session, "/topic/alerts");

        openAlert("ROUTE_DEVIATION|BUS-042");
        broadcaster().broadcast();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();

        jdbcTemplate.update("DELETE FROM alert");
        broadcaster().broadcast();

        // An empty list is a change worth sending: without it the screen would keep showing an alert
        // that has closed.
        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(List.of());
    }

    private void openAlert(String fingerprint) {
        jdbcTemplate.update("""
                INSERT INTO alert (type, fingerprint, severity, status, opened_at, last_observed_at)
                VALUES ('ROUTE_DEVIATION', ?, 'MAJOR', 'OPEN', now(), now())
                ON CONFLICT DO NOTHING
                """, fingerprint);
    }

    @Autowired
    private RealtimeBroadcaster realtimeBroadcaster;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private RealtimeBroadcaster broadcaster() {
        return realtimeBroadcaster;
    }

    private String controllerToken() {
        return authenticationService.login("controller@metropulse.test", "controller-dev-password").accessToken();
    }

    private StompSession connect(String token) throws Exception {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }

        CompletableFuture<StompSession> future = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                });

        return future.get(10, TimeUnit.SECONDS);
    }

    private BlockingQueue<Object> subscribe(StompSession session, String destination) {
        BlockingQueue<Object> received = new LinkedBlockingQueue<>();

        session.subscribe(destination, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return List.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(payload == null ? List.of() : payload);
            }

            @Override
            public void handleException(
                    StompSession session,
                    StompCommand command,
                    StompHeaders headers,
                    byte[] payload,
                    Throwable exception
            ) {
                received.add(exception);
            }
        });

        return received;
    }
}
