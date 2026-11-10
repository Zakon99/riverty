# KI-Nutzung

Dieses Dokument beschreibt transparent, wie ich KI bei der Case Study eingesetzt habe. Es ist kuratiert und fasst die wichtigen Prompts zusammen, Rohprotokolle liegen nur lokal.

## Werkzeuge

- **Claude (Chat)** als Sparringspartner für das fachliche Design: Fachmodell, Verrechnungslogik, Abwägungen. Daraus ist der initiale Auftrag entstanden.
- **Claude Code** für die Umsetzung im Repository, immer mit Plan-Freigabe vor jeder Stufe.
- **Hooks und Leitplanken** (lokal):
  - Ein `UserPromptSubmit`-Hook protokolliert jeden meiner Prompts automatisch. Das Protokoll ist die Grundlage für dieses Dokument.
  - Deny-Regeln verbieten `git push --force`, `git reset --hard` und das Löschen von `.git`.
  - Ein versionierter `pre-push`-Hook führt `./mvnw verify` aus.
- **Zweite Claude-Instanz zur Gegenprüfung:** Die Ergebnisse habe ich zusätzlich von einer zweiten, unabhängigen Claude-Instanz gegenprüfen lassen. Die Entscheidungen habe ich selbst getroffen, teils auch gegen deren Empfehlung.
- **Subagents mit getrennten Rollen:**
  - **Product Manager:** leitet Akzeptanzkriterien (Given/When/Then) ab und nimmt die Stufe ab.
  - **Tester:** schreibt die Tests aus den Akzeptanzkriterien.
  - **Coder:** implementiert, bis die Tests grün sind.
  - **Reviewer:** prüft rein lesend.

**Warum Tests aus den Akzeptanzkriterien statt aus dem Code?** Tests, die aus der fertigen Implementierung abgeleitet werden, bestätigen nur, was der Code ohnehin tut, auch seine Fehler. Werden sie aus den fachlichen Kriterien und vor der Implementierung geschrieben, prüfen sie die Anforderung und nicht die Umsetzung.

**Warum ein separater Reviewer?** Wer den Code geschrieben hat, übersieht leicht die eigenen Annahmen. Der Reviewer hat keinen Schreibzugriff und keinen Anteil an der Umsetzung. Er prüft gezielt die fachlichen Regeln (§ 367, append-only, Beträge nie negativ), die Architektur und typische Lombok-, JPA- und Sonar-Fallen.

## Vorgehen

1. Design im Chat erarbeitet, daraus der initiale Prompt für Claude Code.
2. Claude Code stellt zuerst den Plan vor (Paketstruktur, Entitäten, Schema, Endpunkte) und fragt bei unklaren Regeln nach. Umgesetzt wird erst nach meiner Freigabe.
3. Umsetzung in Sprints, eine Prioritätsstufe pro Sprint. Ich reviewe jede Stufe, erst danach wird committed und gepusht.
4. Tests als Absicherung. Eine Stufe gilt erst als fertig, wenn `./mvnw verify` grün ist und es keine Compiler-Warnungen gibt.

## Meine fachlichen Entscheidungen

- **Verrechnung nach § 367 BGB statt nach dem Folien-Beispiel:** Kosten (Gebühren) → Zinsen → Hauptforderung.
- **Verrechnungen persistiert statt berechnet:** Jede Zuordnung von Zahlung zu Posten ist nachvollziehbar und wird nie gelöscht, sondern über eine Aufhebung aufgelöst.
- **Debitor optional und n:m mit Gesamtschuld-Annahme:** Jeder Debitor haftet für das ganze Konto.
- **Guthaben pro Debitor:** Eine Überzahlung bleibt dem zahlenden Debitor zugeordnet.
- **Ausbuchung über denselben Verrechnungsmechanismus, nur Gesamtrest.**
- **Beträge nie negativ:** Die Richtung ergibt sich aus der Buchungsart.
- **H2 im Dateimodus statt Container:** Startet ohne Infrastruktur. Flyway und `MODE=PostgreSQL` halten den Wechsel zu PostgreSQL klein.
- **Dublettenschlüssel für Debitoren:** normalisiert aus Name, Geburtsdatum und PLZ.

