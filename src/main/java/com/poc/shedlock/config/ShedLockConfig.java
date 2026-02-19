package com.poc.shedlock.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Configuration ShedLock avec KeepAliveLockProvider.
 *
 * ATTENTION : le ScheduledExecutorService pour le KeepAlive ne doit PAS
 * etre expose comme bean Spring. Sinon Spring Boot le detecte via
 * TaskSchedulingAutoConfiguration et l'utilise comme scheduler pour
 * les methodes @Scheduled. Le batch et le renouvellement du verrou
 * se retrouvent sur le meme thread unique, le Thread.sleep du batch
 * bloque le renouvellement, le verrou expire, et une autre instance
 * l'acquiert -> execution parallele.
 */
@Configuration
@EnableScheduling
// defaultLockAtMostFor = filet de securite en cas de crash de la JVM.
// Avec KeepAliveLockProvider, ce parametre NE LIMITE PAS la duree d'execution.
// Il definit le delai apres lequel un verrou "orphelin" (crash) sera libere.
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

    private static final Logger log = LoggerFactory.getLogger(ShedLockConfig.class);

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {

        JdbcTemplateLockProvider jdbcProvider = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                // usingDbTime() : utilise l'horloge PostgreSQL (pas celle du serveur app)
                // et genere INSERT ... ON CONFLICT DO NOTHING (pas de DuplicateKeyException)
                .usingDbTime()
                .build()
        );

        // IMPORTANT : cree en local, PAS comme bean Spring
        // Sinon Spring l'utilise comme TaskScheduler et le batch bloque le renouvellement
        ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shedlock-keep-alive");
            t.setDaemon(true);
            return t;
        });

        log.info("+----------------------------------------------------------+");
        log.info("| ShedLock configure avec KeepAliveLockProvider            |");
        log.info("| Provider : JdbcTemplate (PostgreSQL + usingDbTime)      |");
        log.info("| Le verrou sera renouvele automatiquement                |");
        log.info("| tant que le batch tourne.                               |");
        log.info("+----------------------------------------------------------+");

        return new KeepAliveLockProvider(jdbcProvider, keepAliveExecutor);
    }
}
