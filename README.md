# Pillar Game

A Paper Minecraft minigame where players battle from bedrock pillars while receiving random items as the match becomes progressively faster and more chaotic.

## Features

- Ready-up lobby with a clickable dye or `/ready`
- Eight automatically generated bedrock pillars
- Five continuous phases with increasingly frequent random items
- Final phase continues until only one player remains
- Three lives per player
- Double Jump unlocks in phase 2 and upgrades to Triple Jump in phase 3
- Lucky blocks that respawn immediately on top of each pillar, with a weighted jackpot table
- Random combat loot and building blocks in a protected chest on a random pillar
- Phase countdown boss bar and player sidebar
- Persistent wins, current streaks, and best streaks
- Automatic cleanup between matches

## Random Items

Every permitted Minecraft item is automatically sorted into one category - blocks, food,
projectiles, utility, tools, weapons, armor, or chaos (TNT, spawn eggs, and other
wildcards) - once at startup. Two independent, weighted loot profiles pick a category by
weight and then an item uniformly within it:

- **Timed phase rewards**: every surviving player gets one independently selected item
  at each phase's item interval, using balanced category weights (blocks/weapons/armor
  are the most common; chaos is rare).
- **Lucky-block rewards**: the leaf block on top of each pillar respawns immediately
  when broken - no delay - so with every player able to mine roughly one per second,
  the lucky-block weights are tuned much lower for equipment and jackpot rewards than
  the timed table, so matches don't get flooded with gear.

Lucky-block rewards additionally roll a **jackpot** category (a small chance out of the
lucky weights) that draws from its own weighted table: `totem_of_undying`,
`enchanted_golden_apple`, `netherite_chestplate`, `netherite_sword`, `mace`, `trident`,
and `ender_pearl` (worth 3 pearls; every other jackpot reward is a single item).
Whenever equipment is created by the game - from timed phase rewards, lucky blocks,
admin random-item commands, or the center chest - it receives every useful, mutually
compatible enchantment at its maximum level. Curses are excluded. Conflicting families
use one preferred choice, such as Sharpness, Fortune, Protection, Infinity, Loyalty,
Density, and Multishot. Knockback is deliberately excluded. Non-enchantable items
remain unchanged.

To keep lucky-block rewards from feeling repetitive or gear-flooded:

- The same exact item can't repeat within a player's last 5 lucky-block rolls.
- Receiving a tool, weapon, armor piece, or equipment-flagged jackpot item puts that
  player on a 15-break cooldown from any further equipment reward (non-equipment
  categories keep rolling normally during the cooldown).
- Both reset at the start of every match.

A category (or jackpot item) with a weight of `0`, or with no items left in it after
`random-items.excluded`, is simply skipped rather than treated as an error. See
`config.yml` for the exact default weights and how to override them.

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
- Items excluded from both loot profiles (`random-items.excluded`)
- Timed-phase and lucky-block category weights (`random-items.timed-weights` and
  `random-items.lucky-weights`; see [Random Items](#random-items))
- The lucky-block jackpot table (`random-items.jackpot-items`)
- Void rescue behavior outside active matches

Existing configuration names continue to use `rounds` and `*-round` internally for compatibility, although they represent phases in the game.
