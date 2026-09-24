# Contributing

## Prerequisites

- **JDK 25.** The build targets Java 25 and CI runs on Temurin 25. If your default JDK is newer,
  point `JAVA_HOME` at a 25 install before running the wrapper so local results match CI:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` on macOS.
- **A running Docker daemon.** Integration tests (`*IT`) use Testcontainers, and `spring-boot:run`
  starts `compose.yaml` automatically. With OrbStack, `orb start` if `docker ps` cannot connect.

## Commands

| Task | Command |
|---|---|
| Full build (format check, unit + integration tests, coverage) | `./mvnw verify` |
| Unit tests only | `./mvnw test` |
| Apply formatting | `./mvnw spotless:apply` |
| Mutation-check a guard | `scripts/mutate.sh <file> '<perl s///>' <TestClass>` (prints KILLED or SURVIVED) |
| Run locally | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |

Unit tests are named `*Test` (Surefire); tests that need a database or HTTP stub are named `*IT` (Failsafe).
The coverage report is written to `target/site/jacoco/index.html`.

## Conventions

- Formatting is enforced by Spotless (palantir-java-format) in the `validate` phase; run `spotless:apply`
  before committing.
- The compiler runs with `-Xlint:all -Werror`.
- Packages are `@NullMarked` (JSpecify); mark nullable values with `@Nullable`.

## Traps

- **Stale classes after restoring a file.** Maven's incremental compiler compares timestamps. Restoring a source
  file with `mv`/`cp -p` (for example after a mutation check) keeps its older mtime, so the previously compiled
  class is silently reused. `touch` the restored file or run `./mvnw clean ...`.
