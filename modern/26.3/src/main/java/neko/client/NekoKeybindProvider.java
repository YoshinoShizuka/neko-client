package neko.client;

import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 把本模组的热键登记进 malilib 的<b>两张不同的表</b>。
 *
 * <h2>🔴 为什么必须有它（0.5.9 之前的致命缺陷）</h2>
 * <p>malilib 0.30.x 里「热键」是两件事，由两个入口分别喂：</p>
 * <ul>
 *   <li>{@code addHotkeysForCategory(...)} → 只往 {@code allKeybinds} 加一个
 *       {@code KeybindCategory}，<b>这张表只给配置界面的「热键」页显示用</b>；</li>
 *   <li>{@code registerKeybindProvider(...)} → 把 provider 放进列表；之后
 *       {@code InputEventHandler#updateUsedKeys()} 会 {@code hotkeyMap.clear()}，
 *       再<b>只遍历 provider</b> 调 {@code addKeysToMap} 重建「按键码 → keybind」映射。</li>
 * </ul>
 *
 * <p>只做第一件事的后果是「看得见、绑得上、按不动」：配置界面里热键列表齐全、能绑键，
 * 但按键进来时 {@code checkKeyBindsForChanges} 查的是 {@code hotkeyMap}，
 * 里面一个键都没有 ⇒ {@code updateIsPressed()} 永不被调用 ⇒ 回调永不执行。
 * 而且 malilib 在初始化完成和每次进世界都会重建一次映射表，
 * 所以「手动 {@code addKeybindToMap} 补一下」也会被清掉 —— 必须走 provider。</p>
 *
 * <p>两个方法都要实现：{@link #addKeysToMap} 喂映射表（决定能不能触发），
 * {@link #addHotkeys} 喂分类表（决定配置界面里显不显示）。</p>
 */
public final class NekoKeybindProvider implements IKeybindProvider {

    /** 热键表里的分组名（品牌名，不翻译） */
    private static final String CATEGORY = "Neko Client";

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : SearchConfig.OPTIONS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(CATEGORY,
                StringUtils.translate("neko_client.hotkeys.category"),
                SearchConfig.OPTIONS);
    }
}