Bei der Planfreigabe habe ich zusätzlich festgelegt:
- Storno einer Sollbuchung, auf die eine Ausbuchung verrechnet ist, wird mit 422 abgelehnt. Zuerst muss die Ausbuchung storniert werden.
- Die automatische Verrechnung bei Zahlungseingang verteilt nur die neue Zahlung. Vorhandenes Guthaben wird nur über den Verrechnungs-Endpunkt verteilt.
- Eine Zahlung auf ein Konto ohne offene Posten ist erlaubt und wird vollständig Guthaben.
- Der Idempotenz-Hash umfasst Operationstyp, Konto und ggf. die stornierte Buchung.

## Wo ich KI-Vorschläge korrigiert oder verworfen habe

| Thema | Vorschlag der KI | Meine Entscheidung |
|---|---|---|
| Valuta einer Storno-Buchung | Datum des Buchungstags (aus der `Clock`) | Valuta des **Originals**: Ein Storno korrigiert eine von Anfang an falsche Buchung. So ist der Saldo zu jedem Stichtag korrekt, und es entstehen keine Zinsen auf eine Forderung, die nie bestanden hat. Den Zeitpunkt der Korrektur zeigt die Buchungszeit |
| Commit-Attribution | Standardmäßig `Co-Authored-By` in den Commits | Keine Attribution in Commits. Die KI-Nutzung ist stattdessen hier dokumentiert |
| Konsistenz-Invariante 2 | „Saldo = Σ offene Posten − Σ Guthaben“, verglichen mit dem berechneten Saldo | Ersetzt durch zwei direkte Prüfungen: kein Posten überbezahlt, keine Zahlung oder Ausbuchung überverteilt. Die Summenformel ist schwer nachvollziehbar und benennt die fehlerhafte Buchung nicht |
| Storno mit Buchung eines anderen Kontos | 404 („in diesem Konto nicht gefunden“) | 422: Die Buchung existiert, die Anfrage verletzt aber die Regel, dass Storno und Original zum selben Konto gehören |
| Zeitzone für „heute“ (Ausbuchungs-Valuta) | Europe/Berlin (deutscher Geschäftstag) | UTC, für ein weltweit einheitliches System |
| Parallele Buchungen auf ein Konto | Optimistisches Locking: Die zweite parallele Buchung bekommt 409, der Client wiederholt | Pessimistisches Locking pro Konto mit `@Version` als Absicherung. Parallele Zahlungen werden nacheinander gebucht, beide erfolgreich, abgesichert durch einen Test mit zwei Threads |
| Review-Findings | Schärfere Prüfung der Error-Logs in Tests, Sonar-Kleinkram sofort beheben | Log-Prüfung nicht verschärft (wenig Mehrwert). Sonar-Findings erst in Stufe 5, wenn SonarQube Cloud läuft |
| Review Stufe 2: Betrag mit extremem Exponenten | Größenordnung vor dem Runden prüfen (DoS-Schutz) | Zunächst nur als bekannte Grenze dokumentiert. In der letzten Runde vor der Abgabe doch umgesetzt: Größenordnung vor dem Runden prüfen, abgesichert durch Tests mit Timeout |
| Review Stufe 2: optionale Erweiterungen | `feldfehler` bei Typfehlern, `Location`-Header | Keine neuen Features. Nur Fehlerbehebungen (`@Size` gegen 500) und Aufräumarbeiten |
| Lock-Timeout | Expliziter `LOCK_TIMEOUT` in der JDBC-URL plus Test für das Überschreiten | Nicht gesetzt. Der Datenbank-Standard gilt, die README beschreibt das offen als bekannte Grenze |
| Pre-Push-Hook | Einmalig `--no-verify` für den Fundament-Commit angeboten | Kein Überspringen. Fundament-Commits gehen zusammen mit Stufe 1 raus, wenn alles grün ist |

## Zentrale Prompts (Kurzform)

