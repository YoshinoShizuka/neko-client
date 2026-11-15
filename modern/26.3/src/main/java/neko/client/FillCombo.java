package neko.client;

import java.util.List;

/**
 * 「取满组合键」的<b>纯判据</b>：给定「玩家绑了哪些键」+「这一下点的是哪个鼠标键」+
 * 「点击那一瞬间 Shift / Ctrl / Alt 各按着没有」，回答「这一下点击算不算取满」。
 *
 * <h2>为什么单独一个类，而不是写在 {@link SearchConfig} 里</h2>
 * <p>{@code SearchConfig} 的静态初始化就 new 了 malilib 的 {@code ConfigHotkey} ——
 * 那些类在「没启动游戏」的 JVM 里加载不了，于是<b>那条规则一行都测不到</b>。
 * 而它恰恰是本模组最容易静默失效的一段判据：写错了不会崩、不会报错，只会
 * 「按下去什么都不发生」。所以规则本身被拎到这里，<b>不 import malilib、不 import MC</b>，
 * 入参全是纯数据，JUnit 里直接喂。</p>
 *
 * <h2>🔴 0.4.3 的事故：为什么不能用 malilib 的 isKeyDown / isKeybindHeld</h2>
 * <p>0.4.3 在这里逐键问 malilib「这个键按着没有」（{@code KeybindMulti.isKeyDown}），
 * 在 26.3 真机上<b>一次都没命中，而且日志里零异常</b>。原因是 26.x 的 malilib 把
 * <b>SDLK</b> 当成 SDL 的 <b>scancode</b> 用：</p>
 * <pre>
 * KeybindMulti.isKeyDown(1073742048)          // LEFT_CONTROL 的 SDLK
 *   → InputConstants.isKeyDown(1073742048)
 *       → SDLKeyboard.SDL_GetKeyboardState().get(1073742048) != 0
 *              ↑ 这张表按 scancode 索引（0..511），传进来的却是 SDLK
 *              ↑ LWJGL 给的是 Integer.MAX_VALUE 容量的「裸内存视图」
 *              ⇒ 越界不抛异常，读的是缓冲区之外的一兆字节处，恰好为 0 ⇒ 恒 false
 * </pre>
 * <p>{@code KeybindMulti.isKeybindHeld()} 也救不了：它的 {@code pressed} 由
 * {@code PRESSED_KEYS.containsAll(scanCodes)} 算出，而 {@code PRESSED_KEYS} 每帧都被
 * {@code reCheckPressedKeys()} 拿同一个坏掉的 {@code isKeyDown} 清理一遍 ——
 * 按住的 Ctrl / Alt 会在下一帧被当成「已经松开」删掉。（1.21.11 的 malilib
 * 走 {@code GLFW.glfwGetKey}，没有这个问题，所以这个坑只在 26.x 上发作。）</p>
 *
 * <h2>所以改问谁</h2>
 * <p>问<b>点击事件自己</b>：{@code MouseButtonEvent} 带着<b>点击那一瞬间</b>的修饰键
 * 掩码（原版「Shift 点击 = 整组快速移动」就是读它），那份数据不经过 malilib，
 * 也不靠轮询键盘状态。代价是<b>键盘那半边只能绑修饰键</b> —— 原版只把 Shift / Ctrl /
 * Alt 三位暴露出来，其它字母键没有任何可靠的实时查询入口。所以</p>
 * <ul>
 *   <li>组合键 = <b>恰好一个鼠标键 + 若干修饰键</b>（哪个鼠标键、哪几个修饰键都由玩家定）；</li>
 *   <li>绑了字母键 / 数字键 ⇒ 判否，并在日志里说清楚为什么（见 {@link Combo#problem()}）——
 *       <b>「静默不生效」是上一轮最贵的一课</b>，宁可大声拒绝，也不许默默不响应。</li>
 * </ul>
 *
 * <h2>多按了没绑的修饰键算不算</h2>
 * <p>算。malilib 的 {@code allowExtraKeys} 就是这个语义（我们建 {@code KeybindSettings}
 * 时传的也是 {@code true}）：绑 Ctrl+左键，按 Ctrl+Shift+左键照样取满。
 * 反过来<b>少按</b>一个绑定的修饰键一定不算。</p>
 *
 * <h2>🔴🔴 0.4.5 的事故：绝不许给按钮号加「容错」</h2>
 * <p>0.4.5 为了让 0.4.4 日志里那一击（{@code 按钮=3 → 键码=-97}，绑定 {@code -99}）
 * 能生效，加了一条「键码差 ≤2 就算命中」的隐性容错。真机上它确实让取满跑通了：
 * 日志里连续十几次 {@code 已取出 …} 都跟着「命中（按钮号容错 ±2）」。
 * <b>但功能能用 ≠ 语义正确</b> —— 玩家绑的是 Ctrl+Alt+左键，真正生效的却大面积是
 * Ctrl+Alt+<b>右键</b>。玩家反馈的原话：「实际生效的不是 Ctrl+Alt+左键 而是
 * Ctrl+Alt+右键」。「组合键由玩家自定义」被退化成「修饰键按全、点哪个键都行」。</p>
 * <p>0.4.6 起只有一条判据：{@link #matches} —— <b>绑什么键就是什么键</b>。
 * 想用右键就把鼠标键显式绑成 {@code BUTTON_2}（26.3 也可以写 {@code MOUSE_RIGHT}）；
 * 没有任何隐性放宽。判据日志会对「按钮号对不上」直接说出点的是哪个码、绑的是哪个码，
 * 而不是静默判否。这条教训已固化进 D 组单测与 jar 级负向断言。</p>
 *
 * <h2>🔴 为什么精确匹配不会踩 0 基 / 1 基版本差</h2>
 * <p>配置里存的是<b>键名</b>（{@code BUTTON_1} / {@code MOUSE_LEFT}），不是数字。
 * {@code getKeys()} 由<b>当前版本的 malilib</b> 把名字解析成该版本的码值：</p>
 * <ul>
 *   <li>26.3（SDL，按钮号 1 基）：{@code MOUSE_LEFT=-99}、{@code MOUSE_MIDDLE=-98}、
 *       {@code MOUSE_RIGHT=-97}；别名 {@code BUTTON_1=-99}、{@code BUTTON_2=-97}、
 *       {@code BUTTON_3=-98}（javap 真机 0.30.2 实证）；</li>
 *   <li>26.2 / 26.1.x / 1.21.11（0 基）：{@code MOUSE_BUTTON_1=-100}、
 *       {@code MOUSE_BUTTON_2=-99}、{@code MOUSE_BUTTON_3=-98}。</li>
 * </ul>
 * <p>点击事件用的也是同一版本的按钮号，所以同一份配置在五个版本都自洽 ——
 * <b>版本差不需要、也不许用容错去抹平</b>。</p>
 *
 * <h2>🔴 鼠标键 = 原版按钮号 - 100（负数），键盘键非负</h2>
 * <p>这不是我编的换算，而是 malilib 自己在「玩家绑键」时用的同一条公式
 * （{@code ConfigButtonKeybind} 里 {@code addKey(input() - 100)}）—— 所以两边必然对得上。
 * 26.3 左键 {@code 1-100=-99}、右键 {@code 3-100=-97}；旧版左键 {@code 0-100=-100}、
 * 右键 {@code 1-100=-99}。各自都与本版本 malilib 的鼠标键码对齐。</p>
 */
