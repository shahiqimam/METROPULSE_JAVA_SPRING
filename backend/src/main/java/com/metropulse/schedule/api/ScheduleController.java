package com.metropulse.schedule.api;

import com.metropulse.schedule.read.RouteStop;
import com.metropulse.schedule.read.RouteSummary;
import com.metropulse.schedule.read.ScheduleQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
public class ScheduleController {

    private final ScheduleQueryService scheduleQueryService;

    public ScheduleController(ScheduleQueryService scheduleQueryService) {
        this.scheduleQueryService = scheduleQueryService;
    }

    @GetMapping
    public List<RouteSummary> routes() {
        return scheduleQueryService.findRoutes();
    }

    @GetMapping("/{code}/stops")
    public List<RouteStop> routeStops(@PathVariable String code) {
        return scheduleQueryService.findRouteStops(code);
    }
}
