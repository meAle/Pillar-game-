# Pillar Game

A Paper Minecraft minigame where players battle from bedrock pillars while receiving random items as the match becomes progressively faster and more chaotic.

## Features

- Ready-up lobby with a clickable dye or `/ready`
- Eight automatically generated bedrock pillars
- Five continuous phases with increasingly frequent random items
- Final phase continues until only one player remains
- Three lives per player
- Double Jump unlocks in phase 2 and upgrades to Triple Jump in phase 3
- Lucky blocks that respawn on top of each pillar
- Random building blocks in a protected center chest
- Phase countdown boss bar and player sidebar
- Persistent wins, current streaks, and best streaks
- Automatic cleanup between matches

## Requirements

- Java 21
- Paper 1.21.11

## Building

Clone the repository and run the Gradle build from its root directory.

Windows:

```powershell
.\gradlew.bat build
```

Linux or macOS:

```bash
./gradlew build
```

The compiled plugin will be created in `build/libs/`.

For a local development server, run:

```powershell
.\gradlew.bat runServer
```

## Installation

1. Build the plugin or download a compiled release.
2. Place the JAR in the Paper server's `plugins` directory.
3. Start the server.
4. Edit `plugins/MiniGames/config.yml` if desired.
5. Restart the server after changing the configuration.

The plugin creates and manages its own void world. It refuses to use an existing world that was not originally created by the plugin.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/ready` | Toggle readiness before a match | Everyone |
| `/minigames ready` | Toggle readiness before a match | Everyone |
| `/minigames restart` | Reset the arena and open a new ready check | `minigames.admin` |
| `/minigames forcestart` | Start a match immediately | `minigames.admin` |
| `/minigames give <1-64> [player]` | Give random items to one player or everyone in the arena | `minigames.admin` |
| `/minigames feather <double\|triple> [player]` | Give a jump feather | `minigames.admin` |

`/mg` is an alias for `/minigames`. The `minigames.admin` permission defaults to server operators.

## Default Match Progression

| Phase | Item interval | Ability |
| ---: | ---: | --- |
| 1 | 15 seconds | None |
| 2 | 12.5 seconds | Double Jump |
| 3 | 10 seconds | Triple Jump |
| 4 | 7.5 seconds | Triple Jump |
| 5 | 5 seconds | Triple Jump; continues until one player remains |

Each of the first four phases lasts 70 seconds by default. Every surviving player receives an independently selected random item at each interval.

## Configuration

The generated `config.yml` controls:

- Managed world name
- Pillar height, radius, and bedrock depth
- Phase count and duration
- Random-item intervals
- Double and Triple Jump phases and movement strength
- Items excluded from the random pool
- Void rescue behavior outside active matches

Existing configuration names continue to use `rounds` and `*-round` internally for compatibility, although they represent phases in the game.
