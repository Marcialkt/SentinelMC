# SentinelMC 🛡️
### The cleanest all-in-one moderation suite for Paper servers.

[![Modrinth](https://img.shields.io/badge/Modrinth-SentinelMC-00AF5C?style=flat-square&logo=modrinth)](https://modrinth.com/plugin/SentinelModeration)
[![Paper](https://img.shields.io/badge/Paper-1.20--1.21-F7CF0D?style=flat-square)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-17+-blue?style=flat-square)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square)](LICENSE)
[![Author](https://img.shields.io/badge/Author-MarcialKT-purple?style=flat-square)](https://modrinth.com/user/MarcialKT)

> **One command. Everything in a GUI. Zero TPS impact.**

SentinelMC replaces a pile of separate moderation plugins with a single, polished GUI-driven suite. Staff types `/sentinel` — no commands to memorize, no permission nodes for every action.

---

## ✨ Features at a Glance

### 🔍 Intelligence Panel
- Full player dossier: IP, first join, last seen, ban & warn status
- **Alt account detection** — automatically finds players sharing an IP
- Staff note system — attach private notes to any player profile

### ⚔️ Moderation Actions

| Action | Online | Offline |
|--------|:------:|:-------:|
| Permanent Ban | ✅ | ✅ |
| Unban | ✅ | ✅ |
| Kick | ✅ | — |
| Freeze (movement lock) | ✅ | — |
| Shadow Jail (blindness + lock) | ✅ | — |
| Sky Launch | ✅ | — |
| Drain HP | ✅ | — |
| Divine Smite (real lightning) | ✅ | — |
| Ignite | ✅ | — |
| Wither II | ✅ | — |
| Nausea II | ✅ | — |
| Heal | ✅ | — |
| InvSee (live inventory) | ✅ | — |
| EnderChest inspect | ✅ | — |
| Teleport to player | ✅ | — |
| Spectate (exit with /sentinel) | ✅ | — |

### 👤 Staff Personal Tools
- **Vanish** — invisible to players, persists across reconnects
- **Ghost Mode** — silently open any container without animation
- **God Mode** — personal invincibility
- **Social Spy** — monitor `/msg`, `/tell`, `/w`, `/r`
- **Staff Chat** — private channel, one click to toggle

### 🎖️ Staff Warn System *(Owner only)*
- Issue official warnings to staff members, stored in database
- Configurable warn threshold (default: **3 warns**)
- At threshold → **Demotion GUI** opens automatically
- Owner selects destination rank visually — no commands needed
- **LuckPerms integration** — rank change is instant and automatic
- Warns auto-clear after demotion (configurable)

### 🖥️ Server Ops
- Maintenance mode (blocks non-staff joins)
- Chat lock (silences players)
- World cleanup (day + clear weather in one click)
- Entity purge (drops, arrows, snowballs)

### 📋 Audit Log
- Last 20 bans + staff warns in a single scrollable GUI

---

## 📦 Installation

1. Download `SentinelMC-1.0.0.jar`
2. Drop it in your server's `plugins/` folder
3. **Restart** the server — do **not** use `/reload`
4. Edit `plugins/SentinelMC/config.yml` with your rank names and preferences
5. Assign permissions (see below)

> **LuckPerms is optional.** All features work without it — only automatic rank demotion requires LuckPerms.

---

## 🔑 Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `sentinel.use` | Full access to the moderation GUI | `op` |
| `sentinel.owner` | Staff Warn panel + rank demotion | `false` |

**Quick setup with LuckPerms:**
```
/lp group mod permission set sentinel.use true
/lp group owner permission set sentinel.owner true
```

---

## ⌨️ Commands

| Command | Description |
|---------|-------------|
| `/sentinel` | Open the moderation hub |
| `/sen` | Alias |
| `/mod` | Alias |
| `/staff` | Alias |

> While spectating, `/sentinel` exits spectator mode and restores your previous gamemode.

---

## ⚙️ Configuration Highlights

The `config.yml` is thoroughly commented. Key options:

```yaml
# Your LuckPerms group names, highest → lowest
rank-hierarchy:
  - owner
  - admin
  - mod
  - helper
  - member

# Warn threshold for staff demotion
staff-warns:
  max-warns: 3
  clear-after-demotion: true

# Toggle broadcast messages to staff
broadcast:
  bans: true
  kicks: true
  freeze: true

# Customize punishment values
punishments:
  sky-launch:
    velocity-y: 5.5
  divine-smite:
    strike-count: 2

# Fully customizable screens (& color codes supported)
messages:
  ban-screen: "&c&l☠ YOU ARE BANNED\n&7Reason: &f%reason%"
```

---

## 🔌 Compatibility

| | |
|--|--|
| **Server software** | Paper 1.20 – 1.21.x only |
| **Java** | 17 or higher |
| **LuckPerms** | 5.x — optional (softdepend) |
| **Spigot / CraftBukkit** | ❌ Not supported |

> This plugin uses Paper-specific APIs (`AsyncChatEvent`, Adventure). It will refuse to load on Spigot.

---

## 🏗️ Building from Source

```bash
git clone https://github.com/MarcialKT/SentinelMC
cd SentinelMC
mvn clean package
# Output: target/SentinelMC-1.0.0.jar
```

---

## 🐛 Issues & Support

Please open a GitHub issue with:
- Paper version (`/version` in-game)
- SentinelMC version
- Full stack trace from `logs/latest.log` (if any)
- Steps to reproduce

---

## 📄 License

MIT — free to use, modify and distribute with attribution.

---

*Made with ❤️ by MarcialKT*
