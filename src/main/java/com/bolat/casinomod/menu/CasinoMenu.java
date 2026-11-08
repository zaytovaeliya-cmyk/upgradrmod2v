package com.bolat.casinomod.menu;

import com.bolat.casinomod.registry.ModMenus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UPGRADER.
 * Slot 0 = STAKE  : player puts items here (the whole stack is bet).
 * Slot 1 = TARGET : display-only slot. The player picks the item in the picker (PickPacket)
 *                   and its AMOUNT with the +/- buttons.
 * Chance = value(stake) / (value(target) * amount) * 90%, max 85%. Below 1% the game is refused.
 * The result is decided when PLAY is pressed, but the prize is only handed out after the
 * roulette animation has finished (RESOLVE_DELAY ticks).
 */
public class CasinoMenu extends AbstractContainerMenu {
    public static final int STAKE = 0;
    public static final int TARGET = 1;

    public static final int BTN_PLAY = 0, BTN_PLUS_1 = 1, BTN_MINUS_1 = 2, BTN_PLUS_10 = 3, BTN_MINUS_10 = 4;

    public static final int ST_IDLE = 0, ST_WON = 1, ST_LOST = 2, ST_NEED_STAKE = 3, ST_NEED_TARGET = 4, ST_TOO_LOW = 5;

    /** server ticks between PLAY and handing out the prize (client spin = 70 ticks + start delay) */
    private static final int RESOLVE_DELAY = 78;

    private final Player owner;
    private final DataSlot chanceData = DataSlot.standalone();
    private final DataSlot statusData = DataSlot.standalone();
    private final DataSlot roundData = DataSlot.standalone();
    private final DataSlot lastChanceData = DataSlot.standalone();

    // prize waiting for the roulette to finish (server only)
    private boolean pending = false;
    private ItemStack pendingPrize = ItemStack.EMPTY;
    private long pendingUntil = 0;

