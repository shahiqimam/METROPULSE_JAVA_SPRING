-- Operational state projection: place each vehicle on the route it is assigned to.

ALTER TABLE vehicle
    ADD COLUMN assigned_route_id BIGINT REFERENCES route(id);

CREATE INDEX idx_vehicle_assigned_route
    ON vehicle (assigned_route_id);

ALTER TABLE vehicle_current_state
    ADD COLUMN route_id BIGINT REFERENCES route(id),
    ADD COLUMN route_progress NUMERIC(8,7),
    ADD COLUMN route_deviation_meters NUMERIC(10,2);

-- Development fleet runs the seeded M42 crosstown route.
UPDATE vehicle
SET assigned_route_id = route.id
FROM route
WHERE route.code = 'M42'
  AND vehicle.fleet_number IN ('BUS-042', 'BUS-101', 'BUS-204', 'BUS-317');

-- Backfill the projection for state rows that already exist.
UPDATE vehicle_current_state AS state
SET route_id = route.id,
    route_progress = ST_LineLocatePoint(route.geometry, state.location),
    route_deviation_meters = ST_Distance(route.geometry::geography, state.location::geography)
FROM vehicle
JOIN route ON route.id = vehicle.assigned_route_id
WHERE vehicle.id = state.vehicle_id;
