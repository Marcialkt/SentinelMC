package dev.marcialkt.sentinelmc;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.event.player.AsyncChatEvent;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ┌─────────────────────────────────────────────────────┐
 * │           SentinelMC — Moderation Suite              │
 * │  Paper 1.20-1.21 · LuckPerms (optional) · SQLite    │
 * │         Author: MarcialKT                            │
 * │         modrinth.com/plugin/sentinelmc               │
 * └─────────────────────────────────────────────────────┘
 *
 *  One command (/sentinel), entirely GUI-driven.
 *  Zero TPS impact — all DB operations are async.
 *  LuckPerms is optional; staff demotion disables itself if absent.
 */
@SuppressWarnings("deprecation")
public final class SentinelMC extends JavaPlugin implements Listener {

    private static final String VERSION = "1.0.0";

    // ── INTEGRATION ───────────────────────────────────────────────────────────
    private LuckPerms luckPerms;
    private boolean lpEnabled = false;

    // ── DATABASE ──────────────────────────────────────────────────────────────
    private Connection db;

    // ── RUNTIME STATE (all thread-safe) ───────────────────────────────────────
    private final Set<UUID> frozen     = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished   = ConcurrentHashMap.newKeySet();
    private final Set<UUID> shadowJail = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ghostMode  = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChat  = ConcurrentHashMap.newKeySet();
    private final Set<UUID> socialSpy  = ConcurrentHashMap.newKeySet();
    private final Set<UUID> godMode    = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Location> frozenAt    = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> prevGM       = new ConcurrentHashMap<>();
    // pendingInput: adminUUID → { "type", "target" }
    // types: "ban" | "staffwarn" | "note"
    private final Map<UUID, String[]> pendingInput = new ConcurrentHashMap<>();

    private boolean chatLocked  = false;
    private boolean maintenance = false;

    private BukkitTask visualTask;

    // ── CONFIG CACHE ──────────────────────────────────────────────────────────
    private List<String> rankHierarchy;
    private String  prefix;
    private int     maxWarns;
    private boolean broadcastBans, broadcastKicks, broadcastWarns;
    private String  banMessage, maintenanceMessage, kickMessage;