    private final SimpleContainer casino = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            CasinoMenu.this.slotsChanged(this);
        }
    };

    public CasinoMenu(int id, Inventory inv) {
        super(ModMenus.CASINO_MENU.get(), id);
        this.owner = inv.player;

        addSlot(new Slot(casino, STAKE, 18, 46) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !stack.isEmpty();
            }

            @Override
            public boolean mayPickup(Player player) {
                return !pending; // stake is locked while the roulette spins
            }
        });
        // display-only slot: nothing can be put in or taken out by hand (no duplication possible)
        addSlot(new Slot(casino, TARGET, 142, 46) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 136 + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, 194));
        }
        addDataSlot(chanceData);
        addDataSlot(statusData);
        addDataSlot(roundData);
        addDataSlot(lastChanceData);
    }

    public int getChance() { return chanceData.get(); }
    public int getStatus() { return statusData.get(); }
    public int getRound() { return roundData.get(); }
    public int getLastChance() { return lastChanceData.get(); }

    // ---------------- item values (balance) ----------------
    // Plain/common items are worth very little (default 0.1), so a stack of dirt cannot buy valuable items.
    private static final Map<Item, Double> VALUE = Map.ofEntries(
            Map.entry(Items.COAL, 1.5), Map.entry(Items.CHARCOAL, 1.0), Map.entry(Items.COPPER_INGOT, 1.5),
            Map.entry(Items.RAW_COPPER, 1.0), Map.entry(Items.IRON_INGOT, 5.0), Map.entry(Items.RAW_IRON, 3.5),
            Map.entry(Items.GOLD_INGOT, 10.0), Map.entry(Items.RAW_GOLD, 7.0), Map.entry(Items.LAPIS_LAZULI, 2.5),
            Map.entry(Items.REDSTONE, 1.5), Map.entry(Items.QUARTZ, 2.0), Map.entry(Items.AMETHYST_SHARD, 2.0),
            Map.entry(Items.EMERALD, 20.0), Map.entry(Items.DIAMOND, 50.0), Map.entry(Items.NETHERITE_SCRAP, 80.0),
            Map.entry(Items.ANCIENT_DEBRIS, 90.0), Map.entry(Items.NETHERITE_INGOT, 400.0),
            Map.entry(Items.COPPER_BLOCK, 13.0), Map.entry(Items.COAL_BLOCK, 13.0), Map.entry(Items.REDSTONE_BLOCK, 13.0),
            Map.entry(Items.LAPIS_BLOCK, 22.0), Map.entry(Items.IRON_BLOCK, 45.0), Map.entry(Items.GOLD_BLOCK, 90.0),
            Map.entry(Items.EMERALD_BLOCK, 180.0), Map.entry(Items.DIAMOND_BLOCK, 450.0),
            Map.entry(Items.NETHERITE_BLOCK, 3600.0),
            Map.entry(Items.IRON_ORE, 4.5), Map.entry(Items.GOLD_ORE, 10.0), Map.entry(Items.EMERALD_ORE, 22.0),
            Map.entry(Items.DIAMOND_ORE, 55.0), Map.entry(Items.DEEPSLATE_DIAMOND_ORE, 58.0),
            Map.entry(Items.GOLDEN_CARROT, 6.0), Map.entry(Items.GOLDEN_APPLE, 40.0),
            Map.entry(Items.ENCHANTED_GOLDEN_APPLE, 500.0), Map.entry(Items.ENDER_PEARL, 12.0),
            Map.entry(Items.ENDER_EYE, 18.0), Map.entry(Items.BLAZE_ROD, 8.0), Map.entry(Items.GHAST_TEAR, 25.0),
            Map.entry(Items.SHULKER_SHELL, 40.0), Map.entry(Items.OBSIDIAN, 4.0), Map.entry(Items.CRYING_OBSIDIAN, 8.0),
            Map.entry(Items.SLIME_BALL, 2.0), Map.entry(Items.GUNPOWDER, 1.5), Map.entry(Items.BONE, 0.5),
            Map.entry(Items.STRING, 0.5), Map.entry(Items.LEATHER, 1.0), Map.entry(Items.GLOWSTONE_DUST, 1.5),
            Map.entry(Items.PRISMARINE_SHARD, 1.5), Map.entry(Items.HEART_OF_THE_SEA, 150.0),
            Map.entry(Items.NAUTILUS_SHELL, 20.0), Map.entry(Items.TRIDENT, 250.0), Map.entry(Items.TNT, 3.0),
            Map.entry(Items.EXPERIENCE_BOTTLE, 12.0), Map.entry(Items.NETHER_STAR, 600.0), Map.entry(Items.BEACON, 900.0),
            Map.entry(Items.ELYTRA, 700.0), Map.entry(Items.TOTEM_OF_UNDYING, 300.0), Map.entry(Items.DRAGON_EGG, 2000.0));
    private static final List<String> GEAR = List.of("_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
            "_helmet", "_chestplate", "_leggings", "_boots");
    private static final Map<String, Double> GEAR_VALUE = Map.of("netherite_", 500.0, "diamond_", 130.0,
            "iron_", 30.0, "golden_", 22.0, "chainmail_", 20.0, "stone_", 3.0, "wooden_", 1.0, "leather_", 2.0);

    public static double valueOf(Item item) {
        Double v = VALUE.get(item);
        if (v != null) return v;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key != null) {
            String p = key.getPath();
            for (String g : GEAR) {
                if (p.endsWith(g)) {
                    for (Map.Entry<String, Double> e : GEAR_VALUE.entrySet()) {
                        if (p.startsWith(e.getKey())) return e.getValue();
                    }
                }
            }
        }
        return switch (new ItemStack(item).getRarity()) {
            case UNCOMMON -> 3.0;
            case RARE -> 12.0;
            case EPIC -> 35.0;
            default -> 0.1;
        };
    }

    /** value(stake) / (value(target) * amount) * 90%, at most 85%; 0 means "too low to play". */
    public static int chanceFor(ItemStack stake, ItemStack target) {
        if (stake.isEmpty() || target.isEmpty()) return 0;
        double sv = valueOf(stake.getItem()) * stake.getCount();
        double tv = valueOf(target.getItem()) * target.getCount();
        int c = (int) Math.floor(sv / tv * 90.0);
        return Math.min(85, Math.max(0, c));
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (!owner.level().isClientSide()) {
            chanceData.set(chanceFor(casino.getItem(STAKE), casino.getItem(TARGET)));
        }
    }

    // ---------------- item picker pool (same order on client and server) ----------------
    private static final Set<String> BANNED = Set.of(
            "air", "barrier", "bedrock", "light", "debug_stick", "knowledge_book", "spawner",
            "reinforced_deepslate", "end_portal_frame", "budding_amethyst", "written_book", "filled_map",
            "farmland", "frogspawn", "chorus_plant", "petrified_oak_slab");
    private static final List<String> BANNED_PARTS = List.of(
            "command_block", "structure_", "jigsaw", "spawn_egg", "infested", "suspicious");

    private static List<Item> pool;

    public static List<Item> pool() {
        if (pool == null) {
            List<Item> list = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                if (key == null || !key.getNamespace().equals("minecraft")) continue;
                String path = key.getPath();
                if (BANNED.contains(path)) continue;
                boolean bad = false;
                for (String part : BANNED_PARTS) {
                    if (path.contains(part)) { bad = true; break; }
                }
                if (!bad) list.add(item);
            }
            list.sort(Comparator.comparing(i -> ForgeRegistries.ITEMS.getKey(i).getPath()));
            pool = list;
        }
        return pool;
    }

    // ---------------- buttons ----------------
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player != owner) return false;
        switch (id) {
            case BTN_PLAY -> { if (player instanceof ServerPlayer sp) play(sp); }
            case BTN_PLUS_1 -> changeCount(1);
            case BTN_MINUS_1 -> changeCount(-1);
            case BTN_PLUS_10 -> changeCount(10);
            case BTN_MINUS_10 -> changeCount(-10);
            default -> { return false; }
        }
        return true;
    }

    private void changeCount(int delta) {
        if (owner.level().isClientSide() || pending) return;
        ItemStack t = casino.getItem(TARGET);
        if (t.isEmpty()) return;
        int n = Math.max(1, Math.min(t.getItem().getMaxStackSize(), t.getCount() + delta));
        casino.setItem(TARGET, new ItemStack(t.getItem(), n));
        statusData.set(ST_IDLE);
        broadcastChanges();
    }

    /** Called on the server by PickPacket. */
    public void pick(int idx) {
        List<Item> p = pool();
        if (owner.level().isClientSide() || pending || idx < 0 || idx >= p.size()) return;
        casino.setItem(TARGET, new ItemStack(p.get(idx), 1));
        statusData.set(ST_IDLE);
        broadcastChanges();
    }

    private void play(ServerPlayer player) {
        if (pending) return; // roulette still running
        ItemStack stake = casino.getItem(STAKE);
        ItemStack target = casino.getItem(TARGET);
        if (stake.isEmpty()) {
            statusData.set(ST_NEED_STAKE);
            broadcastChanges();
            return;
        }
        if (target.isEmpty()) {
            statusData.set(ST_NEED_TARGET);
            broadcastChanges();
            return;
        }
        int chance = chanceFor(stake, target);
        if (chance < 1) {
            statusData.set(ST_TOO_LOW);
            broadcastChanges();
            return;
        }
        ItemStack prize = new ItemStack(target.getItem(), target.getCount());
        casino.setItem(STAKE, ItemStack.EMPTY); // the whole stake is spent
        lastChanceData.set(chance);
        roundData.set(roundData.get() + 1);

        boolean win = player.getRandom().nextInt(100) < chance;
        statusData.set(win ? ST_WON : ST_LOST); // the client needs this to aim the needle
        if (win) {
            // the prize is handed out only AFTER the roulette has finished
            pending = true;
            pendingPrize = prize;
            pendingUntil = player.level().getGameTime() + RESOLVE_DELAY;
        }
        broadcastChanges();
    }

    private void deliverPending() {
        if (!pending) return;
        pending = false;
        if (!pendingPrize.isEmpty()) {
            owner.getInventory().placeItemBackInInventory(pendingPrize);
        }
        pendingPrize = ItemStack.EMPTY;
    }

    /** runs every server tick while the menu is open */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (pending && !owner.level().isClientSide() && owner.level().getGameTime() >= pendingUntil) {
            deliverPending();
        }
    }

    // ---------------- shift-click ----------------
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index == TARGET || pending) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index == STAKE) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, STAKE, STAKE + 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            deliverPending(); // closed during the spin: the prize is not lost
            ItemStack st = casino.removeItemNoUpdate(STAKE);
            if (!st.isEmpty()) player.getInventory().placeItemBackInInventory(st);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}
