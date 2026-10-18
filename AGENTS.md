# StopBloatingMe

A main menu browser to blacklist ships, weapons, fighters, items and bar quests from a big mod
list. These instructions apply to this repository, including when it is opened without the parent
mod workspace. The workspace `AGENTS.md` one folder up governs releases, packaging, Git identity
and communication.

## Notion

Tasks, history and Andrzej's locked decisions: the **StopBloatingMe board** in Andrzej's Notion,
under **Starsector mods** (`collection://f3c9bb39-0b63-4fd8-ad9a-2354d93cbc85`). Working rules and
game API notes, including "Game API: bar events": **Claude Information**
(`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting with "Start here: working on
Starsector mods through Notion". Read the board before working, give every task a ticket (ticket
ids start with SBM) and keep it current. StopBloatingMe is also bundled in Coalesced Little Mods:
bring every change over there too. The Notion tools are deferred: load them with ToolSearch first.

Update the board during the work, not only at release time. Before changing files, put the
message on its ticket: a new request gets a new ticket (In progress), and a problem Andrzej
reports on Needs Human Test work moves that ticket to Changes Requested with the notes quoted.
Before ending the turn, update the ticket's body (What was done, How, Commits, Test list) and
its Status, Version and End date. Read the board with `notion-query-data-sources` mode `view`
on `view://3fd20ca7-5d20-81fd-b6c7-000c9acd186f` or `notion-fetch` a ticket. SQL and rows mode share a
small quota that runs out. The workspace `AGENTS.md` has the full routine.

## Essentials

- id `stopbloatingme`, package `stopbloatingme`, Kotlin and Java, Gradle, LazyLib and LunaLib.
- Build: `./gradlew jar` gives `jars/StopBloatingMe.jar` (runs `checkNoForbiddenApis`). Package:
  stage `StopBloatingMe/` and write `StopBloatingMe.zip`.
- The UI lives on the main menu only. The blacklist is per installation in `saves/common/`.
- Ships are removed entirely, weapons and fighters from markets only. Codex hiding uses
  `Tags.INVISIBLE_IN_CODEX`, never removal from the codex tree.
- Bar quests are blocked by holding the game's creator cooldown, never by removing creators.

## Update metadata

Keep `mod_info.json`, `stopbloatingme.version` and `data/config/version/version_files.csv`
consistent. Forum topic 35850.

- updateCheckURL and masterVersionFile:
  `https://raw.githubusercontent.com/andrzejsokolowski/starsector-stopbloatingme/main/stopbloatingme.version`
- directDownloadURL, asset `StopBloatingMe.zip`:
  `https://github.com/andrzejsokolowski/starsector-stopbloatingme/releases/latest/download/StopBloatingMe.zip`
