package com.safecall.service.common.crypto;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import static com.safecall.service.auth.repository.AuthRepository.*;

/** Dedicated connections for short recovery-intent inserts; never used by cleanup or business work. */
@Component
public class KeyCreationJournal {
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public KeyCreationJournal(HikariDataSource businessPool) {
        var config = new HikariConfig();
        businessPool.copyStateTo(config);
        config.setPoolName("key-creation-journal");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setRegisterMbeans(false);
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    public UUID prepare(String ref, Instant now) {
        return transaction.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO `keyDiscardJob` (`id`,`keyRef`,`createdAt`,`nextAttemptAt`) VALUES (?,?,?,?)",
                bin(id), ref, time(now), time(now.plusSeconds(30)));
            return id;
        });
    }

    @PreDestroy
    public void close() { pool.close(); }
}
