package totemhelper;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Totem Helper: keeps a totem of undying in the offhand and fills empty armor slots from the
 * inventory. Client side only. Keys J (totem), K (armor), L (shield), N (mace switch) toggle them.
 */
public class TotemHelperClient implements ClientModInitializer {
    public static final String ID = "totemhelper";
    private static final Logger LOG = LoggerFactory.getLogger(ID);

    /** Ticks to wait between two inventory actions. */
    private static final int DELAY_TICKS = 3;

    // Slot numbers of the player's own inventory menu (containerId 0).
    private static final int[] ARMOR_MENU_SLOTS = {5, 6, 7, 8}; // head, chest, legs, feet
    private static final String[] ARMOR_SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};
    private static final int OFFHAND_BUTTON = 40;

    private static volatile boolean totemOn = true;
    private static volatile boolean armorOn = true;
    // Shield vs projectiles: ON by default only when TrackerOM is not installed (it has its own).
    private static volatile boolean shieldOn =
            !FabricLoader.getInstance().isModLoaded("trackerom");
    private static boolean shieldHeld = false;
    private static final double SHIELD_RANGE = 10.0;
    // Mace switch: while you fall the mace in your hotbar is selected,
    // and the slot you were on is selected again once you have landed.
    private static volatile boolean maceOn = true;
    private static final double MACE_FALL_SPEED = -0.4;
    private static final int MACE_RESTORE_TICKS = 10;
    private static int previousSlot = -1;
    private static int maceSlot = -1;
    private static int groundTicks = 0;
    private static KeyMapping totemKey;
    private static KeyMapping armorKey;
    private static KeyMapping shieldKey;
    private static KeyMapping maceKey;
    private static int cooldown = 0;
    private static LocalPlayer lastPlayer = null;

    private static String onOff(boolean on) {
        return on ? "ON" : "OFF";
    }

    // Armor taken off by hand (while a screen is open) must not be put back on by the mod.
    private static final boolean[] armorWasFilled = new boolean[4];
    private static final boolean[] armorBlocked = new boolean[4];
    private static int screenGrace = 0;

    // The container-click method is found by its shape at runtime.
    private static Method clickMethod;
    private static Object swapType;
    private static Object quickMoveType;
    private static boolean clickLookupFailed = false;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "category"));
        totemKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.totemhelper.totem", InputConstants.Type.KEYSYM, 74, category)); // J
        armorKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.totemhelper.armor", InputConstants.Type.KEYSYM, 75, category)); // K
        shieldKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.totemhelper.shield", InputConstants.Type.KEYSYM, 76, category)); // L
        maceKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.totemhelper.mace", InputConstants.Type.KEYSYM, 78, category)); // N
        ClientTickEvents.END_CLIENT_TICK.register(TotemHelperClient::tick);
    }

    /** Selects the hotbar mace while falling, and goes back to the previous slot after landing. */
    private static void maceTick(LocalPlayer player, boolean screenOpen) {
        Inventory inv = player.getInventory();
        if (!maceOn || screenOpen) {
            return;
        }
        boolean falling = !player.onGround() && !player.isFallFlying()
                && player.getDeltaMovement().y < MACE_FALL_SPEED;
        if (falling) {
            groundTicks = 0;
            if (!inv.getItem(inv.getSelectedSlot()).is(Items.MACE)) {
                for (int i = 0; i < 9; i++) {
                    if (inv.getItem(i).is(Items.MACE)) {
                        if (previousSlot < 0) {
                            previousSlot = inv.getSelectedSlot();
                        }
                        inv.setSelectedSlot(i);
                        maceSlot = i;
                        break;
                    }
                }
            }
        } else if (previousSlot >= 0) {
            if (player.onGround()) {
                groundTicks++;
            }
            if (groundTicks >= MACE_RESTORE_TICKS) {
                if (inv.getSelectedSlot() == maceSlot) {
                    inv.setSelectedSlot(previousSlot);
                }
                previousSlot = -1;
                maceSlot = -1;
                groundTicks = 0;
            }
        }
    }

    /** Raises the shield (holds the use key) while a projectile that is not yours flies at you. */
    private static void shieldTick(Minecraft mc, LocalPlayer player, boolean screenOpen) {
        boolean hasShield = player.getMainHandItem().is(Items.SHIELD)
                || player.getOffhandItem().is(Items.SHIELD);
        boolean threat = false;
        if (shieldOn && hasShield && !screenOpen) {
            Vec3 me = player.getBoundingBox().getCenter();
            AABB area = player.getBoundingBox().inflate(SHIELD_RANGE);
            List<Projectile> projectiles = mc.level.getEntitiesOfClass(
                    Projectile.class, area, p -> p.getOwner() != player);
            for (Projectile p : projectiles) {
                Vec3 velocity = p.getDeltaMovement();
                Vec3 toMe = me.subtract(p.position());
                if (velocity.lengthSqr() < 1.0E-4 || toMe.lengthSqr() < 1.0E-4) {
                    continue;
                }
                if (velocity.normalize().dot(toMe.normalize()) > 0.9) {
                    threat = true;
                    break;
                }
            }
        }
        if (threat) {
            mc.options.keyUse.setDown(true);
            shieldHeld = true;
        } else if (shieldHeld) {
            mc.options.keyUse.setDown(false);
            shieldHeld = false;
        }
    }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

        if (player != lastPlayer) {
            lastPlayer = player;
            player.sendSystemMessage(Component.literal("Totem Helper: totem " + onOff(totemOn)
                    + ", armor " + onOff(armorOn) + ", shield " + onOff(shieldOn)
                    + ", mace " + onOff(maceOn) + " (keys J / K / L / N, see Controls)"));
        }

        while (totemKey.consumeClick()) {
            totemOn = !totemOn;
            player.sendSystemMessage(Component.literal("Auto totem: " + (totemOn ? "ON" : "OFF")));
        }
        while (armorKey.consumeClick()) {
            armorOn = !armorOn;
            player.sendSystemMessage(Component.literal("Auto armor: " + (armorOn ? "ON" : "OFF")));
        }

        while (maceKey.consumeClick()) {
            maceOn = !maceOn;
            player.sendSystemMessage(Component.literal("Mace switch: " + onOff(maceOn)));
        }
        while (shieldKey.consumeClick()) {
            shieldOn = !shieldOn;
            player.sendSystemMessage(Component.literal("Auto shield: " + (shieldOn ? "ON" : "OFF")));
        }

        boolean screenOpen = mc.gui.screen() != null;
        shieldTick(mc, player, screenOpen);
        maceTick(player, screenOpen);
        if (screenOpen) {
            screenGrace = 40;
        } else if (screenGrace > 0) {
            screenGrace--;
        }
        for (int part = 0; part < 4; part++) {
            boolean filled = player.inventoryMenu.getSlot(ARMOR_MENU_SLOTS[part]).hasItem();
            if (filled) {
                armorWasFilled[part] = true;
                armorBlocked[part] = false;
            } else if (armorWasFilled[part]) {
                armorWasFilled[part] = false;
                armorBlocked[part] = screenGrace > 0;
            }
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (screenOpen) {
            return;
        }
        if (!ensureClickMethod(mc)) {
            return;
        }

        if (totemOn && !player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            int slot = findTotemMenuSlot(player);
            if (slot >= 0) {
                click(mc, player, slot, OFFHAND_BUTTON, swapType);
                cooldown = DELAY_TICKS;
                return;
            }
        }

        if (armorOn) {
            for (int part = 0; part < 4; part++) {
                if (player.inventoryMenu.getSlot(ARMOR_MENU_SLOTS[part]).hasItem() || armorBlocked[part]) {
                    continue;
                }
                int slot = findBestArmorMenuSlot(player, ARMOR_SUFFIX[part]);
                if (slot >= 0) {
                    click(mc, player, slot, 0, quickMoveType);
                    cooldown = DELAY_TICKS;
                    return;
                }
            }
        }
    }

    /** Menu slot of a totem in the inventory (hotbar or main), or -1. */
    private static int findTotemMenuSlot(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                return toMenuSlot(i);
            }
        }
        return -1;
    }

    /** Menu slot of the best armor piece with this name suffix, or -1. */
    private static int findBestArmorMenuSlot(Player player, String suffix) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!path.endsWith(suffix)) {
                continue;
            }
            int score = materialScore(path);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = toMenuSlot(i);
            }
        }
        return bestSlot;
    }

    private static int materialScore(String path) {
        if (path.startsWith("netherite_")) return 5;
        if (path.startsWith("diamond_")) return 4;
        if (path.startsWith("iron_")) return 3;
        if (path.startsWith("chainmail_") || path.startsWith("golden_") || path.startsWith("copper_")) return 2;
        return 1;
    }

    /** Inventory index (0-8 hotbar, 9-35 main) to the slot number used by the inventory menu. */
    private static int toMenuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    private static void click(Minecraft mc, Player player, int slot, int button, Object type) {
        try {
            clickMethod.invoke(mc.gameMode, player.inventoryMenu.containerId, slot, button, type, player);
        } catch (ReflectiveOperationException e) {
            LOG.error("Inventory click failed", e);
            clickLookupFailed = true;
        }
    }

    private static boolean ensureClickMethod(Minecraft mc) {
        if (clickMethod != null) {
            return true;
        }
        if (clickLookupFailed) {
            return false;
        }
        for (Method m : mc.gameMode.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5 && p[0] == int.class && p[1] == int.class && p[2] == int.class
                    && p[3].isEnum() && p[4] == Player.class) {
                Object swap = null;
                Object quick = null;
                for (Object constant : p[3].getEnumConstants()) {
                    String name = ((Enum<?>) constant).name();
                    if (name.equals("SWAP")) swap = constant;
                    if (name.equals("QUICK_MOVE")) quick = constant;
                }
                if (swap != null && quick != null) {
                    clickMethod = m;
                    swapType = swap;
                    quickMoveType = quick;
                    LOG.info("Using inventory click method {}", m.getName());
                    return true;
                }
            }
        }
        clickLookupFailed = true;
        LOG.error("Could not find the inventory click method; Totem Helper is disabled");
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("Totem Helper: click method not found (disabled)"));
        }
        return false;
    }
}
