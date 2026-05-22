
---

📡 QQRedstone – Drahtloser Redstone-Signalgeber

QQRedstone ist ein leistungsstarker Minecraft-Plugin, mit dem Spieler Redstone-Signale drahtlos über große Entfernungen, Dimensionen und sogar zwischen Welten übertragen können – ohne Redstone-Staub, Repeater oder Komparatoren.

---

✨ Hauptfunktionen

· Drahtlose Signalübertragung zwischen Sendern und Empfängern
· Frequenzbasiertes System – Mehrere unabhängige Kanäle
· Unterstützte Mechanismen:
  · Hebel
  · Knöpfe (alle Varianten)
    – Druckplatten (alle Varianten)
    – Blitzableiter
    – Redstone-Fackeln
· Individuelle Signalstärke per Buch (Seite 2) oder durch Vanilla-Signal
· Verschachtelte Übertragungsmodi: Vanilla, Buch oder beide (maximale Stärke)
· WorldGuard-Integration mit eigenen Flags
· Antiflood-System zum Schutz vor Lag-Maschinen
· Cross-World-Support mit Berechtigungsprüfung
· SQLite-Datenbank für dauerhafte Speicherung aller Geräte
· Automatische Bereinigung defekter Mechanismen

---

📖 Wie funktioniert es?

1. Buch mit Feder vorbereiten:
   · Seite 1: Frequenz (z. B. 1)
   · Seite 2: Signalstärke (optional, 1–15)
   · Buch umbenennen in Sender oder Empfänger
2. Buch verwenden (Rechtsklick) auf einen Mechanismus (Hebel, Knopf etc.)
3. Fertig!
      Das Gerät ist registriert und sendet/empfängt Signale drahtlos.

---

🎮 Befehle

Befehl Beschreibung
/qqredstone reload Lädt Konfiguration und Datenbank neu
/qqredstone resetflood <Frequenz> Entsperrt eine blockierte Frequenz
/qqredstone resetallfloods Entsperrt alle blockierten Frequenzen

Berechtigung: qqredstone.admin.reload

---

🔧 WorldGuard-Flags

Flag Beschreibung
qqredstone-use Nutzung von QQRedstone erlaubt?
qqredstone-sender Sender dürfen erstellt werden?
qqredstone-receiver Empfänger dürfen erstellt werden?
qqredstone-max-power Maximale Signalstärke in der Region
qqredstone-book-power Buch-Signalstärke erlaubt?

---

📁 Konfiguration

Alle Einstellungen befinden sich in plugins/QQRedstone/config.yml:

· Mechanismus-Typen pro Rolle
· Übertragungsmodus (Vanilla/Buch/Beide)
· Cooldown & Antiflood
· Sprachauswahl (DE/RU/EN)

---

🌍 Unterstützte Sprachen

· Deutsch (DE)
· Русский (RU)
· English (EN)

---

📦 Anforderungen

· Minecraft 1.21+ (Paper/Folia/Spigot)
· WorldGuard 7.0.9+ (optional)
· Java 21

---

💾 Datenbank

Alle Geräte werden in einer SQLite-Datenbank gespeichert:
plugins/QQRedstone/mechanisms.db

Enthält:

· Gerätetyp (Sender/Empfänger)
· Frequenz
· Besitzer (UUID)
· Position (Welt + Koordinaten)
· Befestigungsblock & Seite
· Buch-Signalstärke

---

📄 Lizenz

MIT License – Frei zur Nutzung und Anpassung.

---

🔗 Links

· GitHub: github.com/AllFiRE/QQRedstone
· Autor: AllFiRE

---

⚡ Beispiel

1. Buch auf Sender umbenennen, Frequenz 5 auf Seite 1.
2. Rechtsklick auf einen Hebel.
3. Zweites Buch auf Empfänger umbenennen, gleiche Frequenz 5.
4. Rechtsklick auf eine Redstone-Lampe.
5. Hebel umlegen → Lampe leuchtet auf – ohne Kabel!

---

QQRedstone – Redstone ohne Grenzen! 🚀
