package com.example.impersonate;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HeadImpersonatePlugin extends JavaPlugin implements Listener {

    // Item PDC Keys
    private NamespacedKey headTargetNameKey;
    private NamespacedKey headTargetTextureKey;
    private NamespacedKey headTargetSignatureKey;

    // Player Crash-Recovery PDC Keys
    private NamespacedKey realNameBackupKey;
    private NamespacedKey realTextureBackupKey;
    private NamespacedKey realSignatureBackupKey;

    private final Map<UUID, ImpersonationSession> activeSessions = new ConcurrentHashMap<>();
    private BukkitTask timerTask;

    private static final int DURATION_SECONDS = 30 * 60; // 30 minutes (1800s)

    @Override
    public void onEnable() {
        this.headTargetNameKey = new NamespacedKey(this, "head_target_name");
        this.headTargetTextureKey = new NamespacedKey(this, "head_target_texture");
        this.headTargetSignatureKey = new NamespacedKey(this, "head_target_signature");

        this.realNameBackupKey = new NamespacedKey(this, "real_name_backup");
        this.realTextureBackupKey = new NamespacedKey(this, "real_texture_backup");
        this.realSignatureBackupKey = new NamespacedKey(this, "real_signature_backup");

        Bukkit.getPluginManager().registerEvents(this, this);
        startActionBarTimer();
    }

    @Override
    public void onDisable() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        // Restore all disguised players cleanly on reload/stop
        for (UUID uuid : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                stopImpersonating(player, false);
            }
        }
        activeSessions.clear();
    }

    // =========================================================================
    // 1. DROP HEAD ON PLAYER KILL
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        // Always get the victim's TRUE identity even if the victim is currently disguised
        ImpersonationSession victimSession = activeSessions.get(victim.getUniqueId());
        String trueVictimName = (victimSession != null) ? victimSession.realName : victim.getName();
        PlayerProfile trueVictimProfile = (victimSession != null) ? victimSession.originalProfile : victim.getPlayerProfile();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta != null) {
            meta.setPlayerProfile(trueVictimProfile);

            // Exact Name & Lore from video (without default italics)
            meta.displayName(Component.text("Head of " + trueVictimName, NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(Collections.singletonList(
                    Component.text("Right click to impersonate for 30 minutes", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(headTargetNameKey, PersistentDataType.STRING, trueVictimName);

            for (ProfileProperty prop : trueVictimProfile.getProperties()) {
                if ("textures".equals(prop.getName())) {
                    pdc.set(headTargetTextureKey, PersistentDataType.STRING, prop.getValue());
                    if (prop.getSignature() != null) {
                        pdc.set(headTargetSignatureKey, PersistentDataType.STRING, prop.getSignature());
                    }
                    break;
                }
            }
            head.setItemMeta(meta);
        }

        event.getDrops().add(head);

        // If the victim was impersonating someone when they died, end their disguise
        if (victimSession != null) {
            stopImpersonating(victim, true);
        }
    }

    // =========================================================================
    // 2. RIGHT-CLICK ACTIVATION & DUPE/GLITCH GUARDS
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRightClickHead(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isImpersonationHead(item)) return;

        // Completely block placing or equipping the head
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String targetName = pdc.get(headTargetNameKey, PersistentDataType.STRING);
        String texture = pdc.get(headTargetTextureKey, PersistentDataType.STRING);
        String signature = pdc.get(headTargetSignatureKey, PersistentDataType.STRING);

        if (targetName == null) return;

        // Prevent accidentally wasting a head of yourself
        String currentRealName = getTrueName(player);
        if (currentRealName.equalsIgnoreCase(targetName)) {
            player.sendMessage(Component.text("You cannot impersonate yourself!", NamedTextColor.RED));
            return;
        }

        // Safely consume 1 head from main hand
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        startImpersonating(player, targetName, texture, signature);
    }

    // Block placing the custom head if another plugin un-cancels interact events
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isImpersonationHead(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    // Block dispensers from placing or equipping the custom head
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isImpersonationHead(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // Block anvil renaming so players cannot forge fake lore/names
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilRename(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        if (isImpersonationHead(first)) {
            event.setResult(null);
        }
    }

    // Block putting the custom head into the helmet slot manually
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHelmetEquip(InventoryClickEvent event) {
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (isImpersonationHead(event.getCursor()) || isImpersonationHead(event.getCurrentItem())) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isImpersonationHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(headTargetNameKey, PersistentDataType.STRING);
    }

    // =========================================================================
    // 3. IMPERSONATION ENGINE (1.20.4 TO 26.3+ SAFE)
    // =========================================================================
    private void startImpersonating(Player player, String targetName, String texture, String signature) {
        // If already disguised, revert to original first so we never overwrite real backup data
        if (activeSessions.containsKey(player.getUniqueId())) {
            stopImpersonating(player, false);
        }

        PlayerProfile originalProfileCopy = player.getPlayerProfile().clone();
        String realName = player.getName();

        // Save crash-recovery backup to player's persistent NBT data
        saveCrashBackup(player, originalProfileCopy, realName);

        ImpersonationSession session = new ImpersonationSession(
                originalProfileCopy,
                realName,
                player.displayName(),
                player.playerListName(),
                targetName,
                DURATION_SECONDS
        );
        activeSessions.put(player.getUniqueId(), session);

        // Build disguised profile (Truncate name to 16 chars max to prevent packet kick on 1.20.4–26.3)
        String safeProfileName = targetName.length() > 16 ? targetName.substring(0, 16) : targetName;
        PlayerProfile disguisedProfile = Bukkit.createProfile(player.getUniqueId(), safeProfileName);

        // Copy textures from head NBT or online target
        if (texture != null && !texture.isEmpty()) {
            disguisedProfile.setProperty(new ProfileProperty("textures", texture, signature));
        } else {
            Player onlineTarget = Bukkit.getPlayerExact(targetName);
            if (onlineTarget != null) {
                disguisedProfile.setProperties(onlineTarget.getPlayerProfile().getProperties());
            }
        }

        // Apply profile and update Adventure components for Tab & Chat
        Component targetNameComponent = Component.text(targetName);
        player.setPlayerProfile(disguisedProfile);
        player.displayName(targetNameComponent);
        player.playerListName(targetNameComponent);

        // Refresh entity tracker across all online players to prevent invisible skin glitches
        refreshPlayerVisuals(player);

        // Optional SkinsRestorer hook for offline/cracked servers
        if (Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sr skin " + realName + " " + targetName);
        }

        // Cross-version particle & sound effect (works on 1.20.4 through 26.3 without enum mismatch)
        spawnCrossVersionActivationEffect(player);

        // Exact chat message from video
        player.sendMessage(Component.text("You are now impersonating ", NamedTextColor.GREEN)
                .append(Component.text(targetName, NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.GRAY)));
    }

    private void stopImpersonating(Player player, boolean notify) {
        ImpersonationSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) return;

        player.setPlayerProfile(session.originalProfile);
        player.displayName(session.originalDisplayName);
        player.playerListName(session.originalListName);
        clearCrashBackup(player);

        if (player.isOnline()) {
            refreshPlayerVisuals(player);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sr skin clear " + session.realName);
        }

        if (notify && player.isOnline()) {
            player.sendActionBar(Component.empty());
            player.sendMessage(Component.text("Your impersonation of ", NamedTextColor.RED)
                    .append(Component.text(session.targetName, NamedTextColor.AQUA))
                    .append(Component.text(" has expired!", NamedTextColor.RED)));
        }
    }

    private void refreshPlayerVisuals(Player disguisedPlayer) {
        if (!isEnabled()) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(disguisedPlayer)) continue;
            viewer.hidePlayer(this, disguisedPlayer);
            viewer.showPlayer(this, disguisedPlayer);
        }
    }

    private void spawnCrossVersionActivationEffect(Player player) {
        // Resolve TOTEM particle dynamically so 1.20.4 (TOTEM) and 1.20.5–26.3+ (TOTEM_OF_UNDYING) both work
        Particle totemParticle = null;
        try {
            totemParticle = Particle.valueOf("TOTEM_OF_UNDYING");
        } catch (IllegalArgumentException ignored) {
            try {
                totemParticle = Particle.valueOf("TOTEM");
            } catch (IllegalArgumentException ignored2) {
                totemParticle = Particle.VILLAGER_HAPPY;
            }
        }
        player.getWorld().spawnParticle(totemParticle, player.getLocation().add(0, 1.0, 0), 30, 0.4, 0.5, 0.4, 0.15);
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.7f, 1.2f);
    }

    // =========================================================================
    // 4. CHAT OVERRIDE & CRASH RECOVERY
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        ImpersonationSession session = activeSessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;

        // Ensure chat renders the impersonated name even if another chat plugin isn't installed
        Component disguisedName = Component.text(session.targetName);
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.translatable("chat.type.text", disguisedName, message));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (activeSessions.containsKey(event.getPlayer().getUniqueId())) {
            stopImpersonating(event.getPlayer(), false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Recover true identity if server crashed while player was disguised
        Player player = event.getPlayer();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(realNameBackupKey, PersistentDataType.STRING)) {
            String realName = pdc.get(realNameBackupKey, PersistentDataType.STRING);
            String tex = pdc.get(realTextureBackupKey, PersistentDataType.STRING);
            String sig = pdc.get(realSignatureBackupKey, PersistentDataType.STRING);

            if (realName != null) {
                PlayerProfile restored = Bukkit.createProfile(player.getUniqueId(), realName);
                if (tex != null) {
                    restored.setProperty(new ProfileProperty("textures", tex, sig));
                }
                player.setPlayerProfile(restored);
                player.displayName(Component.text(realName));
                player.playerListName(Component.text(realName));
            }
            clearCrashBackup(player);
        }
    }

    private void saveCrashBackup(Player player, PlayerProfile profile, String realName) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(realNameBackupKey, PersistentDataType.STRING, realName);
        for (ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())) {
                pdc.set(realTextureBackupKey, PersistentDataType.STRING, prop.getValue());
                if (prop.getSignature() != null) {
                    pdc.set(realSignatureBackupKey, PersistentDataType.STRING, prop.getSignature());
                }
                break;
            }
        }
    }

    private void clearCrashBackup(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(realNameBackupKey);
        pdc.remove(realTextureBackupKey);
        pdc.remove(realSignatureBackupKey);
    }

    private String getTrueName(Player player) {
        ImpersonationSession session = activeSessions.get(player.getUniqueId());
        return session != null ? session.realName : player.getName();
    }

    // =========================================================================
    // 5. LIVE ACTION BAR COUNTDOWN ("Impersonating <Name> | MM:SS ⏳")
    // =========================================================================
    private void startActionBarTimer() {
        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Map.Entry<UUID, ImpersonationSession> entry : activeSessions.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                ImpersonationSession session = entry.getValue();

                if (player == null || !player.isOnline()) {
                    continue;
                }

                if (session.remainingSeconds <= 0) {
                    stopImpersonating(player, true);
                    continue;
                }

                int minutes = session.remainingSeconds / 60;
                int seconds = session.remainingSeconds % 60;
                String formattedTime = String.format("%02d:%02d", minutes, seconds);

                Component actionBar = Component.text("Impersonating ", NamedTextColor.GOLD)
                        .append(Component.text(session.targetName, NamedTextColor.AQUA))
                        .append(Component.text(" | ", NamedTextColor.GRAY))
                        .append(Component.text(formattedTime, NamedTextColor.YELLOW))
                        .append(Component.text(" \u23F3", NamedTextColor.GRAY));

                player.sendActionBar(actionBar);
                session.remainingSeconds--;
            }
        }, 0L, 20L);
    }

    private static class ImpersonationSession {
        final PlayerProfile originalProfile;
        final String realName;
        final Component originalDisplayName;
        final Component originalListName;
        final String targetName;
        int remainingSeconds;

        ImpersonationSession(PlayerProfile originalProfile, String realName,
                             Component originalDisplayName, Component originalListName,
                             String targetName, int remainingSeconds) {
            this.originalProfile = originalProfile;
            this.realName = realName;
            this.originalDisplayName = originalDisplayName;
            this.originalListName = originalListName;
            this.targetName = targetName;
            this.remainingSeconds = remainingSeconds;
        }
    }
}
