package com.metropulse.ev.api;

import com.metropulse.ev.application.ChargingService;
import com.metropulse.ev.domain.ChargerView;
import com.metropulse.ev.domain.ChargingSessionView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class EvController {

    private final ChargingService chargingService;

    public EvController(ChargingService chargingService) {
        this.chargingService = chargingService;
    }

    @GetMapping("/chargers")
    public List<ChargerView> chargers() {
        return chargingService.findChargers();
    }

    @GetMapping("/charging-sessions")
    public List<ChargingSessionView> sessions(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return chargingService.findSessions(activeOnly);
    }

    @PostMapping("/charging-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargingSessionView start(
            @Valid @RequestBody StartChargingSessionRequest request,
            Principal principal
    ) {
        return chargingService.startSession(
                request.chargerCode(),
                request.vehicleId(),
                principal == null ? "unknown" : principal.getName());
    }

    @PostMapping("/charging-sessions/{id}/complete")
    public ChargingSessionView complete(
            @PathVariable long id,
            @RequestBody(required = false) EndChargingSessionRequest request
    ) {
        return chargingService.endSession(
                id,
                request == null ? com.metropulse.ev.domain.ChargingSessionStatus.COMPLETED : request.statusOrCompleted());
    }
}
