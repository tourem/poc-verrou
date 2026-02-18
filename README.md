# ShedLock KeepAliveLockProvider — POC

## Objectif

Ce POC démontre que **ShedLock avec `KeepAliveLockProvider`** maintient le verrou tant que le batch tourne, même si la durée d'exécution dépasse le `lockAtMostFor`.

## Architecture du POC

```
┌─────────────────────────────────────────────────────────────────┐
│                         POC ShedLock                            │
│                                                                 │
│  Spring Boot 3.4.1 / JDK 21                                    │
│                                                                 │
│  ┌──────────────────┐     ┌───────────────────────────────┐    │
│  │  DemoBatchJob     │     │  ShedLockConfig               │    │
│  │                  │     │                               │    │
│  │  @Scheduled       │────▶│  KeepAliveLockProvider        │    │
│  │  (cron 10 min)   │     │    └─ JdbcTemplateLockProvider│    │
│  │                  │     │                               │    │
│  │  Durée simulée   │     │                               │    │
│  │  configurable    │     └──────────────┬────────────────┘    │
│  └──────────────────┘                    │                      │
│                                          ▼                      │
│                              ┌─────────────────────┐           │
│                              │   H2 (TCP server)   │           │
│                              │   table: shedlock    │           │
│                              │   partagée entre     │           │
│                              │   les 2 instances    │           │
│                              └─────────────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

## Prérequis

- JDK 21
- Maven 3.9+

## Comment tester

### Étape 1 — Builder le projet

```bash
mvn clean package -DskipTests
```

### Étape 2 — Lancer le serveur H2 (dans un terminal dédié)

H2 doit tourner en mode **TCP server** (pas en mode fichier) pour garantir
l'isolation transactionnelle entre les deux instances JVM.

```bash
java -cp target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     org.h2.tools.Server -tcp -tcpPort 9092 -tcpAllowOthers -ifNotExists
```

Vous devriez voir : `TCP server running at tcp://localhost:9092`

### Étape 3 — Lancer l'instance A

```bash
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     --server.port=8080 \
     --batch.simulated-duration-minutes=20
```

### Étape 4 — Lancer l'instance B (dans un autre terminal)

```bash
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     --server.port=8081 \
     --batch.simulated-duration-minutes=20
```

### Étape 5 — Observer les logs

**Instance A** (celle qui a acquis le verrou en premier) :
```
[START] BATCH DEMARRE
  Instance       : instance-12345@hostname
  Duree simulee  : 20 minutes
  [RUNNING] [instance-12345@hostname] 0m30s ecoules | ~19m restantes | verrou maintenu par KeepAlive
  [RUNNING] [instance-12345@hostname] 1m0s ecoules  | ~18m restantes | verrou maintenu par KeepAlive
  ...
  ************************************************************
  * [KEEPALIVE] 5 min depassees !                            *
  * Sans KeepAlive, le verrou aurait expire.                 *
  * KeepAliveLockProvider l'a renouvele automatiquement !     *
  ************************************************************
  ...
  [DONE] BATCH TERMINE
```

**Instance B** (le batch est SKIP car le verrou est tenu par A) :
```
INFO n.j.s.core.DefaultLockingTaskExecutor - Not executing 'demo-batch-job'. It's locked.
```

### Étape 6 — Verifier la table shedlock (optionnel)

Ouvrir un navigateur sur `http://localhost:8082` (console H2 web).
Ou bien lancer depuis le jar :

```bash
java -cp target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     org.h2.tools.Console -web -webPort 8082
```

JDBC URL : `jdbc:h2:tcp://localhost:9092/~/shedlock-poc`, user `sa`, pas de mot de passe.

```sql
SELECT * FROM shedlock;
```

Vous verrez que `lock_until` est regulierement mis a jour (renouvele par KeepAlive) tant que le batch tourne.

## Ce que le POC démontre

| Scénario | Résultat attendu |
|---|---|
| Instance A lance le batch | ✅ Verrou acquis, batch s'exécute |
| Instance B tente de lancer le batch pendant que A tourne | ✅ SKIP (verrou tenu par A) |
| Le batch dure 20 min alors que lockAtMostFor = 5 min | ✅ Le verrou est renouvelé automatiquement par KeepAlive |
| Instance A crashe pendant le batch | ✅ Le verrou expire après 5 min (lockAtMostFor), B peut reprendre |

## Configuration des durées

Pour tester différents scénarios, ajustez la durée simulée :

```bash
# Batch court (2 min) — pas besoin de KeepAlive
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar --batch.simulated-duration-minutes=2

# Batch long (20 min) — KeepAlive indispensable
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar --batch.simulated-duration-minutes=20

# Batch très long (60 min) — KeepAlive maintient le verrou pendant 1h
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar --batch.simulated-duration-minutes=60
```

## Structure du projet

```
shedlock-poc/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/poc/shedlock/
    │   ├── ShedlockPocApplication.java        # Point d'entree
    │   ├── config/
    │   │   └── ShedLockConfig.java            # Config KeepAliveLockProvider
    │   └── batch/
    │       ├── DemoBatchJob.java              # Cas 1 : batch cron avec @SchedulerLock
    │       └── ProgrammaticLockController.java # Cas 2 : verrou programmatique sans annotation
    └── resources/
        ├── application.yml                     # Configuration
        └── schema.sql                          # DDL table shedlock
```

## Cas 1 : Batch cron avec @SchedulerLock (annotation)

C'est le cas classique des taches planifiees. Le verrou est pose automatiquement
par l'annotation. Voir `DemoBatchJob.java`.

## Cas 2 : Verrou programmatique SANS annotation

C'est le cas des traitements BFF, listeners JMS, appels REST, ou tout code
qui n'est pas un cron. Le verrou est pose manuellement via `LockingTaskExecutor`.

### Comparaison directe : lock maison vs ShedLock

**AVANT (lock maison avec try/finally) :**
```java
boolean acquired = lockClient.tryAcquire("mon-verrou", Duration.ofMinutes(30));
if (!acquired) {
    log.info("Verrou non acquis, skip.");
    return;
}
try {
    // traitement
} finally {
    lockClient.release("mon-verrou");
}
```

**APRES (ShedLock programmatique) :**
```java
LockingTaskExecutor executor = new DefaultLockingTaskExecutor(lockProvider);

LockConfiguration config = new LockConfiguration(
    Instant.now(), "mon-verrou",
    Duration.ofMinutes(30),    // lockAtMostFor
    Duration.ZERO              // lockAtLeastFor
);

executor.executeWithLock(() -> {
    // traitement — le verrou est acquis automatiquement
    // et libere a la fin, meme en cas d'exception
}, config);
```

Pas de try/finally, pas de release manuelle, pas d'appel WS.

### Tester le verrou programmatique

```bash
# Terminal 1 : lancer le traitement (verrou pendant 3 min par defaut)
curl http://localhost:8080/api/traitement-programmatique

# Terminal 1 : lancer avec une duree specifique (15 min)
curl "http://localhost:8080/api/traitement-programmatique?durationMinutes=15"

# Terminal 2 : tenter le meme traitement sur l'autre instance -> SKIP
curl http://localhost:8081/api/traitement-programmatique
```

Reponses attendues :
- Instance A : `[instance-12345@host] Traitement execute avec succes (duree: 3 min)`
- Instance B : `[instance-67890@host] Verrou deja pris par une autre instance -> SKIP`
