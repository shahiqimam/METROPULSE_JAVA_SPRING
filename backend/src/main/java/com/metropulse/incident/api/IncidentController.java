package com.metropulse.incident.api;

import com.metropulse.incident.application.IncidentService;
import com.metropulse.incident.domain.IncidentView;
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

/**
 * The incident workflow over HTTP.
 *
 * <p>Each action is its own endpoint rather than a status field on a PUT. That is the whole point of
 * the workflow: the set of legal moves depends on where the incident is, and a status field would
 * let a caller skip straight to any of them.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public List<IncidentView> incidents(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return incidentService.findIncidents(includeClosed);
    }

    @GetMapping("/{id}")
    public IncidentView incident(@PathVariable long id) {
        return incidentService.findIncident(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView open(@Valid @RequestBody OpenIncidentRequest request, Principal principal) {
        return incidentService.open(request, actor(principal));
    }

    @PostMapping("/{id}/acknowledge")
    public IncidentView acknowledge(
            @PathVariable long id,
            @Valid @RequestBody(required = false) IncidentActionRequest request,
            Principal principal
    ) {
        return incidentService.acknowledge(id, note(request), actor(principal));
    }

    @PostMapping("/{id}/mitigate")
    public IncidentView mitigate(
            @PathVariable long id,
            @Valid @RequestBody(required = false) IncidentActionRequest request,
            Principal principal
    ) {
        return incidentService.startMitigation(id, note(request), actor(principal));
    }

    @PostMapping("/{id}/resolve")
    public IncidentView resolve(
            @PathVariable long id,
            @Valid @RequestBody(required = false) IncidentActionRequest request,
            Principal principal
    ) {
        return incidentService.resolve(id, note(request), actor(principal));
    }

    @PostMapping("/{id}/cancel")
    public IncidentView cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) IncidentActionRequest request,
            Principal principal
    ) {
        return incidentService.cancel(id, note(request), actor(principal));
    }

    @PostMapping("/{id}/notes")
    public IncidentView addNote(
            @PathVariable long id,
            @Valid @RequestBody AddNoteRequest request,
            Principal principal
    ) {
        return incidentService.addNote(id, request.note(), actor(principal));
    }

    /** Who acted is taken from the authenticated principal, never from the request body. */
    private String actor(Principal principal) {
        return principal == null ? "unknown" : principal.getName();
    }

    private String note(IncidentActionRequest request) {
        return request == null ? null : request.note();
    }
}
