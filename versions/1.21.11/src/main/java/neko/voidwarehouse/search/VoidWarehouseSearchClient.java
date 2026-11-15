package neko.voidwarehouse.search;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import neko.voidwarehouse.fill.FillRequestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口：注册搜索通道的三条 payload + 目录接收端。
 *
 * <h2>C2S / S2C 的双端注册红线</h2>
 * <p>这三条通道在服务端（carpet-Neko-Addition）已经注册过一份。
 * 客户端这边<b>仍要自己注册，且必须用「撞名吞掉」的幂等写法</b>：</p>
 * <ul>
 *   <li><b>专用服务器上两端是两个进程</b>：客户端进程的两张注册表是空的，
 *       不自己注册的话 C2S 会被编码成原版的 {@code DiscardedPayload}
       —— 不崩，但数据整包丢，{@code canSend} 还显示「支持」，最难查；</li>
 *   <li><b>单人存档里两端同进程</b>：服务端那份先注册（Fabric 按通道名严格去重），
 *       这里的注册抛 {@link IllegalArgumentException} —— 吞掉即可，
 *       注册顺序有保证（服务端在主入口初始化，客户端入口必然更晚）；</li>
 *   <li>S2C（catalog）同理：接收端要求 payload 类型已在 clientbound 表里，
 *       单人时靠服务端那份，专用服务器上靠这里这份。</li>
 * </ul>
 */
public final class VoidWarehouseSearchClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("neko-voidwarehouse-search");

    @Override
    public void onInitializeClient() {
        // 配置先读：后面所有门禁都看它（文件不存在会就地写一份模板）
        SearchConfig.loadFromFile();

        // 交给 malilib 管：① 它的 ConfigManager 能替我们触发 load/save；
        // ② 注册配置界面后，玩家在任意 malilib 模组的配置界面里都能从下拉框跳过来。
        ConfigManager.getInstance().registerConfigHandler(SearchConfig.MOD_ID, new SearchConfig());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(
                SearchConfig.MOD_ID, "Neko VoidWarehouse Search", SearchConfigGui::new));

        // 三个热键（两个开关 + 一个开界面）：malilib 的热键体系，界面打开着也响应
        SearchKeybinds.register();

        // C2S ×2：客户端要「发」的 payload，两端都得在 serverbound 表里
        try {
            PayloadTypeRegistry.playC2S().register(
                    SearchHelloPayload.TYPE, SearchHelloPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // 单人：服务端同进程已注册，吞掉
        }
        try {
            PayloadTypeRegistry.playC2S().register(
                    SearchApplyPayload.TYPE, SearchApplyPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // 单人：同上
        }
        // C2S ×3：Ctrl+Alt+左键 的填充请求（0.2.0 新增）。
        // 通道 id 与服务端 carpet_neko_addition:fill_request 完全一致，
        // 载荷只有一个 VarInt slotId —— 物品是什么、能装多少，全由服务端自己算。
        try {
            PayloadTypeRegistry.playC2S().register(
                    FillRequestPayload.TYPE, FillRequestPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // 单人：同上
        }
        // S2C ×1：客户端要「收」的 payload，两端都得在 clientbound 表里
        try {
            PayloadTypeRegistry.playS2C().register(
                    SearchCatalogPayload.TYPE, SearchCatalogPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // 单人：同上
        }

        ClientPlayNetworking.registerGlobalReceiver(
                SearchCatalogPayload.TYPE,
                (payload, context) -> SearchClientState.onCatalog(payload.itemIds()));

        // 断线就清状态（服务端的过滤跟着菜单消失，这里只是别留着旧引用）
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SearchClientState.clear());

        // 每次进世界重读一次配置：手改过 json 的玩家不用重启游戏，进出一次世界即可生效
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SearchConfig.loadFromFile();
            LOGGER.info("[虚空仓库搜索] 配置：搜索框={} 取满手势={}（{}）",
                    SearchConfig.isSearchBoxEnabled() ? "开" : "关",
                    SearchConfig.isFillGestureEnabled() ? "开" : "关",
                    "config/neko-voidwarehouse-search.json");
        });

        LOGGER.info("[虚空仓库搜索] 客户端通道已就绪（对端没装服务端模组时搜索框自动隐藏）");
    }
}
