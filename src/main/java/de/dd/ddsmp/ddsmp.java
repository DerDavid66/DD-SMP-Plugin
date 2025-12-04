package de.dd.ddsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.help.HelpTopic;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class ddsmp extends JavaPlugin implements Listener, TabCompleter {

    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<UUID, TpaRequest> tpaRequests = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final Map<UUID, BukkitRunnable> teleportTasks = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Location> deathPoints = new HashMap<>();
    private final Map<UUID, String> currentChunkOwner = new HashMap<>();
    private final Map<UUID, Long> tpaCooldowns = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tpaExpirationTasks = new HashMap<>();
    private final Map<UUID, Long> combatEndTime = new HashMap<>();
    private final Map<UUID, BukkitRunnable> combatActionBarTasks = new HashMap<>();
    private final Map<UUID, UUID> lastOpponent = new HashMap<>();
    private final Set<UUID> combatLogDeaths = new HashSet<>();
    private final Map<UUID, Long> spawnCooldowns = new HashMap<>();

    private File homesFile;
    private FileConfiguration homesConfig;
    private File claimsFile;
    private FileConfiguration claimsConfig;
    private File muteFile;
    private FileConfiguration muteConfig;
    private final String prefix = "§bDD SMP §8» ";
    private final String UNKNOWN_COMMAND_MESSAGE = "§bDD SMP §8» §cDieser Befehl existiert nicht!";

    private final long TPA_COOLDOWN_MILLIS = 15000;
    private final long SPAWN_COOLDOWN_MILLIS = 15000;
    private final int TELEPORT_COUNTDOWN_SECONDS = 3;
    private final int TPA_EXPIRATION_TICKS = 20 * 60;
    private final int COMBAT_DURATION_SECONDS = 20;

    private static class TpaRequest {
        UUID sender;
        TpaRequest(UUID sender) { this.sender = sender; }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        createHomesFile();
        createClaimsFile();
        createMuteFile();
        loadHomes();
        getLogger().info("DD SMP Plugin aktiviert!");
    }

    @Override
    public void onDisable() {
        saveHomes();
        saveClaims();
        saveMuteFile();
        combatActionBarTasks.values().forEach(BukkitRunnable::cancel);
    }

    private void createHomesFile() {
        homesFile = new File(getDataFolder(), "homes.yml");
        if (!homesFile.exists()) {
            homesFile.getParentFile().mkdirs();
            saveResource("homes.yml", false);
        }
        homesConfig = YamlConfiguration.loadConfiguration(homesFile);
    }

    private void createClaimsFile() {
        claimsFile = new File(getDataFolder(), "claims.yml");
        if (!claimsFile.exists()) {
            claimsFile.getParentFile().mkdirs();
            try { claimsFile.createNewFile(); } catch (IOException ignored) {}
        }
        claimsConfig = YamlConfiguration.loadConfiguration(claimsFile);
    }

    private void createMuteFile() {
        muteFile = new File(getDataFolder(), "mute.yml");
        if (!muteFile.exists()) {
            muteFile.getParentFile().mkdirs();
            try { muteFile.createNewFile(); } catch (IOException ignored) {}
        }
        muteConfig = YamlConfiguration.loadConfiguration(muteFile);
    }

    private void saveClaims() {
        try { claimsConfig.save(claimsFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void saveMuteFile() {
        try { muteConfig.save(muteFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void loadHomes() {
        if (homesConfig.getConfigurationSection("homes") == null) return;
        for (String uuidStr : homesConfig.getConfigurationSection("homes").getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            Map<String, Location> playerHomes = new HashMap<>();
            for (String homeName : homesConfig.getConfigurationSection("homes." + uuidStr).getKeys(false)) {
                String path = "homes." + uuidStr + "." + homeName;
                World world = Bukkit.getWorld(homesConfig.getString(path + ".world"));
                double x = homesConfig.getDouble(path + ".x");
                double y = homesConfig.getDouble(path + ".y");
                double z = homesConfig.getDouble(path + ".z");
                float yaw = (float) homesConfig.getDouble(path + ".yaw");
                float pitch = (float) homesConfig.getDouble(path + ".pitch");
                if (world != null) playerHomes.put(homeName, new Location(world, x, y, z, yaw, pitch));
            }
            homes.put(uuid, playerHomes);
        }
    }

    private void saveHomes() {
        for (UUID uuid : homes.keySet()) {
            for (String name : homes.get(uuid).keySet()) {
                Location loc = homes.get(uuid).get(name);
                String path = "homes." + uuid + "." + name;
                homesConfig.set(path + ".world", loc.getWorld().getName());
                homesConfig.set(path + ".x", loc.getX());
                homesConfig.set(path + ".y", loc.getY());
                homesConfig.set(path + ".z", loc.getZ());
                homesConfig.set(path + ".yaw", loc.getYaw());
                homesConfig.set(path + ".pitch", loc.getPitch());
            }
        }
        try { homesConfig.save(homesFile); } catch (IOException e) { e.printStackTrace(); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!event.isCancelled()) {
            String command = event.getMessage().split(" ")[0].substring(1);
            if (command.contains(":")) command = command.split(":")[1];
            HelpTopic htopic = Bukkit.getServer().getHelpMap().getHelpTopic("/" + command);
            if (htopic == null) {
                p.sendMessage(UNKNOWN_COMMAND_MESSAGE);
                event.setCancelled(true);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix + "§cNur Spieler können diesen Befehl verwenden!");
            return true;
        }
        Player p = (Player) sender;

        if (cmd.getName().toLowerCase().equals("home") || cmd.getName().toLowerCase().equals("sethome")
                || cmd.getName().toLowerCase().equals("delhome") || cmd.getName().toLowerCase().equals("homes")
                || cmd.getName().toLowerCase().equals("tpa") || cmd.getName().toLowerCase().equals("tpaccept")
                || cmd.getName().toLowerCase().equals("tpadeny") || cmd.getName().toLowerCase().equals("spawn")
                || cmd.getName().equalsIgnoreCase("ec") || cmd.getName().equalsIgnoreCase("enderchest")) {
            if (isInCombat(p)) {
                p.sendMessage(prefix + "§cDu kannst das nicht im Kampf tun!");
                return true;
            }
        }

        if (cmd.getName().equalsIgnoreCase("ec") || cmd.getName().equalsIgnoreCase("enderchest")) {
            p.openInventory(p.getEnderChest());
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("chunk")) {
            if (args.length == 0) { p.sendMessage(prefix + "§dVerwendung: §e/chunk <claim | unclaim | trust | untrust | info | list>"); return true; }
            switch (args[0].toLowerCase()) {
                case "claim": claimChunk(p); return true;
                case "unclaim": unclaimChunk(p); return true;
                case "trust": if (args.length != 2) { p.sendMessage(prefix + "§dVerwendung: §e/chunk trust <Spieler>"); return true; } trustChunk(p, args[1]); return true;
                case "untrust": if (args.length != 2) { p.sendMessage(prefix + "§dVerwendung: §e/chunk untrust <Spieler>"); return true; } untrustChunk(p, args[1]); return true;
                case "info": chunkInfo(p); return true;
                case "list": listChunks(p); return true;
                default: p.sendMessage(prefix + "§dVerwendung: §e/chunk <claim | unclaim | trust | untrust | info | list>"); return true;
            }
        }

        switch (cmd.getName().toLowerCase()) {
            case "sethome": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/sethome <name>"); return true; } setHome(p, args[0]); return true;
            case "home": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/home <name>"); return true; } teleportHome(p, args[0]); return true;
            case "delhome": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/delhome <name>"); return true; } deleteHome(p, args[0]); return true;
            case "homes": listHomes(p); return true;
            case "tpa": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/tpa <Spieler>"); return true; } sendTpaRequest(p, args[0]); return true;
            case "tpaccept":
                if (args.length == 0) {
                    if (!tpaRequests.containsKey(p.getUniqueId())) {
                        p.sendMessage(prefix + "§cKeine offene TPA-Anfrage!");
                        return true;
                    }
                    TpaRequest request = tpaRequests.get(p.getUniqueId());
                    Player senderPlayer = Bukkit.getPlayer(request.sender);
                    if (senderPlayer == null) {
                        p.sendMessage(prefix + "§cDie Anfrage ist ungültig (Sender ist offline).");
                        cleanUpTpaRequest(p.getUniqueId());
                        return true;
                    }
                    acceptTpa(p, senderPlayer.getName());
                    return true;
                } else if (args.length == 1) {
                    acceptTpa(p, args[0]);
                    return true;
                } else {
                    p.sendMessage(prefix + "§dVerwendung: §e/tpaccept <Spieler>");
                    return true;
                }
            case "tpadeny":
                if (args.length == 0) {
                    if (!tpaRequests.containsKey(p.getUniqueId())) {
                        p.sendMessage(prefix + "§cKeine offene TPA-Anfrage!");
                        return true;
                    }
                    TpaRequest request = tpaRequests.get(p.getUniqueId());
                    Player senderPlayer = Bukkit.getPlayer(request.sender);
                    if (senderPlayer == null) {
                        p.sendMessage(prefix + "§cDie Anfrage ist ungültig (Sender ist offline).");
                        cleanUpTpaRequest(p.getUniqueId());
                        return true;
                    }
                    denyTpa(p, senderPlayer.getName());
                    return true;
                } else if (args.length == 1) {
                    denyTpa(p, args[0]);
                    return true;
                } else {
                    p.sendMessage(prefix + "§dVerwendung: §e/tpadeny <Spieler>");
                    return true;
                }
            case "spawn":
                teleportToSpawn(p);
                return true;
            case "mute":
                if (!p.hasPermission("ddsmp.admin")) { p.sendMessage(prefix + "§cKeine Berechtigung!"); return true; }
                if (args.length < 2) { p.sendMessage(prefix + "§dVerwendung: §e/mute <Spieler> <Grund>"); return true; }
                mutePlayer(p, args);
                return true;
            case "unmute":
                if (!p.hasPermission("ddsmp.admin")) { p.sendMessage(prefix + "§cKeine Berechtigung!"); return true; }
                if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/unmute <Spieler>"); return true; }
                unmutePlayer(p, args[0]);
                return true;
            case "checkmute":
                if (!p.hasPermission("ddsmp.admin")) { p.sendMessage(prefix + "§cKeine Berechtigung!"); return true; }
                if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/checkmute <Spieler>"); return true; }
                checkMute(p, args[0]);
                return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player p = (Player) sender;
        List<String> completions = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String currentArg = args[args.length - 1].toLowerCase();

        switch (command.getName().toLowerCase()) {
            case "chunk":
                if (args.length == 1) {
                    suggestions.addAll(Arrays.asList("claim", "unclaim", "trust", "untrust", "info", "list"));
                } else if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
                    suggestions.addAll(getOnlinePlayerNames(p));
                }
                break;
            case "home":
            case "delhome":
                if (args.length == 1) {
                    suggestions.addAll(getPlayerHomeNames(p));
                }
                break;
            case "sethome":
                if (args.length == 1) {
                    suggestions.addAll(getPlayerHomeNames(p));
                }
                break;
            case "tpa":
                if (args.length == 1) {
                    suggestions.addAll(getOnlinePlayerNames(p));
                }
                break;
            case "tpaccept":
            case "tpadeny":
                if (args.length == 1) {
                    if (tpaRequests.containsKey(p.getUniqueId())) {
                        Player senderPlayer = Bukkit.getPlayer(tpaRequests.get(p.getUniqueId()).sender);
                        if (senderPlayer != null) suggestions.add(senderPlayer.getName());
                    }
                }
                break;
            case "mute":
            case "unmute":
            case "checkmute":
                if (args.length == 1) {
                    suggestions.addAll(getAllOnlinePlayerNames());
                }
                break;
        }

        for (String s : suggestions) {
            if (s.toLowerCase().startsWith(currentArg)) {
                completions.add(s);
            }
        }
        return completions;
    }

    private List<String> getOnlinePlayerNames(Player exclude) {
        List<String> playerNames = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (exclude != null && player.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }
            playerNames.add(player.getName());
        }
        return playerNames;
    }

    private List<String> getAllOnlinePlayerNames() {
        List<String> playerNames = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.add(player.getName());
        }
        return playerNames;
    }

    private List<String> getPlayerHomeNames(Player p) {
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
        if (playerHomes == null) return Collections.emptyList();
        return new ArrayList<>(playerHomes.keySet());
    }

    private void claimChunk(Player p) {
        Chunk chunk = p.getLocation().getChunk();
        String chunkKey = chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ();
        for (String uuidStr : claimsConfig.getKeys(false)) {
            List<String> list = claimsConfig.getStringList(uuidStr + ".chunks");
            if (list.contains(chunkKey)) { p.sendMessage(prefix + "§cDieser Chunk ist bereits geclaimt!"); return; }
        }
        UUID uuid = p.getUniqueId();
        List<String> playerClaims = claimsConfig.getStringList(uuid.toString() + ".chunks");
        if (playerClaims.size() >= 25) { p.sendMessage(prefix + "§cDu kannst maximal §b25 Chunks §cclaimen!"); return; }
        playerClaims.add(chunkKey);
        claimsConfig.set(uuid.toString() + ".chunks", playerClaims);
        saveClaims();
        p.sendMessage(prefix + "§aDu hast diesen Chunk erfolgreich geclaimt!");
    }

    private void unclaimChunk(Player p) {
        Chunk chunk = p.getLocation().getChunk();
        String chunkKey = chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ();
        UUID uuid = p.getUniqueId();
        List<String> playerClaims = claimsConfig.getStringList(uuid.toString() + ".chunks");
        if (!playerClaims.contains(chunkKey)) { p.sendMessage(prefix + "§cDu besitzt diesen Chunk nicht!"); return; }
        playerClaims.remove(chunkKey);
        claimsConfig.set(uuid.toString() + ".chunks", playerClaims);
        claimsConfig.set(uuid.toString() + ".trusted", claimsConfig.getStringList(uuid.toString() + ".trusted"));
        saveClaims();
        p.sendMessage(prefix + "§aDu hast diesen Chunk erfolgreich unclaimt!");
    }

    private void trustChunk(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { p.sendMessage(prefix + "§cSpieler nicht gefunden!"); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(prefix + "§cDu kannst dich nicht selbst trusten!"); return; }
        UUID owner = getChunkOwner(p.getLocation().getChunk());
        if (owner == null || !owner.equals(p.getUniqueId())) { p.sendMessage(prefix + "§cDu besitzt diesen Chunk nicht!"); return; }
        List<String> trusted = claimsConfig.getStringList(owner.toString() + ".trusted");
        if (!trusted.contains(target.getUniqueId().toString())) trusted.add(target.getUniqueId().toString());
        claimsConfig.set(owner.toString() + ".trusted", trusted);
        saveClaims();
        p.sendMessage(prefix + "§b" + target.getName() + " §awurde erfolgreich im Chunk getrusted!");
    }

    private void untrustChunk(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { p.sendMessage(prefix + "§cSpieler nicht gefunden!"); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(prefix + "§cDu kannst dich nicht selbst untrusten!"); return; }
        UUID owner = getChunkOwner(p.getLocation().getChunk());
        if (owner == null || !owner.equals(p.getUniqueId())) { p.sendMessage(prefix + "§cDu besitzt diesen Chunk nicht!"); return; }
        List<String> trusted = claimsConfig.getStringList(owner.toString() + ".trusted");
        trusted.remove(target.getUniqueId().toString());
        claimsConfig.set(owner.toString() + ".trusted", trusted);
        saveClaims();
        p.sendMessage(prefix + "§aDu hast §b" + target.getName() + " §aerfolgreich untrusted!");
    }

    private void chunkInfo(Player p) {
        Chunk chunk = p.getLocation().getChunk();
        UUID owner = getChunkOwner(chunk);
        String ownerName = owner == null ? "-" : Bukkit.getOfflinePlayer(owner).getName();
        List<String> trustedNames = new ArrayList<>();
        if (owner != null) {
            for (String uuidStr : claimsConfig.getStringList(owner.toString() + ".trusted")) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                trustedNames.add(op.getName());
            }
        }
        p.sendMessage(prefix + "§dChunk Info:");
        p.sendMessage("§aID: §b" + chunk.getWorld().getName() + ", " + chunk.getX() + ", " + chunk.getZ());
        p.sendMessage("§aBesitzer: §b" + ownerName);
        p.sendMessage("§aVertraute Spieler: §b" + (trustedNames.isEmpty() ? "Keine" : String.join(", ", trustedNames)));
    }

    private void listChunks(Player p) {
        UUID uuid = p.getUniqueId();
        List<String> playerClaims = claimsConfig.getStringList(uuid.toString() + ".chunks");

        if (playerClaims.isEmpty()) {
            p.sendMessage(prefix + "§cDu hast keine Chunks geclaimt.");
            return;
        }

        List<String> trustedNames = new ArrayList<>();
        for (String uuidStr : claimsConfig.getStringList(uuid.toString() + ".trusted")) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
            trustedNames.add(op.getName());
        }
        String trustedStr = trustedNames.isEmpty() ? "Keine" : String.join(", ", trustedNames);

        p.sendMessage(prefix + "§aDeine geclaimten Chunks (" + playerClaims.size() + "/" + 25 + "):");

        for (String chunkKey : playerClaims) {
            p.sendMessage("§r ");
            p.sendMessage("§aID: §b" + chunkKey.replace(",", ", "));
            p.sendMessage("§aVertraute Spieler: §b" + trustedStr);
        }
    }

    private void mutePlayer(Player admin, String[] args) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target == null) { admin.sendMessage(prefix + "§cSpieler nicht gefunden!"); return; }

        if (muteConfig.getBoolean(target.getUniqueId().toString() + ".muted")) {
            admin.sendMessage(prefix + "§b" + target.getName() + " §cist bereits gemutet!");
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        muteConfig.set(target.getUniqueId().toString() + ".muted", true);
        muteConfig.set(target.getUniqueId().toString() + ".reason", reason);
        muteConfig.set(target.getUniqueId().toString() + ".admin", admin.getName());
        saveMuteFile();

        admin.sendMessage(prefix + "§aDu hast §b" + target.getName() + " §agemutet. Grund: §b" + reason);

        if (target.isOnline()) {
            Player onlineTarget = (Player) target;
            onlineTarget.sendMessage(prefix + "§cDu hast einen Mute wegen §b" + reason + " §cbekommen!");
        }
    }

    private void unmutePlayer(Player admin, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null) { admin.sendMessage(prefix + "§cSpieler nicht gefunden!"); return; }

        if (!muteConfig.getBoolean(target.getUniqueId().toString() + ".muted")) {
            admin.sendMessage(prefix + "§b" + target.getName() + " §cist nicht gemutet.");
            return;
        }

        muteConfig.set(target.getUniqueId().toString(), null);
        saveMuteFile();

        admin.sendMessage(prefix + "§aDu hast den Mute von §b" + target.getName() + " §aaufgehoben!");

        if (target.isOnline()) {
            ((Player) target).sendMessage(prefix + "§aDein Mute wurde aufgehoben!");
        }
    }

    private void checkMute(Player admin, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null) { admin.sendMessage(prefix + "§cSpieler nicht gefunden!"); return; }

        UUID targetUUID = target.getUniqueId();
        if (muteConfig.getBoolean(targetUUID.toString() + ".muted")) {
            String reason = muteConfig.getString(targetUUID.toString() + ".reason", "Kein Grund angegeben");
            String mutedBy = muteConfig.getString(targetUUID.toString() + ".admin", "Unbekannt");
            admin.sendMessage(prefix + "§dMute Info:");
            admin.sendMessage("§aSpieler: §b" + target.getName());
            admin.sendMessage("§aStatus: §cGemutet §7- §aGrund: §b" + reason);
            admin.sendMessage("§aVergeben von: §b" + mutedBy);
        } else {
            admin.sendMessage(prefix + "§cDieser Spieler ist nicht gemutet!");
        }
    }

    private UUID getChunkOwner(Chunk chunk) {
        String chunkKey = chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ();
        for (String uuidStr : claimsConfig.getKeys(false)) {
            List<String> list = claimsConfig.getStringList(uuidStr + ".chunks");
            if (list.contains(chunkKey)) return UUID.fromString(uuidStr);
        }
        return null;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        UUID owner = getChunkOwner(b.getChunk());

        if (owner != null && !owner.equals(p.getUniqueId()) && !claimsConfig.getStringList(owner.toString() + ".trusted").contains(p.getUniqueId().toString())) {
            e.setCancelled(true);
            return;
        }

        if (b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD) {
            if (b.getState() instanceof Skull) {
                Skull skullState = (Skull) b.getState();
                OfflinePlayer killedPlayer = skullState.getOwningPlayer();

                if (killedPlayer != null && killedPlayer.getName() != null) {
                    e.setCancelled(true);

                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta meta = (SkullMeta) head.getItemMeta();

                    meta.setOwningPlayer(killedPlayer);

                    String displayName = "§b" + killedPlayer.getName() + "'s Kopf";
                    meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(displayName));

                    head.setItemMeta(meta);

                    b.getWorld().dropItemNaturally(b.getLocation(), head);
                    b.setType(Material.AIR);
                    return;
                }
            }
        }

        if (p.isSneaking() && isAxe(p.getInventory().getItemInMainHand().getType()) && isLog(b.getType())) {
            Set<Block> logBlocks = findTreeBlocks(b);
            if (!logBlocks.isEmpty() && hasLeavesAttached(logBlocks)) {
                e.setCancelled(true);
                chopTree(b, p, logBlocks);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player)) return;

        Player victim = (Player) e.getEntity();
        Player damager = null;

        if (e.getDamager() instanceof Player) {
            damager = (Player) e.getDamager();
        } else if (e.getDamager() instanceof org.bukkit.entity.Projectile) {
            org.bukkit.entity.Projectile projectile = (org.bukkit.entity.Projectile) e.getDamager();
            if (projectile.getShooter() instanceof Player) {
                damager = (Player) projectile.getShooter();
            }
        }

        if (damager != null && !victim.getUniqueId().equals(damager.getUniqueId())) {
            startCombatTag(victim);
            startCombatTag(damager);

            lastOpponent.put(victim.getUniqueId(), damager.getUniqueId());
            lastOpponent.put(damager.getUniqueId(), victim.getUniqueId());
        }
    }

    private boolean isInCombat(Player p) {
        UUID uuid = p.getUniqueId();
        return combatEndTime.containsKey(uuid) && combatEndTime.get(uuid) > System.currentTimeMillis();
    }

    private void startCombatTag(Player p) {
        UUID uuid = p.getUniqueId();
        long newEndTime = System.currentTimeMillis() + (COMBAT_DURATION_SECONDS * 1000L);
        combatEndTime.put(uuid, newEndTime);

        if (combatActionBarTasks.containsKey(uuid)) {
            combatActionBarTasks.get(uuid).cancel();
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    combatActionBarTasks.remove(uuid);
                    return;
                }

                long remainingMillis = combatEndTime.getOrDefault(uuid, 0L) - System.currentTimeMillis();
                if (remainingMillis > 0) {
                    long remainingSeconds = (remainingMillis / 1000L) + 1;
                    p.sendActionBar(Component.text("§cIm Kampf: §b" + remainingSeconds + "s"));
                } else {
                    p.sendActionBar(Component.text("§aDer Kampf ist beendet!"));
                    new BukkitRunnable() { @Override public void run() { p.sendActionBar(Component.text("")); } }.runTaskLater(ddsmp.this, 40L);
                    combatEndTime.remove(uuid);
                    combatActionBarTasks.remove(uuid);
                    cancel();
                }
            }
        };
        task.runTaskTimer(this, 0L, 20L);
        combatActionBarTasks.put(uuid, task);
    }

    private boolean isAxe(Material material) {
        return material.toString().endsWith("_AXE");
    }

    private boolean isLog(Material material) {
        return material.toString().endsWith("_LOG") || material.toString().endsWith("_WOOD");
    }

    private boolean isLeaves(Material material) {
        return material.toString().endsWith("_LEAVES");
    }

    private void chopTree(Block startBlock, Player p, Set<Block> logBlocks) {
        Set<Block> leafBlocks = new HashSet<>();
        for (Block log : logBlocks) {
            for (int x = -2; x <= 2; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -2; z <= 2; z++) {
                        Block neighbor = log.getRelative(x, y, z);
                        if (isLeaves(neighbor.getType()) && !leafBlocks.contains(neighbor)) {
                            leafBlocks.add(neighbor);
                        }
                    }
                }
            }
        }

        ItemStack axe = p.getInventory().getItemInMainHand();
        ItemMeta meta = axe.getItemMeta();
        int durabilityLoss = 0;

        for (Block log : logBlocks) {
            if (!log.getLocation().equals(startBlock.getLocation())) {
                UUID owner = getChunkOwner(log.getChunk());
                if (owner != null && !owner.equals(p.getUniqueId()) && !claimsConfig.getStringList(owner.toString() + ".trusted").contains(p.getUniqueId().toString())) {
                    p.sendMessage(prefix + "§cDas Abholzen des gesamten Baumes wurde gestoppt, da sich ein Teil im geclaimten Gebiet eines anderen Spielers befindet.");
                    return;
                }
            }

            log.breakNaturally(axe);
            durabilityLoss += calculateDurabilityLoss(axe.getType());
        }

        for (Block leaf : leafBlocks) {
            leaf.breakNaturally();
        }

        if (meta instanceof Damageable && durabilityLoss > 0) {
            Damageable damageable = (Damageable) meta;
            int newDamage = damageable.getDamage() + durabilityLoss;

            if (newDamage >= axe.getType().getMaxDurability()) {
                p.getInventory().setItemInMainHand(null);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDamage);
                axe.setItemMeta(meta);
            }
        }

        p.playSound(p.getLocation(), Sound.BLOCK_BEEHIVE_EXIT, 1.0f, 1.0f);
    }

    private int calculateDurabilityLoss(Material toolMaterial) {
        return 1;
    }

    private Set<Block> findTreeBlocks(Block startBlock) {
        Set<Block> logBlocks = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();

        if (!isLog(startBlock.getType())) {
            return new HashSet<>();
        }

        queue.add(startBlock);
        logBlocks.add(startBlock);

        int logCount = 1;
        int maxLogCount = 500;

        while (!queue.isEmpty()) {
            Block current = queue.poll();

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;

                        Block neighbor = current.getRelative(x, y, z);

                        if (isLog(neighbor.getType()) && !logBlocks.contains(neighbor)) {
                            logCount++;
                            if (logCount > maxLogCount) {
                                return new HashSet<>();
                            }

                            logBlocks.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return logBlocks;
    }

    private boolean hasLeavesAttached(Set<Block> logBlocks) {
        for (Block log : logBlocks) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block neighbor = log.getRelative(x, y, z);
                        if (isLeaves(neighbor.getType())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }


    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        UUID owner = getChunkOwner(e.getBlock().getChunk());
        if (owner != null && !owner.equals(p.getUniqueId()) && !claimsConfig.getStringList(owner.toString() + ".trusted").contains(p.getUniqueId().toString())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        UUID owner = getChunkOwner(e.getClickedBlock().getChunk());
        if (owner != null && !owner.equals(p.getUniqueId()) && !claimsConfig.getStringList(owner.toString() + ".trusted").contains(p.getUniqueId().toString())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> getChunkOwner(block.getChunk()) != null);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Chunk from = e.getFrom().getChunk();
        Chunk to = e.getTo().getChunk();

        if (teleporting.contains(p.getUniqueId())) {
            Location fromLoc = lastLocations.get(p.getUniqueId());
            Location toLoc = e.getTo().getBlock().getLocation();

            if (fromLoc != null && !fromLoc.equals(toLoc)) {
                abortTeleportation(p, "bewegt");
                if (from.equals(to)) return;
            }
        }

        if (from.equals(to)) return;

        UUID ownerFrom = getChunkOwner(from);
        UUID ownerTo = getChunkOwner(to);

        if (ownerFrom != null && !ownerFrom.equals(ownerTo)) {
            String ownerFromName = Bukkit.getOfflinePlayer(ownerFrom).getName();
            p.sendActionBar(Component.text("§7Du §cverlässt §7den Chunk von: §b" + ownerFromName));
        }

        if (ownerTo != null && !ownerTo.equals(ownerFrom)) {
            String ownerToName = Bukkit.getOfflinePlayer(ownerTo).getName();
            p.sendActionBar(Component.text("§7Du §abetrittst §7den Chunk von: §b" + ownerToName));
        }

        currentChunkOwner.put(p.getUniqueId(), ownerTo != null ? Bukkit.getOfflinePlayer(ownerTo).getName() : "");
    }

    @EventHandler
    public void onPlayerDamageAbortTeleport(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            Player p = (Player) e.getEntity();
            if (teleporting.contains(p.getUniqueId())) {
                abortTeleportation(p, "Schaden genommen");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (muteConfig.getBoolean(uuid.toString() + ".muted")) {
            e.setCancelled(true);
            String reason = muteConfig.getString(uuid.toString() + ".reason", "Kein Grund angegeben");
            String mutedBy = muteConfig.getString(uuid.toString() + ".admin", "Unbekannt");

            p.sendMessage(prefix + "§cDu bist im Chat gemutet!");
            p.sendMessage("§aGrund: §b" + reason);
            p.sendMessage("§aVergeben von: §b" + mutedBy);
        }
    }

    private void setHome(Player p, String name) {
        homes.putIfAbsent(p.getUniqueId(), new HashMap<>());
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
        if (playerHomes.size() >= 2 && !playerHomes.containsKey(name.toLowerCase())) {
            p.sendMessage(prefix + "§cDu kannst nur maximal §b2 Homes §csetzen!");
            return;
        }
        playerHomes.put(name.toLowerCase(), p.getLocation());
        saveHomes();
        p.sendMessage(prefix + "§aHome §b" + name + " §awurde erfolgreich gesetzt!");
    }

    private void teleportHome(Player p, String name) {
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
        if (playerHomes == null || !playerHomes.containsKey(name.toLowerCase())) {
            p.sendMessage(prefix + "§cEs wurde kein §bHome §cmit diesem Namen gefunden!");
            return;
        }

        if (tpaCooldowns.containsKey(p.getUniqueId())) {
            long lastUsed = tpaCooldowns.get(p.getUniqueId());
            long remaining = TPA_COOLDOWN_MILLIS - (System.currentTimeMillis() - lastUsed);
            if (remaining > 0) {
                p.sendMessage(prefix + "§cBitte warte noch §b" + (remaining / 1000 + 1) + " Sekunden, §cbevor du dich erneut teleportierst.");
                return;
            }
        }

        Location loc = playerHomes.get(name.toLowerCase());
        tpaCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(prefix + "§aDu wirst in §b3 Sekunden §ateleportiert!");
        startTeleportation(p, loc, "zum Home " + name);
    }

    private void deleteHome(Player p, String name) {
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
        if (playerHomes == null || !playerHomes.containsKey(name.toLowerCase())) {
            p.sendMessage(prefix + "§cEs wurde kein §bHome §cmit diesem Namen gefunden!");
            return;
        }
        playerHomes.remove(name.toLowerCase());
        homesConfig.set("homes." + p.getUniqueId() + "." + name.toLowerCase(), null);
        saveHomes();
        p.sendMessage(prefix + "§aHome §b" + name + " §awurde gelöscht!");
    }

    private void listHomes(Player p) {
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
        if (playerHomes == null || playerHomes.isEmpty()) { p.sendMessage(prefix + "§cDu hast kein §bHome §cgesetzt."); return; }
        p.sendMessage(prefix + "§aDeine Homes: §b" + String.join(", ", playerHomes.keySet()));
    }

    private void teleportToSpawn(Player p) {
        if (p.getWorld().getSpawnLocation().equals(p.getLocation().getBlock().getLocation())) {
            p.sendMessage(prefix + "§cDu bist bereits am Spawn!");
            return;
        }

        if (spawnCooldowns.containsKey(p.getUniqueId())) {
            long lastUsed = spawnCooldowns.get(p.getUniqueId());
            long remaining = SPAWN_COOLDOWN_MILLIS - (System.currentTimeMillis() - lastUsed);
            if (remaining > 0) {
                p.sendMessage(prefix + "§cBitte warte noch §b" + (remaining / 1000 + 1) + " Sekunden, §cbevor du dich erneut zum Spawn teleportierst.");
                return;
            }
        }

        Location spawnLoc = p.getWorld().getSpawnLocation();
        spawnCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(prefix + "§aDu wirst in §b3 Sekunden §ateleportiert!");
        startTeleportation(p, spawnLoc, "zum Spawn");
    }

    private void cleanUpTpaRequest(UUID receiverUUID) {
        tpaRequests.remove(receiverUUID);
        if (tpaExpirationTasks.containsKey(receiverUUID)) {
            tpaExpirationTasks.remove(receiverUUID).cancel();
        }
    }

    private void sendTpaRequest(Player sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { sender.sendMessage(prefix + "§cDieser Spieler wurde nicht gefunden!"); return; }
        if (sender.getUniqueId().equals(target.getUniqueId())) { sender.sendMessage(prefix + "§cDu kannst keine TPA-Anfragen an dich selbst senden!"); return; }

        if (tpaCooldowns.containsKey(sender.getUniqueId())) {
            long lastSent = tpaCooldowns.get(sender.getUniqueId());
            long remaining = TPA_COOLDOWN_MILLIS - (System.currentTimeMillis() - lastSent);
            if (remaining > 0) {
                sender.sendMessage(prefix + "§cBitte warte noch §b" + (remaining / 1000 + 1) + " Sekunden, §cbevor du eine neue TPA-Anfrage sendest.");
                return;
            }
        }

        if (tpaRequests.containsKey(target.getUniqueId())) {
            cleanUpTpaRequest(target.getUniqueId());
        }

        tpaRequests.put(target.getUniqueId(), new TpaRequest(sender.getUniqueId()));
        tpaCooldowns.put(sender.getUniqueId(), System.currentTimeMillis());

        BukkitRunnable expirationTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (tpaRequests.containsKey(target.getUniqueId()) && tpaRequests.get(target.getUniqueId()).sender.equals(sender.getUniqueId())) {
                    cleanUpTpaRequest(target.getUniqueId());
                    if (target.isOnline()) {
                        target.sendMessage(prefix + "§cDie TPA-Anfrage von §b" + sender.getName() + " §cist abgelaufen.");
                    }
                    if (sender.isOnline()) {
                        sender.sendMessage(prefix + "§cDie TPA-Anfrage an §b" + target.getName() + " §cist abgelaufen.");
                    }
                }
            }
        };
        expirationTask.runTaskLater(this, TPA_EXPIRATION_TICKS);
        tpaExpirationTasks.put(target.getUniqueId(), expirationTask);

        sender.sendMessage(prefix + "§aTPA-Anfrage an §b" + target.getName() + " §agesendet!");
        target.sendMessage(prefix + "§b" + sender.getName() + " §ahat dir eine TPA-Anfrage gesendet!");
        target.sendMessage(prefix + "§aNutze §e/tpaccept §aum die TPA-Anfrage zu akzeptieren!");
    }

    private void acceptTpa(Player accepter, String requesterName) {
        Player requester = Bukkit.getPlayerExact(requesterName);
        if (requester == null || !tpaRequests.containsKey(accepter.getUniqueId())) {
            accepter.sendMessage(prefix + "§cKeine offene TPA-Anfrage von diesem Spieler!");
            return;
        }
        TpaRequest request = tpaRequests.get(accepter.getUniqueId());
        if (!request.sender.equals(requester.getUniqueId())) {
            accepter.sendMessage(prefix + "§cKeine offene TPA-Anfrage von diesem Spieler!");
            return;
        }

        cleanUpTpaRequest(accepter.getUniqueId());

        requester.sendMessage(prefix + "§b" + accepter.getName() + " §ahat deine TPA-Anfrage akzeptiert!");
        accepter.sendMessage(prefix + "§aDu hast die TPA-Anfrage von §b" + requester.getName() + " §aakzeptiert!");

        startTeleportation(requester, accepter.getLocation(), "zu " + accepter.getName());
    }

    private void denyTpa(Player denier, String requesterName) {
        Player requester = Bukkit.getPlayerExact(requesterName);
        if (requester == null || !tpaRequests.containsKey(denier.getUniqueId())) {
            denier.sendMessage(prefix + "§cKeine offene TPA-Anfrage von diesem Spieler!");
            return;
        }
        TpaRequest request = tpaRequests.get(denier.getUniqueId());
        if (!request.sender.equals(requester.getUniqueId())) {
            denier.sendMessage(prefix + "§cKeine offene TPA-Anfrage von diesem Spieler!");
            return;
        }

        cleanUpTpaRequest(denier.getUniqueId());

        requester.sendMessage(prefix + "§cDeine TPA-Anfrage wurde von §b" + denier.getName() + " §cabgelehnt!");
        denier.sendMessage(prefix + "§aDu hast die TPA-Anfrage von §b" + requester.getName() + " §cabgelehnt!");
    }

    private void abortTeleportation(Player p, String reason) {
        if (teleporting.contains(p.getUniqueId())) {
            teleporting.remove(p.getUniqueId());
            lastLocations.remove(p.getUniqueId());

            if (teleportTasks.containsKey(p.getUniqueId())) {
                teleportTasks.remove(p.getUniqueId()).cancel();
            }

            p.sendActionBar(Component.text("§cTeleportation abgebrochen."));
            new BukkitRunnable() { @Override public void run() { p.sendActionBar(Component.text("")); } }.runTaskLater(this, 40L);

            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1.0f, 1.0f);

            p.sendMessage(prefix + "§cTeleportation abgebrochen, da du " + reason + " hast.");
        }
    }

    private void startTeleportation(Player p, Location targetLoc, String targetName) {
        if (teleporting.contains(p.getUniqueId())) {
            p.sendMessage(prefix + "§cDu teleportierst bereits!");
            return;
        }

        if (isInCombat(p)) {
            p.sendMessage(prefix + "§cDu kannst das nicht im Kampf tun!");
            return;
        }

        teleporting.add(p.getUniqueId());
        lastLocations.put(p.getUniqueId(), p.getLocation().getBlock().getLocation());

        BukkitRunnable task = new BukkitRunnable() {
            int seconds = TELEPORT_COUNTDOWN_SECONDS;
            @Override
            public void run() {
                if (!p.isOnline() || !teleporting.contains(p.getUniqueId())) {
                    cancel();
                    teleporting.remove(p.getUniqueId());
                    lastLocations.remove(p.getUniqueId());
                    teleportTasks.remove(p.getUniqueId());
                    return;
                }

                if (isInCombat(p)) {
                    abortTeleportation(p, "in den Kampf gekommen");
                    cancel();
                    return;
                }

                if (seconds > 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);

                    p.sendActionBar(Component.text("§7Teleport in §b" + seconds + " Sekunden"));
                    seconds--;
                } else {
                    p.teleport(targetLoc);
                    p.sendMessage(prefix + "§aDu wurdest teleportiert!");

                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

                    teleporting.remove(p.getUniqueId());
                    lastLocations.remove(p.getUniqueId());
                    teleportTasks.remove(p.getUniqueId());
                    p.sendActionBar(Component.text("§aDu wurdest teleportiert!"));
                    cancel();
                }
            }
        };
        task.runTaskTimer(this, 0L, 20L);
        teleportTasks.put(p.getUniqueId(), task);
    }

    private void dropPlayerHead(Player killed, Player killer) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);

        if (!(head.getItemMeta() instanceof SkullMeta)) {
            return;
        }

        SkullMeta meta = (SkullMeta) head.getItemMeta();

        meta.setOwningPlayer(killed);

        String displayName = "§b" + killed.getName() + "'s Kopf";
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(displayName));

        head.setItemMeta(meta);

        killed.getWorld().dropItemNaturally(killed.getLocation(), head);

    }


    private void dropPlayerInventoryAndXP(Player p) {
        World world = p.getWorld();
        Location loc = p.getLocation();

        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                world.dropItemNaturally(loc, item);
            }
        }

        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);

        if (p.getTotalExperience() > 0) {
            org.bukkit.entity.ExperienceOrb orb = (org.bukkit.entity.ExperienceOrb) world.spawnEntity(loc, org.bukkit.entity.EntityType.EXPERIENCE_ORB);
            orb.setExperience(p.getTotalExperience());
        }
        p.setTotalExperience(0);
        p.setLevel(0);
        p.setExp(0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setGameMode(GameMode.SURVIVAL);
        e.joinMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("§bDD SMP §8» §7[§a+§7] §a" + p.getName()));

        if (combatLogDeaths.contains(p.getUniqueId())) {

            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);

            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);

            p.setTotalExperience(0);
            p.setLevel(0);
            p.setExp(0);

            p.teleport(p.getWorld().getSpawnLocation());

            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);

            p.sendTitle("§4Du bist gestorben!", "§cDu hast dich im Kampf ausgeloggt!", 5, 40, 10);

            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));

            combatLogDeaths.remove(p.getUniqueId());
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (int i = 0; i < 1000; ++i) p.sendMessage("§r ");
            p.sendMessage("§r                                                                      ");
            p.sendMessage("§r          §7◆      §aWillkommen zurück! §7- §b" + p.getName() + "      §7◆          ");
            p.sendMessage("§r                                                                      ");
            p.sendMessage("§r                                                                      ");
            p.sendMessage("§r                                                                      ");
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.quitMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("§bDD SMP §8» §7[§c-§7] §c" + p.getName()));

        if (isInCombat(p)) {
            dropPlayerInventoryAndXP(p);

            UUID opponentUUID = lastOpponent.get(p.getUniqueId());
            Player opponent = opponentUUID != null ? Bukkit.getPlayer(opponentUUID) : null;

            dropPlayerHead(p, opponent);

            p.sendMessage("§cDu hast den Server während dem Kampf verlassen! Deine Gegenstände und XP wurden fallengelassen.");

            combatLogDeaths.add(p.getUniqueId());

            combatEndTime.remove(p.getUniqueId());
            if (combatActionBarTasks.containsKey(p.getUniqueId())) {
                combatActionBarTasks.remove(p.getUniqueId()).cancel();
            }
            p.sendActionBar(Component.text("§aDer Kampf ist beendet!"));

            if (opponent != null && opponent.isOnline()) {
                opponent.sendActionBar(Component.text("§aDer Kampf mit §b" + p.getName() + " §aist beendet!"));
                new BukkitRunnable() { @Override public void run() { opponent.sendActionBar(Component.text("")); } }.runTaskLater(ddsmp.this, 40L);
                combatEndTime.remove(opponent.getUniqueId());
                if (combatActionBarTasks.containsKey(opponent.getUniqueId())) {
                    combatActionBarTasks.remove(opponent.getUniqueId()).cancel();
                }
            }

            lastOpponent.remove(p.getUniqueId());
        }

        deathPoints.remove(p.getUniqueId());
        currentChunkOwner.remove(p.getUniqueId());

        teleporting.remove(p.getUniqueId());
        lastLocations.remove(p.getUniqueId());
        cleanUpTpaRequest(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        UUID pUuid = p.getUniqueId();

        Player killer = p.getKiller();

        if (killer != null && !killer.getUniqueId().equals(pUuid)) {
            dropPlayerHead(p, killer);
        }

        if (isInCombat(p)) {
            UUID opponentUUID = lastOpponent.get(pUuid);
            Player combatKiller = opponentUUID != null ? Bukkit.getPlayer(opponentUUID) : null;

            combatEndTime.remove(pUuid);
            if (combatActionBarTasks.containsKey(pUuid)) {
                combatActionBarTasks.remove(pUuid).cancel();
            }

            if (combatKiller != null && combatKiller.isOnline() && isInCombat(combatKiller)) {
                combatEndTime.remove(opponentUUID);
                if (combatActionBarTasks.containsKey(opponentUUID)) {
                    combatActionBarTasks.remove(opponentUUID).cancel();
                }
                combatKiller.sendActionBar(Component.text("§aDer Kampf mit §b" + p.getName() + " §aist beendet!"));
                new BukkitRunnable() { @Override public void run() { combatKiller.sendActionBar(Component.text("")); } }.runTaskLater(ddsmp.this, 40L);
            }
        }

        lastOpponent.remove(pUuid);

        Location deathLoc = p.getLocation();
        deathPoints.put(pUuid, deathLoc);
        p.sendMessage(prefix + "§cDu bist gestorben bei §eX: " + deathLoc.getBlockX() + " Y: " + deathLoc.getBlockY() + " Z: " + deathLoc.getBlockZ());
        Map<String, Location> playerHomes = homes.get(pUuid);
        if (playerHomes != null && !playerHomes.isEmpty()) {
            String closestHome = null;
            double closestDist = Double.MAX_VALUE;
            for (Map.Entry<String, Location> entry : playerHomes.entrySet()) {
                double dist = entry.getValue().distance(deathLoc);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestHome = entry.getKey();
                }
            }
            if (closestHome != null) p.sendMessage(prefix + "§aNächstes Home: §b" + closestHome + " §7- §aEntfernung: §e" + (int) closestDist + " Blöcke");
        }
    }
}
