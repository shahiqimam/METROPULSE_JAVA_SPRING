package com.metropulse.analytics;

import com.metropulse.analytics.application.AnalyticsService;
import com.metropulse.analytics.domain.EvAnalytics;
import com.metropulse.analytics.domain.IncidentAnalytics;
import com.metropulse.ev.application.ChargingService;
import com.metropulse.ev.domain.ChargingSessionStatus;
import com.metropulse.ev.domain.ChargingSessionView;
import com.metropulse.incident.api.OpenIncidentRequest;
import com.metropulse.incident.application.IncidentService;
import com.metropulse.incident.domain.IncidentSeverity;
import com.metropulse.incident.domain.IncidentType;
import com.metropulse.incident.domain.IncidentView;
import com.metropulse.support.MutableClock;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metrics over stored history.
 *
 * <p>Each test writes the history it measures through the real services, so what is asserted is the
 * relationship between what happened and what the metric says about it.
 */
class AnalyticsIntegrationTest extends PostgisIntegrationTest {

    private static final Duration WINDOW = Duration.ofHours(24);

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ChargingService chargingService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbcTemplate.update("DELETE FROM incident_timeline");
        jdbcTemplate.update("DELETE FROM incident");
        jdbcTemplate.update("DELETE FROM charging_session");
        jdbcTemplate.update("UPDATE charger SET status = 'AVAILABLE' WHERE status = 'OCCUPIED'");
        jdbcTemplate.update("DELETE FROM alert");
        jdbcTemplate.update("DELETE FROM headway_condition");
    }

    @Test
    void anEmptyNetworkReportsZeroesRatherThanFailing() {
        IncidentAnalytics incidents = analyticsService.incidents(WINDOW);

        assertThat(incidents.total()).isZero();
        assertThat(incidents.averageSecondsToResolve()).isZero();
        assertThat(analyticsService.alertCountsByType(WINDOW)).isEmpty();
    }

    @Test
    void everyActiveRouteAppearsInRegularityEvenWithNothingWrong() {
        assertThat(analyticsService.serviceRegularity(WINDOW))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.routeCode()).isEqualTo("M42");
                    assertThat(route.targetHeadwaySeconds()).isEqualTo(83);
                    assertThat(route.conditionsNow()).isZero();
                    assertThat(route.bunchingAlertsInWindow()).isZero();
                });
    }

    @Test
    void incidentTimingsComeFromTheIncidentsOwnTimestamps() {
        IncidentView incident = openIncident();
        clock.advance(Duration.ofMinutes(2));
        incidentService.acknowledge(incident.id(), null, "controller-b");
        clock.advance(Duration.ofMinutes(8));
        incidentService.resolve(incident.id(), null, "controller-b");

        IncidentAnalytics analytics = analyticsService.incidents(WINDOW);

        assertThat(analytics.total()).isEqualTo(1);
        assertThat(analytics.resolved()).isEqualTo(1);
        assertThat(analytics.live()).isZero();
        assertThat(analytics.averageSecondsToAcknowledge()).isEqualTo(120.0);
        assertThat(analytics.averageSecondsToResolve()).isEqualTo(600.0);
    }

    @Test
    void liveAndClosedIncidentsAreCountedSeparately() {
        openIncident();
        IncidentView resolved = openIncident();
        incidentService.resolve(resolved.id(), null, "controller-a");
        IncidentView cancelled = openIncident();
        incidentService.cancel(cancelled.id(), null, "controller-a");

        IncidentAnalytics analytics = analyticsService.incidents(WINDOW);

        assertThat(analytics.total()).isEqualTo(3);
        assertThat(analytics.live()).isEqualTo(1);
        assertThat(analytics.resolved()).isEqualTo(1);
        assertThat(analytics.cancelled()).isEqualTo(1);
        assertThat(analytics.critical()).isZero();
    }

    @Test
    void incidentsOutsideTheWindowAreNotCounted() {
        openIncident();
        assertThat(analyticsService.incidents(Duration.ofMinutes(5)).total()).isEqualTo(1);

        // Time moves on; the incident falls out of the back of a short window.
        clock.advance(Duration.ofMinutes(10));

        assertThat(analyticsService.incidents(Duration.ofMinutes(5)).total())
                .as("opened ten minutes ago, asked for the last five")
                .isZero();
    }

    @Test
    void chargerStateIsReportedFromTheChargersThemselves() {
        EvAnalytics analytics = analyticsService.ev(WINDOW);

        // The seed has six chargers, one of which is in maintenance.
        assertThat(analytics.chargersTotal()).isEqualTo(6);
        assertThat(analytics.chargersAvailable()).isEqualTo(5);
        assertThat(analytics.chargersOccupied()).isZero();
        assertThat(analytics.chargersOutOfService()).isEqualTo(1);
    }

    @Test
    void anActiveSessionShowsAsAnOccupiedCharger() {
        chargingService.startSession("CHG-W01", "BUS-042", "controller-a");

        EvAnalytics analytics = analyticsService.ev(WINDOW);

        assertThat(analytics.chargersOccupied()).isEqualTo(1);
        assertThat(analytics.chargersAvailable()).isEqualTo(4);
        assertThat(analytics.sessionsActive()).isEqualTo(1);
        assertThat(analytics.sessionsInWindow()).isEqualTo(1);
    }

    @Test
    void completedSessionsContributeTheirDuration() {
        ChargingSessionView session = chargingService.startSession("CHG-W01", "BUS-042", "controller-a");
        clock.advance(Duration.ofMinutes(45));
        chargingService.endSession(session.id(), ChargingSessionStatus.COMPLETED);

        EvAnalytics analytics = analyticsService.ev(WINDOW);

        assertThat(analytics.sessionsInWindow()).isEqualTo(1);
        assertThat(analytics.sessionsActive()).isZero();
        assertThat(analytics.averageSessionSeconds()).isEqualTo(2700.0);
        assertThat(analytics.chargersAvailable()).isEqualTo(5);
    }

    @Test
    void alertsAreCountedByType() {
        insertAlert("ROUTE_DEVIATION", "ROUTE_DEVIATION|BUS-042");
        insertAlert("ROUTE_DEVIATION", "ROUTE_DEVIATION|BUS-101");
        insertAlert("LOW_BATTERY", "LOW_BATTERY|BUS-204");

        assertThat(analyticsService.alertCountsByType(WINDOW))
                .containsEntry("ROUTE_DEVIATION", 2)
                .containsEntry("LOW_BATTERY", 1);
    }

    private IncidentView openIncident() {
        return incidentService.open(new OpenIncidentRequest(
                IncidentType.SERVICE_DISRUPTION,
                IncidentSeverity.MAJOR,
                "Diversion in place",
                null,
                "BUS-042",
                "M42"), "controller-a");
    }

    private void insertAlert(String type, String fingerprint) {
        jdbcTemplate.update("""
                INSERT INTO alert (type, fingerprint, severity, status, opened_at, last_observed_at)
                VALUES (?, ?, 'MAJOR', 'CLOSED', now(), now())
                """, type, fingerprint);
    }
}
