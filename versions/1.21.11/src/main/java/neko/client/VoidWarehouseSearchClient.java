package neko.client;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import neko.voidwarehouse.fill.FillRequestPayload;
import neko.voidwarehouse.search.SearchApplyPayload;
import neko.voidwarehouse.search.SearchCatalogPayload;
import neko.voidwarehouse.search.SearchHelloPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口：注册搜索通道的四条 payload + 目录接收端。
 *
 * <h2>为什么那四个 payload 类放在 {@code neko.voidwarehouse.*}，而不是 {@code neko.client}</h2>
 * <p>🔴 <b>它们必须与服务端模组（carpet-Neko-Addition）里的对应类同包同名。</b>
 * Fabric knot 对同 FQCN 只加载一份 —— 两个 jar 里各塞一份时，跑起来的永远是同一个 class。
 * 这一点直接决定单人存档的死活：</p>
 * <ul>
 *   <li><b>同 FQCN</b> ⇒ 两边的 {@code TYPE} / {@code CODEC} 静态常量是<b>同一个对象</b> ⇒
 *       服务端先注册、这里注册抛异常，但表里那份 codec 就是我们自己的那份 ⇒ 编码顺利；</li>
 *   <li><b>不同 FQCN</b>（0.4.0 曾把包名整体挪到 {@code neko.client}）⇒
 *       注册表里躺着的是服务端那个类的 codec，客户端发自己类的实例 ⇒ 编码时 cast 失败 ⇒
 *       {@code Can't encode 'X', expected 'X'}（两个类同名不同包，报错看起来一模一样）⇒
 *       <b>当场断线</b>。这就是 0.4.1 把它们搬回来的唯一原因。</li>
 * </ul>
 * <p>客户端的<b>其余逻辑</b>（索引、配置、热键、mixin、状态机）与服务端没有任何类级契约，
 * 留在 {@code neko.client} 就好 —— 不必为了几条通道把整个模组的包名绑死在别人的命名空间里。</p>
 *
 * <h2>C2S / S2C 的双端注册红线</h2>
 * <ul>
 *   <li><b>专用服务器上两端是两个进程</b>：客户端进程的两张注册表是空的，
 *       不自己注册的话 C2S 会被编码成原版的 {@code DiscardedPayload}
 *       —— 不崩，但数据整包丢，{@code canSend} 还显示「支持」，最难查；</li>
 *   <li><b>单人存档里两端同进程</b>：服务端那份先注册（Fabric 按通道名严格去重），
 *       这里的注册必然抛 {@link IllegalArgumentException} —— 那是常态，不是错误；
 *       能不能用由 {@link PayloadChannels} 核对归属后决定；</li>
 *   <li>S2C（catalog）同理：接收端要求 payload 类型已在 clientbound 表里，
 *       单人时靠服务端那份，专用服务器上靠这里这份。</li>
 * </ul>
 */
public final class VoidWarehouseSearchClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    @Override
    public void onInitializeClient() {
        // 配置先读：后面所有门禁都看它（文件不存在会就地写一份模板）
        SearchConfig.loadFromFile();

        // 交给 malilib 管：① 它的 ConfigManager 能替我们触发 load/save；
        // ② 注册配置界面后，玩家在任意 malilib 模组的配置界面里都能从下拉框跳过来。
        ConfigManager.getInstance().registerConfigHandler(SearchConfig.MOD_ID, new SearchConfig());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(
                SearchConfig.MOD_ID, "Neko Client", SearchConfigGui::new));

        // 三个热键（两个开关 + 一个开界面）：malilib 的热键体系，界面打开着也响应
        SearchKeybinds.register();

        // 四条通道：注册 + 当场核对归属（为什么必须核对，见 PayloadChannels 的头注释）。
        // 🔴 单人存档里这里必然「撞名」，那是正常的（服务端模组先注册了同一个类）；
        // 真正危险的是撞名的是另一个类 —— 那种情况下 send 会在编码阶段抛错，
        // 异常落在 netty 线程上，catch 不到，表现为断线。所以门禁必须在发送前，不能靠 try/catch。
        PayloadTypeRegistry<?> c2s = PayloadTypeRegistry.playC2S();
        PayloadTypeRegistry<?> s2c = PayloadTypeRegistry.playS2C();
        PayloadChannels.register(c2s, "search_hello", SearchHelloPayload.TYPE, SearchHelloPayload.CODEC);
        PayloadChannels.register(c2s, "search_apply", SearchApplyPayload.TYPE, SearchApplyPayload.CODEC);
        // 取满手势（0.2.0 新增，0.4.3 起组合键由玩家绑）的填充请求：载荷只有一个 VarInt slotId ——
        // 物品是什么、能装多少，全由服务端自己算。
        PayloadChannels.register(c2s, "fill_request", FillRequestPayload.TYPE, FillRequestPayload.CODEC);
        PayloadChannels.register(s2c, "search_catalog", SearchCatalogPayload.TYPE, SearchCatalogPayload.CODEC);

        // 接收端：同一个 id 已被别人注册过时返回 false（后注册者覆盖前者）。
        // 拿不到 catalog 就等于搜索框永远不亮 —— 这里必须说明白，别让人以为是「功能没做」。
        if (!ClientPlayNetworking.registerGlobalReceiver(SearchCatalogPayload.TYPE,
                (payload, context) -> SearchClientState.onCatalog(payload.itemIds()))) {
            LOGGER.warn("[neko-client] catalog 通道的接收端已被别的模组注册过 ⇒ 目录可能收不到，搜索框不会出现。"
                    + "这不影响连接，也不影响取满手势（它只认 canSend）。");
        }

        // 断线就清状态（服务端的过滤跟着菜单消失，这里只是别留着旧引用）
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SearchClientState.clear());

        // 每次进世界重读一次配置：手改过 json 的玩家不用重启游戏，进出一次世界即可生效
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SearchConfig.loadFromFile();
            // 取满组合键一起打出来：「取满没反应」时先看这一行，能立刻分辨
            // 「组合键没读到」和「读到了但判据不对」
            LOGGER.info("[虚空仓库搜索] 配置：搜索框={} 取满手势={} 取满组合键={}（{}）",
                    SearchConfig.isSearchBoxEnabled() ? "开" : "关",
                    SearchConfig.isFillGestureEnabled() ? "开" : "关",
                    SearchConfig.fillComboDisplay(),
                    "config/neko-client.json");
        });

        // 🔴 先自检，后说话：不再无条件「已就绪」。四条里只要有一条不归本模组，
        // 就说清楚是哪条、并且明确「不会断线」—— 免得玩家把「功能没反应」当成模组坏了。
        if (PayloadChannels.allUsable()) {
            LOGGER.info("[虚空仓库搜索] 通道自检通过：{}（对端没装服务端模组时搜索框自动隐藏）",
                    PayloadChannels.summary());
        } else {
            LOGGER.warn("[虚空仓库搜索] 通道自检未通过：{} —— 相关功能已自行关闭，客户端不会因此断线。",
                    PayloadChannels.summary());
        }
    }
}
