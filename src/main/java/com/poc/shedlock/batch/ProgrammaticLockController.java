package com.poc.shedlock.batch;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Demonstration du verrouillage PROGRAMMATIQUE (sans annotation).
 *
 * Deux patterns sont presentes :
 *
 *   Pattern 1 (executeWithLock) :
 *     executor.executeWithLock(() -> { ... }, config);
 *     if (result.wasExecuted()) { ... }
 *
 *   Pattern 2 (try/catch - equivalent LockNotAcquiredException) :
 *     Optional<SimpleLock> lock = lockProvider.lock(config);
 *     if (lock.isEmpty()) {
 *         // equivalent de LockNotAcquiredException
 *     }
 *     try {
 *         // traitement
 *     } finally {
 *         lock.get().unlock();
 *     }
 *
 * Pour tester :
 *   Pattern 1 : curl http://localhost:8080/api/traitement-programmatique
 *   Pattern 2 : curl http://localhost:8080/api/traitement-try-catch
 */
@RestController
public class ProgrammaticLockController {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticLockController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LockProvider lockProvider;
    private final LockingTaskExecutor executor;
    private final String instanceId;

    public ProgrammaticLockController(LockProvider lockProvider) {
        this.lockProvider = lockProvider;
        this.executor = new DefaultLockingTaskExecutor(lockProvider);

        String pid = ManagementFactory.getRuntimeMXBean().getName();
        this.instanceId = "instance-" + pid;

        log.info("+---------------------------------------------------+");
        log.info("| ProgrammaticLockController initialise             |");
        log.info("| Mode : verrouillage SANS annotation               |");
        log.info("| Pattern 1 : GET /api/traitement-programmatique    |");
        log.info("| Pattern 2 : GET /api/traitement-try-catch         |");
        log.info("+---------------------------------------------------+");
    }

    // =========================================================================
    // PATTERN 1 : executeWithLock (simple, recommande pour la plupart des cas)
    // =========================================================================

    @GetMapping("/api/traitement-programmatique")
    public String lancerTraitement(
            @RequestParam(defaultValue = "3") long durationMinutes) {

        log.info("  [PATTERN-1] Tentative d'acquisition du verrou...");

        Instant now = Instant.now();

        // lockAtMostFor = filet de securite en cas de crash (PAS la duree max du batch)
        // Avec KeepAliveLockProvider, le verrou est renouvele automatiquement.
        LockConfiguration config = new LockConfiguration(
            now,
            "traitement-programmatique",
            Duration.ofMinutes(5),         // lockAtMostFor : delai de recovery apres crash
            Duration.ofMinutes(1)          // lockAtLeastFor : 1 min minimum
        );

        LockingTaskExecutor.TaskResult<String> result;
        try {
            result = executor.executeWithLock(
                (LockingTaskExecutor.TaskWithResult<String>) () -> {
                    log.info("  [PATTERN-1] Verrou ACQUIS ! Traitement en cours...");
                    simulerTraitement("PATTERN-1", durationMinutes);
                    log.info("  [PATTERN-1] Traitement TERMINE. Verrou libere.");
                    return "OK";
                },
                config
            );
        } catch (Throwable e) {
            log.error("  [PATTERN-1] Erreur pendant l'execution : {}", e.getMessage(), e);
            return "[" + instanceId + "] Erreur : " + e.getMessage();
        }

        if (result.wasExecuted()) {
            return "[" + instanceId + "] Traitement execute avec succes (duree: " + durationMinutes + " min)";
        } else {
            // Equivalent de LockNotAcquiredException
            log.info("  [PATTERN-1] Verrou deja pris par une autre instance -> SKIP");
            return "[" + instanceId + "] Verrou deja pris -> SKIP";
        }
    }

    // =========================================================================
    // PATTERN 2 : try/catch explicite (equivalent direct de CustomLock)
    //
    // C'est le mapping 1:1 avec le pattern CustomLock :
    //
    //   AVANT :
    //     try (CustomLock lock = lockManager.lock("nomDuVerrou")) {
    //         // traitement
    //     } catch (LockNotAcquiredException e) {
    //         // verrou non acquis
    //     }
    //
    //   APRES :
    //     Optional<SimpleLock> lock = lockProvider.lock(config);
    //     if (lock.isEmpty()) {
    //         // verrou non acquis (= LockNotAcquiredException)
    //         return;
    //     }
    //     try {
    //         // traitement
    //     } finally {
    //         lock.get().unlock();
    //     }
    // =========================================================================

    @GetMapping("/api/traitement-try-catch")
    public String lancerTraitementTryCatch(
            @RequestParam(defaultValue = "3") long durationMinutes) {

        log.info("  [PATTERN-2] Tentative d'acquisition du verrou (mode try/catch)...");

        // lockAtMostFor = filet de securite en cas de crash
        LockConfiguration config = new LockConfiguration(
            Instant.now(),
            "traitement-try-catch",
            Duration.ofMinutes(5),         // lockAtMostFor : delai de recovery apres crash
            Duration.ofMinutes(1)          // lockAtLeastFor
        );

        // --- Tentative d'acquisition du verrou ---
        Optional<SimpleLock> lock = lockProvider.lock(config);

        if (lock.isEmpty()) {
            // *** C'est l'equivalent exact de LockNotAcquiredException ***
            log.info("  [PATTERN-2] Verrou NON ACQUIS (= LockNotAcquiredException)");
            log.info("  [PATTERN-2] Une autre instance detient le verrou -> SKIP");
            return "[" + instanceId + "] Verrou non acquis -> SKIP";
        }

        // --- Verrou acquis, traitement avec finally pour garantir la liberation ---
        try {
            log.info("  [PATTERN-2] Verrou ACQUIS ! Traitement en cours...");
            simulerTraitement("PATTERN-2", durationMinutes);
            log.info("  [PATTERN-2] Traitement TERMINE.");
            return "[" + instanceId + "] Traitement execute avec succes (duree: " + durationMinutes + " min)";

        } finally {
            lock.get().unlock();
            log.info("  [PATTERN-2] Verrou LIBERE.");
        }
    }

    /**
     * Simule un traitement long avec logs de progression.
     */
    private void simulerTraitement(String pattern, long durationMinutes) {
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

            log.info("  [{}] [{}] {}m{}s ecoules | ~{}m restantes",
                    pattern, instanceId, minutesEcoulees, secondesEcoulees, minutesRestantes);

            try {
                Thread.sleep(intervalleLogSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("  [{}] Traitement interrompu !", pattern);
                return;
            }
        }
    }
}
