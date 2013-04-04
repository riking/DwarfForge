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


import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

class DFInventoryListener implements DwarfForge.Listener, Listener {
    private DwarfForge main;

    public void onEnable(DwarfForge main) {
        this.main = main;
        main.getServer().getPluginManager().registerEvents(this, main);
        // Event registration
    }

    public void onDisable() {
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        // NOTE: This identifies the START of a fuel burning event, not its
        // completion. Still, it's a good opportunity to reload the fuel slot
        // if it is now empty.

        // Monitoring event: do nothing if event was cancelled.
        if (event.isCancelled()) {
            return;
        }

        /*        final Block block = event.getFurnace(); */
        final Block block = event.getBlock();
        final Forge forge = Forge.find(block);

        // If it was a lava bucket that was used, preserve an empty bucket
        if (event.getFuel().getType() == Material.LAVA_BUCKET) {
            final ItemStack bucket = new ItemStack(Material.BUCKET, 1);
            main.queueTask(new Runnable() {
                public void run() {
                    ItemStack item = bucket;

                    if (forge != null) {    // It is a Dwarf Forge.
                        Block inputChest = forge.getInputChest();
                        Block outputChest = forge.getOutputChest();

                        // First try putting the bucket in the output chest.
                        if (item != null && outputChest != null) {
                            item = forge.addTo(item, outputChest, false);
                        }

                        // Next try putting the bucket in the input chest.
                        if (item != null && inputChest != null) {
                            item = forge.addTo(item, inputChest, false);
                        }

                        if (item == null) {
                            Inventory inv = ((Furnace) block.getState()).getInventory();
                            inv.setItem(Forge.FUEL_SLOT, new ItemStack(Material.AIR));
                        }
                    }
                }
            });
        }

        // Do nothing else if the furnace isn't a Dwarf Forge.
        if (forge == null) {
            return;
        }

        // Reload fuel if required.
        if (Config.isRequireFuel()) {
            main.queueTask(new Runnable() {
                public void run() {
                    forge.burnUpdate();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        // Monitoring event: do nothing if event was cancelled.
        if (event.isCancelled()) {
            return;
        }

        // Do nothing if the furnace isn't a Dwarf Forge.
        @SuppressWarnings("deprecation")
        Block block = event.getFurnace();
        if (!Forge.isValid(block)) {
            return;
        }

        // Queue up task to unload and reload the furnace.
        final Forge forge = Forge.find(block);
        main.queueTask(new Runnable() {
            public void run() {
                forge.smeltUpdate();
            }
        });
    }
}

