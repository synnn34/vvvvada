package jp.shanron.stackgame;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

public final class StackGamePlugin extends JavaPlugin implements Listener {

    private Location min;
    private Location max;
    private Material dropMaterial = Material.DIAMOND_BLOCK;
    private final Queue<Integer> pendingDrops = new ArrayDeque<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        startDropWorker();
        startBoundaryWorker();
        getLogger().info("StackGame enabled.");
    }

    private void startDropWorker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = 25;
                while (budget > 0 && !pendingDrops.isEmpty()) {
                    int remaining = pendingDrops.poll();
                    spawnOne();
                    remaining--;
                    budget--;
                    if (remaining > 0) {
                        pendingDrops.offer(remaining);
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private boolean ready() {
        return min != null
                && max != null
                && min.getWorld() != null
                && min.getWorld().equals(max.getWorld());
    }

    private void setArea(Location a, Location b) {
        World world = a.getWorld();
        min = new Location(
                world,
                Math.min(a.getBlockX(), b.getBlockX()),
                Math.min(a.getBlockY(), b.getBlockY()),
                Math.min(a.getBlockZ(), b.getBlockZ())
        );
        max = new Location(
                world,
                Math.max(a.getBlockX(), b.getBlockX()),
                Math.max(a.getBlockY(), b.getBlockY()),
                Math.max(a.getBlockZ(), b.getBlockZ())
        );
    }

    private boolean inside(Location location) {
        if (!ready() || location.getWorld() == null || !location.getWorld().equals(min.getWorld())) {
            return false;
        }

        return location.getBlockX() >= min.getBlockX()
                && location.getBlockX() <= max.getBlockX()
                && location.getBlockY() >= min.getBlockY()
                && location.getBlockY() <= max.getBlockY()
                && location.getBlockZ() >= min.getBlockZ()
                && location.getBlockZ() <= max.getBlockZ();
    }

    private boolean frame(Location location) {
        if (!ready() || location.getWorld() == null || !location.getWorld().equals(min.getWorld())) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        boolean inY = y >= min.getBlockY() - 1 && y <= max.getBlockY();
        boolean wallX = (x == min.getBlockX() - 1 || x == max.getBlockX() + 1)
                && z >= min.getBlockZ() - 1 && z <= max.getBlockZ() + 1;
        boolean wallZ = (z == min.getBlockZ() - 1 || z == max.getBlockZ() + 1)
                && x >= min.getBlockX() - 1 && x <= max.getBlockX() + 1;
        boolean floor = y == min.getBlockY() - 1
                && x >= min.getBlockX() - 1 && x <= max.getBlockX() + 1
                && z >= min.getBlockZ() - 1 && z <= max.getBlockZ() + 1;

        return floor || (inY && (wallX || wallZ));
    }

    private void buildFrame() {
        if (!ready()) {
            return;
        }

        World world = min.getWorld();

        for (int x = min.getBlockX() - 1; x <= max.getBlockX() + 1; x++) {
            for (int z = min.getBlockZ() - 1; z <= max.getBlockZ() + 1; z++) {
                world.getBlockAt(x, min.getBlockY() - 1, z).setType(Material.GLASS, false);
            }
        }

        for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
            for (int z = min.getBlockZ() - 1; z <= max.getBlockZ() + 1; z++) {
                world.getBlockAt(min.getBlockX() - 1, y, z).setType(Material.GLASS, false);
                world.getBlockAt(max.getBlockX() + 1, y, z).setType(Material.GLASS, false);
            }

            for (int x = min.getBlockX() - 1; x <= max.getBlockX() + 1; x++) {
                world.getBlockAt(x, y, min.getBlockZ() - 1).setType(Material.GLASS, false);
                world.getBlockAt(x, y, max.getBlockZ() + 1).setType(Material.GLASS, false);
            }
        }
    }

    private void spawnOne() {
        if (!ready()) {
            return;
        }

        World world = min.getWorld();
        int x = ThreadLocalRandom.current().nextInt(min.getBlockX(), max.getBlockX() + 1);
        int z = ThreadLocalRandom.current().nextInt(min.getBlockZ(), max.getBlockZ() + 1);

        FallingBlock fallingBlock = world.spawnFallingBlock(
                new Location(world, x + 0.5, max.getBlockY() + 0.8, z + 0.5),
                dropMaterial.createBlockData()
        );

        fallingBlock.setDropItem(false);
        fallingBlock.setHurtEntities(false);
        fallingBlock.setCancelDrop(false);
        fallingBlock.addScoreboardTag("stackgame");
    }

    private void startBoundaryWorker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!ready()) return;

                World world = min.getWorld();

                for (FallingBlock falling : world.getEntitiesByClass(FallingBlock.class)) {
                    if (!falling.getScoreboardTags().contains("stackgame")) continue;

                    Location l = falling.getLocation();

                    // Never allow the falling entity to leave the interior X/Z footprint.
                    double minX = min.getBlockX() + 0.5;
                    double maxX = max.getBlockX() + 0.5;
                    double minZ = min.getBlockZ() + 0.5;
                    double maxZ = max.getBlockZ() + 0.5;

                    double x = Math.max(minX, Math.min(maxX, l.getX()));
                    double z = Math.max(minZ, Math.min(maxZ, l.getZ()));

                    if (x != l.getX() || z != l.getZ()) {
                        falling.teleport(new Location(world, x, l.getY(), z, l.getYaw(), l.getPitch()));
                        falling.setVelocity(falling.getVelocity().setX(0).setZ(0));
                    }

                    // If the pile is already full, remove anything trying to land above the top.
                    if (l.getY() > max.getBlockY() + 2.0) {
                        // Keep falling normally; this check is only for lateral boundary handling.
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void resetArea() {
        if (!ready()) {
            return;
        }

        World world = min.getWorld();

        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        world.getEntitiesByClass(FallingBlock.class).stream()
                .filter(entity -> {
                    Location l = entity.getLocation();
                    return l.getWorld().equals(world)
                            && l.getX() >= min.getX() - 1
                            && l.getX() <= max.getX() + 2
                            && l.getZ() >= min.getZ() - 1
                            && l.getZ() <= max.getZ() + 2;
                })
                .forEach(FallingBlock::remove);

        pendingDrops.clear();
        buildFrame();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        if (inside(location) || frame(location)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!ready()) {
            return;
        }

        Location placed = event.getBlockPlaced().getLocation();

        if (frame(placed) || !inside(placed)) {
            event.setCancelled(true);
            return;
        }

        // The held item's appearance does not decide the actual block.
        // A normal placement becomes the currently selected falling block.
        event.setCancelled(true);

        FallingBlock fallingBlock = placed.getWorld().spawnFallingBlock(
                placed.clone().add(0.5, 0.2, 0.5),
                dropMaterial.createBlockData()
        );
        fallingBlock.setDropItem(false);
        fallingBlock.setHurtEntities(false);
        fallingBlock.setCancelDrop(false);
        fallingBlock.addScoreboardTag("stackgame");
    }

    @EventHandler
    public void onFallingLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) {
            return;
        }

        Location destination = event.getBlock().getLocation();

        if (!inside(destination)) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("stack")) {
            return false;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setup" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("This command must be run by a player.");
                    return true;
                }

                Location base = player.getLocation().getBlock().getLocation();

                // 6x6 interior, 20 blocks high.
                setArea(
                        base.clone().add(-2, 1, -2),
                        base.clone().add(3, 20, 3)
                );

                resetArea();
                sender.sendMessage("StackGame setup complete: 6x6 interior / 20 blocks high.");
            }

            case "block" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /stack block <block>");
                    return true;
                }

                Material material = Material.matchMaterial(args[1]);

                if (material == null || !material.isBlock() || material == Material.AIR) {
                    sender.sendMessage("Unknown block: " + args[1]);
                    return true;
                }

                dropMaterial = material;
                sender.sendMessage("Stack block changed to " + dropMaterial.name() + ".");
            }

            case "drop" -> {
                if (!ready()) {
                    sender.sendMessage("Run /stack setup first.");
                    return true;
                }

                int count = 1;

                if (args.length >= 2) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage("Count must be a number.");
                        return true;
                    }
                }

                count = Math.max(1, Math.min(200, count));
                pendingDrops.offer(count);
                sender.sendMessage("Queued " + count + " block(s).");
            }

            case "reset" -> {
                if (!ready()) {
                    sender.sendMessage("Run /stack setup first.");
                    return true;
                }

                resetArea();
                sender.sendMessage("StackGame reset complete.");
            }

            case "info" -> {
                sender.sendMessage("Block: " + dropMaterial.name());
                sender.sendMessage("Arena: " + (ready() ? "READY" : "NOT SET"));

                if (ready()) {
                    sender.sendMessage("Min: " + format(min));
                    sender.sendMessage("Max: " + format(max));
                }
            }

            default -> help(sender);
        }

        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("/stack setup");
        sender.sendMessage("/stack block <block>");
        sender.sendMessage("/stack drop <1-200>");
        sender.sendMessage("/stack reset");
        sender.sendMessage("/stack info");
    }

    private String format(Location location) {
        return location.getBlockX() + ","
                + location.getBlockY() + ","
                + location.getBlockZ();
    }
}
