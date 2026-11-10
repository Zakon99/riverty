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

## Konfiguration

Die Zugangsdaten der lokalen H2-Datenbank (`sa`, leeres Passwort) stehen der Einfachheit halber im Klartext in `application.yml`. In produktivem Code stünden Credentials natürlich nicht im Klartext im Repository. Sie würden über Umgebungsvariablen oder einen Secret-Store (z. B. Vault, Kubernetes Secrets oder einen Cloud Secrets Manager) injiziert.

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

- Die Storno-Buchung muss zum Konto im Pfad gehören. Die Id einer Buchung eines anderen Kontos wird als Regelverstoß abgelehnt (422).

### Ausbuchung
- Ausgebucht wird immer der gesamte offene Rest des Kontos. Erfasst werden alle offenen Posten, **unabhängig von ihrer Valuta**.
- Die Valuta einer Ausbuchung ist das aktuelle Datum aus der injizierten `Clock`, bestimmt in **UTC**. Das System verwendet weltweit einheitlich UTC.
- Hat ein Debitor noch Guthaben, während Posten offen sind, wird die Ausbuchung abgelehnt (422). Zuerst muss das Guthaben über `POST /api/konten/{id}/verrechnung` verteilt werden. Sonst würde eine Forderung abgeschrieben, die mit vorhandenem Geld bezahlt werden kann.

### Explizite Verrechnung
- Gibt es nichts zu verteilen (kein Guthaben oder keine offenen Posten), ist der Aufruf ein No-op und liefert eine leere Liste mit 200. So bleibt der Endpunkt idempotent.

### Beteiligte und Konto
- Der Dublettenschlüssel eines Debitors besteht aus den getrennt normalisierten Feldern. „Anna Lena / Schmidt“ und „Anna / Lena Schmidt“ sind daher verschiedene Personen. Normalisiert wird nur wie spezifiziert (trim, lowercase, Umlaute, ß, Leerzeichen). Akzente und Bindestriche bleiben unverändert.
- Kreditorennummer und Aktenzeichen werden getrimmt, Groß- und Kleinschreibung bleibt erhalten. Ein doppeltes Aktenzeichen liefert wie bei Kreditor und Debitor 409 mit der Id des vorhandenen Kontos.
- Wird ein Debitor einem Konto zugeordnet, dem er schon zugeordnet ist, antwortet der Service mit 409.
- Wird bei der Kontoanlage direkt ein Debitor zugeordnet, steht in `zugeordnetVon` der Wert „Kontoanlage“, weil der Request dafür kein Feld hat.

### Fehlerfälle
- Ein Betrag, der nach dem Runden 0.00 ergibt (z. B. 0.004), ist aus Client-Sicht eine ungültige Eingabe wie 0 oder ein negativer Betrag und ergibt 400.
- Eine Zahlung mit einer nicht existierenden `debitorId` ergibt 404. 422 gibt es, wenn der Debitor existiert, aber dem Konto nicht zugeordnet ist.

### Valuta
- Valuten dürfen in der Zukunft liegen und vom Buchungstag abweichen.

### Konsistenzprüfung
Geprüft werden zwei Invarianten:
1. Der gespeicherte Saldo entspricht dem Saldo aus allen Buchungen.
2. Die Verrechnungen sind stimmig:
   - Kein Sollposten ist überbezahlt: Der netto verrechnete Betrag (Verrechnungen minus Aufhebungen) ist höchstens so groß wie der Betrag des Postens.
   - Keine Haben-Buchung (Zahlung oder Ausbuchung) ist überverteilt.

Die ursprünglich geplante Invariante „Saldo = Σ offene Posten − Σ Guthaben“ wurde bewusst durch diese direkten Prüfungen ersetzt. Sie benennen die betroffene Buchung konkret und sind einfacher nachzuvollziehen. Eine Abweichung wird mit erwartetem Wert, Istwert und Differenz (Ist − Erwartet) gemeldet und zusätzlich als Error geloggt.

### Idempotenz
- Der Request-Hash umfasst neben den fachlichen Feldern auch den Operationstyp, die Konto-Id und bei Storno die Id der stornierten Buchung. Dieselbe `clientRequestId` auf einem anderen Konto oder für eine andere Operation führt daher zu 409.
- Ein Replay hat Vorrang vor fachlichen Prüfungen. Ein identisch wiederholtes Storno liefert die Original-Storno-Buchung und keinen Doppelstorno-Konflikt.
