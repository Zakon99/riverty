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
- Innerhalb einer Buchungsart wird der Posten mit der ältesten Valuta zuerst bedient. Bei gleicher Valuta entscheidet die frühere Buchungszeit, bei gleicher Buchungszeit die kleinere Id. Die Reihenfolge ist damit immer eindeutig.
- **Die Valuta ist kein Stichtag:** Sie bestimmt nur die Reihenfolge. Auch Posten mit einer Valuta in der Zukunft werden sofort von Zahlungen bedient, und Zahlungen mit Zukunftsvaluta werden sofort verrechnet.
- **Gesamtschuld:** Jeder dem Konto zugeordnete Debitor haftet für alle Posten. Guthaben jedes Debitors darf deshalb auf alle offenen Posten des Kontos verteilt werden.
- **Guthaben gilt pro Konto:** Ein Guthaben, das ein Debitor auf Konto A hat, wird nie auf Konto B verrechnet, auch wenn er beiden Konten zugeordnet ist.
- Die **automatische Verrechnung** bei Zahlungseingang verteilt nur die neue Zahlung. Vorhandenes Guthaben aus früheren Zahlungen wird nur explizit über `POST /api/konten/{id}/verrechnung` verteilt. Dabei kommt die Zahlung mit der ältesten Valuta zuerst, danach die mit der früheren Buchungszeit, danach die mit der kleineren Id.

### Zahlung
- Eine Zahlung ist immer einem Debitor zugeordnet, der dem Konto zugeordnet sein muss.
- Eine Überzahlung bleibt als Guthaben beim zahlenden Debitor.
- Eine Zahlung auf ein Konto ohne offene Posten ist erlaubt und wird vollständig Guthaben dieses Debitors.

### Sollbuchung
- Ein Forderungskonto hat immer genau einen Kreditor, der nicht geändert werden kann. Jede Sollbuchung speichert zusätzlich die Referenz auf diesen Kreditor. So ist die geforderte Zuordnung jeder Sollbuchung zu einem Kreditor direkt an der Buchung nachvollziehbar.

### Storno
- Die Valuta einer Storno-Buchung ist die **Valuta der Originalbuchung**. Original und Storno fallen in der Historie zeitlich zusammen.
- Eine Sollbuchung, auf die bereits eine Ausbuchung verrechnet ist, kann nicht storniert werden (422). Zuerst muss die Ausbuchung storniert werden, denn eine Ausbuchung darf nie Guthaben erzeugen.

- Ein Storno hebt **alle** aktiven Verrechnungen der Originalbuchung auf, egal ob sie aus der automatischen Verrechnung bei Zahlungseingang stammen oder aus der expliziten Verrechnung.
- Eine Zahlung kann auch nach einer Ausbuchung storniert werden. Aufgehoben werden nur die Verrechnungen dieser Zahlung, die Ausbuchung bleibt bestehen. Die Posten, die die Zahlung bedient hatte, sind wieder offen, und der Saldo wird wieder positiv. Sie können erneut bezahlt oder ausgebucht werden.
- Die Storno-Buchung muss zum Konto im Pfad gehören. Die Id einer Buchung eines anderen Kontos wird als Regelverstoß abgelehnt (422).

### Ausbuchung
- Ausgebucht wird immer der gesamte offene Rest des Kontos. Erfasst werden alle offenen Posten, **unabhängig von ihrer Valuta**.
- Die Valuta einer Ausbuchung ist das aktuelle Datum aus der injizierten `Clock`, bestimmt in **UTC**. Das System verwendet weltweit einheitlich UTC.
- Hat ein Debitor noch Guthaben, während Posten offen sind, wird die Ausbuchung abgelehnt (422). Zuerst muss das Guthaben über `POST /api/konten/{id}/verrechnung` verteilt werden. Sonst würde eine Forderung abgeschrieben, die mit vorhandenem Geld bezahlt werden kann.

### Explizite Verrechnung
- Gibt es nichts zu verteilen (kein Guthaben oder keine offenen Posten), ist der Aufruf ein No-op und liefert eine leere Liste mit 200. So bleibt der Endpunkt idempotent.

