package com.metropulse.incident;

import com.metropulse.incident.api.OpenIncidentRequest;
import com.metropulse.incident.application.IncidentService;
import com.metropulse.incident.domain.IncidentSeverity;
import com.metropulse.incident.domain.IncidentStatus;
import com.metropulse.incident.domain.IncidentTimelineEntry;
import com.metropulse.incident.domain.IncidentType;
import com.metropulse.incident.domain.IncidentView;
import com.metropulse.incident.domain.InvalidIncidentTransitionException;
import com.metropulse.incident.domain.UnknownIncidentException;
import com.metropulse.support.MutableClock;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentServiceIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        clock.reset();
        jdbcTemplate.update("DELETE FROM incident_timeline");
        jdbcTemplate.update("DELETE FROM incident");
    }

    @Test
    void openingAnIncidentRecordsWhoRaisedItAndStartsItsTimeline() {
        IncidentView incident = open();

        assertThat(incident.incidentNumber()).startsWith("INC-");
        assertThat(incident.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.openedBy()).isEqualTo("controller-a");
        assertThat(incident.vehicleId()).isEqualTo("BUS-042");
        assertThat(incident.routeCode()).isEqualTo("M42");
        assertThat(incident.startedAt()).isEqualTo(clock.instant());

        assertThat(incident.timeline()).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo("TRANSITION");
            assertThat(entry.fromStatus()).isNull();
            assertThat(entry.toStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(entry.actor()).isEqualTo("controller-a");
        });
    }

    @Test
    void incidentNumbersAreUniqueAndSequential() {
        IncidentView first = open();
        IncidentView second = open();

        assertThat(first.incidentNumber()).isNotEqualTo(second.incidentNumber());
        assertThat(number(second)).isGreaterThan(number(first));
    }

    @Test
    void anIncidentCanBeOpenedWithoutAVehicleOrRoute() {
        IncidentView incident = incidentService.open(new OpenIncidentRequest(
                IncidentType.DEPOT_ISSUE,
                IncidentSeverity.MINOR,
                "Depot gate stuck",
                null,
                null,
                null), "controller-a");

        assertThat(incident.vehicleId()).isNull();
        assertThat(incident.routeCode()).isNull();
    }

    @Test
    void theFullWorkflowRecordsEveryStepWithItsActor() {
        IncidentView incident = open();

        clock.advance(Duration.ofMinutes(2));
        incidentService.acknowledge(incident.id(), "on it", "controller-b");
        clock.advance(Duration.ofMinutes(3));
        incidentService.startMitigation(incident.id(), "sending a replacement", "controller-b");
        clock.advance(Duration.ofMinutes(10));
        IncidentView resolved = incidentService.resolve(incident.id(), "service restored", "controller-b");

        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.acknowledgedAt()).isNotNull();
        assertThat(resolved.mitigatingAt()).isNotNull();
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.assignedController()).isEqualTo("controller-b");

        assertThat(resolved.timeline()).hasSize(4);
        assertThat(resolved.timeline()).extracting(IncidentTimelineEntry::toStatus)
                .containsExactly(
                        IncidentStatus.OPEN,
                        IncidentStatus.ACKNOWLEDGED,
                        IncidentStatus.MITIGATING,
                        IncidentStatus.RESOLVED);
        assertThat(resolved.timeline().get(1).note()).isEqualTo("on it");
    }

    @Test
    void theTimelineIsInTheOrderThingsHappened() {
        IncidentView incident = open();

        clock.advance(Duration.ofMinutes(1));
        incidentService.addNote(incident.id(), "first note", "controller-a");
        clock.advance(Duration.ofMinutes(1));
        incidentService.acknowledge(incident.id(), null, "controller-b");
        clock.advance(Duration.ofMinutes(1));
        IncidentView latest = incidentService.addNote(incident.id(), "second note", "controller-b");

        assertThat(latest.timeline()).extracting(IncidentTimelineEntry::recordedAt).isSorted();
        assertThat(latest.timeline()).extracting(IncidentTimelineEntry::note)
                .containsSubsequence("first note", "second note");
    }

    @Test
    void anIllegalTransitionIsRefusedAndChangesNothing() {
        IncidentView incident = open();
        incidentService.startMitigation(incident.id(), null, "controller-b");

        assertThatThrownBy(() -> incidentService.cancel(incident.id(), "changed my mind", "controller-b"))
                .isInstanceOf(InvalidIncidentTransitionException.class)
                .hasMessageContaining("MITIGATING")
                .hasMessageContaining("CANCELLED");

        IncidentView unchanged = incidentService.findIncident(incident.id());
        assertThat(unchanged.status()).isEqualTo(IncidentStatus.MITIGATING);
        assertThat(unchanged.cancelledAt()).isNull();
        assertThat(unchanged.timeline()).as("a refused transition leaves no timeline entry").hasSize(2);
    }

    @Test
    void aResolvedIncidentCannotBeMovedAnywhere() {
        IncidentView incident = open();
        incidentService.resolve(incident.id(), "done", "controller-a");

        assertThatThrownBy(() -> incidentService.acknowledge(incident.id(), null, "controller-b"))
                .isInstanceOf(InvalidIncidentTransitionException.class);
        assertThatThrownBy(() -> incidentService.startMitigation(incident.id(), null, "controller-b"))
                .isInstanceOf(InvalidIncidentTransitionException.class);
        assertThatThrownBy(() -> incidentService.resolve(incident.id(), null, "controller-b"))
                .isInstanceOf(InvalidIncidentTransitionException.class);
    }

    @Test
    void aNoteCanStillBeAddedToAResolvedIncident() {
        IncidentView incident = open();
        incidentService.resolve(incident.id(), "done", "controller-a");

        clock.advance(Duration.ofHours(1));
        IncidentView withNote = incidentService.addNote(incident.id(), "cause found later", "controller-c");

        assertThat(withNote.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(withNote.timeline().getLast().note()).isEqualTo("cause found later");
        assertThat(withNote.timeline().getLast().recordedAt()).isEqualTo(clock.instant());
    }

    @Test
    void acknowledgingKeepsTheFirstControllerWhoTookIt() {
        IncidentView incident = open();
        incidentService.acknowledge(incident.id(), null, "controller-b");
        IncidentView mitigating = incidentService.startMitigation(incident.id(), null, "controller-c");

        assertThat(mitigating.assignedController()).isEqualTo("controller-b");
    }

    @Test
    void anIncidentCanBeCancelledStraightFromOpen() {
        IncidentView incident = open();

        IncidentView cancelled = incidentService.cancel(incident.id(), "raised in error", "controller-a");

        assertThat(cancelled.status()).isEqualTo(IncidentStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
    }

    @Test
    void theLiveListLeavesOutResolvedAndCancelledIncidents() {
        IncidentView live = open();
        IncidentView resolved = open();
        IncidentView cancelled = open();
        incidentService.resolve(resolved.id(), null, "controller-a");
        incidentService.cancel(cancelled.id(), null, "controller-a");

        assertThat(incidentService.findIncidents(false))
                .extracting(IncidentView::id)
                .containsExactly(live.id());
        assertThat(incidentService.findIncidents(true)).hasSize(3);
    }

    @Test
    void listedIncidentsCarryTheirTimelines() {
        IncidentView incident = open();
        incidentService.acknowledge(incident.id(), "taken", "controller-b");

        assertThat(incidentService.findIncidents(false).getFirst().timeline()).hasSize(2);
    }

    @Test
    void actingOnAnIncidentThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> incidentService.findIncident(999_999L))
                .isInstanceOf(UnknownIncidentException.class);
        assertThatThrownBy(() -> incidentService.acknowledge(999_999L, null, "controller-a"))
                .isInstanceOf(UnknownIncidentException.class);
    }

    private IncidentView open() {
        return incidentService.open(new OpenIncidentRequest(
                IncidentType.VEHICLE_BREAKDOWN,
                IncidentSeverity.MAJOR,
                "BUS-042 will not start",
                "Driver reports no power at West Terminal.",
                "BUS-042",
                "M42"), "controller-a");
    }

    private int number(IncidentView incident) {
        return Integer.parseInt(incident.incidentNumber().substring("INC-".length()));
    }
}
