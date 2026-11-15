package neko.voidwarehouse.search;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 两个功能的开关热键 —— 参照 MiniHUD 的做法：<b>功能本身有个持久化的布尔开关，
 * 热键只负责把它翻过来</b>（MiniHUD 的 hotkey 也是这个语义：按一下开、按一下关，
 * 状态落进它的配置文件）。
 *
 * <h2>为什么不自己造一套热键系统</h2>
 * <p>MiniHUD 走 malilib 的热键表（自己画配置界面、自己存 json）。本项目没有
 * malilib 依赖，但目的只是「让玩家能自定义」—— 那就<b>直接注册成原版按键绑定</b>：
 * 玩家在「选项 → 控制 → 按键绑定 → 虚空仓库」里改键，原版替我们存进
 * {@code options.txt}、替我们处理冲突提示、替我们做「按键与鼠标键都可以」。
 * 少一套自绘 UI，行为还更符合玩家预期。</p>
 *
 * <h2>与「开关」的分工</h2>
 * <ul>
 *   <li><b>开关</b>（{@link SearchConfig}）：持久化在 json 里，决定功能在不在；</li>
 *   <li><b>热键</b>（本类）：只是切换开关的一种入口，和手改 json 等价 ——
 *       按一下立刻写盘，崩一次客户端也不会把状态吞掉。</li>
 * </ul>
 *
 * <h2>为什么用 {@code consumeClick()} 死循环</h2>
 * <p>一个 tick 里可能攒了多次按下（掉帧时），{@code while} 全部消费掉，
 * 否则下一次 tick 还会再触发一次「切换」—— 玩家会觉得按一下翻了两下。</p>
 */
public final class SearchKeybinds {

    /**
     * 按键分类。26.x 起 {@code KeyMapping} 必须挂在一个 {@code Category} 上
     * （老的「分类字符串」写法已经没了），分类自己的名字走
     * {@code key.category.<namespace>.<path>}，所以语言文件里要有
     * {@code key.category.neko_voidwarehouse_search.main}。
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("neko_voidwarehouse_search", "main"));

    /**
     * 搜索框开关，默认 <b>H</b>（原版默认键位里 H 是空的）。
     *
     * <p>用 {@code InputConstants.KEY_H} 而不是 {@code GLFW.GLFW_KEY_H}：loom 的非 remap
     * 编译类路径上没有 LWJGL，而 {@code InputConstants} 的常量是 MC 自己的、五个版本都在。</p>
     *
     * <p>🔴 <b>用三参构造，不要显式传 {@code InputConstants.Type}</b>：26.3 把它改名成
     * {@code KEYBOARD}（并且删掉了 {@code SCANCODE}），1.21.11 还是 {@code KEYSYM/SCANCODE/MOUSE}。
     * 三参构造内部就是 {@code (name, Type.KEYSYM/KEYBOARD, key, category)}，行为一样但不用碰这个名字。</p>
     */
    private static final KeyMapping TOGGLE_SEARCH = new KeyMapping(
            "key.neko_voidwarehouse_search.toggle_search", InputConstants.KEY_H, CATEGORY);

    /** 取满手势开关，默认 <b>J</b>（同样空闲） */
    private static final KeyMapping TOGGLE_FILL = new KeyMapping(
            "key.neko_voidwarehouse_search.toggle_fill", InputConstants.KEY_J, CATEGORY);

    private SearchKeybinds() {
    }

    /** 客户端初始化时调一次：注册两个按键 + 挂 tick 监听 */
    public static void register() {
        SearchPlatform.registerKeyMapping(TOGGLE_SEARCH);
        SearchPlatform.registerKeyMapping(TOGGLE_FILL);

        ClientTickEvents.END_CLIENT_TICK.register(SearchKeybinds::onEndTick);
    }

    private static void onEndTick(Minecraft client) {
        while (TOGGLE_SEARCH.consumeClick()) {
            boolean on = SearchConfig.toggleSearchBox();
            SearchPlatform.showActionBar(Component.translatable(on
                    ? "message.neko_voidwarehouse_search.search_on"
                    : "message.neko_voidwarehouse_search.search_off"));
            // 当前正开着容器界面就地刷新：关掉=藏框并撤掉过滤，打开=立刻可见
            SearchClientState.applyFeatureStateToCurrentScreen();
        }
        while (TOGGLE_FILL.consumeClick()) {
            boolean on = SearchConfig.toggleFillGesture();
            SearchPlatform.showActionBar(Component.translatable(on
                    ? "message.neko_voidwarehouse_search.fill_on"
                    : "message.neko_voidwarehouse_search.fill_off"));
        }
    }
}