    // ── GUI TITLE CONSTANTS ───────────────────────────────────────────────────
    // §0 prefix guarantees no other plugin accidentally matches these titles.
    private static final String T_HUB       = "§0Sentinel · Hub";
    private static final String T_OPS       = "§0Sentinel · Server Ops";
    private static final String T_SELECT    = "§0Sentinel · Players: ";
    private static final String T_INTEL     = "§0Sentinel · Intel: ";
    private static final String T_PUNISH    = "§0Sentinel · Punish: ";
    private static final String T_SWPANEL   = "§0Sentinel · Staff Warns";
    private static final String T_SWPROFILE = "§0Sentinel · SW: ";
    private static final String T_RANKPICK  = "§0Sentinel · Demote: ";
    private static final String T_AUDIT     = "§0Sentinel · Audit Log";

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // LuckPerms — soft depend, gracefully disabled if absent
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                luckPerms = LuckPermsProvider.get();
                lpEnabled = true;
                log(Level.INFO, "LuckPerms hooked successfully. Staff demotion is active.");
            } catch (Exception ex) {
                log(Level.WARNING, "LuckPerms found but hook failed: " + ex.getMessage());
            }
        } else {
            log(Level.WARNING, "LuckPerms not found. Staff rank demotion will be disabled.");
        }

        if (!setupDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("sentinel")).setExecutor(this);

        startVisualEngine();
        printBanner();
    }

    @Override
    public void onDisable() {
        if (visualTask != null) visualTask.cancel();

        // Restore all vanished players before shutdown
        for (UUID u : vanished) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Bukkit.getOnlinePlayers().forEach(a -> a.showPlayer(this, p));
        }
        // Remove jail effects
        for (UUID u : shadowJail) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.removePotionEffect(PotionEffectType.BLINDNESS);
        }

        closeDatabase();
        log(Level.INFO, "SentinelMC v" + VERSION + " disabled cleanly.");
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();
        rankHierarchy      = cfg.getStringList("rank-hierarchy");
        prefix             = color(cfg.getString("prefix", "&8[&bSentinel&8] &r"));
        maxWarns           = cfg.getInt("staff-warns.max-warns", 3);
        broadcastBans      = cfg.getBoolean("broadcast.bans", true);
        broadcastKicks     = cfg.getBoolean("broadcast.kicks", true);
        broadcastWarns     = cfg.getBoolean("broadcast.staff-warns", true);
        banMessage         = cfg.getString("messages.ban-screen", "&c&l☠ YOU ARE BANNED\n&7Reason: &f%reason%");
        maintenanceMessage = cfg.getString("messages.maintenance-kick", "&b&lMaintenance\n&7We'll be back soon.");
        kickMessage        = cfg.getString("messages.kick-screen", "&cYou have been kicked by a staff member.");

        if (rankHierarchy.isEmpty()) {
            rankHierarchy = Arrays.asList("owner", "admin", "mod", "helper", "member");
            cfg.set("rank-hierarchy", rankHierarchy);
            saveConfig();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DATABASE — SQLite with WAL (Write-Ahead Logging)
    //  WAL allows concurrent reads while a write is in progress,
    //  keeping TPS stable even during heavy player join bursts.
    // ══════════════════════════════════════════════════════════════════════════

    private boolean setupDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                log(Level.SEVERE, "Could not create plugin data folder.");
                return false;
            }
            String dbPath = getConfig().getString("database.file", "sentinel.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/" + dbPath);

            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA cache_size=500");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid       TEXT PRIMARY KEY,
                        name       TEXT NOT NULL COLLATE NOCASE,
                        ip         TEXT,
                        first_join TEXT NOT NULL,
                        last_seen  TEXT NOT NULL
                    )""");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS bans (
                        uuid       TEXT PRIMARY KEY,
                        reason     TEXT NOT NULL,
                        banned_by  TEXT NOT NULL,
                        timestamp  TEXT NOT NULL
                    )""");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS staff_warns (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid       TEXT NOT NULL,
                        reason     TEXT NOT NULL,
                        warned_by  TEXT NOT NULL,
                        timestamp  TEXT NOT NULL
                    )""");

                s.execute("""
                    CREATE TABLE IF NOT EXISTS notes (
                        uuid       TEXT PRIMARY KEY,
                        content    TEXT NOT NULL,
                        author     TEXT NOT NULL,
                        timestamp  TEXT NOT NULL
                    )""");

                s.execute("CREATE INDEX IF NOT EXISTS idx_ip   ON players(ip)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_name ON players(name)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_sw   ON staff_warns(uuid)");
            }
            log(Level.INFO, "SQLite (WAL) database initialized.");
            return true;
        } catch (SQLException e) {
            log(Level.SEVERE, "SQLite initialization failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void closeDatabase() {
        try { if (db != null && !db.isClosed()) db.close(); }
        catch (SQLException e) { e.printStackTrace(); }
    }

    // Thread helpers
    private void async(Runnable r) { Bukkit.getScheduler().runTaskAsynchronously(this, r); }
    private void sync(Runnable r)  { Bukkit.getScheduler().runTask(this, r); }
    private String now()           { return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()); }

    /** Returns the UUID string for a player name from the DB, or null if not found. ASYNC ONLY. */
    private String uuidOf(String name) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT uuid FROM players WHERE name=? COLLATE NOCASE")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("uuid") : null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMMAND ENTRY POINT — /sentinel (and aliases /sen /mod /staff)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cSentinel: This command is GUI-only and cannot be used from console.");
            return true;
        }
        if (!p.hasPermission("sentinel.use")) { noPerms(p); return true; }

        // If spectating, /sentinel exits spectator and restores previous gamemode
        if (prevGM.containsKey(p.getUniqueId())) {
            p.setGameMode(prevGM.remove(p.getUniqueId()));
            p.sendMessage(prefix + "§7Exited spectator mode.");
            return true;
        }

        openHub(p);
        sfx(p, Sound.BLOCK_CHEST_OPEN);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VISUAL ENGINE — runs every 10 ticks (0.5 s)
    //  Handles: vanish action bar, freeze particles, jail particles, god mode HP
    // ══════════════════════════════════════════════════════════════════════════

    private void startVisualEngine() {
        visualTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID u : vanished) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) actionBar(p, "§b§lGHOST §8| §7Invisible to players §8| §b/sentinel §7to open hub");
            }
            for (UUID u : frozen) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1.1, 0), 5, 0.3, 0.4, 0.3, 0.01);
                actionBar(p, "§b§l❄ FROZEN — Do not disconnect ❄");
            }
            for (UUID u : shadowJail) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1.1, 0), 10, 0.4, 0.8, 0.4, 0.02);
                actionBar(p, "§8§l☠ SHADOW JAIL — Under staff investigation ☠");
            }
            for (UUID u : godMode) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.getHealth() < p.getMaxHealth()) p.setHealth(p.getMaxHealth());
            }
        }, 0L, 10L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — HUB
    // ══════════════════════════════════════════════════════════════════════════

    private void openHub(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, T_HUB);
        fill(inv, glass(Material.GRAY_STAINED_GLASS_PANE));
        stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);
        stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 45, 53);

        inv.setItem(10, item(Material.COMPASS,       "§6§l⬡ INTELLIGENCE", "§7Browse player profiles,", "§7IP lookup & alt detection.", "§8▸ Click to open"));
        inv.setItem(12, item(Material.NETHERITE_AXE, "§c§l⬡ MODERATION",   "§7Kick, ban, punish", "§7online and offline players.", "§8▸ Click to open"));
        inv.setItem(14, item(Material.COMMAND_BLOCK,  "§b§l⬡ SERVER OPS",   "§7Maintenance, chat lock,", "§7world cleanup & entity purge.", "§8▸ Click to open"));
        inv.setItem(16, item(Material.BOOK,            "§e§l⬡ AUDIT LOG",    "§7Recent bans and staff warns.", "§8▸ Click to view"));

        if (p.hasPermission("sentinel.owner")) {
            inv.setItem(22, item(Material.ORANGE_BANNER, "§6§l★ STAFF WARNS",
                    "§7Issue official warnings to staff.", "§7At " + maxWarns + " warns, demotion is available.",
                    "§c● Owner only", "§8▸ Click to open"));
        } else {
            inv.setItem(22, glass(Material.BLACK_STAINED_GLASS_PANE));
        }

        UUID uid = p.getUniqueId();
        inv.setItem(28, tog(Material.ENDER_EYE,        "§d§lVANISH",      vanished.contains(uid),   "§7Invisible to non-staff players."));
        inv.setItem(29, tog(Material.GHAST_SPAWN_EGG,  "§f§lGHOST MODE",  ghostMode.contains(uid),  "§7Open containers without animation."));
        inv.setItem(30, tog(Material.BEACON,            "§a§lSOCIAL SPY",  socialSpy.contains(uid),  "§7Monitor private /msg messages."));
        inv.setItem(31, tog(Material.WRITABLE_BOOK,     "§9§lSTAFF CHAT",  staffChat.contains(uid),  "§7Staff-only private chat channel."));
        inv.setItem(32, tog(Material.GOLDEN_APPLE,      "§6§lGOD MODE",    godMode.contains(uid),    "§7Personal invincibility toggle."));

        double tps = Bukkit.getTPS()[0];
        String tpsColor = tps >= 18 ? "§a" : tps >= 15 ? "§e" : "§c";
        inv.setItem(49, item(Material.PAPER, "§f§lServer Status",
                "§7Players: §a" + Bukkit.getOnlinePlayers().size() + " §7/ §f" + Bukkit.getMaxPlayers(),
                "§7TPS: " + tpsColor + String.format("%.1f", tps),
                "§7Maintenance: " + (maintenance ? "§cON" : "§aOFF"),
                "§7Chat: "        + (chatLocked  ? "§cLOCKED" : "§aOPEN"),
                "§8SentinelMC v" + VERSION + " by MarcialKT"));

        p.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — SERVER OPS
    // ══════════════════════════════════════════════════════════════════════════

    private void openServerOps(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, T_OPS);
        fill(inv, glass(Material.GRAY_STAINED_GLASS_PANE));
        stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);

        inv.setItem(10, tog(Material.IRON_DOOR,  "§f§lMaintenance Mode",  maintenance, "§7Prevents non-staff from joining."));
        inv.setItem(12, tog(Material.PAPER,       "§f§lChat Lock",         chatLocked,  "§7Silences all non-staff players."));
        inv.setItem(14, item(Material.SUNFLOWER,  "§e§lClean World",       "§7Set time to day, clear weather."));
        inv.setItem(16, item(Material.BARRIER,    "§c§lPurge Entities",    "§7Remove dropped items, arrows, snowballs."));

        inv.setItem(22, back());
        p.openInventory(inv);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — PLAYER LIST (async DB load)
    // ══════════════════════════════════════════════════════════════════════════

    private void openPlayerList(Player admin, String mode) {
        admin.sendMessage(prefix + "§7Loading player list...");
        int limit = getConfig().getInt("player-list.max-display", 45);
        String sort = getConfig().getString("player-list.sort-by", "last_seen");
        String orderCol = sort.equals("name") ? "name" : "last_seen";

        async(() -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT name, last_seen FROM players ORDER BY " + orderCol + " DESC LIMIT ?")) {
                ps.setInt(1, Math.min(limit, 45));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) rows.add(new String[]{rs.getString("name"), rs.getString("last_seen")});
            } catch (SQLException e) { e.printStackTrace(); }

            sync(() -> {
                Inventory inv = Bukkit.createInventory(null, 54, T_SELECT + mode);
                fill(inv, glass(Material.GRAY_STAINED_GLASS_PANE));
                stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);

                int slot = 9;
                for (String[] row : rows) {
                    if (slot >= 54) break;
                    String name = row[0];
                    boolean on = Bukkit.getPlayer(name) != null;
                    inv.setItem(slot++, skull(name,
                            (on ? "§a" : "§7") + "§l" + name,
                            "§7Status: " + (on ? "§aONLINE" : "§cOFFLINE"),
                            "§7Last seen: §f" + row[1],
                            "§8▸ Click to " + (mode.equals("Intel") ? "view profile" : "punish")));
                }
                if (rows.isEmpty()) inv.setItem(22, item(Material.BARRIER, "§cNo players registered yet."));
                inv.setItem(4, back());
                admin.openInventory(inv);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — DOSSIER / INTEL (async)
    // ══════════════════════════════════════════════════════════════════════════

    private void openDossier(Player admin, String target) {
        admin.sendMessage(prefix + "§7Fetching intel on §f" + target + "§7...");
        async(() -> {
            String ip = "Unknown", join = "—", lastSeen = "—", note = "No notes on record.";
            List<String> alts = new ArrayList<>();
            int warnCount = 0;
            boolean banned = false;

            try {
                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT ip, first_join, last_seen FROM players WHERE name=? COLLATE NOCASE")) {
                    ps.setString(1, target);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        ip = rs.getString("ip");
                        join = rs.getString("first_join");
                        lastSeen = rs.getString("last_seen");
                        try (PreparedStatement pa = db.prepareStatement(
                                "SELECT name FROM players WHERE ip=? AND name!=? COLLATE NOCASE")) {
                            pa.setString(1, ip); pa.setString(2, target);
                            ResultSet ra = pa.executeQuery();
                            while (ra.next()) alts.add(ra.getString("name"));
                        }
                    }
                }
                String uuid = uuidOf(target);
                if (uuid != null) {
                    try (PreparedStatement pw = db.prepareStatement("SELECT COUNT(*) FROM staff_warns WHERE uuid=?")) {
                        pw.setString(1, uuid); ResultSet rw = pw.executeQuery();
                        if (rw.next()) warnCount = rw.getInt(1);
                    }
                    try (PreparedStatement pb = db.prepareStatement("SELECT 1 FROM bans WHERE uuid=?")) {
                        pb.setString(1, uuid); banned = pb.executeQuery().next();
                    }
                    try (PreparedStatement pn = db.prepareStatement("SELECT content FROM notes WHERE uuid=?")) {
                        pn.setString(1, uuid); ResultSet rn = pn.executeQuery();
                        if (rn.next()) note = rn.getString("content");
                    }
                }
            } catch (SQLException e) { e.printStackTrace(); }

            final String fIp = ip, fJoin = join, fLast = lastSeen, fNote = note;
            final List<String> fAlts = new ArrayList<>(alts);
            final int fW = warnCount; final boolean fB = banned;

            sync(() -> {
                Player t = Bukkit.getPlayer(target);
                Inventory inv = Bukkit.createInventory(null, 54, T_INTEL + target);
                fill(inv, glass(Material.GRAY_STAINED_GLASS_PANE));
                stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);

                inv.setItem(4, skull(target,
                        (fB ? "§4§l☠ " : "§6§l") + target,
                        "§7Status: " + (t != null ? "§aONLINE" : "§cOFFLINE"),
                        "§7Banned: " + (fB ? "§cYES" : "§aNo"),
                        "§7Staff Warns: §e" + fW + " §7/ §c" + maxWarns));

                List<String> netLore = new ArrayList<>(Arrays.asList(
                        "§7IP Address: §f" + fIp,
                        "§7First joined: §f" + fJoin,
                        "§7Last seen: §f" + fLast, ""));
                if (fAlts.isEmpty()) netLore.add("§aNo alt accounts detected.");
                else { netLore.add("§c§lALT ACCOUNTS:"); fAlts.forEach(a -> netLore.add("  §c• " + a)); }
                inv.setItem(20, itemL(Material.MAP, "§b§l⬡ Network Analysis", netLore));

                inv.setItem(24, item(Material.WRITABLE_BOOK, "§e§l⬡ Staff Note",
                        "§f" + fNote, "", "§8▸ Click to set/update"));

                if (t != null) {
                    inv.setItem(28, item(Material.CHEST,       "§a§lOpen Inventory",  "§7Inspect player items live."));
                    inv.setItem(29, item(Material.ENDER_CHEST,  "§a§lOpen Enderchest", "§7Inspect enderchest live."));
                    inv.setItem(30, item(Material.COMPASS,      "§a§lTeleport To",     "§7Jump to their location."));
                    inv.setItem(31, item(Material.SPYGLASS,     "§a§lSpectate",        "§7Spectator cam on target.", "§8/sentinel to exit."));
                }

                inv.setItem(37, item(Material.NETHERITE_AXE, "§c§l⬡ Open Punish Menu", "§8▸ Click"));
                inv.setItem(40, back());
                admin.openInventory(inv);
                sfx(admin, Sound.BLOCK_ENDER_CHEST_OPEN);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — PUNISH MENU
    // ══════════════════════════════════════════════════════════════════════════

    private void openPunishMenu(Player admin, String target) {
        Player t = Bukkit.getPlayer(target);
        Inventory inv = Bukkit.createInventory(null, 54, T_PUNISH + target);
        fill(inv, glass(Material.RED_STAINED_GLASS_PANE));
        stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);

        inv.setItem(4, skull(target, "§4§l" + target,
                "§7Status: " + (t != null ? "§aONLINE" : "§cOFFLINE")));

        // Always available (works offline)
        inv.setItem(10, item(Material.RED_BANNER,  "§4§lPERMANENT BAN", "§7Permanent blacklist.", "§8Works while offline."));
        inv.setItem(11, item(Material.MILK_BUCKET, "§a§lUNBAN",          "§7Remove from blacklist.", "§8Works while offline."));

        if (t != null) {
            boolean fr = frozen.contains(t.getUniqueId());
            boolean sj = shadowJail.contains(t.getUniqueId());

            inv.setItem(19, item(Material.TNT,                    "§c§lKICK",          "§7Boot from the server."));
            inv.setItem(20, tog(Material.PACKED_ICE,               "§b§lFREEZE",   fr,  "§7Lock all movement."));
            inv.setItem(21, tog(Material.OBSIDIAN,                 "§8§lSHADOW JAIL",sj,"§7Blindness + movement lock."));
            inv.setItem(23, item(Material.FEATHER,                 "§f§lSKY LAUNCH",   "§7Propel to the sky."));
            inv.setItem(24, item(Material.POISONOUS_POTATO,        "§4§lDRAIN HP",     "§7Set health to 0.5 HP."));
            inv.setItem(25, item(Material.LIGHTNING_ROD,           "§e§lDIVINE SMITE", "§7Real lightning — deals damage."));
            inv.setItem(28, item(Material.BLAZE_POWDER,            "§6§lIGNITE",       "§7Set on fire for 10 seconds."));
            inv.setItem(29, item(Material.WITHER_SKELETON_SKULL,   "§7§lWITHER II",    "§7Wither II for 10 seconds."));
            inv.setItem(30, item(Material.NAUTILUS_SHELL,          "§9§lNAUSEA II",    "§7Nausea II for 15 seconds."));
            inv.setItem(32, item(Material.GOLDEN_APPLE,            "§6§lHEAL",         "§7Restore to full health."));
        } else {
            inv.setItem(22, item(Material.BARRIER, "§8Online Actions Unavailable",
                    "§7Target is offline.", "§7Ban and Unban are still available."));
        }

        inv.setItem(49, item(Material.ARROW, "§7« Back to Intel"));
        admin.openInventory(inv);
        sfx(admin, Sound.BLOCK_ANVIL_LAND);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — STAFF WARN PANEL  (sentinel.owner only)
    // ══════════════════════════════════════════════════════════════════════════

    private void openStaffWarnPanel(Player owner) {
        if (!owner.hasPermission("sentinel.owner")) { noPerms(owner); return; }
        owner.sendMessage(prefix + "§7Loading staff warn panel...");
        async(() -> {
            List<String> names = new ArrayList<>();
            try (Statement s = db.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name FROM players ORDER BY last_seen DESC LIMIT 45")) {
                while (rs.next()) names.add(rs.getString("name"));
            } catch (SQLException e) { e.printStackTrace(); }
            sync(() -> {
                Inventory inv = Bukkit.createInventory(null, 54, T_SWPANEL);
                fill(inv, glass(Material.ORANGE_STAINED_GLASS_PANE));
                stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);
                int slot = 9;
                for (String name : names) {
                    if (slot >= 54) break;
                    inv.setItem(slot++, skull(name, "§f§l" + name, "§8▸ Click to manage warns"));
                }
                if (names.isEmpty()) inv.setItem(22, item(Material.LIME_DYE, "§aNo players registered yet."));
                inv.setItem(4, back());
                owner.openInventory(inv);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — STAFF WARN PROFILE (async)
    // ══════════════════════════════════════════════════════════════════════════

    private void openStaffWarnProfile(Player owner, String target) {
        owner.sendMessage(prefix + "§7Loading warns for §f" + target + "§7...");
        async(() -> {
            List<String[]> warns = new ArrayList<>();
            try {
                String uuid = uuidOf(target);
                if (uuid != null) {
                    try (PreparedStatement ps = db.prepareStatement(
                            "SELECT reason, warned_by, timestamp FROM staff_warns WHERE uuid=? ORDER BY id DESC LIMIT 36")) {
                        ps.setString(1, uuid);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) warns.add(new String[]{
                                rs.getString("reason"), rs.getString("warned_by"), rs.getString("timestamp")});
                    }
                }
            } catch (SQLException e) { e.printStackTrace(); }

            final List<String[]> fW = new ArrayList<>(warns);
            sync(() -> {
                int total = fW.size();
                String status = total >= maxWarns   ? "§4§l☠ DEMOTION AVAILABLE"
                        : total == maxWarns - 1      ? "§c§l⚠ ONE WARN AWAY"
                        : total > 0                  ? "§e⚠ Warned"
                        :                              "§a✔ Clean";

                Inventory inv = Bukkit.createInventory(null, 54, T_SWPROFILE + target);
                fill(inv, glass(Material.ORANGE_STAINED_GLASS_PANE));
                stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);

                inv.setItem(4, skull(target, "§6§l" + target,
                        "§7Warns: §e" + total + " §7/ §c" + maxWarns,
                        "§7Status: " + status));

                int slot = 9;
                for (String[] w : fW) {
                    if (slot >= 45) break;
                    inv.setItem(slot++, item(Material.PAPER, "§e⚠ " + w[0],
                            "§7By: §f" + w[1], "§7Date: §f" + w[2]));
                }
                if (fW.isEmpty()) inv.setItem(22, item(Material.LIME_DYE, "§a✔ No warns on record."));

                inv.setItem(46, item(Material.ORANGE_BANNER, "§6§l+ ISSUE WARN",    "§7You will type the reason in chat.", "§8▸ Click"));
                inv.setItem(47, item(Material.GREEN_DYE,     "§a§lREMOVE LAST",     "§7Delete the most recent warn.", "§8▸ Click"));
                inv.setItem(48, item(Material.LAVA_BUCKET,   "§c§lCLEAR ALL",       "§7Delete all warns for this player.", "§8▸ Click"));
                inv.setItem(50, total >= maxWarns
                        ? item(Material.NETHERITE_AXE, "§4§l☠ EXECUTE DEMOTION",
                        "§7Select the target rank in the next screen.",
                        (lpEnabled ? "§8LuckPerms: §aActive" : "§8LuckPerms: §cNot found"),
                        "§8▸ Click")
                        : glass(Material.BLACK_STAINED_GLASS_PANE));

                inv.setItem(49, back());
                owner.openInventory(inv);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — RANK PICKER (demotion destination)
    // ══════════════════════════════════════════════════════════════════════════

    private void openRankPicker(Player owner, String target) {
        Inventory inv = Bukkit.createInventory(null, 27, T_RANKPICK + target);
        fill(inv, glass(Material.RED_STAINED_GLASS_PANE));
        stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);
        inv.setItem(4, skull(target, "§4§l☠ Demote: " + target,
                "§7Select the destination rank.",
                "§cThis modifies LuckPerms groups.",
                "§cThis action is hard to undo."));

        Material[] mats = {
                Material.NETHERITE_BLOCK, Material.DIAMOND_BLOCK, Material.GOLD_BLOCK,
                Material.IRON_BLOCK, Material.STONE, Material.OAK_PLANKS, Material.DIRT
        };
        int slot = 10;
        for (int i = 0; i < rankHierarchy.size() && slot < 18; i++, slot++) {
            String rank = rankHierarchy.get(i);
            inv.setItem(slot, item(i < mats.length ? mats[i] : Material.STONE,
                    "§f§l" + rank.toUpperCase(),
                    "§7Demote to: §f" + rank,
                    "§8▸ Click to confirm"));
        }
        inv.setItem(22, item(Material.ARROW, "§7« Cancel"));
        owner.openInventory(inv);
        sfx(owner, Sound.BLOCK_PORTAL_TRIGGER);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GUI — AUDIT LOG (async)
    // ══════════════════════════════════════════════════════════════════════════

    private void openAuditLog(Player admin) {
        admin.sendMessage(prefix + "§7Loading audit log...");
        async(() -> {
            List<ItemStack> entries = new ArrayList<>();
            try {
                try (ResultSet rb = db.createStatement().executeQuery(
                        "SELECT b.uuid, p.name, b.reason, b.banned_by, b.timestamp " +
                                "FROM bans b LEFT JOIN players p ON b.uuid=p.uuid ORDER BY b.timestamp DESC LIMIT 20")) {
                    while (rb.next())
                        entries.add(item(Material.RED_BANNER,
                                "§4BAN: §c" + rb.getString("name"),
                                "§7Reason: §f" + rb.getString("reason"),
                                "§7By: §f"     + rb.getString("banned_by"),
                                "§7Date: §f"   + rb.getString("timestamp")));
                }
                try (ResultSet rw = db.createStatement().executeQuery(
                        "SELECT w.uuid, p.name, w.reason, w.warned_by, w.timestamp " +
                                "FROM staff_warns w LEFT JOIN players p ON w.uuid=p.uuid ORDER BY w.id DESC LIMIT 20")) {
                    while (rw.next())
                        entries.add(item(Material.ORANGE_BANNER,
                                "§6STAFF WARN: §e" + rw.getString("name"),
                                "§7Reason: §f" + rw.getString("reason"),
                                "§7By: §f"     + rw.getString("warned_by"),
                                "§7Date: §f"   + rw.getString("timestamp")));
                }
            } catch (SQLException e) { e.printStackTrace(); }

            sync(() -> {
                Inventory inv = Bukkit.createInventory(null, 54, T_AUDIT);
                fill(inv, glass(Material.GRAY_STAINED_GLASS_PANE));
                stripe(inv, Material.BLACK_STAINED_GLASS_PANE, 0, 8);
                entries.forEach(inv::addItem);
                if (entries.isEmpty()) inv.setItem(22, item(Material.LIME_DYE, "§a✔ Audit log is clean."));
                inv.setItem(49, back());
                admin.openInventory(inv);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INVENTORY CLICK ENGINE
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = LegacyComponentSerializer.legacySection().serialize(e.getView().title());
        if (!title.startsWith("§0Sentinel")) return;

        // Always block clicks on the player's own bottom inventory while in our menus
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(p.getInventory())) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);

        ItemStack cl = e.getCurrentItem();
        if (cl == null || cl.getType() == Material.AIR || !cl.hasItemMeta()) return;
        Material m = cl.getType();
        String dn = ChatColor.stripColor(cl.getItemMeta().getDisplayName());

        sfx(p, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);

        // ── HUB ───────────────────────────────────────────────────────────────
        if (title.equals(T_HUB)) {
            switch (m) {
                case COMPASS         -> openPlayerList(p, "Intel");
                case NETHERITE_AXE   -> openPlayerList(p, "Punish");
                case COMMAND_BLOCK   -> openServerOps(p);
                case BOOK            -> openAuditLog(p);
                case ORANGE_BANNER   -> { if (p.hasPermission("sentinel.owner")) openStaffWarnPanel(p); else noPerms(p); }
                case ENDER_EYE       -> doVanish(p);
                case GHAST_SPAWN_EGG -> { togSet(ghostMode, p); openHub(p); p.sendMessage(prefix + (ghostMode.contains(p.getUniqueId()) ? "§aGhost Mode ON" : "§cGhost Mode OFF")); }
                case BEACON          -> { togSet(socialSpy, p);  openHub(p); p.sendMessage(prefix + (socialSpy.contains(p.getUniqueId())  ? "§aSocial Spy ON"  : "§cSocial Spy OFF"));  }
                case WRITABLE_BOOK   -> { togSet(staffChat, p);  openHub(p); p.sendMessage(prefix + (staffChat.contains(p.getUniqueId())  ? "§9Staff Chat ON"  : "§cStaff Chat OFF"));  }
                case GOLDEN_APPLE    -> { togSet(godMode,   p);  openHub(p); p.sendMessage(prefix + (godMode.contains(p.getUniqueId())    ? "§aGod Mode ON"   : "§cGod Mode OFF"));    }
            }
        }

        // ── SERVER OPS ────────────────────────────────────────────────────────
        else if (title.equals(T_OPS)) {
            switch (m) {
                case ARROW     -> openHub(p);
                case IRON_DOOR -> { maintenance = !maintenance; openServerOps(p); staffAnnounce(prefix + "Maintenance: " + (maintenance ? "§aON" : "§cOFF") + " §7— §f" + p.getName()); }
                case PAPER     -> { chatLocked  = !chatLocked;  openServerOps(p); staffAnnounce(prefix + "Chat: " + (chatLocked ? "§cLOCKED" : "§aOPEN") + " §7— §f" + p.getName()); }
                case SUNFLOWER -> { p.getWorld().setTime(1000); p.getWorld().setStorm(false); p.sendMessage(prefix + "§aDay set, storm cleared."); }
                case BARRIER   -> {
                    long count = p.getWorld().getEntities().stream()
                            .filter(ent -> { String t = ent.getType().name(); return t.equals("DROPPED_ITEM") || t.equals("ARROW") || t.equals("SNOWBALL"); })
                            .peek(Entity::remove).count();
                    p.sendMessage(prefix + "§aRemoved §f" + count + " §aentities.");
                }
            }
        }

        // ── PLAYER LIST ───────────────────────────────────────────────────────
        else if (title.startsWith(T_SELECT)) {
            if (m == Material.ARROW)       { openHub(p); return; }
            if (m == Material.PLAYER_HEAD) {
                if (title.contains("Intel")) openDossier(p, dn);
                else openPunishMenu(p, dn);
            }
        }

        // ── INTEL / DOSSIER ───────────────────────────────────────────────────
        else if (title.startsWith(T_INTEL)) {
            String target = title.substring(T_INTEL.length());
            Player t = Bukkit.getPlayer(target);
            if (m == Material.ARROW)         { openPlayerList(p, "Intel"); return; }
            if (m == Material.NETHERITE_AXE) { openPunishMenu(p, target);  return; }
            if (m == Material.WRITABLE_BOOK) { requestInput(p, "note", target); p.closeInventory(); return; }
            if (t != null) {
                switch (m) {
                    case CHEST       -> { p.openInventory(t.getInventory());  p.sendMessage(prefix + "§7InvSee: §f" + t.getName()); }
                    case ENDER_CHEST -> { p.openInventory(t.getEnderChest()); p.sendMessage(prefix + "§7EnderChest: §f" + t.getName()); }
                    case COMPASS     -> { p.teleport(t.getLocation()); sfx(p, Sound.ENTITY_ENDERMAN_TELEPORT); p.sendMessage(prefix + "§7Teleported to §f" + t.getName()); }
                    case SPYGLASS    -> doSpectate(p, t);
                }
            } else p.sendMessage(prefix + "§c" + target + " is offline.");
        }

        // ── PUNISH MENU ───────────────────────────────────────────────────────
        else if (title.startsWith(T_PUNISH)) {
            String target = title.substring(T_PUNISH.length());
            Player t = Bukkit.getPlayer(target);
            if (m == Material.ARROW)       { openDossier(p, target); return; }
            if (m == Material.RED_BANNER)  { requestInput(p, "ban", target); p.closeInventory(); return; }
            if (m == Material.MILK_BUCKET) { doUnban(target, p); return; }
            if (t == null) { p.sendMessage(prefix + "§c" + target + " went offline."); return; }

            int smiteCount  = getConfig().getInt("punishments.divine-smite.strike-count", 2);
            int smiteDelay  = getConfig().getInt("punishments.divine-smite.delay-ticks", 8);
            int igniteTicks = getConfig().getInt("punishments.ignite.ticks", 200);
            int witherDur   = getConfig().getInt("punishments.wither.duration-ticks", 200);
            int witherAmp   = getConfig().getInt("punishments.wither.amplifier", 1);
            int nauseaDur   = getConfig().getInt("punishments.nausea.duration-ticks", 300);
            int nauseaAmp   = getConfig().getInt("punishments.nausea.amplifier", 1);
            double launchY  = getConfig().getDouble("punishments.sky-launch.velocity-y", 5.5);

            switch (m) {
                case TNT -> {
                    t.kickPlayer(color(kickMessage));
                    if (broadcastKicks) staffAnnounce(prefix + "§c" + p.getName() + " §7kicked §c" + t.getName());
                    sfx(p, Sound.ENTITY_TNT_PRIMED);
                }
                case PACKED_ICE       -> { doFreeze(t, p);     openPunishMenu(p, target); }
                case OBSIDIAN         -> { doShadowJail(t, p); openPunishMenu(p, target); }
                case FEATHER          -> { t.setVelocity(t.getVelocity().setY(launchY)); sfx(t, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH); p.sendMessage(prefix + "§fSky Launch → §f" + t.getName()); }
                case POISONOUS_POTATO -> { t.setHealth(1.0); sfx(t, Sound.ENTITY_WARDEN_HEARTBEAT); p.sendMessage(prefix + "§4Drained HP of §f" + t.getName()); }
                case LIGHTNING_ROD    -> {
                    for (int i = 0; i < smiteCount; i++) {
                        final int fi = i;
                        Bukkit.getScheduler().runTaskLater(this, () -> { if (t.isOnline()) t.getWorld().strikeLightning(t.getLocation()); }, (long) fi * smiteDelay);
                    }
                    p.sendMessage(prefix + "§e⚡ Divine Smite on §f" + t.getName());
                }
                case BLAZE_POWDER          -> { t.setFireTicks(igniteTicks); p.sendMessage(prefix + "§6Ignited §f" + t.getName()); }
                case WITHER_SKELETON_SKULL -> { t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, witherDur, witherAmp)); p.sendMessage(prefix + "§7Wither applied to §f" + t.getName()); }
                case NAUTILUS_SHELL        -> { t.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nauseaDur, nauseaAmp)); p.sendMessage(prefix + "§9Nausea applied to §f" + t.getName()); }
                case GOLDEN_APPLE          -> { t.setHealth(t.getMaxHealth()); sfx(t, Sound.ENTITY_PLAYER_LEVELUP); p.sendMessage(prefix + "§aHealed §f" + t.getName()); }
            }
        }

        // ── STAFF WARN PANEL ──────────────────────────────────────────────────
        else if (title.equals(T_SWPANEL)) {
            if (m == Material.ARROW)       { openHub(p); return; }
            if (m == Material.PLAYER_HEAD) openStaffWarnProfile(p, dn);
        }

        // ── STAFF WARN PROFILE ────────────────────────────────────────────────
        else if (title.startsWith(T_SWPROFILE)) {
            String target = title.substring(T_SWPROFILE.length());
            if (m == Material.ARROW)          { openStaffWarnPanel(p); return; }
            if (m == Material.ORANGE_BANNER)  { requestInput(p, "staffwarn", target); p.closeInventory(); return; }
            if (m == Material.GREEN_DYE)      { doRemoveLastWarn(target, p); Bukkit.getScheduler().runTaskLater(this, () -> openStaffWarnProfile(p, target), 15L); return; }
            if (m == Material.LAVA_BUCKET)    { doClearAllWarns(target, p); Bukkit.getScheduler().runTaskLater(this, () -> openStaffWarnProfile(p, target), 15L); return; }
            if (m == Material.NETHERITE_AXE)  { p.closeInventory(); openRankPicker(p, target); }
        }

        // ── RANK PICKER ───────────────────────────────────────────────────────
        else if (title.startsWith(T_RANKPICK)) {
            String target = title.substring(T_RANKPICK.length());
            if (m == Material.ARROW) { openStaffWarnProfile(p, target); return; }
            String rank = dn.toLowerCase().trim();
            if (rankHierarchy.contains(rank)) { executeDemotion(target, rank, p); p.closeInventory(); }
        }

        // ── AUDIT LOG ─────────────────────────────────────────────────────────
        else if (title.equals(T_AUDIT)) {
            if (m == Material.ARROW) openHub(p);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MODERATION LOGIC
    // ══════════════════════════════════════════════════════════════════════════

    private void doBan(String target, String reason, Player admin) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> admin.sendMessage(prefix + "§cPlayer not found in the database. They must have joined at least once.")); return; }
                try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO bans VALUES (?,?,?,?)")) {
                    ps.setString(1, uuid); ps.setString(2, reason);
                    ps.setString(3, admin.getName()); ps.setString(4, now());
                    ps.executeUpdate();
                }
                sync(() -> {
                    Player t = Bukkit.getPlayer(target);
                    String screen = color(banMessage.replace("%reason%", reason).replace("%player%", target));
                    if (t != null) t.kickPlayer(screen);
                    if (broadcastBans) staffAnnounce(prefix + "§c§lBAN §8» §f" + target + " §7by §f" + admin.getName() + "§7 — " + reason);
                    admin.sendMessage(prefix + "§c§lBANNED: §f" + target);
                    sfx(admin, Sound.ENTITY_WITHER_SPAWN);
                });
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void doUnban(String target, Player admin) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> admin.sendMessage(prefix + "§cPlayer not found.")); return; }
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM bans WHERE uuid=?")) {
                    ps.setString(1, uuid); int rows = ps.executeUpdate();
                    sync(() -> {
                        if (rows > 0) { admin.sendMessage(prefix + "§a§lUNBANNED: §f" + target); staffAnnounce(prefix + "§a" + target + " §7unbanned by §f" + admin.getName()); }
                        else admin.sendMessage(prefix + "§c" + target + " is not banned.");
                    });
                }
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void doFreeze(Player t, Player admin) {
        if (frozen.remove(t.getUniqueId())) {
            frozenAt.remove(t.getUniqueId());
            t.sendMessage(prefix + "§aYou have been unfrozen.");
            admin.sendMessage(prefix + "§aUnfrozen: §f" + t.getName());
            sfx(t, Sound.BLOCK_GLASS_BREAK);
        } else {
            frozen.add(t.getUniqueId());
            frozenAt.put(t.getUniqueId(), t.getLocation().clone());
            t.sendMessage(prefix + "§b§lYou have been frozen by staff. Do not disconnect.");
            admin.sendMessage(prefix + "§bFrozen: §f" + t.getName());
            if (getConfig().getBoolean("broadcast.freeze", true))
                staffAnnounce(prefix + "§b" + t.getName() + " §7frozen by §f" + admin.getName());
            sfx(t, Sound.ENTITY_ELDER_GUARDIAN_CURSE);
        }
    }

    private void doShadowJail(Player t, Player admin) {
        if (shadowJail.remove(t.getUniqueId())) {
            frozenAt.remove(t.getUniqueId());
            t.removePotionEffect(PotionEffectType.BLINDNESS);
            t.sendMessage(prefix + "§aYou have been released from Shadow Jail.");
            admin.sendMessage(prefix + "§aShadow Jail OFF: §f" + t.getName());
        } else {
            shadowJail.add(t.getUniqueId());
            frozenAt.put(t.getUniqueId(), t.getLocation().clone());
            t.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 999999, 1, false, false));
            t.sendMessage(prefix + "§8§lYou are in Shadow Jail — under staff investigation.");
            admin.sendMessage(prefix + "§8Shadow Jail ON: §f" + t.getName());
            if (getConfig().getBoolean("broadcast.shadow-jail", true))
                staffAnnounce(prefix + "§8" + t.getName() + " §7shadow jailed by §f" + admin.getName());
        }
    }

    private void doVanish(Player p) {
        if (vanished.remove(p.getUniqueId())) {
            Bukkit.getOnlinePlayers().forEach(a -> a.showPlayer(this, p));
            p.sendMessage(prefix + "§cVanish OFF — You are now visible.");
        } else {
            vanished.add(p.getUniqueId());
            Bukkit.getOnlinePlayers().forEach(a -> {
                if (!a.hasPermission("sentinel.use")) a.hidePlayer(this, p);
            });
            p.sendMessage(prefix + "§aVanish ON — You are now invisible.");
        }
        sfx(p, Sound.ENTITY_ENDERMAN_TELEPORT);
        openHub(p);
    }

    private void doSpectate(Player admin, Player target) {
        prevGM.put(admin.getUniqueId(), admin.getGameMode());
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target);
        admin.sendMessage(prefix + "§7Spectating §f" + target.getName() + "§7. Use §f/sentinel §7to exit.");
    }

    private void doAddStaffWarn(String target, String reason, Player owner) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> owner.sendMessage(prefix + "§cPlayer not found.")); return; }

                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO staff_warns (uuid,reason,warned_by,timestamp) VALUES (?,?,?,?)")) {
                    ps.setString(1, uuid); ps.setString(2, reason);
                    ps.setString(3, owner.getName()); ps.setString(4, now());
                    ps.executeUpdate();
                }

                int total;
                try (PreparedStatement pc = db.prepareStatement("SELECT COUNT(*) FROM staff_warns WHERE uuid=?")) {
                    pc.setString(1, uuid); ResultSet rc = pc.executeQuery();
                    total = rc.next() ? rc.getInt(1) : 0;
                }
                final int ft = total;

                sync(() -> {
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) {
                        t.sendMessage(""); t.sendMessage("§e§l⚠ OFFICIAL STAFF WARNING ⚠");
                        t.sendMessage("§7Issued by: §f" + owner.getName());
                        t.sendMessage("§7Reason: §f" + reason);
                        t.sendMessage("§7Warns: §e" + ft + " §7/ §c" + maxWarns);
                        if (ft >= maxWarns) t.sendMessage("§c§l⚠ You have reached the warn limit. A demotion may follow.");
                        t.sendMessage(""); sfx(t, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.3f);
                    }
                    if (broadcastWarns)
                        staffAnnounce(prefix + "§6§lSTAFF WARN §8» §f" + target +
                                " §7warn §e#" + ft + " §7by §f" + owner.getName() +
                                "§7 — " + reason + (ft >= maxWarns ? " §c— DEMOTION AVAILABLE" : ""));
                    owner.sendMessage(prefix + "§6Warn issued to §f" + target + "§6. Total: §e" + ft + "§6/§c" + maxWarns);
                    if (ft >= maxWarns) Bukkit.getScheduler().runTaskLater(this, () -> openRankPicker(owner, target), 20L);
                });
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void doRemoveLastWarn(String target, Player owner) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> owner.sendMessage(prefix + "§cPlayer not found.")); return; }
                try (PreparedStatement ps = db.prepareStatement(
                        "DELETE FROM staff_warns WHERE id=(SELECT MAX(id) FROM staff_warns WHERE uuid=?)")) {
                    ps.setString(1, uuid); int rows = ps.executeUpdate();
                    sync(() -> owner.sendMessage(prefix + (rows > 0 ? "§aRemoved last warn from §f" + target : "§c" + target + " has no warns.")));
                }
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void doClearAllWarns(String target, Player owner) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> owner.sendMessage(prefix + "§cPlayer not found.")); return; }
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM staff_warns WHERE uuid=?")) {
                    ps.setString(1, uuid); int rows = ps.executeUpdate();
                    sync(() -> owner.sendMessage(prefix + "§aCleared §f" + rows + " §awarns from §f" + target));
                }
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    private void doSetNote(String target, String note, Player admin) {
        async(() -> {
            try {
                String uuid = uuidOf(target);
                if (uuid == null) { sync(() -> admin.sendMessage(prefix + "§cPlayer not found.")); return; }
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO notes (uuid,content,author,timestamp) VALUES (?,?,?,?) " +
                                "ON CONFLICT(uuid) DO UPDATE SET content=excluded.content, author=excluded.author, timestamp=excluded.timestamp")) {
                    ps.setString(1, uuid); ps.setString(2, note);
                    ps.setString(3, admin.getName()); ps.setString(4, now());
                    ps.executeUpdate();
                }
                sync(() -> admin.sendMessage(prefix + "§aNote saved for §f" + target));
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LUCKPERMS DEMOTION
    // ══════════════════════════════════════════════════════════════════════════

    private void executeDemotion(String targetName, String newRank, Player owner) {
        if (!lpEnabled) {
            owner.sendMessage(prefix + "§cLuckPerms is not installed. Please change the rank manually with /lp.");
            return;
        }
        async(() -> {
            try {
                String uuidStr = uuidOf(targetName);
                if (uuidStr == null) { sync(() -> owner.sendMessage(prefix + "§cPlayer not found in database.")); return; }
                UUID targetUuid = UUID.fromString(uuidStr);

                luckPerms.getUserManager().loadUser(targetUuid).thenAcceptAsync(user -> {
                    if (user == null) { sync(() -> owner.sendMessage(prefix + "§cFailed to load LuckPerms user.")); return; }

                    // Remove all known ranks, then assign new one
                    rankHierarchy.forEach(r -> user.data().remove(InheritanceNode.builder(r).build()));
                    user.data().add(InheritanceNode.builder(newRank).build());

                    luckPerms.getUserManager().saveUser(user).thenRun(() -> sync(() -> {
                        Player t = Bukkit.getPlayer(targetUuid);
                        if (t != null) {
                            t.sendMessage(""); t.sendMessage("§c§l⚠ YOU HAVE BEEN DEMOTED ⚠");
                            t.sendMessage("§7New rank: §f" + newRank.toUpperCase());
                            t.sendMessage("§7Reason: Accumulated §e" + maxWarns + " §7staff warns.");
                            t.sendMessage(""); sfx(t, Sound.ENTITY_WITHER_DEATH);
                        }
                        if (getConfig().getBoolean("broadcast.demotions", true))
                            staffAnnounce(prefix + "§c§lDEMOTION §8» §f" + targetName +
                                    " §7demoted to §f" + newRank.toUpperCase() +
                                    " §7by §f" + owner.getName());
                        owner.sendMessage(prefix + "§aDemotion applied: §f" + targetName + " §a→ §f" + newRank.toUpperCase());
                        sfx(owner, Sound.UI_TOAST_CHALLENGE_COMPLETE);

                        // Auto-clear warns after demotion (configurable)
                        if (getConfig().getBoolean("staff-warns.clear-after-demotion", true)) {
                            async(() -> {
                                try {
                                    String u = uuidOf(targetName);
                                    if (u != null) {
                                        try (PreparedStatement ps = db.prepareStatement("DELETE FROM staff_warns WHERE uuid=?")) {
                                            ps.setString(1, u); ps.executeUpdate();
                                        }
                                    }
                                } catch (SQLException ex) { ex.printStackTrace(); }
                            });
                        }
                    }));
                });
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CHAT INPUT (reason prompts via chat)
    // ══════════════════════════════════════════════════════════════════════════

    private void requestInput(Player p, String type, String target) {
        pendingInput.put(p.getUniqueId(), new String[]{type, target});
        p.sendMessage(prefix + switch (type) {
            case "ban"       -> "§cType the §lban reason §cfor §f" + target + "§c. Type §fcancel §cto abort.";
            case "staffwarn" -> "§6Type the §lstaff warn reason §6for §f" + target + "§6. Type §fcancel §6to abort.";
            case "note"      -> "§7Type the §lstaff note §7for §f" + target + "§7. Type §fcancel §7to abort.";
            default          -> "§7Enter text or type §fcancel§7:";
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  EVENTS
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());

        // Pending reason input (ban/staffwarn/note)
        if (pendingInput.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            String[] data = pendingInput.remove(p.getUniqueId());
            if (msg.equalsIgnoreCase("cancel")) { p.sendMessage(prefix + "§7Action cancelled."); return; }
            switch (data[0]) {
                case "ban"       -> doBan(data[1], msg, p);
                case "staffwarn" -> doAddStaffWarn(data[1], msg, p);
                case "note"      -> doSetNote(data[1], msg, p);
            }
            return;
        }

        // Staff chat channel
        if (staffChat.contains(p.getUniqueId())) {
            e.setCancelled(true);
            String fmt = "§9§l[STAFF] §f" + p.getName() + " §8» §7" + msg;
            Bukkit.getOnlinePlayers().stream()
                    .filter(u -> u.hasPermission("sentinel.use"))
                    .forEach(u -> u.sendMessage(fmt));
            getLogger().info("[STAFF] " + p.getName() + ": " + msg);
            return;
        }

        // Chat lock
        if (chatLocked && !p.hasPermission("sentinel.use")) {
            e.setCancelled(true);
            p.sendMessage(prefix + "§cChat is currently locked by staff.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrivateMsg(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().toLowerCase();
        if (!raw.startsWith("/msg ") && !raw.startsWith("/tell ") && !raw.startsWith("/w ") && !raw.startsWith("/r ")) return;
        String spied = "§d§l[SocialSpy] §8" + e.getPlayer().getName() + " §7» §f" + e.getMessage();
        Bukkit.getOnlinePlayers().stream()
                .filter(a -> socialSpy.contains(a.getUniqueId()) && !a.equals(e.getPlayer()))
                .forEach(a -> a.sendMessage(spied));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        // Skip head-rotation-only movements
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        if (frozen.contains(p.getUniqueId()) || shadowJail.contains(p.getUniqueId())) {
            Location lock = frozenAt.getOrDefault(p.getUniqueId(), from);
            Location stay = lock.clone();
            // Allow camera rotation, lock position
            if (getConfig().getBoolean("freeze.allow-camera-rotation", true)) {
                stay.setYaw(to.getYaw()); stay.setPitch(to.getPitch());
            }
            e.setTo(stay);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && godMode.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        try (PreparedStatement ps = db.prepareStatement("SELECT reason FROM bans WHERE uuid=?")) {
            ps.setString(1, e.getUniqueId().toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String screen = color(banMessage
                        .replace("%reason%", rs.getString("reason"))
                        .replace("%player%", e.getName()));
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
            }
        } catch (SQLException ex) { ex.printStackTrace(); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (maintenance && !p.hasPermission("sentinel.use")) {
            p.kickPlayer(color(maintenanceMessage));
            return;
        }

        // Re-apply vanish
        if (vanished.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () ->
                    Bukkit.getOnlinePlayers().forEach(a -> { if (!a.hasPermission("sentinel.use")) a.hidePlayer(this, p); }), 1L);
        }
        // Hide vanished staff from this player
        if (!p.hasPermission("sentinel.use")) {
            vanished.forEach(v -> { Player vp = Bukkit.getPlayer(v); if (vp != null) p.hidePlayer(this, vp); });
        }
        // Re-apply freeze
        if (frozen.contains(p.getUniqueId())) {
            frozenAt.put(p.getUniqueId(), p.getLocation().clone());
            p.sendMessage(prefix + color(getConfig().getString("messages.frozen-login", "&bYou are still frozen. Please wait for a staff member.")));
        }
        // Re-apply shadow jail
        if (shadowJail.contains(p.getUniqueId())) {
            frozenAt.put(p.getUniqueId(), p.getLocation().clone());
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 999999, 1, false, false));
            p.sendMessage(prefix + color(getConfig().getString("messages.shadow-jail-login", "&8You are still under staff investigation.")));
        }

        // Upsert player data (async)
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "unknown";
        async(() -> {
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO players (uuid,name,ip,first_join,last_seen) VALUES (?,?,?,?,?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, ip=excluded.ip, last_seen=excluded.last_seen")) {
                ps.setString(1, p.getUniqueId().toString()); ps.setString(2, p.getName());
                ps.setString(3, ip); ps.setString(4, now()); ps.setString(5, now());
                ps.executeUpdate();
            } catch (SQLException ex) { ex.printStackTrace(); }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prevGM.remove(e.getPlayer().getUniqueId());
        async(() -> {
            try (PreparedStatement ps = db.prepareStatement("UPDATE players SET last_seen=? WHERE uuid=?")) {
                ps.setString(1, now()); ps.setString(2, e.getPlayer().getUniqueId().toString());
                ps.executeUpdate();
            } catch (SQLException ex) { ex.printStackTrace(); }
        });
    }

    @EventHandler
    public void onGhostInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!ghostMode.contains(e.getPlayer().getUniqueId())) return;
        if (e.getClickedBlock() != null && e.getClickedBlock().getState() instanceof Container c) {
            e.setCancelled(true);
            e.getPlayer().openInventory(c.getInventory());
            if (getConfig().getBoolean("ghost.notify-on-inspect", true))
                actionBar(e.getPlayer(), color(getConfig().getString("ghost.notify-message", "&8[Ghost] Silently inspecting container...")));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════════

    private void staffAnnounce(String msg) {
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("sentinel.use"))
                .forEach(p -> p.sendMessage(msg));
        getLogger().info(ChatColor.stripColor(msg));
    }

    private void noPerms(Player p) { p.sendMessage(prefix + "§cYou don't have permission to do that."); }
    private void log(Level lvl, String msg) { getLogger().log(lvl, msg); }
    private void actionBar(Player p, String msg) { p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(msg)); }
    private void sfx(Player p, Sound s) { sfx(p, s, 1f, 1f); }
    private void sfx(Player p, Sound s, float v, float pit) { p.playSound(p.getLocation(), s, v, pit); }
    private String color(String s) { return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s); }

    private void togSet(Set<UUID> set, Player p) {
        if (!set.remove(p.getUniqueId())) set.add(p.getUniqueId());
    }

    // ── Item Builders ─────────────────────────────────────────────────────────

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m); ItemMeta meta = i.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name); meta.setLore(Arrays.asList(lore));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            i.setItemMeta(meta);
        }
        return i;
    }

    private ItemStack itemL(Material m, String name, List<String> lore) {
        ItemStack i = new ItemStack(m); ItemMeta meta = i.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name); meta.setLore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            i.setItemMeta(meta);
        }
        return i;
    }

    private ItemStack tog(Material m, String name, boolean on, String... desc) {
        List<String> lore = new ArrayList<>(Arrays.asList(desc));
        lore.add(""); lore.add("§7Status: " + (on ? "§a● ACTIVE" : "§c● INACTIVE"));
        lore.add(on ? "§8▸ Click to disable" : "§8▸ Click to enable");
        return itemL(m, name, lore);
    }

    private ItemStack skull(String owner, String displayName, String... lore) {
        ItemStack i = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) i.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            meta.setDisplayName(displayName); meta.setLore(Arrays.asList(lore));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            i.setItemMeta(meta);
        }
        return i;
    }

    private ItemStack glass(Material m) { return item(m, " "); }
    private ItemStack back()            { return item(Material.ARROW, "§7« Back"); }

    private void fill(Inventory inv, ItemStack g) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g.clone());
    }

    private void stripe(Inventory inv, Material m, int from, int to) {
        ItemStack g = glass(m);
        for (int i = from; i <= to; i++) inv.setItem(i, g.clone());
    }

    private void printBanner() {
        String[] lines = {
                "",
                "  ███████╗███████╗███╗   ██╗████████╗██╗███╗   ██╗███████╗██╗     ",
                "  ██╔════╝██╔════╝████╗  ██║╚══██╔══╝██║████╗  ██║██╔════╝██║     ",
                "  ███████╗█████╗  ██╔██╗ ██║   ██║   ██║██╔██╗ ██║█████╗  ██║     ",
                "  ╚════██║██╔══╝  ██║╚██╗██║   ██║   ██║██║╚██╗██║██╔══╝  ██║     ",
                "  ███████║███████╗██║ ╚████║   ██║   ██║██║ ╚████║███████╗███████╗ ",
                "  ╚══════╝╚══════╝╚═╝  ╚═══╝   ╚═╝   ╚═╝╚═╝  ╚═══╝╚══════╝╚══════╝",
                "",
                "  SentinelMC v" + VERSION + " — by MarcialKT",
                "  Paper 1.20-1.21 | LuckPerms: " + (lpEnabled ? "Active" : "Not found (demotion disabled)"),
                ""
        };
        for (String l : lines) log(Level.INFO, l);
    }
}