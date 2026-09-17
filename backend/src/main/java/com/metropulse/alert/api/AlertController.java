package com.metropulse.alert.api;

import com.metropulse.alert.application.AlertService;
import com.metropulse.alert.domain.AlertView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<AlertView> alerts(
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(required = false) Integer limit
    ) {
        return alertService.findAlerts(includeClosed, limit);
    }

    @GetMapping("/summary")
    public Map<String, Integer> summary() {
        return alertService.liveCountsBySeverity();
    }

    @GetMapping("/{id}")
    public AlertView alert(@PathVariable long id) {
        return alertService.findAlert(id);
    }

    /**
     * Records that a controller has seen the alert.
     *
     * <p>The acknowledging controller is taken from the authenticated principal rather than from the
     * request body: who did it is not something the caller gets to assert.
     */
    @PostMapping("/{id}/acknowledge")
    public AlertView acknowledge(@PathVariable long id, Principal principal) {
        return alertService.acknowledge(id, principal == null ? "unknown" : principal.getName());
    }

    @PostMapping("/{id}/close")
    public AlertView close(@PathVariable long id) {
        return alertService.close(id);
    }
}
