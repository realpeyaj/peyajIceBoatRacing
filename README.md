# IceBoatRacing v3.2.3

*A feature-rich, high-performance competitive Ice Boat Racing plugin for Paper **1.21.x - 26.3+**.*

![Preview](https://i.imgur.com/z8kkRxi.gif)

Are you a filipino and want to host your Minecraft server in the Philippines? Visit https://mcziehost.fun

![web banner](https://i.imgur.com/D5vYv0R.jpeg)

---

## Key Features

| Feature | Description |
|---|---|
| **Multi-Arena Engine** | Run multiple simultaneous races across independent tracks. |
| **Race Modes** | `DEFAULT` (Sprint / Point-to-Point), `LAP` (Circuit), and `ELIMINATION` (last racer eliminated each lap). |
| **Ice Staircase Gliding** | Instant 1-block ice step-up climbing that preserves full forward momentum without stopping. |
| **Zero-Collision Assist** | Built-in anti-rubberbanding and lateral stabilization for vanilla clients, plus native **OpenBoatUtils** protocol support. |
| **100% Opponent Visibility** | All racers and boats stay visible to vanilla and modded players throughout the race. |
| **Standalone Holograms** | Built-in modern `TextDisplay` leaderboard holograms with clean transparent backgrounds—no external hologram plugins needed! |
| **Hypixel-Style Replays** | In-engine race recording and spectator replay playback. |
| **Ghost Time Trials** | Race against server records with lightweight packet-based ghost boats. |
| **Configurable Starting Grid** | Customizable countdown cages (2x2 or 3x3) with full 360° steering freedom and traffic light particles. |
| **17 Cosmetic Trails** | Rainbow, Electric, Sculk, Cherry Blossom, Lava, Soul Fire, and more. |
| **Party & Spectator System** | Party racing (`/race party`) and dynamic camera modes (Free-fly, Follow Leader, Follow Player). |
| **Discord & PAPI** | Live race results via Discord webhooks and comprehensive PlaceholderAPI expansions. |

---

## Installation

1. **Requirements:**
   - Server: Paper / Purpur **1.21.x - 26.3+** (Java 21+)
   - Standalone: Built-in PacketEvents & TextDisplay engine (no ProtocolLib or DecentHolograms required!)
   - *(Optional)* [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
2. Download `IceBoatRacing-3.2.3.jar` and place it in your server's `/plugins` folder.
3. **Restart** your server.
4. Configure options in `plugins/IceBoatRacing/config.yml`.

---

## Commands & Permissions

| Command | Description | Permission |
|---|---|---|
| `/iceboat` | Open the main racing GUI | `race.use` (default: true) |
| `/race join <arena>` | Join an arena queue | `race.use` |
| `/race leave` | Leave the current race | `race.use` |
| `/racequit` | Quick shortcut to exit race | `race.use` |
| `/checkpoint` | Respawn at your last checkpoint | `race.use` |
| `/race vote` | Vote for an arena map | `race.use` |
| `/race party <create\|invite\|accept\|leave>` | Manage racing parties | `race.party` (default: true) |
| `/race replay <list\|watch>` | View and watch race replays | `race.replay` (default: true) |
| `/race spectate <arena>` | Spectate an active race | `race.spectate` (default: true) |
| `/race admin wand` | Receive arena setup wand | `race.admin` (default: op) |
| `/race admin create <name>` | Create a new arena | `race.admin` |
| `/race admin edit <name>` | Open in-game arena editor GUI | `race.admin` |
| `/race admin reload` | Reload configuration files | `race.admin` |
| `/race start <arena>` | Force start an arena race | `race.admin` |
| `/race stop <arena>` | Force stop an active race | `race.admin` |

---

## PlaceholderAPI Placeholders

| Placeholder | Description |
|---|---|
| `%iceboat_wins%` | Total player wins |
| `%iceboat_races%` | Total races completed |
| `%iceboat_winrate%` | Win percentage |
| `%iceboat_best_time_<arena>%` | Player personal best time on specified arena |
| `%iceboat_arena_record_<arena>%` | Overall record time on specified arena |
| `%iceboat_current_arena%` | Current arena name |
| `%iceboat_title%` | Current racing rank/title |
| `%iceboat_in_race%` | `true` if currently in an active race |
| `%iceboat_total_arenas%` | Total configured arenas |

---

## Configuration (`config.yml`)

Key options available in `config.yml`:
- `settings.collision-mode`: `DEFAULT` (vanilla collision assist + OpenBoatUtils), or `GHOST` (solo ghost mode).
- `settings.cage-size`: `3` (3x3 air space for free steering) or `2` (snug 2x2 fit).
- `settings.checkpoint-radius`: Radius for checkpoint detection (blocks).
- `music`: Custom soundtrack support with loop duration and volume.
- `replay`: Recording tick intervals and saved replays per track.
- `discord`: Webhook notifications for race starts, results, and track records.

---

## License

GNU General Public License v3.0 (GPL-3.0)
