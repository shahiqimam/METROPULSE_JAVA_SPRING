package com.metropulse.realtime;

import com.metropulse.alert.application.AlertService;
import com.metropulse.alert.domain.AlertView;
import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayQueryService;
import com.metropulse.telemetry.read.LatestVehicleTelemetry;
import com.metropulse.telemetry.read.TelemetryQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Pushes the current picture to subscribed clients.
 *
 * <p>Broadcasts only when something changed. A control-room screen that redraws every two seconds
 * whether or not anything moved is both wasteful and harder to watch: a change on screen should mean
 * a change in the network.
 *
 * <p>Change is detected by comparing what was last sent with what is current, which is cheap because
 * the payloads are small summaries. It would not be cheap for raw telemetry — one more reason the
 * socket carries summaries rather than every observation.
 */
@Component
public class RealtimeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final TelemetryQueryService telemetryQueryService;
    private final AlertService alertService;
    private final HeadwayQueryService headwayQueryService;

    private List<LatestVehicleTelemetry> lastVehicles = List.of();
    private List<AlertView> lastAlerts = List.of();
    private List<HeadwayCondition> lastConditions = List.of();

    public RealtimeBroadcaster(
            SimpMessagingTemplate messagingTemplate,
            TelemetryQueryService telemetryQueryService,
            AlertService alertService,
            HeadwayQueryService headwayQueryService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.telemetryQueryService = telemetryQueryService;
        this.alertService = alertService;
        this.headwayQueryService = headwayQueryService;
    }

    @Scheduled(fixedDelayString = "${metropulse.realtime.broadcast-delay-ms:2000}")
    public void broadcast() {
        try {
            broadcastVehicles();
            broadcastAlerts();
            broadcastHeadway();
        } catch (RuntimeException ex) {
            // Realtime is a convenience on top of REST. If it fails, the dashboard still polls.
            log.warn("Realtime broadcast failed.", ex);
        }
    }

    private void broadcastVehicles() {
        List<LatestVehicleTelemetry> vehicles = telemetryQueryService.findLatestVehicleTelemetry();
        if (!Objects.equals(vehicles, lastVehicles)) {
            lastVehicles = vehicles;
            messagingTemplate.convertAndSend("/topic/vehicles", vehicles);
        }
    }

    private void broadcastAlerts() {
        List<AlertView> alerts = alertService.findAlerts(false, 200);
        if (!Objects.equals(alerts, lastAlerts)) {
            lastAlerts = alerts;
            messagingTemplate.convertAndSend("/topic/alerts", alerts);
        }
    }

    private void broadcastHeadway() {
        List<HeadwayCondition> conditions = headwayQueryService.findConditions();
        if (!Objects.equals(conditions, lastConditions)) {
            lastConditions = conditions;
            messagingTemplate.convertAndSend("/topic/headway", conditions);
        }
    }
}
