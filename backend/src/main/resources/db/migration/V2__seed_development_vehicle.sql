INSERT INTO vehicle (fleet_number, vehicle_type, propulsion_type, capacity, status)
VALUES ('BUS-042', 'STANDARD_BUS', 'BATTERY_ELECTRIC', 80, 'ACTIVE')
ON CONFLICT (fleet_number) DO NOTHING;
