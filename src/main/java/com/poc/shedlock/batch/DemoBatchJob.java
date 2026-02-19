package com.poc.shedlock.batch;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Batch de demonstration pour tester le KeepAliveLockProvider.
 *
 * Le cron se declenche toutes les 10 minutes.
 * La duree d'execution est simulee via la propriete batch.simulated-duration-minutes.
 *
 * Scenario de test :
 *   - Instance A demarre le batch -> acquiert le verrou
 *   - Le batch simule un traitement de 20 min (configurable)
 *   - Au bout de 5 min (lockAtMostFor), le verrou DEVRAIT expirer...
 *     MAIS KeepAliveLockProvider le renouvelle automatiquement a ~2m30
 *   - Si Instance B demarre pendant ce temps -> le verrou est toujours tenu -> SKIP
 *   - A la fin des 20 min, le verrou est libere normalement
 */
@Component
public class DemoBatchJob {

    private static final Logger log = LoggerFactory.getLogger(DemoBatchJob.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final long simulatedDurationMinutes;
    private final String instanceId;

    public DemoBatchJob(@Value("${batch.simulated-duration-minutes:20}") long simulatedDurationMinutes) {
        this.simulatedDurationMinutes = simulatedDurationMinutes;

        // Identifiant unique de l'instance (PID + hostname) pour tracer dans les logs
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        this.instanceId = "instance-" + pid;

        log.info("+---------------------------------------------------+");
        log.info("| DemoBatchJob initialise                           |");
        log.info("| Instance ID     : {}", pad(instanceId));
        log.info("| Duree simulee   : {} minutes", pad(String.valueOf(simulatedDurationMinutes)));
        log.info("| Cron            : toutes les 10 minutes           |");
        log.info("| lockAtMostFor   : 5 minutes                      |");
        log.info("| KeepAlive       : renouvelle a ~2m30              |");
        log.info("+---------------------------------------------------+");
    }

    /**
     * Tache planifiee protegee par ShedLock.
     *
     * lockAtMostFor = 5m  -> AVEC KeepAliveLockProvider, ce parametre NE LIMITE PAS
     *                        la duree d'execution du batch. Le verrou est renouvele
     *                        automatiquement tant que le batch tourne.
     *                        Son SEUL role : filet de securite en cas de CRASH.
     *                        Si la JVM meurt, le verrou sera libere apres 5 min max.
     *                        Choisir une valeur courte (5-10 min) pour un recovery rapide.
     *
     * lockAtLeastFor = 1m -> Empeche une reexecution dans la minute qui suit.
     */
    @Scheduled(cron = "0 */10 * * * *")  // Toutes les 10 minutes
    @SchedulerLock(
        name = "demo-batch-job",
        lockAtMostFor = "5m",       // != duree max du batch, = delai de recovery apres crash
        lockAtLeastFor = "1m"
    )
    public void executerBatch() {
        // Verifie que le verrou est bien acquis (detecte les erreurs de config AOP)
        LockAssert.assertLocked();

        Instant debut = Instant.now();
        Duration dureeTotale = Duration.ofMinutes(simulatedDurationMinutes);

        log.info("============================================================");
        log.info("  [START] BATCH DEMARRE");
        log.info("  Instance       : {}", instanceId);
        log.info("  Heure debut    : {}", LocalDateTime.now().format(FMT));
        log.info("  Duree simulee  : {} minutes", simulatedDurationMinutes);
        log.info("  lockAtMostFor  : 5 minutes (renouvele par KeepAlive)");
        log.info("============================================================");

        // Simulation du traitement batch avec logs de progression
        long intervalleLogSeconds = 30; // Log toutes les 30 secondes

        while (true) {
            Duration ecoulee = Duration.between(debut, Instant.now());

            if (ecoulee.compareTo(dureeTotale) >= 0) {
                break;
            }

            long minutesEcoulees = ecoulee.toMinutes();
            long secondesEcoulees = ecoulee.toSeconds() % 60;
            long minutesRestantes = dureeTotale.minus(ecoulee).toMinutes();

            // Log de progression
            log.info("  [RUNNING] [{}] {}m{}s ecoules | ~{}m restantes | verrou maintenu par KeepAlive",
                    instanceId,
                    minutesEcoulees,
                    secondesEcoulees,
                    minutesRestantes);

            // Alerte quand on depasse lockAtMostFor (5 min)
            if (minutesEcoulees == 5 && secondesEcoulees < intervalleLogSeconds) {
                log.info("  ************************************************************");
                log.info("  * [KEEPALIVE] 5 min depassees !                            *");
                log.info("  * Sans KeepAlive, le verrou aurait expire.                 *");
                log.info("  * KeepAliveLockProvider l'a renouvele automatiquement !     *");
                log.info("  ************************************************************");
            }

            try {
                Thread.sleep(intervalleLogSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("  [WARN] [{}] Batch interrompu !", instanceId);
                return;
            }
        }

        Duration dureeReelle = Duration.between(debut, Instant.now());
        log.info("============================================================");
        log.info("  [DONE] BATCH TERMINE");
        log.info("  Instance       : {}", instanceId);
        log.info("  Heure fin      : {}", LocalDateTime.now().format(FMT));
        log.info("  Duree reelle   : {}m {}s", dureeReelle.toMinutes(), dureeReelle.toSeconds() % 60);
        log.info("  Le verrou va etre libere par ShedLock.");
        log.info("============================================================");
    }

    private String pad(String s) {
        return String.format("%-25s|", s);
    }
}
