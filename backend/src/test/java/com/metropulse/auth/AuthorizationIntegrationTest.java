package com.metropulse.auth;

import com.metropulse.auth.api.AuthTokens;
import com.metropulse.auth.application.AuthenticationService;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorisation policy, exercised through the filter chain rather than asserted about.
 *
 * <p>These go through MockMvc with Spring Security wired in, so what is tested is the same path a
 * real request takes: bearer token, decoder, role claim, rules. Asserting on the configuration object
 * would prove the rules were written, not that they apply.
 */
class AuthorizationIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // These tests are about who may do a thing, not about what the thing leaves behind. Without
        // this, one test occupying a charger makes a later one fail with 409 - and since JUnit orders
        // methods by a hash of their names, adding an unrelated test can be what triggers it.
        jdbcTemplate.update("DELETE FROM charging_session");
        jdbcTemplate.update("UPDATE charger SET status = 'AVAILABLE' WHERE status = 'OCCUPIED'");
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void readingWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/telemetry/vehicles/latest")).andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsTokensAndMeReportsTheCaller() throws Exception {
        String token = tokenFor("controller@metropulse.test", "controller-dev-password");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("controller@metropulse.test"))
                .andExpect(jsonPath("$.role").value("CONTROLLER"));
    }

    @Test
    void loginWithBadCredentialsSaysNothingUseful() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"controller@metropulse.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void aViewerMayReadEverythingAndChangeNothing() throws Exception {
        String token = tokenFor("viewer@metropulse.test", "viewer-dev-password");

        mockMvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/analytics/ev").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/alerts/1/acknowledge").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incidentJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlannerMayReadButNotAct() throws Exception {
        String token = tokenFor("planner@metropulse.test", "planner-dev-password");

        mockMvc.perform(get("/api/v1/routes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incidentJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlannerMayReviewScheduleFeedsButNotPutOneIntoService() throws Exception {
        String token = tokenFor("planner@metropulse.test", "planner-dev-password");

        mockMvc.perform(get("/api/v1/admin/schedule/imports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Staging is a proposal; activation changes what every operational number is measured
        // against, so it stays an administrator's decision.
        mockMvc.perform(post("/api/v1/admin/schedule/imports/1/activate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/schedule/imports/1/discard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aControllerHasNoBusinessInTheScheduleArea() throws Exception {
        String token = tokenFor("controller@metropulse.test", "controller-dev-password");

        mockMvc.perform(get("/api/v1/admin/schedule/imports").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void aControllerMayOpenIncidents() throws Exception {
        String token = tokenFor("controller@metropulse.test", "controller-dev-password");

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incidentJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openedBy").value("controller@metropulse.test"));
    }

    @Test
    void aFleetSupervisorMayStartChargingButNotWorkIncidents() throws Exception {
        String token = tokenFor("supervisor@metropulse.test", "supervisor-dev-password");

        mockMvc.perform(post("/api/v1/charging-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargerCode\":\"CHG-E02\",\"vehicleId\":\"BUS-317\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incidentJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAnAdminReachesAdminEndpoints() throws Exception {
        String controller = tokenFor("controller@metropulse.test", "controller-dev-password");
        String admin = tokenFor("admin@metropulse.test", "admin-dev-password");

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + controller))
                .andExpect(status().isForbidden());
        // The admin is allowed through the rules; whether the endpoint is exposed is a separate
        // question, so anything other than 403 means authorisation did its job.
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + admin))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 403) {
                        throw new AssertionError("An admin should not be forbidden.");
                    }
                });
    }

    @Test
    void telemetryIngestDoesNotUseOperatorCredentials() throws Exception {
        // No Authorization header at all: ingest is machine-to-machine, guarded by the ingest key.
        mockMvc.perform(post("/api/v1/telemetry/ingest")
                        .header("X-Ingest-Key", "test-ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceEventId": "authz-test-1",
                                  "vehicleId": "BUS-042",
                                  "recordedAt": "2026-09-16T10:15:00Z",
                                  "latitude": 40.7152,
                                  "longitude": -73.9980,
                                  "speedKph": 20.0,
                                  "headingDegrees": 90.0,
                                  "occupancyEstimate": 10,
                                  "batteryPercent": 80
                                }
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void ingestWithTheWrongKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/telemetry/ingest")
                        .header("X-Ingest-Key", "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceEventId": "authz-test-2",
                                  "vehicleId": "BUS-042",
                                  "recordedAt": "2026-09-16T10:15:00Z",
                                  "latitude": 40.7152,
                                  "longitude": -73.9980,
                                  "speedKph": 20.0,
                                  "headingDegrees": 90.0,
                                  "occupancyEstimate": 10,
                                  "batteryPercent": 80
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String tokenFor(String email, String password) {
        AuthTokens tokens = authenticationService.login(email, password);
        return tokens.accessToken();
    }

    private String incidentJson() {
        return """
                {
                  "type": "SERVICE_DISRUPTION",
                  "severity": "MINOR",
                  "title": "Authorization test incident"
                }
                """;
    }
}
