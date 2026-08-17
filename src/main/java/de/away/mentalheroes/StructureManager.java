package de.away.mentalheroes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Admin-only bundled structure placer for the MentalHeroes world.
 *
 * /mental place NetherInsel
 */
final class StructureManager implements CommandExecutor, TabCompleter {

    private static final String NETHER_ISLAND_NAME = "NetherInsel";
    private static final String NETHER_ISLAND_FILE = "NetherInsel.schem";
    private static final String NETHER_ISLAND_RESOURCE_PATTERN =
            "structures/netherinsel-parts/part-%02d.txt";

    private final MentalHeroesPlugin plugin;

    StructureManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return true;
        }
        if (!player.hasPermission("mentalheroes.command.place")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (!plugin.isHeroesWorld(player)) {
            player.sendMessage(ChatColor.RED + "Structures can only be placed in MentalHEROS.");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("place")) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /mental place <structure>");
            return true;
        }
        if (!args[1].equalsIgnoreCase(NETHER_ISLAND_NAME)) {
            player.sendMessage(ChatColor.RED + "Unknown structure. Available: " + NETHER_ISLAND_NAME);
            return true;
        }
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required to place this structure.");
            return true;
        }

        player.sendMessage(ChatColor.GRAY + "Placing " + NETHER_ISLAND_NAME + "...");
        Location at = player.getLocation().getBlock().getLocation();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                File schematic = extractNetherIsland();
                pasteWithWorldEdit(schematic, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
                player.sendMessage(ChatColor.GREEN + NETHER_ISLAND_NAME + " placed at your position.");
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not place " + NETHER_ISLAND_NAME + ": " + describe(exception));
                exception.printStackTrace();
                player.sendMessage(ChatColor.RED + "Could not place " + NETHER_ISLAND_NAME
                        + ". Check the server console.");
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return "place".startsWith(args[0].toLowerCase(Locale.ROOT))
                    ? List.of("place")
                    : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return NETHER_ISLAND_NAME.toLowerCase(Locale.ROOT)
                    .startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of(NETHER_ISLAND_NAME)
                    : List.of();
        }
        return List.of();
    }

    private File extractNetherIsland() throws Exception {
        File structures = new File(plugin.getDataFolder(), "structures");
        if (!structures.isDirectory() && !structures.mkdirs()) {
            throw new IllegalStateException("Could not create the structures folder");
        }

        File target = new File(structures, NETHER_ISLAND_FILE);
        File temporary = new File(structures, NETHER_ISLAND_FILE + ".tmp");

        byte[] bundled = readBundledSchematic();
        if (bundled.length < 4 || (bundled[0] & 0xff) != 0x1f || (bundled[1] & 0xff) != 0x8b) {
            throw new IllegalStateException("Bundled NetherInsel schematic is invalid");
        }

        Files.write(temporary.toPath(), bundled);
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private byte[] readBundledSchematic() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (int index = 0; index < 100; index++) {
            String resource = String.format(NETHER_ISLAND_RESOURCE_PATTERN, index);
            InputStream input = plugin.getResource(resource);
            if (input == null) {
                break;
            }
            try (input) {
                input.transferTo(encoded);
            }
        }
        if (encoded.size() == 0) {
            throw new IllegalStateException("Bundled NetherInsel schematic is missing");
        }
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }

    private void pasteWithWorldEdit(File schematic, World world, int x, int y, int z) throws Exception {
        if (world == null) {
            throw new IllegalStateException("World is not available");
        }

        Class<?> formatsClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
        Object format = formatsClass.getMethod("findByFile", File.class).invoke(null, schematic);
        if (format == null) {
            throw new IllegalStateException("WorldEdit does not recognize the schematic format");
        }

        Object reader;
        try (InputStream stream = Files.newInputStream(schematic.toPath())) {
            Class<?> formatApi = Class.forName(
                    "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat"
            );
            reader = formatApi.getMethod("getReader", InputStream.class).invoke(format, stream);
            try {
                Class<?> readerApi = Class.forName(
                        "com.sk89q.worldedit.extent.clipboard.io.ClipboardReader"
                );
                Object clipboard = readerApi.getMethod("read").invoke(reader);

                Object worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit")
                        .getMethod("getInstance").invoke(null);
                Object sessionBuilder = invoke(worldEdit, "newEditSessionBuilder");

                Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object adaptedWorld = adapterClass.getMethod("adapt", World.class).invoke(null, world);
                invoke(sessionBuilder, "world", adaptedWorld);
                invoke(sessionBuilder, "maxBlocks", -1);

                Object editSession = invoke(sessionBuilder, "build");
                try {
                    Class<?> clipboardClass = Class.forName(
                            "com.sk89q.worldedit.extent.clipboard.Clipboard"
                    );
                    Constructor<?> holderConstructor = Class.forName(
                            "com.sk89q.worldedit.session.ClipboardHolder"
                    ).getConstructor(clipboardClass);
                    Object holder = holderConstructor.newInstance(clipboard);
                    Object pasteBuilder = invoke(holder, "createPaste", editSession);

                    Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    Object destination = vectorClass.getMethod("at", int.class, int.class, int.class)
                            .invoke(null, x, y, z);
                    invoke(pasteBuilder, "to", destination);
                    invoke(pasteBuilder, "ignoreAirBlocks", true);

                    Object operation = invoke(pasteBuilder, "build");
                    Class<?> operationClass = Class.forName(
                            "com.sk89q.worldedit.function.operation.Operation"
                    );
                    Class.forName("com.sk89q.worldedit.function.operation.Operations")
                            .getMethod("complete", operationClass)
                            .invoke(null, operation);
                    invokeOptional(editSession, "flushSession");
                } finally {
                    closeQuietly(editSession);
                }
            } finally {
                closeQuietly(reader);
            }
        }
    }

    private Object invoke(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!isCompatible(parameterTypes[index], arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private boolean isCompatible(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }
        if (!type.isPrimitive()) {
            return type.isAssignableFrom(value.getClass());
        }
        return type == boolean.class && value instanceof Boolean
                || type == int.class && value instanceof Integer
                || type == long.class && value instanceof Long
                || type == double.class && value instanceof Double
                || type == float.class && value instanceof Float;
    }

    private void invokeOptional(Object target, String name) {
        try {
            invoke(target, name);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void closeQuietly(Object object) {
        if (object instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
