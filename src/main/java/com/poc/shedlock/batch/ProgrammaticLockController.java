package com.poc.shedlock.batch;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Demonstration du verrouillage PROGRAMMATIQUE (sans annotation).
 *
 * C'est l'equivalent du pattern classique try/finally avec un lock maison :
 *
 *   AVANT (lock maison) :
 *     boolean acquired = lockClient.tryAcquire("mon-verrou", duration);
 *     if (!acquired) return;
 *     try {
 *         // traitement
 *     } finally {
 *         lockClient.release("mon-verrou");
 *     }
 *
 *   APRES (ShedLock programmatique) :
 *     executor.executeWithLock(
 *         () -> { // traitement },
 *         new LockConfiguration(...)
 *     );
 *
 * Utilisable depuis : REST controller, BFF, listener JMS, event handler,
 * traitement ponctuel, n'importe quel code Java.
 *
 * Pour tester :
 *   curl http://localhost:8080/api/traitement-programmatique
 *   curl http://localhost:8080/api/traitement-programmatique?durationMinutes=15
 */
@RestController
public class ProgrammaticLockController {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticLockController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LockingTaskExecutor executor;
    private final String instanceId;

    public ProgrammaticLockController(LockProvider lockProvider) {
        // LockingTaskExecutor est le composant cle pour le verrouillage sans annotation
        this.executor = new DefaultLockingTaskExecutor(lockProvider);

        String pid = ManagementFactory.getRuntimeMXBean().getName();
        this.instanceId = "instance-" + pid;

        log.info("+---------------------------------------------------+");
        log.info("| ProgrammaticLockController initialise             |");
        log.info("| Mode : verrouillage SANS annotation               |");
        log.info("| Endpoint : GET /api/traitement-programmatique     |");
        log.info("+---------------------------------------------------+");
    }

    @GetMapping("/api/traitement-programmatique")
    public String lancerTraitement(
            @RequestParam(defaultValue = "3") long durationMinutes) {

        log.info("============================================================");
        log.info("  [PROGRAMMATIC] Tentative d'acquisition du verrou...");
        log.info("  Instance       : {}", instanceId);
        log.info("  Heure          : {}", LocalDateTime.now().format(FMT));
        log.info("  Duree demandee : {} minutes", durationMinutes);
        log.info("============================================================");

        Instant now = Instant.now();

        // --- C'est ici que tout se joue ---
        // lockAtMostFor  = duree du traitement + marge de securite
        // lockAtLeastFor = duree minimale du verrou (evite reexecution trop rapide)
        LockConfiguration config = new LockConfiguration(
            now,
            "traitement-programmatique",                  // nom unique du verrou
            Duration.ofMinutes(durationMinutes + 5),      // lockAtMostFor : duree + 5 min de marge
            Duration.ofMinutes(1)                         // lockAtLeastFor : 1 min minimum
        );

        // executeWithLock tente d'acquerir le verrou et execute le Runnable si reussi.
        // Si le verrou est deja pris -> le Runnable n'est PAS execute (skip).
        // Pas de try/finally, pas de release manuelle, ShedLock gere tout.
        LockingTaskExecutor.TaskResult<String> result;
        try {
            result = executor.executeWithLock(
                (LockingTaskExecutor.TaskWithResult<String>) () -> {
                    log.info("  [PROGRAMMATIC] Verrou ACQUIS ! Traitement en cours...");

                    // Simulation du traitement
                    simulerTraitement(durationMinutes);

                    log.info("  [PROGRAMMATIC] Traitement TERMINE. Verrou libere.");
                    return "OK";
                },
                config
            );
        } catch (Throwable e) {
            log.error("  [PROGRAMMATIC] Erreur pendant l'execution : {}", e.getMessage(), e);
            return "[" + instanceId + "] Erreur : " + e.getMessage();
        }

        // Verifier si le verrou a ete acquis ou non
        if (result.wasExecuted()) {
            String msg = String.format(
                "[%s] Traitement execute avec succes (duree: %d min)", instanceId, durationMinutes);
            log.info("  [PROGRAMMATIC] {}", msg);
            return msg;
        } else {
            String msg = String.format(
                "[%s] Verrou deja pris par une autre instance -> SKIP", instanceId);
            log.info("  [PROGRAMMATIC] {}", msg);
            return msg;
        }
    }

    /**
     * Simule un traitement long avec logs de progression.
     */
    private void simulerTraitement(long durationMinutes) {
        Instant debut = Instant.now();
        Duration dureeTotale = Duration.ofMinutes(durationMinutes);
        long intervalleLogSeconds = 10;

        while (true) {
            Duration ecoulee = Duration.between(debut, Instant.now());
            if (ecoulee.compareTo(dureeTotale) >= 0) {
                break;
            }

            long minutesEcoulees = ecoulee.toMinutes();
            long secondesEcoulees = ecoulee.toSeconds() % 60;
            long minutesRestantes = dureeTotale.minus(ecoulee).toMinutes();

            log.info("  [PROGRAMMATIC] [{}] {}m{}s ecoules | ~{}m restantes",
                    instanceId, minutesEcoulees, secondesEcoulees, minutesRestantes);

            try {
                Thread.sleep(intervalleLogSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("  [PROGRAMMATIC] Traitement interrompu !");
                return;
            }
        }
    }
}
