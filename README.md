*A feature-rich, competitive Ice Boat Racing plugin for Paper **1.21.x - 26.3**.*

## Features Overview
![Preview](https://i.imgur.com/z8kkRxi.gif)
### Core Racing
- **Multi-Arena Support** - Run multiple races simultaneously
- **Race Modes** - DEFAULT (Sprint), LAP (Looping), ELIMINATION (Last place eliminated each lap)
- **Ray-Traced Physics** - Detects checkpoints at **100km/h+** without skipping
- **Visual Editor** - Wand tool with real-time particle visualization

Are you a filipino and want to host your Minecraft server in the Philippines? Visit https://mcziehost.fun

![web banner](https://i.imgur.com/D5vYv0R.jpeg)

### Features

| Feature | Description |
|---------|-------------|
| **Race Replays** | Hypixel-style replay playback of completed races |
| **Enhanced Ghosts** | Race against server best times with fake packet boats |
| **Traffic Light Start** | Colored particle countdown (Red -> Yellow -> Green) |
| **Victory Celebrations** | Fireworks and broadcasts for race winners |
| **Elimination Mode** | Last place eliminated each lap |
| **Spectator Modes** | Free-fly, Follow Leader, Follow Player cameras |
| **17 Particle Trails** | Rainbow, Electric, Sculk, Cherry, Lava, and more |
| **Ice Staircase Gliding** | Instant 1-block ice step-up climbing with zero momentum loss |
| **Zero-Collision Assist** | Anti-rubberband protection for vanilla clients + OpenBoatUtils support |
| **Titles System** | Unlockable ranks based on wins |
| **PlaceholderAPI** | Stats placeholders for scoreboards |
| **Discord Integration** | Rich embeds for race results and records |

---

## Installation

1. **Requirements:**
   - Paper 1.21.x - 26.3+ server
   - Standalone (Built-in PacketEvents & TextDisplay Hologram Engine - No ProtocolLib or DecentHolograms required!)
   - *(Optional)* [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

2. Download `IceBoatRacing-3.2.3.jar` and place in `/plugins`
3. **Restart** the server (do not use `/reload`)
4. Configure `config.yml` as needed

---

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/iceboat` | Open main GUI menu | - |
| `/race join <arena>` | Join an arena | - |
| `/race leave` | Leave current race | - |
| `/racequit` | Quick alias for leaving | - |
| `/checkpoint` | Respawn at last checkpoint | - |
| `/race vote` | Vote for map (during voting) | - |
| `/race party create` | Create a party | - |
| `/race party invite <player>` | Invite to party | - |
| `/race party accept` | Accept party invite | - |
| `/race party leave` | Leave party | - |
| `/race replay list <arena>` | List arena replays | - |
| `/race replay watch <arena> <#>` | Watch a replay | - |
| `/race admin wand` | Get setup wand | `race.admin` |
| `/race admin startvote` | Start map voting | `race.admin` |
| `/race admin reload` | Reload config | `race.admin` |
| `/race start <arena>` | Force start race | `race.admin` |
| `/race stop <arena>` | Stop race | `race.admin` |

---

## PlaceholderAPI Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%iceboat_wins%` | Total wins |
| `%iceboat_races%` | Races played |
| `%iceboat_winrate%` | Win percentage |
| `%iceboat_best_time_<arena>%` | Best time on arena |
| `%iceboat_current_arena%` | Current arena name |
| `%iceboat_title%` | Current title |
| `%iceboat_in_race%` | true/false |
| `%iceboat_arena_record_<arena>%` | Arena record time |
| `%iceboat_total_arenas%` | Total arena count |

---

## Particle Trails

Unlockable trails with permissions:
- `race.trail.smoke` -> Smoke
- `race.trail.flame` -> Flame  
- `race.trail.soul` -> Soul Fire
- `race.trail.rainbow` -> Rainbow (HSB color cycling)
- `race.trail.electric` -> Electric Spark
- `race.trail.sculk` -> Sculk (Deep Dark themed)
- `race.trail.honey` -> Dripping Honey
- `race.trail.lava` -> Lava Drip
- `race.trail.cherry` -> Cherry Blossom
- `race.trail.snow` -> Snowflake
- `race.trail.water` -> Water Splash
- And more!

---

## Configuration

All features are configurable in `config.yml`:
- Checkpoint detection radius
- Discord webhook URL
- Music settings
- Replay limits (max per arena)
- Party settings (size, cooldowns)
- Victory celebrations
- Trail particle rates
- Title unlock thresholds


## License

GNU GPL v3
