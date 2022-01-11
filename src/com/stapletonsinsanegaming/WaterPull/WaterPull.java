package com.stapletonsinsanegaming.WaterPull;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permission;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class WaterPull extends WaterAbility implements AddonAbility {

    private enum State {
        SOURCE_SELECTED, TRAVELLING, PULLING
    }

    private static final String AUTHOR = "OutLaw";
    private static final String VERSION = "1.0.0";
    private static final String NAME = "WaterPull";
    private static final String DESCRIPTION = "A move that allows a water bender to pull a enemy into a water source.";

    private static final Vector NULL_VECTOR = new Vector();

    private static final long COOLDOWN = 2000;
    private static final double SOURCE_RANGE = 6;
    private static final double RANGE = 20;
    private static final double SPEED = .9;
    private static final double HITBOX = 1.5;

    private Listener listener;
    private Permission perm;

    private Block sourceBlock;
    private Location location;
    private Vector direction;
    private LivingEntity target;
    private double distanceTravelled;
    private List<TempBlock> tempBlocks;
    private State state;

    public WaterPull(Player player) {
        super(player);

        // find source block
        Block block = getWaterSourceBlock(player, SOURCE_RANGE, bPlayer.canPlantbend());
        if (block == null) {
            return;
        }

        WaterPull existing = getAbility(player, getClass());
        if (existing != null) {
            existing.remove();
        }

        sourceBlock = block;
        location = block.getLocation().add(.5, .5, .5);
        tempBlocks = new LinkedList<>();
        state = State.SOURCE_SELECTED;

        start();
    }

    private void progressSourceSelected() {
        playFocusWaterEffect(sourceBlock);

        if (sourceBlock.getLocation().distanceSquared(player.getLocation()) > SOURCE_RANGE * SOURCE_RANGE
                || !isWaterbendable(player, sourceBlock)) {
            remove();
        }
    }

    private void progressTravelling() {
        location.add(direction);
        distanceTravelled += SPEED;

        if (isWater(location.getBlock()) && location.getWorld() != null) {
            location.getWorld().spawnParticle(Particle.WATER_BUBBLE, location, 8, .5, .5, .5, 0);
        }

        List<Block> line = getBlocksAlongLine(sourceBlock.getLocation().add(.5, .5, .5), location);
        for (int i = tempBlocks.size(); i < line.size(); i++) {
            Block blockOnLine = line.get(i);

            if (GeneralMethods.isSolid(blockOnLine)) {
                if (TempBlock.isTempBlock(blockOnLine)) {
                    TempBlock tb = TempBlock.get(blockOnLine);
                    if (!tempBlocks.contains(tb)) {
                        state = State.PULLING;
                        return;
                    }
                } else if (blockOnLine != sourceBlock) {
                    // hit a wall
                    state = State.PULLING;
                    return;
                }
            }

            tempBlocks.add(new TempBlock(blockOnLine, Material.WATER));
        }

        Optional<LivingEntity> potentialTarget = GeneralMethods.getEntitiesAroundPoint(location, HITBOX)
                .stream()
                .filter(entity -> entity instanceof LivingEntity && entity.getUniqueId() != player.getUniqueId())
                .map(entity -> (LivingEntity) entity)
                .findFirst();

        if (potentialTarget.isPresent() || distanceTravelled > RANGE) {
            target = potentialTarget.orElse(null);
            state = State.PULLING;
        }
    }

    private void progressPulling() {
        location.subtract(direction);

        List<Block> line = getBlocksAlongLine(sourceBlock.getLocation().add(.5, .5, .5), location);
        if (line.size() < tempBlocks.size()) {
            for (int i = line.size(); i < tempBlocks.size(); i++) {
                tempBlocks.remove(i).revertBlock();
            }
        }

        if (target != null) {
            if (target.getLocation().getWorld() != location.getWorld() || target.isDead()) {
                target = null;
            } else if (target instanceof Player && !((Player) target).isOnline()) {
                target = null;
            } else {
                Vector towardsLocation = GeneralMethods.getDirection(target.getLocation(), location);
                if (towardsLocation.length() < .2) {
                    target.setVelocity(NULL_VECTOR);
                } else {
                    target.setVelocity(towardsLocation.normalize().multiply(SPEED));
                }
            }
        }

        if (location.distanceSquared(sourceBlock.getLocation()) < 1) {
            removeWithCooldown();
        }
    }

    private List<Block> getBlocksAlongLine(Location from, Location to) {
        from = from.clone();
        Vector between = GeneralMethods.getDirection(from, to).normalize();
        List<Block> result = new ArrayList<>();
        while (from.distanceSquared(to) > 1) {
            if (!result.contains(from.getBlock())) {
                result.add(from.getBlock());
            }
            from.add(between);
        }
        result.add(to.getBlock());
        return result;
    }

    public void onClick() {
        if (state == State.SOURCE_SELECTED) {
            state = State.TRAVELLING;
            direction = GeneralMethods.getDirection(location, GeneralMethods.getTargetedLocation(player, RANGE)).normalize().multiply(SPEED);
        }
    }

    @Override
    public void progress() {
        if (!bPlayer.canBend(this) || !player.isSneaking()) {
            removeWithCooldown();
            return;
        }
        switch (state) {
            case SOURCE_SELECTED:
                progressSourceSelected();
                break;
            case TRAVELLING:
                progressTravelling();
                break;
            case PULLING:
                progressPulling();
                break;
        }
    }

    private void removeWithCooldown() {
        bPlayer.addCooldown(this);
        remove();
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void load() {
        perm = new Permission("bending.ability." + NAME);
        ProjectKorra.plugin.getServer().getPluginManager().addPermission(perm);
        listener = new WaterPullListener(this);
        ProjectKorra.plugin.getServer().getPluginManager().registerEvents(listener, ProjectKorra.plugin);
    }

    @Override
    public void stop() {
        ProjectKorra.plugin.getServer().getPluginManager().removePermission(perm);
        HandlerList.unregisterAll(listener);
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public String getDescription(){
        return DESCRIPTION;
    }

    @Override
    public long getCooldown() {
        return COOLDOWN;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getAuthor() {
        return AUTHOR;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }
}