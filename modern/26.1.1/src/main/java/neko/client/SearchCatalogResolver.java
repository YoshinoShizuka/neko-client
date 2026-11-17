package neko.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neko.voidwarehouse.search.SearchCatalogPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的注册表查询：物品 id → 本地化显示名 / 可直接绘制的 {@link ItemStack}。
 *
 * <p><b>只有客户端能做这一步</b>：专用服务器没有 {@code zh_cn.json}，
 * 服务端把「铁锭」翻译成中文这件事在原理上就不成立 —— 这正是
 * 「目录下发 + 客户端匹配」方案的出发点（见 {@link SearchCatalogPayload}）。</p>
 *
 * <p>仓库里的物品全部不含额外数据组件（服务端 {@code insert} 拒收），
 * 所以显示名只取决于物品本身，一个默认 {@code ItemStack} 就能解析，
 * 不需要任何组件上下文。</p>
 *
 * <h2>0.5.0：为什么加了缓存</h2>
 * <p>配方本 0.5.0 起要按<b>显示名</b>做搜索排序：一次搜索会拿本子里的每个产物算一遍
 * {@code matchRank}，再加上列表每行都要解析一遍名字与图标。2048 条 × 每次滚动的每行，
 * 如果每次都走 {@code new ItemStack(item).getHoverName()}（内部会建 Component、查翻译表），
 * 帧率会被这一行拖下来。id → 名字是<b>纯函数</b>（物品没有额外组件），缓存永不失效，
 * 只在换语言时需要清（见 {@link #clearCache()}）。</p>
 */
public final class SearchCatalogResolver {

    /**
     * id → 显示名缓存。
     *
     * <p>不加同步：只在客户端主线程（渲染/收包）访问，与 malilib 的其余状态同一个线程模型。
     * 加锁反而会在滚动时每帧抢锁。</p>
     */
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    /**
     * id → {@code 全拼\u0000首字母}。
     *
     * <p>和 {@link #NAME_CACHE} 同一个线程模型（只在主线程访问）、同一条生命周期
     * （{@link #clearCache()} 一起清）。合成一个串而不是两个 Map，是为了少一次
     * 哈希查找 —— 列表每行、白名单每条都要拿它。</p>
     */
    private static final Map<String, String> PINYIN_CACHE = new HashMap<>();

    private SearchCatalogResolver() {
    }

    /**
     * 物品 id → 本地化显示名；客户端不认识这个 id（例如服务端装了客户端没有的模组）
     * 时返回 {@code null}，调用方（{@link SearchIndex#build}）会静默跳过该条目。
     */
    public static String displayNameOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        String cached = NAME_CACHE.get(itemId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        Identifier key = Identifier.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            // 缓存「查不到」用空串占位：服务端有、客户端没有的模组物品会每帧都被问到
            NAME_CACHE.put(itemId, "");
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        String name = new ItemStack(item).getHoverName().getString();
        NAME_CACHE.put(itemId, name);
        return name;
    }

    /**
     * 显示名的<b>非空</b>版本：解析不出来就退回 id 的 path（{@code minecraft:oak_planks → oak_planks}）。
     *
     * <p>排序 / 搜索都用这个：名字为 null 的条目如果直接跳过，玩家会发现「这条配方明明在，
     * 却搜不到也排不出来」。</p>
     */
    public static String nameOf(String itemId) {
        String name = displayNameOf(itemId);
        if (name != null && !name.isBlank()) {
            return name;
        }
        String bare = RecipeBrowseModel.bareId(itemId);
        return bare.isEmpty() ? "？" : bare;
    }

    /**
     * 显示名的<b>全拼</b>（无声调、无分隔，{@code 铁锭 → tieding}；解析不出时是空串）。
     *
     * <p>0.5.1 起配方本搜索支持拼音 / 首字母 / 模糊拼音，token 直接复用
     * {@link SearchIndex#of} —— 仓库搜索框用的就是它，两处永远不会算出两套读音。
     * 拼音与名字一起缓存：多音字取 jpinyin 词库默认读音（与仓库搜索同一条误差口径）。</p>
     */
    public static String pinyinFullOf(String itemId) {
        return tokensOf(itemId)[0];
    }

    /** 显示名的拼音首字母（{@code 铁锭 → td}）；解析不出时是空串 */
    public static String pinyinInitialsOf(String itemId) {
        return tokensOf(itemId)[1];
    }

    /**
     * {@code [全拼, 首字母]}，走同一份缓存。
     *
     * <p>失败（jpinyin 抛 {@code PinyinException} / 名字解析不出）时两项都是空串，
     * 搜索会自动退化成「名字 + id」两条路，绝不让一个词条把整份白名单建挂。</p>
     */
    private static String[] tokensOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new String[]{"", ""};
        }
        String cached = PINYIN_CACHE.get(itemId);
        if (cached != null) {
            int sep = cached.indexOf('\u0000');
            return new String[]{cached.substring(0, sep), cached.substring(sep + 1)};
        }
        String name = displayNameOf(itemId);
        SearchIndex.Entry entry = (name == null || name.isBlank())
                ? null : SearchIndex.of(itemId, name);
        String full = entry == null ? "" : entry.pinyinFull();
        String initials = entry == null ? "" : entry.pinyinInitials();
        PINYIN_CACHE.put(itemId, full + '\u0000' + initials);
        return new String[]{full, initials};
    }

    /**
     * 物品 id → 可直接交给 {@code GuiContext#renderItem} 的栈。
     *
     * <p>解析不出来时返回 {@link ItemStack#EMPTY}（渲染出来是空气，不会崩）——
     * 这一条对「服务端装了客户端没有的模组」很关键：配方本照样能浏览、能删。</p>
     */
    public static ItemStack stackOf(String itemId) {
        Identifier key = itemId == null ? null : Identifier.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(key));
    }

    /** 换语言（{@code LanguageManager} 重新加载）后显示名与读音都会变，界面重开时清一次 */
    public static void clearCache() {
        NAME_CACHE.clear();
        PINYIN_CACHE.clear();
    }
}
