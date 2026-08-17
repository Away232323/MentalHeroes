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

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Admin-only WorldEdit schematic placer for MentalHEROS. */
final class StructureManager implements CommandExecutor, TabCompleter {

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
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required to place structures.");
            return true;
        }

        String requested = sanitize(args[1]);
        if (requested == null) {
            player.sendMessage(ChatColor.RED + "Invalid structure name.");
            return true;
        }

        File schematic = findSchematic(requested);
        if (schematic == null) {
            player.sendMessage(ChatColor.RED + "Structure '" + args[1] + "' was not found.");
            player.sendMessage(ChatColor.GRAY + "Put it in plugins/MentalHeroes/structures/<name>.schem");
            return true;
        }

        Location pasteAt = player.getLocation().getBlock().getLocation();
        player.sendMessage(ChatColor.GRAY + "Placing " + displayName(schematic) + "...");

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                pasteWithWorldEdit(schematic, pasteAt.getWorld(),
                        pasteAt.getBlockX(), pasteAt.getBlockY(), pasteAt.getBlockZ());
                player.sendMessage(ChatColor.GREEN + displayName(schematic)
                        + " placed at your position.");
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not place " + schematic.getName() + ": " + describe(exception));
                exception.printStackTrace();
                player.sendMessage(ChatColor.RED + "Could not place the structure. Check the console.");
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return "place".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("place") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            File folder = structuresFolder();
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".schem"));
            if (files != null) {
                for (File file : files) {
                    String name = displayName(file);
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(name);
                    }
                }
            }
            if ("netherinsel".startsWith(prefix) && names.stream().noneMatch(n -> n.equalsIgnoreCase("NetherInsel"))) {
                names.add("NetherInsel");
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
        return List.of();
    }

    private File structuresFolder() {
        File folder = new File(plugin.getDataFolder(), "structures");
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        return folder;
    }

    private File findSchematic(String requested) {
        File folder = structuresFolder();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".schem"));
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (displayName(file).equalsIgnoreCase(requested)) {
                return file;
            }
        }
        return null;
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank() || !input.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        return input;
    }

    private String displayName(File file) {
        String name = file.getName();
        return name.toLowerCase(Locale.ROOT).endsWith(".schem")
                ? name.substring(0, name.length() - 6)
                : name;
    }

    private void pasteWithWorldEdit(File schematic, World world, int x, int y, int z) throws Exception {
        if (world == null) {
            throw new IllegalStateException("World is not available");
        }
        Class<?> formatsClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
        Object format = formatsClass.getMethod("findByFile", File.class).invoke(null, schematic);
        if (format == null) {
            throw new IllegalStateException("WorldEdit does not recognize this schematic");
        }

        try (InputStream stream = Files.newInputStream(schematic.toPath())) {
            Class<?> formatApi = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat");
            Object reader = formatApi.getMethod("getReader", InputStream.class).invoke(format, stream);
            try {
                Object clipboard = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardReader")
                        .getMethod("read").invoke(reader);
                Object worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit")
                        .getMethod("getInstance").invoke(null);
                Object sessionBuilder = invoke(worldEdit, "newEditSessionBuilder");
                Object adaptedWorld = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                        .getMethod("adapt", World.class).invoke(null, world);
                invoke(sessionBuilder, "world", adaptedWorld);
                invoke(sessionBuilder, "maxBlocks", -1);
                Object editSession = invoke(sessionBuilder, "build");
                try {
                    Class<?> clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
                    Constructor<?> holderConstructor = Class.forName("com.sk89q.worldedit.session.ClipboardHolder")
                            .getConstructor(clipboardClass);
                    Object holder = holderConstructor.newInstance(clipboard);
                    Object pasteBuilder = invoke(holder, "createPaste", editSession);
                    Class<?> vectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    Object destination = vectorClass.getMethod("at", int.class, int.class, int.class)
                            .invoke(null, x, y, z);
                    invoke(pasteBuilder, "to", destination);
                    invoke(pasteBuilder, "ignoreAirBlocks", true);
                    Object operation = invoke(pasteBuilder, "build");
                    Class<?> operationClass = Class.forName("com.sk89q.worldedit.function.operation.Operation");
                    Class.forName("com.sk89q.worldedit.function.operation.Operations")
                            .getMethod("complete", operationClass).invoke(null, operation);
                    invokeOptional(editSession, "flushSession");
                } finally {
                    closeQuietly(editSession);
                }
            } finally {
                closeQuietly(reader);
            }
        }
    }

    private Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < types.length; i++) {
                if (!isCompatible(types[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private boolean isCompatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isAssignableFrom(value.getClass());
        return type == boolean.class && value instanceof Boolean
                || type == int.class && value instanceof Integer
                || type == long.class && value instanceof Long
                || type == double.class && value instanceof Double
                || type == float.class && value instanceof Float;
    }

    private void invokeOptional(Object target, String name) {
        try { invoke(target, name); } catch (ReflectiveOperationException ignored) { }
    }

    private void closeQuietly(Object object) {
        if (object instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }

    private String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
