package com.metropulse.ev;

import com.metropulse.ev.application.ChargingService;
import com.metropulse.ev.domain.ChargerNotAvailableException;
import com.metropulse.ev.domain.ChargingSessionView;
import com.metropulse.support.PostgisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two controllers racing for the same charger.
 *
 * <p>This is the test the charger lock exists for, and it cannot be written with mocks: what is under
 * test is whether PostgreSQL serialises two real transactions at {@code SELECT ... FOR UPDATE}. A
 * mocked repository would happily let both callers through and the test would pass while the system
 * was broken.
 *
 * <p>Each attempt runs on its own thread, so each gets its own connection and therefore its own
 * transaction. A latch releases them together to make the overlap as tight as the machine allows.
 *
 * <p>The test has been checked the only way that means anything: with {@code FOR UPDATE} removed from
 * the reservation query, {@code onlyOneOfTwoSimultaneousReservationsWins} fails. A concurrency test
 * that passes with the protection removed is not testing the protection.
 */
class ChargingConcurrencyIntegrationTest extends PostgisIntegrationTest {

    private static final String CHARGER = "CHG-W01";

    @Autowired
    private ChargingService chargingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM charging_session");
        jdbcTemplate.update("UPDATE charger SET status = 'AVAILABLE' WHERE status = 'OCCUPIED'");
    }

    @Test
    void onlyOneOfTwoSimultaneousReservationsWins() throws Exception {
        List<Object> outcomes = race(
                () -> chargingService.startSession(CHARGER, "BUS-042", "controller-a"),
                () -> chargingService.startSession(CHARGER, "BUS-101", "controller-b"));

        List<ChargingSessionView> sessions = outcomes.stream()
                .filter(ChargingSessionView.class::isInstance)
                .map(ChargingSessionView.class::cast)
                .toList();
        List<Throwable> failures = outcomes.stream()
                .filter(Throwable.class::isInstance)
                .map(Throwable.class::cast)
                .toList();

        assertThat(sessions).as("exactly one reservation should succeed").hasSize(1);
        assertThat(failures).as("the loser should be told why").hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(ChargerNotAvailableException.class);
        assertThat(failures.getFirst()).hasMessageContaining(CHARGER);

        assertThat(activeSessionsOn(CHARGER)).isEqualTo(1);
        assertThat(chargerStatus(CHARGER)).isEqualTo("OCCUPIED");
    }

    @Test
    void onlyOneOfEightSimultaneousReservationsWins() throws Exception {
        List<String> fleet = List.of(
                "BUS-042", "BUS-101", "BUS-204", "BUS-317",
                "BUS-042", "BUS-101", "BUS-204", "BUS-317");

        List<Callable<Object>> attempts = new ArrayList<>();
        for (String vehicle : fleet) {
            attempts.add(() -> chargingService.startSession(CHARGER, vehicle, "controller-" + vehicle));
        }

        List<Object> outcomes = race(attempts.toArray(Callable[]::new));

        long succeeded = outcomes.stream().filter(ChargingSessionView.class::isInstance).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(activeSessionsOn(CHARGER)).isEqualTo(1);
    }

    @Test
    void aVehicleCannotBePluggedIntoTwoChargersAtOnce() throws Exception {
        List<Object> outcomes = race(
                () -> chargingService.startSession("CHG-W01", "BUS-042", "controller-a"),
                () -> chargingService.startSession("CHG-W02", "BUS-042", "controller-b"));

        long succeeded = outcomes.stream().filter(ChargingSessionView.class::isInstance).count();

        // Different chargers, so the charger lock does not serialise these two; the unique partial
        // index on active sessions per vehicle is what holds the line.
        assertThat(succeeded).as("one vehicle, one session").isEqualTo(1);
        assertThat(activeSessionsForVehicle("BUS-042")).isEqualTo(1);
    }

    @Test
    void afterTheSessionEndsTheChargerCanBeReservedAgain() {
        ChargingSessionView first = chargingService.startSession(CHARGER, "BUS-042", "controller-a");
        chargingService.endSession(first.id(), com.metropulse.ev.domain.ChargingSessionStatus.COMPLETED);

        ChargingSessionView second = chargingService.startSession(CHARGER, "BUS-101", "controller-b");

        assertThat(second.chargerCode()).isEqualTo(CHARGER);
        assertThat(activeSessionsOn(CHARGER)).isEqualTo(1);
        assertThat(chargerStatus(CHARGER)).isEqualTo("OCCUPIED");
    }

    /**
     * Runs the attempts on separate threads, released together.
     *
     * @return each attempt's return value, or the exception it threw, in completion order
     */
    @SafeVarargs
    private List<Object> race(Callable<Object>... attempts) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(attempts.length);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ready = new AtomicInteger();

        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> attempt : attempts) {
                futures.add(executor.submit(() -> {
                    ready.incrementAndGet();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return attempt.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }

            // Let every thread reach the line before any of them crosses it.
            while (ready.get() < attempts.length) {
                Thread.onSpinWait();
            }
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private int activeSessionsOn(String chargerCode) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM charging_session s
                JOIN charger c ON c.id = s.charger_id
                WHERE c.code = ? AND s.status = 'ACTIVE'
                """, Integer.class, chargerCode);
    }

    private int activeSessionsForVehicle(String fleetNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM charging_session s
                JOIN vehicle v ON v.id = s.vehicle_id
                WHERE v.fleet_number = ? AND s.status = 'ACTIVE'
                """, Integer.class, fleetNumber);
    }

    private String chargerStatus(String chargerCode) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM charger WHERE code = ?", String.class, chargerCode);
    }
}
