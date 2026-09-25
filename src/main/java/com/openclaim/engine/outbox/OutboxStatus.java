package com.openclaim.engine.outbox;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    SENT,
    FAILED
}
