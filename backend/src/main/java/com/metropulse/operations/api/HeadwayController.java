package com.metropulse.operations.api;

import com.metropulse.operations.headway.HeadwayCondition;
import com.metropulse.operations.headway.HeadwayEvaluator;
import com.metropulse.operations.headway.HeadwayQueryService;
import com.metropulse.operations.headway.RouteHeadwaySnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
public class HeadwayController {

    private final HeadwayQueryService headwayQueryService;

    public HeadwayController(HeadwayQueryService headwayQueryService) {
        this.headwayQueryService = headwayQueryService;
    }

    /**
     * Current spacing of the vehicles on a route, plus any conditions the rules are tracking.
     *
     * <p>Reads stored state; it does not trigger an evaluation. The evaluator writes conditions on its
     * own schedule, so a request never changes what the rules believe.
     */
    @GetMapping("/{code}/headway")
    public RouteHeadwaySnapshot routeHeadway(@PathVariable String code) {
        return headwayQueryService.findRouteHeadway(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown route: " + code));
    }

    /** Conditions across every route, worst first, for the control centre's attention list. */
    @GetMapping("/headway/conditions")
    public List<HeadwayCondition> conditions() {
        return headwayQueryService.findConditions();
    }
}
