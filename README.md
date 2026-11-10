# Buchungsservice

[![CI](https://github.com/Zakon99/riverty/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Zakon99/riverty/actions/workflows/ci.yml)

SonarQube-Analyse: [Zakon99_riverty auf SonarQube Cloud](https://sonarcloud.io/summary/overall?id=Zakon99_riverty)

Buchungsservice für Forderungskonten als Case Study für Riverty (Team Cosima Accounting). Java 21, Spring Boot 4, REST-API, nur Backend. Er verwaltet Kreditoren, Debitoren und Konten, bucht Sollposten und Zahlungen, verrechnet nach § 367 BGB und unterstützt Storno und Ausbuchung. Buchungen und Verrechnungen sind append-only.

Architektur, Fachmodell und alle Annahmen im Detail stehen in [`docs/details.md`](docs/details.md). Die KI-Nutzung ist in [`aiprompts.md`](aiprompts.md) dokumentiert.

## Start

Voraussetzung: JDK 21. Der Maven Wrapper ist enthalten.

```sh
./mvnw spring-boot:run                                   # Start (H2-Datei unter ./data)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # mit Demo-Konto DEMO-0001
./mvnw verify                                            # Build und alle Tests
```

Unter PowerShell den Profil-Parameter in Anführungszeichen setzen: `"-Dspring-boot.run.profiles=demo"`.

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| H2-Konsole | http://localhost:8080/h2-console (Benutzer `sa`, Passwort leer) |

JDBC-URL für die H2-Konsole:

```
jdbc:h2:file:./data/buchungsservice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE
```

Den Demo-Ablauf in der Reihenfolge des Interviews enthält [`docs/demo.http`](docs/demo.http): Beteiligte und Konto → Sollbuchung und Zahlung → Verrechnung und Storno. Die Datei läuft direkt im HTTP-Client von IntelliJ, in VS Code oder auf der Kommandozeile mit [httpyac](https://httpyac.github.io/) (`npx httpyac send docs/demo.http --all`). Ohne diese Werkzeuge lassen sich dieselben Requests über die Swagger UI absetzen. Die Erwartungswerte gelten für eine frische Datenbank, deshalb vorher `./data` löschen.

## Annahmen

- **Gesamtschuld:** Ein Konto hat genau einen Kreditor und beliebig viele Debitoren (n:m). Jeder Debitor haftet für alle Posten des Kontos.
- **Guthaben:** Eine Überzahlung bleibt als Guthaben beim zahlenden Debitor. Guthaben gilt nur auf dem eigenen Konto. Eine Zahlung ohne offene Posten ist erlaubt und wird vollständig Guthaben.
- **Verrechnung:** Bei einer Zahlung wird automatisch nur die neue Zahlung verteilt. Vorhandenes Guthaben verteilt nur `POST /api/konten/{id}/verrechnung`. Gibt es nichts zu verteilen, ist der Aufruf ein No-op.
- **Sollbuchung:** Jede Sollbuchung speichert die Referenz auf den Kreditor des Kontos. Damit ist die geforderte Zuordnung zum Kreditor direkt an der Buchung sichtbar.
- **Storno:**
  - Valuta ist die Valuta des Originals.
  - Alle aktiven Verrechnungen der Originalbuchung werden per Aufhebung aufgelöst.
  - Doppelstorno und Storno eines Stornos ergeben 409.
  - Ist auf die Sollbuchung eine Ausbuchung verrechnet, gibt es 422.
- **Ausbuchung:**
  - Ausgebucht wird immer der gesamte offene Rest, unabhängig von der Valuta der Posten.
  - Die Valuta ist das heutige Datum in UTC.
  - Ist noch Guthaben vorhanden, gibt es 422.
- **Valuta und Zeit:** Valuten dürfen in der Zukunft liegen. Die Valuta ist ein `LocalDate`, die Buchungszeit ein `Instant` in UTC. Beide sind bewusst nicht deckungsgleich.
- **Beträge:**
  - Nie negativ, die Richtung ergibt sich aus der Buchungsart.
  - Kaufmännisch auf 2 Stellen gerundet. Ergibt das ≤ 0 (z. B. 0.004), gibt es 400.
  - Nur EUR.
- **Dubletten:** Debitoren werden über einen Dublettenschlüssel erkannt. Er besteht aus Name, Geburtsdatum und PLZ, jedes Feld einzeln normalisiert. Eine Dublette ergibt 409 mit der Id des vorhandenen Debitors.
- **Idempotenz:**
  - Eine neue Buchung ergibt 201, ein identisches Replay 200.
  - Gleiche `clientRequestId` mit anderem Inhalt ergibt 409.
  - Der Hash umfasst auch Konto und Operation.
- **Parallelität:** Jede Buchung sperrt das Konto pessimistisch, `@Version` bleibt als Absicherung. Ein eigener Lock-Timeout ist nicht konfiguriert, es gilt der Datenbank-Standard.
- **Konsistenz:** `GET /api/konten/{id}/konsistenz` prüft zwei Dinge:
  - Der gespeicherte Saldo entspricht dem Saldo aus den Buchungen.
  - Kein Posten ist überbezahlt, und keine Zahlung oder Ausbuchung ist überverteilt.
- **H2 statt PostgreSQL:** Die H2-Datei läuft im Modus `MODE=PostgreSQL`. Durch Flyway ist ein Wechsel zu PostgreSQL klein. Die Zugangsdaten stehen nur lokal im Klartext.
- **SonarQube Cloud** läuft in der CI kostenlos, weil das Repository öffentlich ist. Für privaten Code wäre ein kostenpflichtiger Plan oder ein eigener Server nötig.

## Verrechnungsreihenfolge (§ 367 BGB)

1. **Gebühren (Kosten) → Zinsen → Hauptforderung**
2. Innerhalb einer Art: älteste Valuta zuerst, dann Buchungszeit, dann Id.
3. Beim Verteilen von Guthaben: älteste Zahlung (Valuta) zuerst.

Die Folie der Aufgabenstellung nennt als Beispiel Hauptforderung → Zinsen → Gebühren. Der Service folgt bewusst der gesetzlichen Reihenfolge aus § 367 Abs. 1 BGB: erst Kosten, dann Zinsen, zuletzt die Hauptleistung. Annahme: Inkassogebühren gelten als Kosten im Sinne von § 367. Die Reihenfolge ist an einer Stelle festgelegt (`Buchungsart.tilgungsrang()`).

## Skalierung und Massendaten

Was heute schon trägt:

- **Gespeicherter Saldo:** Der Saldo wird in derselben Transaktion fortgeschrieben, Lesen kostet keine Summenbildung über alle Buchungen.
- **Verrechnung ohne N+1:** Offene Beträge und verteilte Beträge kommen aus zwei aggregierten Abfragen, nicht aus einer Abfrage pro Posten.
- **Sperre pro Konto:** Die pessimistische Sperre gilt nur für das betroffene Konto. Buchungen auf verschiedenen Konten laufen parallel.
- **Idempotenz:** Über `clientRequestId` lassen sich abgebrochene Importe gefahrlos wiederholen.
- **Indizes** auf allen Fremdschlüsseln (Konto, Kreditor, Debitor, Verrechnung).

Was für Massendaten folgen würde:

- **Bulk-Import von Zahlungen** (z. B. CAMT.053) mit Spring Batch, chunk-basiert und idempotent über die Bankreferenz.
- **SEQUENCE statt IDENTITY** mit Pooled-Optimizer und `hibernate.jdbc.batch_size`, damit Hibernate Inserts per JDBC-Batching bündeln kann.
- **Partitionierung der Verarbeitung nach Konto:** Jeder Worker übernimmt eine Menge von Konten, Sperrkonflikte entfallen.
- **Konsistenzprüfung als Nacht-Batch:** mengenbasiert per SQL über alle Konten statt Konto für Konto.
- **PostgreSQL mit Tabellenpartitionierung** für Buchungen und Verrechnungen.
- **Paginierung der Historie.**
- **Outbox für Events,** damit nachgelagerte Systeme zuverlässig und transaktionssicher informiert werden.

## Nächste Schritte

- Ausbuchung einzelner Posten
- Debitor vom Konto entfernen
- Fuzzy-Dublettenprüfung
- Zinsberechnung
- Authentifizierung, Auslöser aus dem Security-Context
- PostgreSQL, Integrationstests mit Testcontainers
- Paginierung der Historie
- Domain Events / Outbox