final class FillCombo {

    /** malilib 的鼠标键编码偏移：{@code 键码 = 原版按钮号 - 100}。推论：负数 = 鼠标键。 */
    static final int MOUSE_KEY_OFFSET = 100;

    private FillCombo() {
    }

    /** 掩码里能被原版看见的三位修饰键 */
    enum Modifier {
        SHIFT, CONTROL, ALT
    }

    /**
     * 解析结果。<b>不可用也是结果</b>，不是异常 —— 理由写在 {@code problem} 里，
     * 由调用方打进日志。这样「绑错了」和「绑对了但没按」在日志里是两件事。
     *
     * @param mouseCode 绑定里的那个鼠标键码（{@code problem != null} 时无意义）
     * @param problem   null = 可用；否则是「为什么不可用」的中文说明
     */
    record Combo(int mouseCode, boolean shift, boolean control, boolean alt, String problem) {

        boolean usable() {
            return this.problem == null;
        }
    }

    /** malilib 的键码里，负数一律是鼠标键（见类注释的换算） */
    static boolean isMouseKey(int code) {
        return code < 0;
    }

    /**
     * 键名 → 修饰键类别；认不出返回 null。
     *
     * <p>为什么按<b>名字</b>而不是按码值认：同一个「左 Ctrl」在 26.x 是 SDLK
     * {@code 1073742048}、在 1.21.11 是 GLFW {@code 340}、在 AZERTY 布局下还会被
     * malilib 改名 —— 码值五个版本各不相同，名字却是稳定的人可读串
     * （真机日志实证：{@code LEFT_CONTROL + LEFT_ALT + MOUSE_LEFT}）。</p>
     *
     * <p>用 {@code endsWith} 而不是全等：左右键各有一个前缀（{@code LEFT_} /
     * {@code RIGHT_}，有的版本还缩成 {@code L} / {@code R}），后缀是稳的。
     * 唯一已知的误判是 {@code KEY_LEVEL5_SHIFT} 会被当成 Shift —— 没人会把它当取满组合键，
     * 真误判了也只是「按住一个冷门键能取满」，不影响正确性。</p>
     */
    static Modifier modifierOf(String name) {
        if (name == null) {
            return null;
        }
        if (name.endsWith("CONTROL")) {
            return Modifier.CONTROL;
        }
        if (name.endsWith("SHIFT")) {
            return Modifier.SHIFT;
        }
        if (name.endsWith("ALT")) {
            return Modifier.ALT;
        }
        return null;
    }

