/*
    Copyright (C) 2011 by Matthew D Moss

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
    THE SOFTWARE.
 */

package org.simiancage.bukkit.DwarfForge;

import net.minecraft.server.v1_5_R2.BlockFurnace;

import org.apache.commons.lang.Validate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.craftbukkit.v1_5_R2.CraftWorld;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.FurnaceAndDispenser;

import java.util.HashMap;


class Forge implements Runnable {

    static final int RAW_SLOT = 0;
    static final int FUEL_SLOT = 1;
    static final int PRODUCT_SLOT = 2;

    private static final int INVALID_TASK = -1;

    // These durations must all be less than max short.
    // Additionally, TASK_DURATION + AVOID_STAMPEDE < BURN_DURATION.
    private static final short ZERO_DURATION = 0;
    private static final short AVOID_STAMPEDE = 2 * Utils.MINS;
    private static final short TASK_DURATION = 20 * Utils.MINS;
    private static final short BURN_DURATION = 25 * Utils.MINS;

    private Log log = Log.getLogger();
    static HashMap<Location, Forge> active = new HashMap<Location, Forge>();
    private static java.util.Random rnd = new java.util.Random();


    private static short avoidStampedeDelay() {
        return (short) rnd.nextInt(AVOID_STAMPEDE);
    }


    private Location loc;
    private int task = INVALID_TASK;


    public Forge(Block block) {
        loc = block.getLocation();
        Config.getInstance();
        log.debug("Forge toggled at", loc.toString());
    }

    public Forge(Location loc) {
        Config.getInstance();
        this.loc = loc;
        log.debug("Forge toggled at", loc.toString());
    }

    @Override
    public boolean equals(Object obj) {
        return loc.equals(((Forge) obj).loc);
    }

    @Override
    public int hashCode() {
        return loc.hashCode();
    }

    Location getLocation() {
        return loc;
    }

    Block getBlock() {
        return loc.getBlock();
    }

    boolean isValid() {
        return Forge.isValid(getBlock());
    }

    static boolean isValid(Block block) {
        return isValid(block, Config.getMaxStackVertical());
    }

    // This static version is kept around so that other code may check if a block
    // is potentially a Forge before actually creating a Forge object.
    static boolean isValid(Block block, int stack) {
        // Can't be a Forge if it isn't a furnace.
        if (!Utils.isBlockOfType(block, Material.FURNACE, Material.BURNING_FURNACE)) {
            return false;
        }

        // Can't be a Forge beyond the vertical stacking limit.
        if (stack <= 0) {
            return false;
        }

        // Is lava or another Forge below? Then it is a Forge.
        Block below = block.getRelative(BlockFace.DOWN);
        return Utils.isBlockOfType(below, Material.LAVA, Material.STATIONARY_LAVA)
                || isValid(below, stack - 1);
    }

    boolean isBurning() {
        Furnace state = (Furnace) getBlock().getState();
        return state.getBurnTime() > 0;
    }

