# Geiger Counters

> Follow the signal, find the source, and uncover loot in TF-Minecraft.

Geiger Counters turns exploration into a shared treasure hunt. Players carry a counter and follow its changing particle signal and clicking sounds toward a hidden radioactive source. Reaching the source awards loot, drains the counter, and sends the next hunt somewhere new.

The signal gives a sense of distance, so finding the reward means moving through the world and watching how the feedback changes.

## Features

- **Visual tracking** — particle rings change in color and number as the player approaches the source.
- **Audible feedback** — irregular counter clicks become more frequent at closer distances.
- **Tiered discoveries** — weighted reward tiers give each successful hunt a chance of different loot.
- **A moving target** — collecting the shared source relocates it within the configured search area.
- **Consumable counters** — a successful collection replaces the active counter with a dead one.
- **Personal collection limits** — timed allowances limit repeated rewards while letting other players continue the hunt.

Originally created by [Justinas Launikonis](https://github.com/JustinasLa).

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/GeigerCounters/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

With Java 21 and the pinned plugin dependencies installed, run `mvn clean verify`.
Tests use JUnit 5, Mockito, and MockBukkit. Surefire test results are in
`target/surefire-reports/`; JaCoCo HTML and XML reports are in `target/site/jacoco/`.
CI uploads both. Verification requires 100% line, branch, and instruction
coverage of production code, with no coverage exclusions.
Client sounds and particles, live world generation, and installed item and region
plugins require separate in-game checks.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
