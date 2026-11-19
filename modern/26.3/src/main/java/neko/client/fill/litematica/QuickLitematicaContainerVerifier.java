// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Trimmed for neko-client: only the projection-template read path is kept.
package neko.client.fill.litematica;

import neko.client.fill.QuickContainerCopy;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.util.BlockInfoAlignment;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只保留「从投影读容器模板」这条路径；校验 UI 与自定义 MismatchType 全部不搬。 */
public final class QuickLitematicaContainerVerifier {


    public static boolean isEnabled() { return false; }
    public static boolean areSlotHintsVisible() { return false; }
    public static void clearCurrentHandledScreenBinding() { }
    public static void rememberContainerUse(Minecraft client, BlockHitResult hitResult) { }

    /** 库存读取状态：容器库存类只用它记录来源，不参与填充判据。 */
    public enum ActualInventoryReadStatus {
        NOT_READ,
        NO_WORLD,
        INTEGRATED_DIRECT,
        DIRECT_INVENTORY,
        NO_DIRECT_INVENTORY,
        CACHE_INVENTORY,
        NO_CACHE_NBT,
        CACHE_WITHOUT_ITEMS,
        CACHE_PARSE_FAILED
    }

    public static ExpectedContainer getExpectedContainerAt(Level foundWorld, BlockPos pos) {
        return getExpectedContainerAt(foundWorld, pos, null);
    }

    public static ExpectedContainer getExpectedContainerAt(
            Level foundWorld,
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        if (foundWorld == null) {
            return null;
        }

        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null) {
            return null;
        }

        if (QuickLitematicaContainerInventory.getChestType(current.state()) != ChestType.SINGLE
                && hasUnreadableExpectedInventory(ChestBlock.getConnectedBlockPos(pos, current.state()), placementFilter)) {
            return null;
        }

        ExpectedContainer merged = getExpectedDoubleChestContainer(pos, current, placementFilter);