### Beteiligte und Konto
- Der Dublettenschlüssel eines Debitors besteht aus den getrennt normalisierten Feldern. „Anna Lena / Schmidt“ und „Anna / Lena Schmidt“ sind daher verschiedene Personen. Normalisiert wird nur wie spezifiziert (trim, lowercase, Umlaute, ß, Leerzeichen). Akzente und Bindestriche bleiben unverändert. Der Feldtrenner `|` und das Maskierungszeichen `\` werden in den Feldwerten maskiert. So können auch Namen, die selbst ein `|` enthalten, keine falsche Dublette erzeugen.
- Kreditorennummer und Aktenzeichen werden getrimmt, Groß- und Kleinschreibung bleibt erhalten. Ein doppeltes Aktenzeichen liefert wie bei Kreditor und Debitor 409 mit der Id des vorhandenen Kontos.
- Wird ein Debitor einem Konto zugeordnet, dem er schon zugeordnet ist, antwortet der Service mit 409.
- Wird bei der Kontoanlage direkt ein Debitor zugeordnet, steht in `zugeordnetVon` der Wert „Kontoanlage“, weil der Request dafür kein Feld hat.

### Fehlerfälle
- Ein Betrag, der nach dem Runden 0.00 ergibt (z. B. 0.004), ist aus Client-Sicht eine ungültige Eingabe wie 0 oder ein negativer Betrag und ergibt 400.
- Beträge haben höchstens 13 Vorkommastellen, passend zur Spalte `NUMERIC(15,2)`. Größere Beträge ergeben ebenfalls 400. Entscheidend ist der gerundete Wert: 9999999999999.995 wird abgelehnt.
- Storno und Ausbuchung: Fehlt `grund` oder ist `ausloeser` leer, antwortet der Service mit 422. Diese Prüfung läuft vor der Idempotenz- und der Kontoprüfung, eine abgelehnte `clientRequestId` bleibt also frei.
- Eine Zahlung mit einer nicht existierenden `debitorId` ergibt 404. 422 gibt es, wenn der Debitor existiert, aber dem Konto nicht zugeordnet ist.

### Valuta
- Valuten dürfen in der Zukunft liegen und vom Buchungstag abweichen.
- Die Valuta ist ein `LocalDate`, also ein fachliches Datum ohne Uhrzeit und ohne Zeitzone. Die Buchungszeit ist dagegen ein `Instant` in UTC. Beide Werte sind bewusst nicht deckungsgleich: Das UTC-Datum der Buchungszeit kann vom lokalen Geschäftstag abweichen. Eine Buchung um 00:30 Uhr deutscher Zeit (MEZ) hat zum Beispiel die Buchungszeit 23:30 UTC des Vortags. Wo der Service selbst eine Valuta bestimmt (Ausbuchung), leitet er sie aus der UTC-Uhr ab. In einem produktiven System müsste festgelegt werden, welche Zeitzone den Geschäftstag bestimmt, etwa pro Mandant.
- Fachliche Zeitstempel (Buchungszeit, Zuordnungszeitpunkt, Verrechnungszeitpunkt) werden auf Mikrosekunden gekürzt, die Genauigkeit der Datenbankspalte. So sind gespeicherter und zurückgegebener Wert identisch.

### Konsistenzprüfung
Geprüft werden zwei Invarianten:
1. Der gespeicherte Saldo entspricht dem Saldo aus allen Buchungen.
2. Die Verrechnungen sind stimmig:
   - Kein Sollposten ist überbezahlt: Der netto verrechnete Betrag (Verrechnungen minus Aufhebungen) ist höchstens so groß wie der Betrag des Postens.
   - Keine Haben-Buchung (Zahlung oder Ausbuchung) ist überverteilt.
   - Kein netto verrechneter Betrag ist negativ (mehr Aufhebungen als Verrechnungen). Geprüft wird je Soll- und je Haben-Buchung. Eine einzelne fehlerhafte Aufhebung erzeugt daher zwei Abweichungen, je eine auf beiden Seiten.

Die ursprünglich geplante Invariante „Saldo = Σ offene Posten − Σ Guthaben“ wurde bewusst durch diese direkten Prüfungen ersetzt. Sie benennen die betroffene Buchung konkret und sind einfacher nachzuvollziehen. Eine Abweichung wird mit erwartetem Wert, Istwert und Differenz (Ist − Erwartet) gemeldet und zusätzlich als Error geloggt.

### Idempotenz
- Der Request-Hash umfasst neben den fachlichen Feldern auch den Operationstyp, die Konto-Id und bei Storno die Id der stornierten Buchung. Dieselbe `clientRequestId` auf einem anderen Konto oder für eine andere Operation führt daher zu 409.
- Ein Replay hat Vorrang vor fachlichen Prüfungen. Ein identisch wiederholtes Storno liefert die Original-Storno-Buchung und keinen Doppelstorno-Konflikt.
- Welche Felder in den Hash eingehen:

  | Operation | Felder |
  |---|---|
  | Sollbuchung | Operation, Konto, Art, gerundeter Betrag, Valuta |
  | Zahlung | Operation, Konto, Debitor, gerundeter Betrag, Valuta |
  | Storno | Operation, Konto, Buchung, Grund, Bemerkung, Auslöser |
  | Ausbuchung | Operation, Konto, Grund, Bemerkung, Auslöser |

  `12` und `12.00` ergeben deshalb denselben Hash. Jedes Feld geht längenkodiert ein, damit Feldgrenzen eindeutig bleiben.
- Ein ungültiger Betrag wird schon vor der Idempotenzprüfung abgelehnt. Er kann nie gespeichert worden sein, ein Replay ist also ausgeschlossen.

### Parallele Zugriffe
- **Pessimistisches Locking pro Konto:** Jede Buchung (Sollbuchung, Zahlung, Storno, Ausbuchung) und jede explizite Verrechnung lädt das Forderungskonto mit einer Schreibsperre (`PESSIMISTIC_WRITE`, also `SELECT … FOR UPDATE`). Parallele Änderungen am selben Konto laufen dadurch nacheinander ab: Zahlen D1 und D2 gleichzeitig, wartet die zweite Zahlung kurz und wird danach ebenfalls erfolgreich gebucht. Saldo und Verrechnungen bleiben konsistent, weil jede Buchung den Stand der vorherigen sieht.
- **`@Version` als Absicherung:** Das Konto behält sein Versionsfeld. Würde ein veralteter Stand geschrieben, etwa durch einen künftigen Codepfad ohne Sperre, schlägt das Optimistic Locking an und der Service antwortet mit 409.
- **Abwägung:** Pessimistisches Locking serialisiert nur Zugriffe auf **dasselbe** Konto, verschiedene Konten laufen weiter parallel. Bei sehr vielen gleichzeitigen Buchungen auf einem einzelnen Konto würden die Requests warten. Für Forderungskonten ist das realistisch unkritisch. Ohne Sperre müsste der Client bei jedem Konflikt erneut senden, und das ist für Zahlungseingänge unpraktisch.
- Auch die Idempotenz profitiert davon: Ein paralleler Request mit derselben `clientRequestId` wartet auf die Sperre und findet danach die bereits committete Originalbuchung (Replay). Kommt dieselbe `clientRequestId` parallel auf einem anderen Konto, greift der Unique-Constraint. Der Service liest dann neu und antwortet mit Konflikt (409), weil die Konto-Id in den Request-Hash eingeht.
- Dauert das Warten länger als das Lock-Timeout der Datenbank, antwortet der Service mit 409. Der Client kann den Request mit derselben `clientRequestId` gefahrlos wiederholen.
- Parallele Anlage von Stammdaten (Kreditor, Debitor, Konto) mit demselben eindeutigen Merkmal: Der Unique-Constraint der Datenbank entscheidet, der zweite Aufruf erhält 409 mit der Id des Vorhandenen.
- Ein Test mit zwei Threads stellt sicher, dass zwei gleichzeitige Zahlungen beide gebucht werden und der Saldo stimmt.

### Prüfreihenfolge
- **Zahlung:** Konto existiert (404) → `debitorId` angegeben (422) → Debitor existiert (404) → Debitor dem Konto zugeordnet (422).
- **Storno:** Konto existiert (404) → Buchung existiert (404) → Buchung gehört zum Konto (422) → nicht bereits storniert (409) → kein Storno eines Stornos (409) → keine aktive Ausbuchungs-Verrechnung (422).
- **Ausbuchung:** Sind keine Posten offen, meldet der Service „nichts offen“ (422). Das wird vor der Guthabenprüfung geprüft.

### Gespeicherte Stammdaten
- Namen und PLZ werden unverändert gespeichert. Normalisiert wird nur der Dublettenschlüssel, getrimmt nur Kreditorennummer und Aktenzeichen.
