INSERT INTO vehicle (fleet_number, vehicle_type, propulsion_type, capacity, status)
VALUES
    ('BUS-101', 'STANDARD_BUS', 'BATTERY_ELECTRIC', 80, 'ACTIVE'),
    ('BUS-204', 'ARTICULATED_BUS', 'BATTERY_ELECTRIC', 120, 'ACTIVE'),
    ('BUS-317', 'STANDARD_BUS', 'HYBRID_DIESEL', 80, 'ACTIVE')
ON CONFLICT (fleet_number) DO NOTHING;
