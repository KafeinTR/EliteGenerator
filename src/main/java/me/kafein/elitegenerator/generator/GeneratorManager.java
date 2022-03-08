package me.kafein.elitegenerator.generator;

import me.kafein.elitegenerator.EliteGenerator;
import me.kafein.elitegenerator.config.FileManager;
import me.kafein.elitegenerator.event.GeneratorDeleteEvent;
import me.kafein.elitegenerator.generator.feature.FeatureManager;
import me.kafein.elitegenerator.generator.feature.auto.autoBreak.AutoBreakManager;
import me.kafein.elitegenerator.generator.feature.boost.task.BoostRunnable;
import me.kafein.elitegenerator.generator.feature.calendar.CalendarSerializer;
import me.kafein.elitegenerator.generator.feature.item.GeneratorItem;
import me.kafein.elitegenerator.generator.feature.permission.MemberPermission;
import me.kafein.elitegenerator.hook.skyblock.SkyBlockHook;
import me.kafein.elitegenerator.storage.Storage;
import me.kafein.elitegenerator.user.User;
import me.kafein.elitegenerator.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GeneratorManager {

    final private FileManager fileManager = EliteGenerator.getInstance().getFileManager();
    final private Storage storage = EliteGenerator.getInstance().getStorageManager().get();
    final private SkyBlockHook skyBlockHook = EliteGenerator.getInstance().getHookManager().getSkyBlockHook();
    final private FeatureManager featureManager;
    private UserManager userManager;

    final private Map<UUID, Generator> generators = new HashMap<>();
    final private Map<Location, UUID> generatorLocations = new HashMap<>();
    final private Map<Location, List<UUID>> generatorIslands = new HashMap<>();

    private GeneratorItem generatorItem = new GeneratorItem(fileManager.getFile(FileManager.ConfigFile.settings));
    private Material firstBlockMaterial = Material.getMaterial(fileManager.getFile(FileManager.ConfigFile.settings).getString("settings.generator.generator-first-material"));

    private boolean boostRunnableStarted;

    final private Plugin plugin;

    public GeneratorManager(final Plugin plugin) {
        this.plugin = plugin;
        featureManager = new FeatureManager(plugin);
    }

    public CompletableFuture<Void> saveGenerators() {

        return CompletableFuture.runAsync(() -> {

            if (generators.isEmpty()) return;

            generators.keySet().forEach(this::saveGenerator);

        });

    }

    public boolean placeGenerator(final Location location, final Player owner, final int level, final boolean autoBreak, final boolean autoPickup, final boolean autoSmelt, final boolean autoChest) {

        final UUID ownerUUID = owner.getUniqueId();
        final UUID generatorUUID = UUID.randomUUID();

        if (!location.getWorld().getName().equals(skyBlockHook.getIslandWorld().getName())) {

            owner.sendMessage(fileManager.getMessage("generator.thisWorldIsNotIslandWorld"));
            return false;
            
        }

        if (!skyBlockHook.hasIsland(ownerUUID)
                || !skyBlockHook.getIslandOwner(ownerUUID).equals(ownerUUID)) {
            owner.sendMessage(fileManager.getMessage("generator.thisIslandIsNotYour"));
            return false;
        }

        final Location islandLocation = skyBlockHook.getIslandCenterLocation(ownerUUID);

        if (generatorIslands.containsKey(islandLocation)
                && generatorIslands.get(islandLocation).size() >= fileManager.getFile(FileManager.ConfigFile.settings).getInt("settings.generator.generator-island-limit")) {

            owner.sendMessage(fileManager.getMessage("generator.generatorIslandLimit")
                    .replace("%max_generator_amount%", Integer.toString(fileManager.getFile(FileManager.ConfigFile.settings).getInt("settings.generator.generator-island-limit"))));
            return false;

        }

        final Generator generator = new Generator(islandLocation, generatorUUID, owner.getName() + "'s Generator", location, level);
        generator.changeOwnerUUID(ownerUUID);
        generator.setAutoBreakBuyed(autoBreak);
        generator.setAutoPickupBuyed(autoPickup);
        generator.setAutoSmeltBuyed(autoSmelt);
        generator.setAutoChestBuyed(autoChest);

        generator.setCreateDate(CalendarSerializer.nowDate());

        for (UUID uuid : skyBlockHook.getIslandMembers(ownerUUID)) {

            final GeneratorMember generatorMember = new GeneratorMember(uuid);

            if (uuid.equals(ownerUUID)) {
                generatorMember.addPermission(MemberPermission.BREAK_GENERATOR);
                generatorMember.addPermission(MemberPermission.OPEN_SETTINGS);
                generatorMember.addPermission(MemberPermission.CHANGE_SETTINGS);
            }

            generator.addGeneratorMember(generatorMember);

            User user = getUserManager().getUser(uuid);
            if (user != null) user.addGenerator(generatorUUID);
            else {
                user = getUserManager().getUserFromStorage(uuid);
                user.addGenerator(generatorUUID);
                getUserManager().saveUser(user);
            }

        }

        loadGenerator(generator);
        owner.sendMessage(fileManager.getMessage("generator.generatorPlaced"));

        return true;

    }

    public boolean loadGenerator(final Generator generator) {

        final UUID generatorUUID = generator.getGeneratorUUID();

        if (generators.containsKey(generator.getGeneratorUUID())) return true;

        generators.put(generatorUUID, generator);
        generatorLocations.put(generator.getGeneratorLocation(), generatorUUID);

        final Location islandLocation = generator.getIslandLocation();
        if (!generatorIslands.containsKey(islandLocation)) generatorIslands.put(islandLocation, new ArrayList<>());
        generatorIslands.get(islandLocation).add(generatorUUID);

        Bukkit.getScheduler().runTask(plugin, () -> {
            generator.getGeneratorLocation().getBlock().setType(firstBlockMaterial);
            featureManager.getHologramManager().loadHologram(generator);
        });

        if (generator.isAutoBreakEnabled()) {
            final AutoBreakManager autoBreakManager = featureManager.getAutoBreakManager();
            autoBreakManager.addAutoBreakerGenerator(generatorUUID);
            autoBreakManager.startRunnable();
        }

        if (!boostRunnableStarted) {
            new BoostRunnable(plugin);
            boostRunnableStarted = true;
        }

        return true;

    }

    public boolean loadGenerator(final UUID generatorUUID) {

        if (generators.containsKey(generatorUUID)) return true;

        final Generator generator = storage.loadGenerator(generatorUUID);
        if (generator == null) return false;

        loadGenerator(generator);

        return true;

    }

    public void saveGenerator(final UUID generatorUUID) {

        final Generator generator = generators.get(generatorUUID);

        generator.clearHologram();

        storage.saveGenerator(generator);

        if (generator.isAutoBreakEnabled()) featureManager.getAutoBreakManager().removeAutoBreakerGenerator(generatorUUID);

        generatorLocations.remove(generator.getGeneratorLocation());
        generatorIslands.get(generator.getIslandLocation()).remove(generatorUUID);
        generators.remove(generatorUUID);

    }

    @Nullable
    public Generator getGenerator(final UUID generatorUUID) {
        return generators.get(generatorUUID);
    }

    @Nullable
    public Generator getGenerator(final Location location) {
        return generators.get(generatorLocations.get(location));
    }

    @Nullable
    public List<UUID> getGenerators(final Location islandLocation) {
        return generatorIslands.get(islandLocation);
    }

    @Nullable
    public UUID getGeneratorUUID(final Location location) {
        return generatorLocations.get(location);
    }

    public boolean deleteGenerator(final UUID generatorUUID) {

        final Generator generator = generators.get(generatorUUID);

        final GeneratorDeleteEvent generatorDeleteEvent = new GeneratorDeleteEvent(generator, false);
        Bukkit.getPluginManager().callEvent(generatorDeleteEvent);

        if (generatorDeleteEvent.isCancelled()) return false;

        if (generator.hasHologram()) generator.clearHologram();

        featureManager.getRegenManager().removeRegenGenerator(generator.getGeneratorLocation());
        generatorLocations.remove(generator.getGeneratorLocation());
        generatorIslands.remove(generator.getIslandLocation());
        storage.deleteGenerator(generatorUUID);
        generators.remove(generatorUUID);

        generator.getGeneratorLocation().getBlock().setType(Material.AIR);

        return true;

    }

    public boolean containsGeneratorLocation(final Location generatorLocation) {
        return generatorLocations.containsKey(generatorLocation);
    }

    public boolean containsGeneratorUUID(final UUID generatorUUID) {
        return generators.containsKey(generatorUUID);
    }

    public boolean containsGeneratorIslandLocation(final Location location) {
        return generatorIslands.containsKey(location);
    }

    public int generatorListSize() {
        return generators.size();
    }

    public GeneratorItem getGeneratorItem() {
        return generatorItem;
    }

    public void reloadGeneratorItem() {
        generatorItem = new GeneratorItem(fileManager.getFile(FileManager.ConfigFile.settings));
    }

    public void reloadFirstBlockMaterial() {
        firstBlockMaterial = Material.getMaterial(fileManager.getFile(FileManager.ConfigFile.settings).getString("settings.generator.generator-first-material"));
    }

    public FeatureManager getFeatureManager() {
        return featureManager;
    }

    private UserManager getUserManager() {
        if (userManager == null) userManager = EliteGenerator.getInstance().getUserManager();
        return userManager;
    }

    public Iterator<Generator> getGeneratorsIterator() {
        return generators.values().iterator();
    }

}
