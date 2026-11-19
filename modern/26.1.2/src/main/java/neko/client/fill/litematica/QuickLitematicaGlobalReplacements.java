// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.litematica;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 替换规则关闭时，quickcraft 的 runtime 视图就是原原理图本身。 */
public final class QuickLitematicaGlobalReplacements {
    private QuickLitematicaGlobalReplacements() { }
    public static LitematicaSchematic runtime(SchematicPlacement placement) { return placement.getSchematic(); }
    public static BlockState baselineWorldState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
    public static BlockEntity baselineWorldEntity(BlockPos pos, Level world) { return null; }
}