    private void internalsSetFurnaceBurning(boolean flag) {
        // This gets into Craftbukkit internals, but it's simple and works.
        // See net.minecraft.server.BlockFurnace.java:69-84 (approx).
        CraftWorld world = (CraftWorld) loc.getWorld();
        BlockFurnace.a(flag, world.getHandle(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private void ignite() {
        Furnace state = (Furnace) getBlock().getState();
        internalsSetFurnaceBurning(true);
        state.setBurnTime(BURN_DURATION);
        state.update();
    }

    private void douse() {
        Furnace state = (Furnace) getBlock().getState();
        internalsSetFurnaceBurning(false);
        state.setBurnTime(ZERO_DURATION);
        state.update();
    }


    // Returns false if forge should be deactivated.
    boolean updateProduct() {
        Furnace state = (Furnace) getBlock().getState();
        Inventory blockInv = state.getInventory();

        ItemStack item = blockInv.getItem(PRODUCT_SLOT);
        if (item != null && item.getType() != Material.AIR) {
            blockInv.clear(PRODUCT_SLOT);

            // Item destination: default is output chest.
            Block dest = getOutputChest();

            // Special case: if charcoal is product and fuel is required,
            // put it back into input chest.
            if (Config.isRequireFuel() && item.getType() == Material.COAL) {
                dest = getInputChest();
            }

            ItemStack remains = addTo(item, dest, false);
            if (remains != null) {
                // Put what remains back into product slot.
                blockInv.setItem(PRODUCT_SLOT, remains);

                // See if the raw slot is full. If so, make sure it
                // is compatible with what remains. If not, shut it
                // down.
                ItemStack raw = blockInv.getItem(RAW_SLOT);
                if (raw != null && raw.getType() != Material.AIR) {
                    if (Utils.resultOfCooking(raw.getType()) != remains.getType()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Update the raw material slot of the forge.
     * @return true if forge can continue working
     */
    boolean updateRawMaterial() {
        Furnace state = (Furnace) getBlock().getState();
        Inventory blockInv = state.getInventory();

        // Can only reload if the raw material slot is empty.
        ItemStack raw = blockInv.getItem(RAW_SLOT);
        if (raw != null && raw.getType() != Material.AIR) {
            // Something already in the raw slot; is it smeltable?
            return Utils.canCook(raw.getType());
        } else {
            // Can only reload if an input chest is available.
            Block input = getInputChest();
            if (input != null) {
                BlockState inpstate = input.getState();
                if (inpstate instanceof InventoryHolder) {
                    Inventory inpInv = ((InventoryHolder)inpstate).getInventory();

                    // Check for the same item first
                    Material want = Utils.getRawProduct(blockInv.getItem(PRODUCT_SLOT).getType());
                    if (inpInv.contains(want)) {
                        ItemStack inp = inpInv.getItem(inpInv.first(want));
                        inpInv.removeItem(inp);
                        blockInv.setItem(RAW_SLOT, inp);
                        return true;
                    }
                    // Can't find the same item, look for anything
                    for (ItemStack item : inpInv.getContents()) {
                        if (Utils.canCook(item.getType())) {
                            int takeAmount = 1;
                            // Take all, because it's generally more efficent
                            if ((item.getAmount()) > 1) {
                                takeAmount = item.getAmount();
                            }
                            ItemStack toTake = item.clone();
                            toTake.setAmount(takeAmount);
                            HashMap<Integer, ItemStack> ghost = inpInv.removeItem(toTake);
                            if (!ghost.isEmpty()) {
                                toTake.setAmount(toTake.getAmount() - ghost.get(0).getAmount());
                            }
                            blockInv.setItem(FUEL_SLOT, toTake);
                            return true;
                        }
                    }
                    return false;
                }
            }
        }

        return false;
    }

    // Returns false if forge should be deactivated.
    boolean updateFuel() {
        // TODO assert DFConfig.requireFuel()

        Furnace state = (Furnace) getBlock().getState();
        Inventory blockInv = state.getInventory();

        // Can reload only if fuel slot is empty.
        ItemStack fuel = blockInv.getItem(FUEL_SLOT);
        if (fuel == null || fuel.getType() == Material.AIR) {

            // Can reload only if an input chest is available.
            Block input = getInputChest();
            if (input != null) {
                BlockState inpstate = input.getState();
                if (inpstate instanceof InventoryHolder) {
                    Inventory inpInv = ((InventoryHolder)inpstate).getInventory();

                    for (ItemStack item : inpInv.getContents()) {
                        if (Utils.canBurn(item.getType())) {
                            int takeAmount = 1;
                            // Only take 1/4 of stack, to allow multiple feeding
                            if ((item.getAmount() / 4) > 1) {
                                takeAmount = item.getAmount() / 4;
                            }
                            ItemStack toTake = item.clone();
                            toTake.setAmount(takeAmount);
                            HashMap<Integer, ItemStack> ghost = inpInv.removeItem(toTake);
                            if (!ghost.isEmpty()) {
                                toTake.setAmount(toTake.getAmount() - ghost.get(0).getAmount());
                            }
                            blockInv.setItem(FUEL_SLOT, toTake);
                            return true;
                        }
                    }
                    return false;
                }
            }
        }

        return true;
    }

    void update() {
        // TODO assert that the forge is active; when would we ever update an
        // inactive forge?

        if (isValid()) {
            if (Config.isRequireFuel()) {
                if (!updateProduct() || !updateRawMaterial() || !updateFuel()) {
                    // Something is preventing further smelting. Unload fuel,
                    // deactivate, and let it burn out naturally.
                    // TODO This may not be the best option...? Try it for now.
                    deactivate();
                    unloadFuel();
                }
            } else {
                // No fuel required; only user interaction changes forge state.
                // No user interaction here; run the processes, but don't change
                // active state.
                updateProduct();
                updateRawMaterial();
                ignite();
            }
        } else {
            // No longer valid: deactivate.
            deactivate();

            // Douse only if fuel is not required.
            if (!Config.isRequireFuel()) {
                douse();
            }

        }
    }

    // Called on furnace fuel burn events.
    void burnUpdate() {
        update();
    }

    // Called on furnace material smelt events.
    void smeltUpdate() {
        // After a normal update (caused by an item-smelted event), set
        // the new cook time.
        update();
        if (isActive()) {
            ((Furnace) getBlock().getState()).setCookTime(Config.cookTime());
        }
    }

    public void run() {
        update();
    }

    private void activate() {
        // Only activate if not already active.
        if (!isActive()) {

            // Add to active forge map.
            active.put(loc, this);

            // Start repeating task.
            task = DwarfForge.main.queueRepeatingTask(
                    0, TASK_DURATION + avoidStampedeDelay(), this);

            // TODO force save
        }
    }

    private void deactivate() {
        // Only deactivate if currently active.
        if (isActive()) {

            // Remove from active forge map.
            active.remove(loc);

            // Cancel repeating task.
            if (task != INVALID_TASK) {
                DwarfForge.main.cancelTask(task);
                task = INVALID_TASK;
            }

            // TODO force save
        }

        // TODO Sanity check: assert(task == INVALID_TASK)
    }

    boolean isActive() {
        return active.containsKey(loc);
    }

    // Manual, user interaction to startup/shutdown a forge.
    void toggle() {
        if (isActive()) {
            if (Config.isRequireFuel()) {
                unloadFuel();
                // TODO Save partial fuel.
            }
            deactivate();
            douse();
        } else {
            activate();
            ((Furnace) getBlock().getState()).setCookTime(Config.cookTime());
        }
    }

    private static BlockFace getForward(Block block) {
        Furnace state = (Furnace) block.getState();
        return ((FurnaceAndDispenser) state.getData()).getFacing();
    }

    private static Block getForgeChest(Block block, BlockFace dir) {
        return getForgeChest(block, dir, Config.getMaxStackHorizontal());
    }

    private static Block getForgeChest(Block block, BlockFace dir, int stack) {
        // Can't use the chest beyond horizontal stacking limit.
        if (stack <= 0) {
            return null;
        }

        // If the adjacent block is a chest, use it.
        Block adjacent = block.getRelative(dir);
        if (Utils.isBlockOfType(adjacent, Material.CHEST)) {
            return adjacent;
        }

        // If there is a forge below, use its chest.
        Block below = block.getRelative(BlockFace.DOWN);
        if (Forge.isValid(below)) {
            return getForgeChest(below, dir, stack);    // Don't change horz stack dist going down.
        }

        // If there is a forge adjacent (in provided direction) and it
        // has a chest, use it.
        if (Forge.isValid(adjacent)) {
            return getForgeChest(adjacent, dir, stack - 1);
        }

        // No chest.
        return null;
    }

    Block getInputChest() {
        // Look for a chest stage-right (i.e. "next" cardinal face);
        Block block = getBlock();
        return getForgeChest(block, Utils.nextCardinalFace(getForward(block)));
    }

    Block getOutputChest() {
        // Look for a chest stage-left (i.e. "prev" cardinal face).
        Block block = getBlock();
        return getForgeChest(block, Utils.prevCardinalFace(getForward(block)));
    }

    // This may get called if fuel is required and the operator toggles the forge off.
    void unloadFuel() {
        Furnace state = (Furnace) getBlock().getState();
        Inventory blockInv = state.getInventory();

        // Remove fuel from the furnace.
        ItemStack fuel = blockInv.getItem(FUEL_SLOT);
        if (fuel == null || fuel.getType() == Material.AIR) {
            return;     // No fuel? WTF? Whatever...
        }

        blockInv.clear(FUEL_SLOT);

        // First, try putting as much fuel back into the input chest.
        Block input = getInputChest();
        if (input != null) {
            BlockState instate = input.getState();
            if (state instanceof InventoryHolder) {
                Inventory chestInv = ((InventoryHolder)instate).getInventory();

                // Add to chest; remember what remains, if any.
                HashMap<Integer, ItemStack> remains = chestInv.addItem(fuel);
                for (ItemStack i: remains.values()) {
                    loc.getWorld().dropItemNaturally(loc, i);
                }
            }
        }

        // Second, drop on ground.
        if (fuel != null) {
            loc.getWorld().dropItemNaturally(loc, fuel);
        }
    }

    /**
     * Move the item stack to the input/output chest as provided.
     * @param item
     * @param chest - block to move the item into. Can be null.
     * @param dropRemains whether to drop anything that can't be moved
     * @return anything that did not get moved
     */
    ItemStack addTo(ItemStack item, Block chest, boolean dropRemains) {
        Validate.notNull(item);

        if (chest == null) {
            // No destination chest.
            if (dropRemains) {
                loc.getWorld().dropItemNaturally(loc, item);
                return null;
            } else {
                return item;
            }
        } else {
            BlockState ch = chest.getState();
            Inventory chestInv;
            if (ch instanceof InventoryHolder) {
                chestInv = ((InventoryHolder)ch).getInventory();
            } else {
                // Destination is not a chest.
                if (dropRemains) {
                    loc.getWorld().dropItemNaturally(loc, item);
                    return null;
                } else {
                    return item;
                }
            }
            HashMap<Integer, ItemStack> remains = chestInv.addItem(item);
            if (remains.isEmpty()) {
                // Everything fit!
                return null;
            } else {
                // Destination chest full.
                if (dropRemains) {
                    loc.getWorld().dropItemNaturally(loc, remains.get(0));
                    return null;
                } else {
                    return remains.get(0);
                }
            }
        }
    }

    ItemStack addToOutput(ItemStack item, boolean dropRemains) {
        return addTo(item, getOutputChest(), dropRemains);
    }

    static Forge find(Block block) {
        return find(block.getLocation());
    }

    static Forge find(Location loc) {
        // Is it in the active Forges?
        if (active.containsKey(loc)) {
            return active.get(loc);
        }

        // Does the location block represent a valid Forge? If so, return a new one.
        if (isValid(loc.getBlock())) {
            return new Forge(loc);
        }

        // Otherwise, null.
        return null;
    }

}

