package org.Cry_Legende.sHInteractReset;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class SHInteractReset extends JavaPlugin implements Listener, CommandExecutor {

    private int delayTicks;
    private Set<String> whitelistedWorldsForReset; // Renommé pour plus de clarté
    private boolean debugMode;

    // Configuration pour la fonctionnalité double porte/trappe
    private boolean doubleFeatureEnabled;
    private boolean doubleFeatureRespectsWhitelist;

    private final Map<Location, PendingResetTask> pendingTasks = new HashMap<>();
    private static final String BYPASS_PERMISSION = "shinteractreset.bypass";

    // Définitions pour trouver les portes partenaires (inspiré de DoorsReloaded)
    private static class PossiblePartnerRule {
        final int offsetX, offsetZ;
        final Door.Hinge primaryDoorHinge;
        final BlockFace primaryDoorFacing;

        public PossiblePartnerRule(int offsetX, int offsetZ, Door.Hinge primaryDoorHinge, BlockFace primaryDoorFacing) {
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.primaryDoorHinge = primaryDoorHinge;
            this.primaryDoorFacing = primaryDoorFacing;
        }
    }

    private static final PossiblePartnerRule[] POSSIBLE_DOOR_PARTNER_RULES = new PossiblePartnerRule[]{
        new PossiblePartnerRule(0, -1, Door.Hinge.RIGHT, BlockFace.EAST),
        new PossiblePartnerRule(0, 1, Door.Hinge.LEFT, BlockFace.EAST),
        new PossiblePartnerRule(1, 0, Door.Hinge.RIGHT, BlockFace.SOUTH),
        new PossiblePartnerRule(-1, 0, Door.Hinge.LEFT, BlockFace.SOUTH),
        new PossiblePartnerRule(0, 1, Door.Hinge.RIGHT, BlockFace.WEST),
        new PossiblePartnerRule(0, -1, Door.Hinge.LEFT, BlockFace.WEST),
        new PossiblePartnerRule(-1, 0, Door.Hinge.RIGHT, BlockFace.NORTH),
        new PossiblePartnerRule(1, 0, Door.Hinge.LEFT, BlockFace.NORTH)
    };


    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shinteractresetreload").setExecutor(this);

        getLogger().info("SHInteractReset a été activé !");
        if (debugMode) {
            getLogger().info("Mondes whitelistés pour reset: " + whitelistedWorldsForReset);
            getLogger().info("Délai (ticks): " + delayTicks);
            getLogger().info("Double feature activé: " + doubleFeatureEnabled);
            getLogger().info("Double feature respecte whitelist: " + doubleFeatureRespectsWhitelist);
        }
    }

    /**
     * Annule les resets en attente et remet tout de suite les blocs dans leur état d'origine :
     * sinon une porte ouverte juste avant un arrêt ou un rechargement resterait ouverte.
     */
    private void flushPendingResets() {
        for (Map.Entry<Location, PendingResetTask> entry : pendingTasks.entrySet()) {
            PendingResetTask taskInfo = entry.getValue();
            if (taskInfo.getTask() != null && !taskInfo.getTask().isCancelled()) {
                taskInfo.getTask().cancel();
            }
            Location loc = entry.getKey();
            if (loc.getWorld() == null) {
                continue;
            }
            Block block = loc.getBlock();
            BlockData target = taskInfo.getInitialState();
            if (block.getType() == target.getMaterial() && !block.getBlockData().matches(target)) {
                block.setBlockData(target, true);
            }
        }
        pendingTasks.clear();
    }

    @Override
    public void onDisable() {
        flushPendingResets();
        getLogger().info("SHInteractReset a été désactivé et les tâches en attente annulées.");
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        delayTicks = config.getInt("delay-seconds", 5) * 20;
        whitelistedWorldsForReset = new HashSet<>(config.getStringList("whitelisted-worlds"));
        debugMode = config.getBoolean("debug-mode", false);

        doubleFeatureEnabled = config.getBoolean("double-feature.enabled", true);
        doubleFeatureRespectsWhitelist = config.getBoolean("double-feature.respects-whitelist", false);

        if (delayTicks <= 0) {
            getLogger().warning("Le délai configuré est invalide (<= 0). Utilisation d'un délai par défaut de 5 secondes (100 ticks).");
            delayTicks = 100;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shinteractresetreload")) {
            if (!sender.hasPermission("shinteractreset.reload")) {
                sender.sendMessage("§cVous n'avez pas la permission d'exécuter cette commande.");
                return true;
            }
            flushPendingResets();

            reloadConfig();
            loadConfigValues(); // Recharge toutes les valeurs
            sender.sendMessage("§aConfiguration de SHInteractReset rechargée.");
            if (debugMode) {
                getLogger().info("Configuration rechargée via commande.");
                getLogger().info("Nouveaux mondes whitelistés pour reset: " + whitelistedWorldsForReset);
                getLogger().info("Nouveau délai (ticks): " + delayTicks);
                getLogger().info("Double feature activé: " + doubleFeatureEnabled);
                getLogger().info("Double feature respecte whitelist: " + doubleFeatureRespectsWhitelist);
            }
            return true;
        }
        return false;
    }

    // Augmenter la priorité pour agir après d'autres plugins qui pourraient modifier l'état, mais avant que le jeu ne finalise l'interaction
    // Surtout si on voulait annuler l'event, ce qu'on ne fait pas ici. MONITOR est sûr si on lit l'état.
    // Si on modifie activement (comme pour la porte partenaire), une priorité plus haute que LOWEST est bien.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        BlockData blockData = clickedBlock.getBlockData();

        if (!(blockData instanceof Openable)) {
            return; // Pas un bloc ouvrable
        }

        Player player = event.getPlayer();
        Location blockLocation = clickedBlock.getLocation();
        String worldName = blockLocation.getWorld().getName();
        Openable openableData = (Openable) blockData;

        // Doit-on appliquer la fonctionnalité de reset pour ce bloc ?
        boolean applyResetLogic = whitelistedWorldsForReset.contains(worldName);

        if (player.hasPermission(BYPASS_PERMISSION)) {
            if (debugMode && applyResetLogic) { // Log seulement si le reset aurait été appliqué
                getLogger().log(Level.INFO, "Joueur " + player.getName() + " a la permission bypass. Interaction ignorée pour SHInteractReset sur " + clickedBlock.getType() + " à " + blockLocation);
            }
            return; // Le joueur bypass le reset, et donc aussi la logique de double porte liée au reset.
        }

        // Accroupi avec un objet en main : le serveur ne touche pas au bloc (il poserait un bloc contre lui),
        // il ne faut donc ni synchroniser le partenaire ni planifier de reset
        if (player.isSneaking() && (!player.getInventory().getItemInMainHand().isEmpty()
            || !player.getInventory().getItemInOffHand().isEmpty())) {
            return;
        }

        // L'état du bloc AVANT que l'interaction du joueur ne soit effectivement appliquée par le serveur.
        BlockData initialStateOfClickedBlock = clickedBlock.getBlockData().clone();
        boolean primaryIsOpenBeforePlayerInteraction = openableData.isOpen();

        if (debugMode) {
            getLogger().log(Level.INFO, "Interaction: " + clickedBlock.getType() + " par " + player.getName() + " à " + blockLocation + " (état avant: " + (primaryIsOpenBeforePlayerInteraction ? "Ouvert" : "Fermé") + ")");
        }

        // Gérer la fonctionnalité double porte/trappe
        if (doubleFeatureEnabled) {
            boolean syncDoubleFeatureInThisWorld = !doubleFeatureRespectsWhitelist || whitelistedWorldsForReset.contains(worldName);
            if (syncDoubleFeatureInThisWorld) {
                Block partnerBlock = findPartnerBlock(clickedBlock, openableData, initialStateOfClickedBlock);
                if (partnerBlock != null) {
                    if (debugMode)
                        getLogger().info("Partenaire trouvé pour " + clickedBlock.getType() + " : " + partnerBlock.getType() + " à " + partnerBlock.getLocation());

                    BlockData partnerBlockData = partnerBlock.getBlockData();
                    if (partnerBlockData instanceof Openable) {
                        Openable partnerOpenable = (Openable) partnerBlockData;
                        BlockData initialStateOfPartnerBlock = partnerBlockData.clone(); // Etat du partenaire AVANT notre modification

                        // La porte principale va changer d'état (ex: fermé -> ouvert). Le partenaire doit faire pareil.
                        boolean targetPartnerOpenState = !primaryIsOpenBeforePlayerInteraction;

                        if (partnerOpenable.isOpen() != targetPartnerOpenState) {
                            partnerOpenable.setOpen(targetPartnerOpenState);
                            partnerBlock.setBlockData(partnerOpenable, true); // true pour physique (son de la porte vanilla)
                            // Notre son custom pour le reset se fera plus tard si reset.
                            // Ici on peut jouer le son de l'interaction immédiate si on veut,
                            // mais le setBlockData avec true le fait déjà.
                            // playSoundForStateChange(partnerBlock, partnerOpenable); // Optionnel ici, car setBlockData(..., true) le fait.

                            if (debugMode)
                                getLogger().info("Partenaire " + partnerBlock.getType() + " changé à " + (targetPartnerOpenState ? "Ouvert" : "Fermé"));

                            // Si le reset est actif pour le bloc principal, il l'est aussi pour le partenaire
                            if (applyResetLogic) {
                                scheduleResetTask(partnerBlock, initialStateOfPartnerBlock);
                            }
                        }
                    }
                }
            }
        }

        // Planifier le reset pour le bloc cliqué si on est dans un monde whitelisté
        if (applyResetLogic) {
            scheduleResetTask(clickedBlock, initialStateOfClickedBlock);
        }
    }

    private void scheduleResetTask(Block block, BlockData stateToRevertTo) {
        Location blockLocation = block.getLocation();

        // Annuler une tâche existante pour ce bloc si le joueur interagit à nouveau rapidement
        PendingResetTask existingTaskInfo = pendingTasks.remove(blockLocation);
        if (existingTaskInfo != null && existingTaskInfo.getTask() != null && !existingTaskInfo.getTask().isCancelled()) {
            existingTaskInfo.getTask().cancel();
            if (debugMode) getLogger().info("Tâche de reset existante annulée pour " + blockLocation);
        }

        // L'état vers lequel on veut revenir est celui avant la PREMIERE interaction de cette "séquence" pour ce bloc.
        // Si une tâche existait, son 'initialState' est le bon. Sinon, c'est 'stateToRevertTo' (qui est l'état avant CE clic).
        BlockData targetRevertState = (existingTaskInfo != null) ? existingTaskInfo.getInitialState() : stateToRevertTo;

        if (debugMode) {
            getLogger().info("Planification du reset pour " + block.getType() + " à " + blockLocation + ". Reviendra à: " + targetRevertState.getAsString());
        }

        BukkitTask newTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            // Monde déchargé entre-temps : rien à remettre
            if (blockLocation.getWorld() == null) {
                pendingTasks.remove(blockLocation);
                return;
            }
            Block blockInFuture = blockLocation.getBlock();

            if (blockInFuture.getType() == targetRevertState.getMaterial()) {
                BlockData currentStateInFuture = blockInFuture.getBlockData();
                if (!currentStateInFuture.matches(targetRevertState)) {
                    blockInFuture.setBlockData(targetRevertState, true);
                    playSoundForStateChange(blockInFuture, targetRevertState); // Jouer notre son custom
                    if (debugMode) {
                        getLogger().info("Bloc à " + blockLocation + " réinitialisé à " + targetRevertState.getAsString());
                    }
                } else {
                    if (debugMode) {
                        getLogger().info("Bloc à " + blockLocation + " est déjà dans l'état de revert cible. Aucune action de reset.");
                    }
                }
            } else {
                if (debugMode) {
                    getLogger().info("Bloc à " + blockLocation + " a changé de type ou n'existe plus. Annulation du reset.");
                }
            }
            pendingTasks.remove(blockLocation);
        }, delayTicks);

        pendingTasks.put(blockLocation, new PendingResetTask(newTask, targetRevertState));
    }

    /**
     * Trouve un bloc partenaire (double porte/trappe).
     *
     * @param primaryBlock                  Le bloc avec lequel le joueur a interagi.
     * @param primaryOpenableData           Le BlockData Openable du bloc primaire.
     * @param primaryStateBeforeInteraction L'état du bloc primaire AVANT l'interaction du joueur.
     * @return Le bloc partenaire, ou null s'il n'est pas trouvé.
     */
    private Block findPartnerBlock(Block primaryBlock, Openable primaryOpenableData, BlockData primaryStateBeforeInteraction) {
        if (primaryOpenableData instanceof Door) {
            Door primaryDoor = (Door) primaryOpenableData;
            Block primaryDoorActualPhysicalBlock = primaryBlock; // Le bloc physique cliqué

            // Toujours travailler à partir de la partie inférieure de la porte pour la logique de détection
            if (primaryDoor.getHalf() == Bisected.Half.TOP) {
                Block bottomHalfBlock = primaryBlock.getRelative(BlockFace.DOWN);
                if (bottomHalfBlock.getBlockData() instanceof Door && ((Door) bottomHalfBlock.getBlockData()).getMaterial() == primaryDoor.getMaterial()) {
                    primaryDoorActualPhysicalBlock = bottomHalfBlock;
                    primaryDoor = (Door) bottomHalfBlock.getBlockData();
                } else {
                    return null; // Porte mal formée
                }
            }

            for (PossiblePartnerRule rule : POSSIBLE_DOOR_PARTNER_RULES) {
                if (primaryDoor.getFacing() != rule.primaryDoorFacing) continue;
                if (primaryDoor.getHinge() != rule.primaryDoorHinge) continue;

                Block potentialPartnerBottomBlock = primaryDoorActualPhysicalBlock.getRelative(rule.offsetX, 0, rule.offsetZ);
                if (potentialPartnerBottomBlock.getType() != primaryDoorActualPhysicalBlock.getType())
                    continue; // Doit être du même matériau

                BlockData partnerData = potentialPartnerBottomBlock.getBlockData();
                if (!(partnerData instanceof Door)) continue;
                Door partnerDoor = (Door) partnerData;

                // S'assurer qu'on a la partie inférieure du partenaire aussi
                if (partnerDoor.getHalf() == Bisected.Half.TOP) {
                    Block tempBottom = potentialPartnerBottomBlock.getRelative(BlockFace.DOWN);
                    if (tempBottom.getBlockData() instanceof Door && ((Door) tempBottom.getBlockData()).getMaterial() == partnerDoor.getMaterial()) {
                        potentialPartnerBottomBlock = tempBottom; // Mettre à jour le bloc vers sa partie inférieure
                        partnerDoor = (Door) tempBottom.getBlockData(); // Mettre à jour le BlockData
                    } else {
                        continue; // Partenaire mal formé
                    }
                }

                // Conditions pour être un partenaire valide (inspiré de DoorsReloaded)
                if (partnerDoor.getFacing() == primaryDoor.getFacing() &&      // Même orientation
                    partnerDoor.getHinge() != primaryDoor.getHinge() &&        // Charnière opposée
                    ((Openable) primaryStateBeforeInteraction).isOpen() == partnerDoor.isOpen()) { // Même état (ouvert/fermé) AVANT l'interaction du joueur
                    return potentialPartnerBottomBlock; // Retourne la partie inférieure du bloc partenaire
                }
            }

        } else if (primaryOpenableData instanceof TrapDoor) {
            TrapDoor primaryTrapDoor = (TrapDoor) primaryOpenableData;
            // Pour les trappes, chercher une trappe adjacente de même type et dans le même état (avant interaction)
            BlockFace[] horizontalAdjacent = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
            for (BlockFace face : horizontalAdjacent) {
                Block potentialPartnerBlock = primaryBlock.getRelative(face);
                if (potentialPartnerBlock.getType() == primaryBlock.getType()) { // Même matériau
                    BlockData partnerData = potentialPartnerBlock.getBlockData();
                    if (partnerData instanceof TrapDoor) {
                        TrapDoor partnerTrapDoor = (TrapDoor) partnerData;
                        // Même état (ouvert/fermé) AVANT l'interaction du joueur
                        if (((Openable) primaryStateBeforeInteraction).isOpen() == partnerTrapDoor.isOpen()) {
                            return potentialPartnerBlock;
                        }
                    }
                }
            }
        }
        return null;
    }


    private void playSoundForStateChange(Block block, BlockData newStateData) {
        if (!(newStateData instanceof Openable)) {
            return;
        }
        Openable openableState = (Openable) newStateData;
        Material material = block.getType();
        boolean isNowOpen = openableState.isOpen();
        Sound soundToPlay = null;
        float pitch = 1.0f;

        String materialName = material.toString();

        if (materialName.contains("_DOOR")) {
            soundToPlay = switch (material) {
                case IRON_DOOR -> isNowOpen ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE;
                default -> isNowOpen ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE;
            };
        } else if (materialName.contains("_TRAPDOOR")) {
            soundToPlay = switch (material) {
                case IRON_TRAPDOOR -> isNowOpen ? Sound.BLOCK_IRON_TRAPDOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_CLOSE;
                default -> isNowOpen ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE;
            };
            // Pitch différent pour les trappes en bois vanilla
            if (!materialName.startsWith("IRON_")) pitch = isNowOpen ? 1.15f : 0.85f; // Ajusté un peu

        } else if (materialName.contains("_FENCE_GATE")) {
            soundToPlay = isNowOpen ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE;
        }

        if (soundToPlay != null) {
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                soundToPlay,
                SoundCategory.BLOCKS,
                1.0f,
                pitch);
            if (debugMode) {
                getLogger().info("Son de reset joué: " + Registry.SOUND_EVENT.getKey(soundToPlay) + " pour " + material.name() + " à " + block.getLocation() + " (nouvel état ouvert: " + isNowOpen + ")");
            }
        }
    }

    private static class PendingResetTask {
        private final BukkitTask task;
        private final BlockData initialState;

        public PendingResetTask(BukkitTask task, BlockData initialState) {
            this.task = task;
            this.initialState = initialState;
        }

        public BukkitTask getTask() {
            return task;
        }

        public BlockData getInitialState() {
            return initialState;
        }
    }
}
