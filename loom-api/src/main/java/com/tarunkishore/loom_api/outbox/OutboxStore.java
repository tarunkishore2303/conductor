package com.tarunkishore.loom_api.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Short, independent claim/ack transactions. No Kafka network operation runs in these scopes. */
@Repository
public class OutboxStore {
    public record Claim(UUID id, String token, String topic, String key, String type, String payload, int attempts) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OutboxStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Optional<Claim> claim(Duration lease) {
        // Claim just one row at a time: a serial batch cannot leave later rows waiting past their leases.
        String token = UUID.randomUUID().toString();
        return transactions.execute(status -> jdbc.query("""
                WITH due AS (
                    SELECT id FROM outbox_events
                    WHERE published_at IS NULL AND available_at <= CURRENT_TIMESTAMP
                      AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP)
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE outbox_events o SET claimed_by=?,
                    claimed_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    publish_attempts=publish_attempts+1, last_attempt_at=CURRENT_TIMESTAMP
                FROM due WHERE o.id=due.id
                RETURNING o.id,o.topic,o.event_key,o.event_type,o.payload_json,o.publish_attempts
                """, (rs, row) -> new Claim(rs.getObject("id", UUID.class), token, rs.getString("topic"),
                rs.getString("event_key"), rs.getString("event_type"), rs.getString("payload_json"),
                rs.getInt("publish_attempts")), token, lease.toMillis()).stream().findFirst());
    }

    public boolean published(Claim claim) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.update("""
                UPDATE outbox_events SET published_at=CURRENT_TIMESTAMP,claimed_by=NULL,claimed_until=NULL
                WHERE id=? AND claimed_by=? AND claimed_until > CURRENT_TIMESTAMP AND published_at IS NULL
                """, claim.id(), claim.token()) == 1));
    }

    public void failed(Claim claim) {
        long delay = Math.min(30, 1L << Math.min(5, claim.attempts() - 1));
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE outbox_events SET claimed_by=NULL,claimed_until=NULL,
                    available_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                WHERE id=? AND claimed_by=? AND published_at IS NULL
                """, delay, claim.id(), claim.token()));
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}
