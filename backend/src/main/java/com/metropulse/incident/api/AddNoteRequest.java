package com.metropulse.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A note added to an incident's timeline without changing its state. */
public record AddNoteRequest(@NotBlank @Size(max = 4000) String note) {
}
