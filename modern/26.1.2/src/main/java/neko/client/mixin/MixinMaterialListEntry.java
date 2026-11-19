package neko.client.mixin;

import neko.client.VoidWarehouseMaterialSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给 Litematica 原生材料列表补上「已有数量 = 背包 + 虚空仓库」。
 *
 * <p>neko-client 没有搬 quickcraft 的材料页，所以不复制它的
 * {@code QuickLitematicaProjectionMaterials}；直接改 Litematica 自己的
 * {@code MaterialListEntry#getCountAvailable}，所有走原生材料列表的入口
 * （投影材料、容器材料、材料 HUD）都会自然生效。</p>
 *
 * <p>🔴 Litematica 是可选依赖：用 {@link Pseudo} + 独立的可选 mixin 配置，
 * 没装 Litematica 时这条注入直接跳过，不许把客户端启动拖下水。</p>
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.materials.MaterialListEntry")
public abstract class MixinMaterialListEntry {

    @Shadow
    public abstract ItemStack getStack();

    @Inject(method = "getCountAvailable", at = @At("RETURN"), cancellable = true)
    private void neko$includeVoidWarehouse(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        int augmented = VoidWarehouseMaterialSource.augmentCount(original, this.getStack());
        if (augmented != original) {
            cir.setReturnValue(augmented);
        }
    }
}
