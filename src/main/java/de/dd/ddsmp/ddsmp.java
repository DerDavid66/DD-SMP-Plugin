package de.dd.ddsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.help.HelpTopic;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class ddsmp extends JavaPlugin implements Listener, TabCompleter {

    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<UUID, TpaRequest> tpaRequests = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Location> deathPoints = new HashMap<>();
    private final Map<UUID, String> currentChunkOwner = new HashMap<>();
    private File homesFile;
    private FileConfiguration homesConfig;
    private File claimsFile;
    private FileConfiguration claimsConfig;
    private File muteFile;
    private FileConfiguration muteConfig;
    private final String prefix = "§bDD SMP §8» ";
    private final String UNKNOWN_COMMAND_MESSAGE = "§bDD SMP §8» §cDieser Befehl existiert nicht!";

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
        if (cmd.getName().equalsIgnoreCase("chunk")) {
            if (args.length == 0) { p.sendMessage(prefix + "§dVerwendung: §e/chunk <claim | unclaim | trust | untrust | info | list>"); return true; }
            switch (args[0].toLowerCase()) {
                case "claim": claimChunk(p); return true;
                case "unclaim": unclaimChunk(p); return true;
                case "trust": if (args.length != 2) { p.sendMessage(prefix + "§dVerwendung: §e/chunk trust <Spieler>"); return true; } trustChunk(p, args[1]); return true;
                case "untrust": if (args.length != 2) { p.sendMessage(prefix + "§dVerwendung: §e/chunk untrust <Spieler>"); return true; } untrustChunk(p, args[1]); return true;
                case "info": chunkInfo(p); return true;
                case "list": listChunks(p); return true;
                default: return false;
            }
        }

        switch (cmd.getName().toLowerCase()) {
            case "sethome": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/sethome <name>"); return true; } setHome(p, args[0]); return true;
            case "home": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/home <name>"); return true; } teleportHome(p, args[0]); return true;
            case "delhome": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/delhome <name>"); return true; } deleteHome(p, args[0]); return true;
            case "homes": listHomes(p); return true;
            case "tpa": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/tpa <Spieler>"); return true; } sendTpaRequest(p, args[0]); return true;
            case "tpaccept": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/tpaccept <Spieler>"); return true; } acceptTpa(p, args[0]); return true;
            case "tpadeny": if (args.length != 1) { p.sendMessage(prefix + "§dVerwendung: §e/tpadeny <Spieler>"); return true; } denyTpa(p, args[0]); return true;
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

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        muteConfig.set(target.getUniqueId().toString() + ".muted", true);
        muteConfig.set(target.getUniqueId().toString() + ".reason", reason);
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
            admin.sendMessage(prefix + "§dMute Info:");
            admin.sendMessage("§aSpieler: §b" + target.getName());
            admin.sendMessage("§aStatus: §cGemutet §7- §aGrund: §b" + reason);
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
        UUID owner = getChunkOwner(e.getBlock().getChunk());
        if (owner != null && !owner.equals(p.getUniqueId()) && !claimsConfig.getStringList(owner.toString() + ".trusted").contains(p.getUniqueId().toString())) {
            e.setCancelled(true);
            return;
        }

        if (p.isSneaking() && isAxe(p.getInventory().getItemInMainHand().getType()) && isLog(e.getBlock().getType())) {
            e.setCancelled(true);
            chopTree(e.getBlock(), p);
        }
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

    private void chopTree(Block startBlock, Player p) {
        Set<Block> logBlocks = findTreeBlocks(startBlock);
        if (logBlocks.isEmpty()) {
            startBlock.breakNaturally(p.getInventory().getItemInMainHand());
            return;
        }

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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (muteConfig.getBoolean(uuid.toString() + ".muted")) {
            e.setCancelled(true);
            String reason = muteConfig.getString(uuid.toString() + ".reason", "Kein Grund angegeben");
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
        Location loc = playerHomes.get(name.toLowerCase());
        startTeleportCountdown(p, loc, true);
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

    private void sendTpaRequest(Player sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { sender.sendMessage(prefix + "§cDieser Spieler wurde nicht gefunden!"); return; }
        if (sender.getUniqueId().equals(target.getUniqueId())) { sender.sendMessage(prefix + "§cDu kannst keine Teleportationsanfragen an dich selbst senden!"); return; }
        tpaRequests.put(target.getUniqueId(), new TpaRequest(sender.getUniqueId()));
        sender.sendMessage(prefix + "§aTPA-Anfrage an §b" + target.getName() + " §agesendet!");
        target.sendMessage(prefix + "§b" + sender.getName() + " §ahat dir eine TPA-Anfrage gesendet!");
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
        tpaRequests.remove(accepter.getUniqueId());
        requester.sendMessage(prefix + "§aDeine TPA-Anfrage wurde akzeptiert!");
        accepter.sendMessage(prefix + "§aDu hast die TPA-Anfrage von §b" + requester.getName() + " §aakzeptiert!");
        startTpaCountdown(requester, accepter);
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
        tpaRequests.remove(denier.getUniqueId());
        requester.sendMessage(prefix + "§cDeine TPA-Anfrage wurde von §b" + denier.getName() + " §cabgelehnt!");
        denier.sendMessage(prefix + "§aDu hast die TPA-Anfrage von §b" + requester.getName() + " §cabgelehnt!");
    }

    private void startTeleportCountdown(Player p, Location target, boolean isHome) {
        if (teleporting.contains(p.getUniqueId())) { p.sendMessage(prefix + "§cDu wirst bereits teleportiert!"); return; }
        teleporting.add(p.getUniqueId());
        lastLocations.put(p.getUniqueId(), p.getLocation());
        new BukkitRunnable() {
            int seconds = 3;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); teleporting.remove(p.getUniqueId()); return; }
                Location start = lastLocations.get(p.getUniqueId());
                if (hasMoved(start, p.getLocation())) { p.sendMessage(prefix + "§cDie Teleportation wurde abgebrochen, da du dich bewegt hast!"); teleporting.remove(p.getUniqueId()); cancel(); return; }
                if (seconds > 0) { p.sendActionBar(Component.text("§7Teleportiere in §b" + seconds + " Sekunden...")); seconds--; }
                else { p.teleport(target); if (isHome) p.sendMessage(prefix + "§aDu wurdest erfolgreich zu deinem §bHome §ateleportiert!"); else p.sendMessage(prefix + "§aDu wurdest erfolgreich teleportiert!"); teleporting.remove(p.getUniqueId()); cancel(); }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startTpaCountdown(Player requester, Player target) {
        if (teleporting.contains(requester.getUniqueId())) { requester.sendMessage(prefix + "§cDu wirst bereits teleportiert!"); return; }
        teleporting.add(requester.getUniqueId());
        new BukkitRunnable() {
            int seconds = 3;
            @Override
            public void run() {
                if (!requester.isOnline()) { cancel(); teleporting.remove(requester.getUniqueId()); return; }
                if (seconds > 0) { requester.sendActionBar(Component.text("§7Teleportiere in §b" + seconds + " Sekunden...")); seconds--; }
                else { requester.teleport(target.getLocation()); requester.sendMessage(prefix + "§aDu wurdest zu §b" + target.getName() + " §ateleportiert!"); teleporting.remove(requester.getUniqueId()); cancel(); }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private boolean hasMoved(Location a, Location b) {
        return a.getBlockX() != b.getBlockX() || a.getBlockY() != b.getBlockY() || a.getBlockZ() != b.getBlockZ();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.setGameMode(GameMode.SURVIVAL);
        e.joinMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("§bDD SMP §8» §7[§a+§7] §a" + p.getName()));
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
        deathPoints.remove(p.getUniqueId());
        currentChunkOwner.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Location deathLoc = p.getLocation();
        deathPoints.put(p.getUniqueId(), deathLoc);
        p.sendMessage(prefix + "§cDu bist gestorben bei §eX: " + deathLoc.getBlockX() + " Y: " + deathLoc.getBlockY() + " Z: " + deathLoc.getBlockZ());
        Map<String, Location> playerHomes = homes.get(p.getUniqueId());
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
