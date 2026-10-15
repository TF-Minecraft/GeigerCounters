# Geiger Counters

> Follow the signal, find the source, and uncover loot in TF-Minecraft.

Geiger Counters turns exploration into a treasure hunt. Each character has their own hidden radioactive source a few hundred blocks away. A signal bar, particle rings and clicking lead them to it. Reaching the source awards loot and drains the counter, and the next counter starts a new hunt near wherever the character is.

## Features

- **A source per character** — each character's source is placed near them and kept until they find it.
- **Signal bar** — a boss bar fills as the player closes in and peaks when they face the source.
- **Visual tracking** — particle rings change in color and number as the player approaches the source.
- **Audible feedback** — irregular counter clicks become more frequent at closer distances.
- **Tiered discoveries** — weighted reward tiers give each successful hunt a chance of different loot.
- **Consumable counters** — a successful collection replaces the active counter with a dead one.
- **Collection limits** — each account may collect a set number of sources per time window.

Originally created by [Justinas Launikonis](https://github.com/JustinasLa).

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/GeigerCounters/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

With Java 21 and the pinned plugin dependencies installed, run `mvn clean verify`.
Tests use JUnit 5, Mockito, and MockBukkit. Surefire test results are in
`target/surefire-reports/`; JaCoCo HTML and XML reports are in `target/site/jacoco/`.
The Build workflow uploads both. Verification requires 100% line, branch, and instruction
coverage of production code, with no coverage exclusions.
Client sounds and particles, live world generation, and installed item and region
plugins require separate in-game checks.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
