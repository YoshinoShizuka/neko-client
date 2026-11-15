package neko.client;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * 「取满组合键」的<b>纯判据</b>：给定「玩家绑了哪些键」+「这一下点的是哪个鼠标键」+
 * 「某个键现在按着没有」，回答「这一下点击算不算取满」。
 *
 * <h2>为什么单独一个类，而不是写在 {@link SearchConfig} 里</h2>
 * <p>{@code SearchConfig} 的静态初始化就 new 了 malilib 的 {@code ConfigHotkey} ——
 * 那些类在「没启动游戏」的 JVM 里加载不了，于是<b>那条规则一行都测不到</b>。
 * 而它恰恰是本模组最容易静默失效的一段判据：写错了不会崩、不会报错，只会
 * 「按下去什么都不发生」。所以规则本身被拎到这里，<b>不 import malilib、不 import MC</b>，
 * 三个入参全是纯数据，JUnit 里直接喂。</p>
 *
 * <h2>🔴 为什么不用 malilib 的 isKeybindHeld() / isPressed()</h2>
 * <ul>
 *   <li>{@code KeybindMulti#isKeybindHeld()} 的实现是
 *       {@code pressed || (settings.getAllowEmpty() && keyCodes.isEmpty())} —— 我们开了
 *       {@code allowEmpty}，所以<b>空绑定时它恒为 true</b>。直接拿来判「按住了组合键」，
 *       「清空 = 关掉手势」会变成「清空 = 任何点击都取满」。</li>
 *   <li>{@code isPressed()} 只反映<b>按键那一刻</b>的边沿（而且要靠 malilib 的按键簿记），
 *       与「鼠标点击这一拍」不在同一个状态机上。这里问的是「现在按着没有」，是电平而非边沿。</li>
 * </ul>
 *
 * <h2>判据三步</h2>
 * <ol>
 *   <li><b>空绑定 ⇒ 恒 false</b>：玩家清空组合键 = 关掉这个手势（开关开着也不响应）；</li>
 *   <li><b>这一下点的鼠标键必须在绑定里</b>：「取满哪一格」由「点在哪」决定，
 *       点右键却按左键的组合键当然不算；</li>
 *   <li><b>绑定里其余的键都得真的按着</b>：键盘键问调用方给的 {@code isKeyDown}；
 *       绑定里若还有<b>第二个鼠标键</b>，一律判否 —— 一个指针按不住两个键。</li>
 * </ol>
 *
 * <h2>🔴 鼠标键编码 = 原版按钮号 - 100</h2>
 * <p>这不是我编的换算，而是 malilib 自己在「玩家绑键」时用的同一条公式
 * （{@code ConfigButtonKeybind} 里 {@code addKey(input() - 100)}）—— 所以两边必然对得上。
 * 而「原版按钮号」26.3 是 1 基（左=1、中=2、右=3）、26.2 及更早是 0 基（左=0、右=1、中=2）：
 * 算出来的左键分别是 {@code -99} 和 {@code -100}，<b>正好等于各自版本 malilib 的
 * {@code BUTTON_1}</b>。也就是说版本差异在这里自己消掉了，一份源码五个版本通用 ——
 * 这也是当初能删掉写死的 {@code LEFT_BUTTON} 常量的原因。</p>
 */
final class FillCombo {

    /**
     * malilib 的鼠标键编码偏移：{@code 键码 = 原版按钮号 - 100}。
     *
     * <p>推论：<b>负数 = 鼠标键，非负 = 键盘键</b> —— {@link #matches} 里那个
     * {@code code < 0} 的分支靠的就是这条。</p>
     */
    static final int MOUSE_KEY_OFFSET = 100;

    private FillCombo() {
    }

    /**
     * 这一下点击算不算「取满组合键」。
     *
     * @param keys      玩家绑定的键码（malilib 的 {@code IKeybind#getKeys()}，可为空）
     * @param mouseButton 原版 {@code MouseButtonEvent#button()}（26.3 是 1 基，其余 0 基）
     * @param isKeyDown 键盘键是否按着（null 视为全都没按）
     * @return 空绑定 ⇒ 恒 false
     */
    static boolean matches(List<Integer> keys, int mouseButton, IntPredicate isKeyDown) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        int clicked = mouseButton - MOUSE_KEY_OFFSET;
        if (!keys.contains(clicked)) {
            return false;
        }
        for (int code : keys) {
            if (code == clicked) {
                continue;   // 就是这一下点的那个键，当然「按着」
            }
            if (code < 0) {
                return false;                               // 还有别的鼠标键：按不住
            }
            if (isKeyDown == null || !isKeyDown.test(code)) {
                return false;
            }
        }
        return true;
    }
}
