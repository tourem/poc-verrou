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
 * KeepAliveLockProvider prolonge automatiquement le verrou a mi-parcours
 * de l'intervalle lockAtMostFor. Par exemple, si lockAtMostFor = 5m,
 * le verrou est renouvele toutes les 2m30 tant que la tache tourne.
 *
 * Cela garantit que le verrou est maintenu tant que le process est vivant,
 * sans dependance a un WS externe.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

    private static final Logger log = LoggerFactory.getLogger(ShedLockConfig.class);

    /**
     * ScheduledExecutorService dedie au renouvellement du verrou.
     * Un seul thread suffit car il ne fait que des UPDATE SQL legers.
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService keepAliveScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shedlock-keep-alive");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * LockProvider avec KeepAlive :
     * 1. JdbcTemplateLockProvider fait les INSERT/UPDATE en base H2
     * 2. KeepAliveLockProvider le wrappe et renouvelle le verrou periodiquement
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource, ScheduledExecutorService keepAliveScheduler) {

        JdbcTemplateLockProvider jdbcProvider = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );

        log.info("+----------------------------------------------------------+");
        log.info("| ShedLock configure avec KeepAliveLockProvider            |");
        log.info("| Provider : JdbcTemplate (H2 file)                       |");
        log.info("| Le verrou sera renouvele automatiquement                |");
        log.info("| tant que le batch tourne.                               |");
        log.info("+----------------------------------------------------------+");

        return new KeepAliveLockProvider(jdbcProvider, keepAliveScheduler);
    }
}
