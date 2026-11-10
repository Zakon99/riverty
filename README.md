# Buchungsservice

Kleiner Buchungsservice für Forderungskonten, entstanden als Case Study für Riverty (Team Cosima Accounting). Nur Backend: Java 21, Spring Boot, REST-API.

Der Service verwaltet Kreditoren, Debitoren und Forderungskonten. Er bucht Sollposten und Zahlungen, verrechnet Zahlungen nach § 367 BGB, unterstützt Storno und Ausbuchung und prüft die Konsistenz eines Kontos.

> Die Dokumentation wird stufenweise vervollständigt.

## Setup

Voraussetzungen: JDK 21 oder neuer. Maven ist nicht nötig, der Maven Wrapper ist enthalten.

Den versionierten Pre-Push-Hook aktivieren. Er führt vor jedem Push `./mvnw verify` aus und bricht den Push bei Fehlern ab:

```sh
git config core.hooksPath .githooks
```

## Annahmen

### Verrechnung
- **Reihenfolge nach § 367 BGB:** Gebühren (Kosten) → Zinsen → Hauptforderung. Die Folie der Aufgabenstellung nennt als Beispiel Hauptforderung → Zinsen → Gebühren. Wir folgen bewusst der gesetzlichen Reihenfolge. Annahme: Inkassogebühren gelten als Kosten im Sinne von § 367.
- Innerhalb einer Buchungsart wird der Posten mit der ältesten Valuta zuerst bedient, bei gleicher Valuta der mit der früheren Buchungszeit.
- **Gesamtschuld:** Jeder dem Konto zugeordnete Debitor haftet für alle Posten. Guthaben jedes Debitors darf deshalb auf alle offenen Posten des Kontos verteilt werden.
- Die **automatische Verrechnung** bei Zahlungseingang verteilt nur die neue Zahlung. Vorhandenes Guthaben aus früheren Zahlungen wird nur explizit über `POST /api/konten/{id}/verrechnung` verteilt, dann die älteste Zahlung (Valuta) zuerst.

### Zahlung
- Eine Zahlung ist immer einem Debitor zugeordnet, der dem Konto zugeordnet sein muss.
- Eine Überzahlung bleibt als Guthaben beim zahlenden Debitor.
- Eine Zahlung auf ein Konto ohne offene Posten ist erlaubt und wird vollständig Guthaben dieses Debitors.

### Sollbuchung
- Ein Forderungskonto hat immer genau einen Kreditor, der nicht geändert werden kann. Jede Sollbuchung speichert zusätzlich die Referenz auf diesen Kreditor. So ist die geforderte Zuordnung jeder Sollbuchung zu einem Kreditor direkt an der Buchung nachvollziehbar.

### Storno
- Die Valuta einer Storno-Buchung ist die **Valuta der Originalbuchung**. Original und Storno fallen in der Historie zeitlich zusammen.
- Eine Sollbuchung, auf die bereits eine Ausbuchung verrechnet ist, kann nicht storniert werden (422). Zuerst muss die Ausbuchung storniert werden, denn eine Ausbuchung darf nie Guthaben erzeugen.

### Ausbuchung
- Ausgebucht wird immer der gesamte offene Rest des Kontos. Erfasst werden alle offenen Posten, **unabhängig von ihrer Valuta**.
- Die Valuta einer Ausbuchung ist das aktuelle Datum (aus der injizierten `Clock`).

### Valuta
- Valuten dürfen in der Zukunft liegen und vom Buchungstag abweichen.

### Idempotenz
- Der Request-Hash umfasst neben den fachlichen Feldern auch den Operationstyp, die Konto-Id und bei Storno die Id der stornierten Buchung. Dieselbe `clientRequestId` auf einem anderen Konto oder für eine andere Operation führt daher zu 409.
