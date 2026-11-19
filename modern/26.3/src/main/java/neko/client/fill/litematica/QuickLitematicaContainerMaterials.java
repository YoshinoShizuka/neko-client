// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Trimmed for neko-client: only the projection-template read path is kept.
package neko.client.fill.litematica;

import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/** 只保留读取投影容器 NBT 所需的 ItemKey / readItems / inferInventorySize。 */
public final class QuickLitematicaContainerMaterials {

    private QuickLitematicaContainerMaterials() { }

    abstract static class ItemKey {
        @Override public abstract boolean equals(Object other);
        @Override public abstract int hashCode();
        @Override public abstract String toString();
    }

    static List<ItemStack> readItems(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return readItems(nbt, registryLookup, false);
    }

    /**
     * 统计用读数：读不出来的槽位<b>跳过</b>，剩多少算多少。
     *
     * <p>替换 / 保存 / 验证必须走严格版（{@link #readItems}）—— 那里的判据是
     * 「读不全就不许动」，统计只是给人看的总数，少一格比整张表空着有用。</p>
     */
    static List<ItemStack> readItemsForStatistics(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return readItems(nbt, registryLookup, true);
    }

    private static List<ItemStack> readItems(CompoundTag nbt, HolderLookup.Provider registryLookup, boolean allowPartial) {
        if (nbt == null || !nbt.contains("Items")) {
            return List.of();
        }

        List<ItemStack> stacks = new ArrayList<>();
        if (!(nbt.get("Items") instanceof ListTag items)) {
            QuickLitematicaSchematicData.unreadableInventory(nbt);
            return allowPartial ? stacks : null;
        }
        int size = inferInventorySize(items);

        for (int i = 0; i < size; i++) {
            stacks.add(ItemStack.EMPTY);
        }

        Set<Integer> seenSlots = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemNbt = QuickLitematicaContainerMaterialsAccess.readCompound(items, i);
            if (!(itemNbt.get("Slot") instanceof net.minecraft.nbt.ByteTag)) {
                QuickLitematicaSchematicData.unreadableInventory(itemNbt);
                if (allowPartial) { continue; }
                return null;
            }
            ItemStack stack = QuickLitematicaContainerMaterialsAccess.itemStackFromNbt(registryLookup, itemNbt);
            if (stack.isEmpty() && !QuickLitematicaSchematicData.isEmptyItem(itemNbt)) {
                if (allowPartial) { continue; }
                return null;
            }
            int slot = QuickLitematicaContainerMaterialsAccess.readByte(itemNbt, "Slot") & 255;
            if (!seenSlots.add(slot)) {
                QuickLitematicaSchematicData.unreadableInventory(itemNbt);
                if (allowPartial) { continue; }
                return null;
            }

            if (!stack.isEmpty() && slot >= 0 && slot < stacks.size()) {
                stacks.set(slot, stack);
            }
        }

        return stacks;
    }

    static int inferInventorySize(ListTag items) {
        int size = 0;

        for (int i = 0; i < items.size(); i++) {
            size = Math.max(size, (QuickLitematicaContainerMaterialsAccess.readByte(QuickLitematicaContainerMaterialsAccess.readCompound(items, i), "Slot") & 255) + 1);
        }

        return size;
    }
}
