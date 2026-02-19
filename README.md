# ShedLock KeepAliveLockProvider -- POC

## Objectif

Ce POC demontre que **ShedLock avec `KeepAliveLockProvider`** maintient le verrou
tant que le batch tourne, meme si la duree d'execution depasse le `lockAtMostFor`.

## Architecture du POC

```
+----------------------------------------------------------------+
|                         POC ShedLock                            |
|                                                                |
|  Spring Boot 3.4.1 / JDK 21                                   |
|                                                                |
|  +------------------+     +-------------------------------+    |
|  |  DemoBatchJob     |     |  ShedLockConfig               |    |
|  |                  |     |                               |    |
|  |  @Scheduled       |---->|  KeepAliveLockProvider        |    |
|  |  (cron 3 min)    |     |    +- JdbcTemplateLockProvider|    |
|  |                  |     |         +- usingDbTime()      |    |
|  |  Duree simulee   |     |                               |    |
|  |  configurable    |     +---------------+---------------+    |
|  +------------------+                     |                    |
|                                           v                    |
|                              +---------------------+           |
|                              |   PostgreSQL         |           |
|                              |   table: shedlock    |           |
|                              |   partagee entre     |           |
|                              |   les 2 instances    |           |
|                              +---------------------+           |
+----------------------------------------------------------------+
```

## Prerequis

- JDK 21
- Maven 3.9+
- PostgreSQL 15+ (voir installation ci-dessous)

## Installation de PostgreSQL

### Windows

1. Telecharger l'installeur depuis https://www.postgresql.org/download/windows/
2. Lancer l'installeur EDB et suivre l'assistant :
   - Mot de passe du superuser `postgres` : **postgres**
   - Port : **5432** (par defaut)
   - Locale : defaut
3. Cocher **pgAdmin 4** si vous voulez une interface graphique
4. A la fin de l'installation, demarrer le service PostgreSQL (il demarre automatiquement)

### macOS

```bash
brew install postgresql@16
brew services start postgresql@16
```

### Linux (Ubuntu/Debian)

```bash
sudo apt install postgresql postgresql-client
sudo systemctl start postgresql
```

## Creer la base de donnees

Ouvrir un terminal (ou pgAdmin) et executer :

```bash
# Windows (depuis git-bash ou PowerShell)
"C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -c "CREATE DATABASE shedlock_poc;"

# macOS / Linux
psql -U postgres -c "CREATE DATABASE shedlock_poc;"
```

Mot de passe : **postgres**

## Comment tester

### Etape 1 -- Builder le projet

```bash
mvn clean package -DskipTests
```

### Etape 2 -- Lancer l'instance A

```bash
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     --server.port=8080 \
     --batch.simulated-duration-minutes=20
```

### Etape 3 -- Lancer l'instance B (dans un autre terminal)

```bash
java -jar target/shedlock-poc-1.0.0-SNAPSHOT.jar \
     --server.port=8081 \
     --batch.simulated-duration-minutes=20
```

### Etape 4 -- Observer les logs

**Instance A** (celle qui a acquis le verrou en premier) :
```
[START] BATCH DEMARRE
  Instance       : instance-12345@hostname
  Duree simulee  : 20 minutes
  [RUNNING] 0m30s ecoules | ~19m restantes | verrou maintenu par KeepAlive
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

### Etape 5 -- Verifier la table shedlock (optionnel)

```bash
psql -U postgres -d shedlock_poc -c "SELECT * FROM shedlock;"
```

Vous verrez que `lock_until` est regulierement mis a jour (renouvele par KeepAlive)
tant que le batch tourne.

## Ce que le POC demontre

| Scenario | Resultat attendu |
|---|---|
| Instance A lance le batch | Verrou acquis, batch s'execute |
| Instance B tente le batch pendant que A tourne | SKIP (verrou tenu par A) |
| Batch dure 20 min, lockAtMostFor = 5 min | Verrou renouvele automatiquement par KeepAlive |
| Instance A crashe pendant le batch | Verrou expire apres 5 min, B peut reprendre |

## Configuration

| Propriete | Defaut | Description |
|---|---|---|
| `batch.simulated-duration-minutes` | 20 | Duree simulee du batch (en minutes) |
| `server.port` | 8080 | Port HTTP de l'instance |
| `spring.datasource.url` | jdbc:postgresql://localhost:5432/shedlock_poc | URL JDBC |
| `spring.datasource.username` | postgres | User PostgreSQL |
| `spring.datasource.password` | postgres | Mot de passe PostgreSQL |

## Structure du projet

```
shedlock-poc/
+-- pom.xml
+-- README.md
+-- src/main/
    +-- java/com/poc/shedlock/
    |   +-- ShedlockPocApplication.java        # Point d'entree
    |   +-- config/
    |   |   +-- ShedLockConfig.java            # Config KeepAliveLockProvider
    |   +-- batch/
    |       +-- DemoBatchJob.java              # Cas 1 : batch cron avec @SchedulerLock
    |       +-- ProgrammaticLockController.java # Cas 2 : verrou programmatique sans annotation
    +-- resources/
        +-- application.yml                     # Configuration
        +-- schema.sql                          # DDL table shedlock
