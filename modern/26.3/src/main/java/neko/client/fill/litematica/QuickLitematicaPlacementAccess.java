// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Adapted for neko-client: package renamed; optional branches are stubbed.
package neko.client.fill.litematica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.core.BlockPos;

/** 26.2+ 的 Litematica 放置部件包围盒位置判定：使用 contains。 */
final class QuickLitematicaPlacementAccess {
    private QuickLitematicaPlacementAccess() {
    }

    static boolean contains(SchematicPlacementManager.PlacementPart part, BlockPos pos) {
        return part.getBox().contains(pos);
    }
}