    /**
     * 把 malilib 的显示串拆成键名列表：{@code "LEFT_CONTROL,LEFT_ALT,BUTTON_1"} 在界面上
     * 显示成 {@code "LEFT_CONTROL + LEFT_ALT + MOUSE_LEFT"}（{@code getKeysDisplayString()}
     * 就是 {@code getStringValue().replaceAll(",", " + ")}，javap 实证）。
     *
     * @return 空绑定 ⇒ 空列表
     */
    static List<String> namesOf(String displayString) {
        if (displayString == null || displayString.isBlank()) {
            return List.of();
        }
        return List.of(displayString.split(" \\+ "));
    }

    /**
     * 把「键码 + 键名」解析成一条可判定的组合键。
     *
     * @param keys  {@code IKeybind#getKeys()}（可为 null）
     * @param names {@code namesOf(getKeysDisplayString())}，必须与 {@code keys} 一一对应
     */
    static Combo parse(List<Integer> keys, List<String> names) {
        if (keys == null || keys.isEmpty()) {
            return problem("组合键为空（清空 = 关掉这个手势）");
        }
        // 两个来源必须一一对应：对不上就干脆不判（宁可手势不生效，也不许张冠李戴）
        if (names == null || names.size() != keys.size()) {
            return problem("键名与键码对不上（" + keys.size() + " 个键码 / "
                    + (names == null ? 0 : names.size()) + " 个键名）");
        }
        int mouseCode = 0;
        int mouseCount = 0;
        boolean shift = false;
        boolean control = false;
        boolean alt = false;
        for (int i = 0; i < keys.size(); i++) {
            int code = keys.get(i);
            if (isMouseKey(code)) {
                mouseCode = code;
                mouseCount++;
                continue;
            }
            Modifier modifier = modifierOf(names.get(i));
            if (modifier == null) {
                return problem("含非修饰键 " + names.get(i)
                        + "（键盘那一半只支持 Shift / Ctrl / Alt）");
            }
            switch (modifier) {
                case SHIFT -> shift = true;
                case CONTROL -> control = true;
                case ALT -> alt = true;
            }
        }
        if (mouseCount == 0) {
            return problem("没有鼠标键（取满要靠点击指定哪一格）");
        }
        if (mouseCount > 1) {
            return problem("绑了 " + mouseCount + " 个鼠标键（一个指针按不住两个）");
        }
        return new Combo(mouseCode, shift, control, alt, null);
    }

    /**
     * 绑定的修饰键在这一击里是不是按全了。
     *
     * <p>单独抽出来是为了让判据日志能分清「按钮号对不上」和「修饰键没按全」——
     * 这两件事给玩家的提示完全不同：前者该改绑定，后者该改按法。</p>
     *
     * <p>绑了就必须要按着；没绑的不要求（多按了也不算错，同 malilib 的
     * {@code allowExtraKeys}）。</p>
     */
    static boolean modifiersMatch(Combo combo, boolean shift, boolean control, boolean alt) {
        if (combo == null || !combo.usable()) {
            return false;
        }
        return (!combo.shift() || shift)
                && (!combo.control() || control)
                && (!combo.alt() || alt);
    }

    /**
     * 这一下点击算不算「取满组合键」——<b>唯一判据，严格匹配</b>。
     *
     * <p>鼠标键必须<b>逐位相等</b>：{@code 绑定键码 == 原版按钮号 - 100}。
     * 0.4.5 曾在这里挂过一条「差 ≤2 也算」的容错回退，结果是绑左键、右键生效；
     * 详见类注释 0.4.5 事故段。<b>不许再加任何形式的按钮号放宽</b>。</p>
     *
     * @param mouseButton 原版 {@code MouseButtonEvent#button()}（26.3 是 1 基，其余 0 基）
     * @param shift       {@code event.hasShiftDown()}
     * @param control     {@code event.hasControlDown()}
     * @param alt         {@code event.hasAltDown()}
     */
    static boolean matches(Combo combo, int mouseButton, boolean shift, boolean control, boolean alt) {
        if (combo == null || !combo.usable()) {
            return false;
        }
        if (combo.mouseCode() != mouseButton - MOUSE_KEY_OFFSET) {
            return false;
        }
        return modifiersMatch(combo, shift, control, alt);
    }

    private static Combo problem(String text) {
        return new Combo(0, false, false, false, text);
    }
}
