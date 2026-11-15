package neko.client;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 客户端 → 服务端：搜索命中的物品 id 集合（<b>空列表 = 清除过滤</b>）。
 *
 * <p><b>服务端 {@code carpet-Neko-Addition} 里有一份同通道、同字段的对偶类。</b>
 * 0.4.0 起客户端把包名从 {@code neko.voidwarehouse.*} 移到了 {@code neko.client}，
 * 于是两边 FQCN 不再相同（「同名类只加载一份」的隐患也随之消失）——
 * 但<b>通道 id 与 {@code CODEC} 结构必须完全一致</b>
 * （{@code carpet_neko_addition:xxx}、同样的字段顺序与类型），
 * 不一致就会出现「一边编的包另一边解不开」（先例：2026-10-31 restock 通道
 * 双 jar 版本错配的 {@code NoSuchMethodError} 崩溃）。</p>
 *
 * <h2>服务端拿到之后做什么</h2>
 * <p>把 id 解析成 {@code Item}（查不到的<b>静默忽略</b>——客户端和服务端的注册表
 * 理论上一致，但防御性地不假设），存进该玩家菜单的
 * {@code VoidWarehousePageContainer}：<b>内存态、不持久化、跟着菜单走</b>——
 * 关掉界面过滤自然消失，重开界面是干净的一页。</p>
 *
 * <p>之后服务端出页时只把「命中集合里的物品」排进 45 格（含可兑虚拟条目），
 * 布局、导航行、点击语义一个字节都不动 —— 与创造模式物品栏搜索同构。</p>
 *
 * <h2>安全边界</h2>
 * <p>集合求交是纯内存操作；客户端发不存在的 id 只会被丢掉，发得再多也受
 * {@link #MAX_IDS} 限长 + 限流双闸。能触发过滤的前提是<b>自己正开着仓库界面</b>，
 * 看到自己仓库的目录不构成信息泄露。</p>
 */
public record SearchApplyPayload(List<String> itemIds) implements CustomPacketPayload {

    /** 过滤通道：{@code carpet_neko_addition:search_apply}（C2S）。 */
    public static final CustomPacketPayload.Type<SearchApplyPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "search_apply"));

    /**
     * 一次最多回传多少种物品。
     *
     * <p>两端必须一致：{@code ByteBufCodecs.list(n)} 在<b>解码</b>时按 {@code n} 限长。
     * 匹配集合是目录的子集，目录上限 4096 ⇒ 这里同取 4096。</p>
     */
    public static final int MAX_IDS = 4096;

    /**
     * 物品 id 清单的编解码器。
     *
     * <p>显式写出 {@code <ByteBuf, String>}：{@code ByteBufCodecs.list} 是 CodecOperation，
     * 三个类型参数全靠推断，不写会退化成 {@code Object}。</p>
     */
    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_IDS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, SearchApplyPayload> CODEC = StreamCodec.composite(
            IDS_CODEC, SearchApplyPayload::itemIds,
            SearchApplyPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