1. **Initialer Auftrag:** Buchungsservice in Java 21 / Spring Boot mit Fachmodell (Konto, Beteiligte, Buchungen, Verrechnung nach § 367, Storno, Ausbuchung), REST-API, Idempotenz, Konsistenzprüfung, Prioritätsstufen 0 bis 6 und Definition of Done. Zuerst Plan vorstellen, dann stufenweise umsetzen.
2. **Planfreigabe:** Arbeit in Sprints mit Review vor jedem Commit. Offene Punkte entschieden: 422 bei Storno einer ausgebuchten Sollbuchung, Valuta-Regeln für Storno und Ausbuchung, Umfang der automatischen Verrechnung, Zahlung ohne offene Posten, Umfang des Idempotenz-Hashes.
3. **Akzeptanzkriterien Stufe 1:** Der Product-Manager-Agent hat 50 Given/When/Then-Kriterien mit konkreten Rechenbeispielen abgeleitet und zehn Lücken im Auftrag als offene Fragen gemeldet. Meine Entscheidungen: 400 für einen Betrag, der auf 0 rundet; 404 bei unbekanntem Debitor; 422 bei Storno über Kontogrenzen; 409 bei doppelter Debitor-Zuordnung; No-op bei expliziter Verrechnung ohne Wirkung; 422 bei Ausbuchung trotz Guthaben; neue Konsistenz-Invariante 2; UTC als Zeitzone.
4. **Umsetzung Stufe 1:** Der Tester hat 92 Testmethoden aus den Kriterien geschrieben, bevor es Produktivcode gab (rot). Der Coder hat implementiert, bis alle Tests grün waren. Der Reviewer fand keine fachlichen Fehler, aber Lücken: Parallelverhalten falsch dokumentiert, keine Obergrenze für Beträge, ungetestete Constraint-Pfade und weitere Randfälle. Ich habe entschieden, welche Findings jetzt umgesetzt werden und welche später.
5. **Akzeptanzkriterien Stufe 2:** 41 Kriterien für REST-Schicht, ProblemDetail, Idempotenz über HTTP, ArchUnit und Demo-Profil. Meine Entscheidungen:
   - 400 für alle fehlenden Pflichtfelder, auch für die im Auftrag nicht genannten
   - eigenes API-Enum `Sollart`, und jeder unbekannte Enum-Wert ergibt 400
   - bei doppelter Zuordnung liefert der 409 die Debitor-Id
   - Demo-Konto ohne Zahlung
   
   Die Demo-Werte habe ich mir vorab geben lassen und den Interview-Ablauf einmal durchgerechnet.
6. **Umsetzung Stufe 2:**
   - Das Review fand zwei Major-Findings.
   - Ich habe entschieden, keine neuen Features aufzunehmen. Den Exponenten-Randfall habe ich nur dokumentiert. Behoben wurden zu lange Strings (500 → 400) und Aufräumpunkte.
   - Einen falschen Test hat der Coder mit Begründung gemeldet statt ihn selbst zu ändern: doppelte URL-Kodierung in MockMvc.
7. **Stufe 3 (Dokumentation):**
   - Wegen des geringen Risikos mit verkürzter Agent-Kette: Die Hauptsession schreibt, der Product Manager prüft gegen den Auftrag, ich reviewe.
   - Die H2-Konsole kam als kleine Abhängigkeit dazu.
   - Den Demo-Ablauf aus `docs/demo.http` habe ich vor dem Schreiben gegen die laufende Anwendung durchgespielt.
8. **Stufe 4 und 5 (CI, SonarQube Cloud):**
   - Vor dem ersten CI-Lauf fiel auf, dass `mvnw` im Repo nicht ausführbar war. Unter Linux wäre der Build sofort gescheitert, lokal unter Windows war das nicht sichtbar.
   - SonarQube Cloud habe ich selbst eingerichtet. Den dabei erzeugten Vorlagen-Workflow habe ich auf Vorschlag der KI in die bestehende CI integriert, damit nur einmal gebaut wird.
   - Einen CVE-Hinweis der IDE zu springdoc habe ich als bekannte Grenze dokumentiert. Die neueste Version ist bereits eingesetzt.
9. **Abschluss:**
   - Die optionale Stufe 6 (Docker) habe ich bewusst weggelassen.
   - Die README habe ich auf vier Abschnitte gekürzt: Start, Annahmen, Verrechnungsreihenfolge, Nächste Schritte.
   - Die Details liegen jetzt in `docs/details.md`.
10. **Letzte Runde vor der Abgabe:**
    - DoS-Schutz in `Betrag`: Die Größenordnung wird vor dem Runden geprüft, abgesichert durch Tests mit einem Timeout von einer Sekunde.
    - Demo-Kreditor umbenannt in „Musterstadt Versandhandel GmbH“.
    - Begründung der Storno-Valuta geschärft.
    - In der README steht „JDK 21“, und es gibt einen Hinweis auf httpyac und Swagger für `demo.http`.