```

## Cas 1 : Batch cron avec @SchedulerLock (annotation)

C'est le cas classique des taches planifiees. Le verrou est pose automatiquement
par l'annotation. Voir `DemoBatchJob.java`.

## A propos de lockAtMostFor avec KeepAliveLockProvider

`lockAtMostFor` peut sembler trompeur : il indique un "temps max" mais avec
KeepAliveLockProvider, le verrou est renouvele automatiquement tant que le
batch tourne. En realite, son role change :

| | Sans KeepAlive | Avec KeepAlive |
|---|---|---|
| `lockAtMostFor` | Duree max du verrou (risque d'expiration) | Filet de securite en cas de CRASH uniquement |
| Batch plus long que lockAtMostFor | Verrou expire -> autre instance execute | Verrou renouvele -> pas de probleme |
| Crash de la JVM | Verrou expire apres lockAtMostFor | Verrou expire apres lockAtMostFor |

**Conseil** : avec KeepAlive, choisir un lockAtMostFor court (5-10 min) pour
un recovery rapide apres crash. Ce parametre est obligatoire dans ShedLock.

## Cas 2 : Verrou programmatique SANS annotation

C'est le cas des traitements BFF, listeners JMS, appels REST, ou tout code
qui n'est pas un cron. Deux patterns sont disponibles.

### Pattern 1 : executeWithLock (simple)

```java
LockingTaskExecutor executor = new DefaultLockingTaskExecutor(lockProvider);

LockConfiguration config = new LockConfiguration(
    Instant.now(), "mon-verrou",
    Duration.ofMinutes(5),     // lockAtMostFor : recovery apres crash
    Duration.ZERO              // lockAtLeastFor
);

TaskResult result = executor.executeWithLock(() -> {
    // traitement -- verrou acquis automatiquement
}, config);

if (!result.wasExecuted()) {
    // verrou non acquis (= LockNotAcquiredException)
}
```

### Pattern 2 : try/catch (equivalent direct de CustomLock)

C'est le mapping 1:1 avec le code existant :

**AVANT :**
```java
try (CustomLock lock = lockManager.lock("nomDuVerrou")) {
    // traitement
} catch (LockNotAcquiredException e) {
    // verrou non acquis -> skip
} catch (Exception e) {
    // erreur
}
```

**APRES :**
```java
Optional<SimpleLock> lock = lockProvider.lock(config);

if (lock.isEmpty()) {
    // equivalent de LockNotAcquiredException
    log.info("Verrou non acquis -> SKIP");
    return;
}

try {
    // traitement
} finally {
    lock.get().unlock();
}
```

### Tester les deux patterns

```bash
# Pattern 1 : executeWithLock
curl http://localhost:8080/api/traitement-programmatique
curl http://localhost:8081/api/traitement-programmatique

# Pattern 2 : try/catch (equivalent CustomLock)
curl http://localhost:8080/api/traitement-try-catch
curl http://localhost:8081/api/traitement-try-catch

# Avec une duree specifique
curl "http://localhost:8080/api/traitement-try-catch?durationMinutes=15"
```

Reponses attendues :
- Instance A : `[instance-12345@host] Traitement execute avec succes (duree: 3 min)`
- Instance B : `[instance-67890@host] Verrou non acquis -> SKIP`
