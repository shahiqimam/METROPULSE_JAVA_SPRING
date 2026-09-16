# 0002 - Modular Monolith

## Context

MetroPulse has many capabilities, but the portfolio goal is correctness and explainability rather than distributed-system ceremony.

## Decision

Use one Spring Boot backend organized by capability packages.

## Alternatives

- Multiple microservices
- One package per technical layer

## Consequences

The system keeps transactions and local reasoning straightforward while still showing clear module boundaries.