        return merged != null ? merged : current;
    }

    public static Container getExpectedInventoryWithReplacements(Level world, BlockPos pos, Container fallback) {
        ExpectedContainer expected = getExpectedContainerAt(world, pos);
        return expected != null && expected.inventory().getContainerSize() == fallback.getContainerSize() ? expected.inventory() : fallback;
    }

    public static QuickContainerCopy.TemplateSnapshot getTemplateSnapshotAt(Level foundWorld, BlockPos pos) {
        ExpectedContainer expected = getExpectedContainerAt(foundWorld, pos);
        if (expected == null) {
            return null;
        }

        QuickContainerCopy.PublicContainerType type = QuickContainerCopy.getPublicContainerType(
                expected.state().getBlock(),
                QuickLitematicaContainerInventory.getChestType(expected.state())
        );
        if (type == null) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(expected.inventory().getContainerSize());
        List<Boolean> disabledStates = new ArrayList<>(expected.inventory().getContainerSize());
        for (int i = 0; i < expected.inventory().getContainerSize(); i++) {
            ItemStack stack = expected.inventory().getItem(i);
            templates.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            disabledStates.add(expected.disabledSlots().contains(i));
        }

        return new QuickContainerCopy.TemplateSnapshot(type, templates, disabledStates);
    }

    public static Set<Integer> getDisabledSlots(BlockEntity blockEntity) {
        return QuickLitematicaContainerInventory.getDisabledSlots(blockEntity);
    }

    private static ExpectedContainer getExpectedDoubleChestContainer(
            BlockPos pos,
            ExpectedContainer current,
            @Nullable SchematicPlacement placementFilter
    ) {
        ChestType chestType = QuickLitematicaContainerInventory.getChestType(current.state());

        if (chestType == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);

        if (adjacent == null) {
            return null;
        }

        Container currentInventory = current.inventory();
        Container adjacentInventory = adjacent.inventory();
        Container merged = chestType == ChestType.RIGHT
                ? QuickLitematicaContainerInventory.mergeInventories(currentInventory, adjacentInventory)
                : QuickLitematicaContainerInventory.mergeInventories(adjacentInventory, currentInventory);

        return new ExpectedContainer(
                pos,
                current.state(),
                current.blockEntity(),
                merged,
                Set.of()
        );
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(BlockPos pos) {
        return getExpectedDoubleChestAdjacentPos(pos, null);
    }

    public static BlockPos getExpectedDoubleChestAdjacentPos(
            BlockPos pos,
            @Nullable SchematicPlacement placementFilter
    ) {
        ExpectedContainer current = getExpectedContainerInternal(pos, placementFilter);

        if (current == null || QuickLitematicaContainerInventory.getChestType(current.state()) == ChestType.SINGLE) {
            return null;
        }

        BlockPos adjacentPos = ChestBlock.getConnectedBlockPos(pos, current.state());
        ExpectedContainer adjacent = getExpectedContainerInternal(adjacentPos, placementFilter);
        return adjacent != null && QuickLitematicaContainerInventory.getChestType(adjacent.state()) != ChestType.SINGLE
                ? adjacentPos
                : null;
    }

    public static ExpectedContainer getExpectedContainerPartAt(SchematicPlacement placement, BlockPos pos) {
        return placement != null ? getExpectedContainerInternal(pos, placement) : null;
    }

    public static boolean hasUnreadableExpectedInventory(BlockPos worldPos, @Nullable SchematicPlacement placement) {
        LocalPlacementPos local = getLocalPlacementPos(worldPos, placement);
        var client = Minecraft.getInstance();
        if (local == null || client.level == null) { return false; }
        var values = QuickLitematicaGlobalReplacements.runtime(local.placement()).getBlockEntityMapForRegion(local.region());
        Object data = values == null ? null : values.get(local.pos());
        if (data == null) { return false; }
        CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(data);
        return nbt.contains("Items") && QuickLitematicaContainerMaterials.readItems(nbt, client.level.registryAccess()) == null;
    }

    private static ExpectedContainer getExpectedContainerInternal(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        LocalPlacementPos placementPos = getLocalPlacementPos(worldPos, placementFilter);

        if (placementPos == null) {
            return null;
        }

        Map<BlockPos, ?> blockEntities = QuickLitematicaGlobalReplacements.runtime(placementPos.placement())
                .getBlockEntityMapForRegion(placementPos.region());

        if (blockEntities == null) {
            return null;
        }

        Object data = blockEntities.get(placementPos.pos());

        if (data == null) {
            return null;
        }

        CompoundTag nbt = QuickLitematicaDataCompat.toVanillaNbt(data);

        Minecraft client = Minecraft.getInstance();
        Level clientWorld = client.level;

        if (clientWorld == null) {
            return null;
        }

        nbt = QuickLitematicaSchematicData.prepareBlockEntity(nbt, placementPos.rawState(), placementPos.pos());

        if (nbt.contains("Items") && QuickLitematicaContainerMaterials.readItems(nbt, clientWorld.registryAccess()) == null) {
            return null;
        }

        BlockEntity blockEntity = BlockEntity.loadStatic(
                placementPos.pos(),
                placementPos.rawState(),
                nbt,
                clientWorld.registryAccess()
        );

        if (!(blockEntity instanceof Container inventory)) {
            return null;
        }

        Container contents = nbt.contains("Items")
                ? QuickLitematicaContainerInventory.getNbtInventoryPreservingComponents(nbt, inventory.getContainerSize(), client.level.registryAccess())
                : QuickLitematicaContainerInventory.copyInventory(inventory);
        if (contents == null) { return null; }

        return new ExpectedContainer(
                worldPos,
                placementPos.worldState(),
                blockEntity,
                contents,
                QuickLitematicaContainerInventory.getDisabledSlots(blockEntity, nbt)
        );
    }

    private static LocalPlacementPos getLocalPlacementPos(
            BlockPos worldPos, @Nullable SchematicPlacement placementFilter) {
        List<SchematicPlacementManager.PlacementPart> parts = DataManager.getSchematicPlacementManager()
                .getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if ((placementFilter != null && part.getPlacement() != placementFilter)
                    || !QuickLitematicaPlacementAccess.contains(part, worldPos)) {
                continue;
            }

            SchematicPlacement placement = part.getPlacement();
            String region = part.getSubRegionName();
            LitematicaSchematic schematic = QuickLitematicaGlobalReplacements.runtime(placement);
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            SubRegionPlacement subRegionPlacement = placement.getRelativeSubRegionPlacement(region);

            if (container == null || subRegionPlacement == null) {
                continue;
            }

            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos,
                    schematic,
                    region,
                    placement,
                    subRegionPlacement,
                    container
            );

            if (schematicPos == null) {
                continue;
            }

            BlockState rawState = container.get(schematicPos.getX(), schematicPos.getY(), schematicPos.getZ());
            BlockState worldState = getTransformedWorldState(rawState, placement, region);

            return new LocalPlacementPos(schematicPos, region, placement, rawState, worldState);
        }

        return null;
    }

    public static BlockState getTransformedWorldState(BlockState state, SchematicPlacement schematicPlacement, String region) {
        SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(region);

        if (placement == null) {
            return state;
        }

        Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        Mirror mirrorMain = schematicPlacement.getMirror();
        Mirror mirrorSub = placement.getMirror();

        if (mirrorSub != Mirror.NONE
                && (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90
                || schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        if (mirrorMain != Mirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != Mirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        if (rotationCombined != Rotation.NONE) {
            state = state.rotate(rotationCombined);
        }

        return state;
    }

    public record ExpectedContainer(
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            Container inventory,
            Set<Integer> disabledSlots
    ) {
    }

    private record LocalPlacementPos(
            BlockPos pos,
            String region,
            SchematicPlacement placement,
            BlockState rawState,
            BlockState worldState
    ) {
    }
}
