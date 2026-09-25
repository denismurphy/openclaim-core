package com.openclaim.engine.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the outbox. Disabled with {@code openclaim.outbox.relay-enabled=false} (tests drive the relay directly). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "openclaim.outbox.relay-enabled", matchIfMissing = true)
class OutboxScheduling {

    private final OutboxRelay relay;

    OutboxScheduling(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${openclaim.outbox.poll-interval:1s}")
    void poll() {
        relay.runOnce();
    }
}
