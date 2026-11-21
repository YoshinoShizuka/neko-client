package neko.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「取满组合键」的<b>纯判据</b>：给定「玩家绑了哪些键」+「这一下点的是哪个鼠标键」+
 * 「点击那一瞬间 Shift / Ctrl / Alt 各按着没有」+「其它键盘键现在按着哪些」，
 * 回答「这一下点击算不算取满」。
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
 * 按住的 Ctrl / Alt 会在下一帧被当成「已经松开」删掉。</p>
 *
 * <h2>那键盘键凭什么能支持（同一轮修复）</h2>
 * <p>修饰键（Shift / Ctrl / Alt）继续问<b>点击事件自己</b> —— {@code MouseButtonEvent}
 * 带着<b>点击那一瞬间</b>的修饰键掩码（原版「Shift 点击 = 整组快速移动」就是读它），
 * 那份数据不经过 malilib，也不靠轮询键盘状态。</p>
 *
 * <p>其余键盘键（字母 / 数字 / F 键……）原版<b>没有</b>任何可靠的实时查询入口
 * （javap 实证：26.3 的 {@code InputConstants.isKeyDown(int)} 就是上面那个坏掉的
 * {@code ByteBuffer.get(key)}；26.3 连 {@code Screen.keyReleased} 都没有，
 * 1.21.11 才有）。但键盘事件本身是完整的：五个版本的
 * {@code KeyboardHandler.keyPress(long, int, KeyEvent)} 都收得到
 * <b>按下（action != 0）与松开（action == 0）</b>两路。所以键盘键走
 * 「<b>按键时记账，点击时对账</b>」：{@code MixinKeyboardHandler} 把按下的键丢进
 * {@link FillKeyState}，点击时把那份账本一起喂进 {@link #matches}。
 * <b>不轮询、不反射、不碰 malilib 的按下状态。</b></p>
 *
 * <h2>合法的组合键有三种形态</h2>
 * <ol>
 *   <li><b>一个鼠标键 + 若干修饰键</b>（默认 {@code Ctrl+Alt+右键}）：点那个键就算，
 *       鼠标键必须<b>逐位相等</b>；</li>
 *   <li><b>一个键盘键 + 一个鼠标键</b>（例如 {@code R + 左键}）：按着 R 点左键才算；</li>
 *   <li><b>只有一个键盘键</b>（例如只绑 {@code R}，或只绑 {@code LEFT_ALT}）：
 *       按着它点<b>任意</b>鼠标键都算 —— 玩家没绑鼠标键，就没有「应该点哪个键」可言。
 *       0.5.7 及以前这种绑定直接被判成「没有鼠标键 ⇒ 不可用」，于是玩家的
 *       「单个按键」一次都不生效（真机反馈）。</li>
 * </ol>
 * <p>❗形态 3 的「点任意键都行」<b>只发生在没有绑定鼠标键时</b>。
 * 一旦绑了鼠标键，{@link #matches} 就退回形态 1/2 的严格匹配 ——
 * 与 0.4.5 那个「绑了左键却让右键生效」的事故是两件不同的事，别混为一谈。</p>
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
 * 右键 {@code 1-100=-99}。各自都与本版本 malilib 的鼠标键码对齐。键盘键那半边同理：
 * malilib 存的就是 {@code InputConstants.Key#getValue()}，而
 * {@code InputConstants.getKey(KeyEvent)} 在五个版本里都是
 * {@code Type.KEYBOARD.getOrCreate(event.key())} ⇒ <b>码值就是 {@code KeyEvent.key()}</b>。</p>
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
     * 一个「非修饰键」的键盘键：码值 + 键名。
     *
     * <p>为什么要同时带名字：码值两边可能各自演化（26.3 是 SDL 的 SDLK、1.21.11 是
     * GLFW 的键码），而<b>键名从 1.0 起就没变过</b>（{@code LEFT_ALT} /
     * {@code key.keyboard.left.alt} 去掉前缀后是同一个串）。判据先比码值、再比名字，
     * 任一对上就算按着 —— 万一哪天某一侧换了编码，功能也不会静默失效。</p>
     */
    record Key(int code, String name) {
    }

    /**
     * 解析结果。<b>不可用也是结果</b>，不是异常 —— 理由写在 {@code problem} 里，
     * 由调用方打进日志。这样「绑错了」和「绑对了但没按」在日志里是两件事。
     *
     * @param mouseCode 绑定里的那个鼠标键码（{@code hasMouse == false} 时无意义）
     * @param hasMouse 有没有绑鼠标键。<b>没绑</b> ⇒ 点哪个鼠标键都算（形态 3）
     * @param keys      需要「按着」的非修饰键；空表 = 这一形态不需要键盘账本
     * @param problem   null = 可用；否则是「为什么不可用」的中文说明
     */
    record Combo(int mouseCode, boolean hasMouse, boolean shift, boolean control, boolean alt,
                 List<Key> keys, String problem) {

        Combo {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        boolean usable() {
            return this.problem == null;
        }

        /** 绑了键盘键 ⇒ 命不命中要看「那些键现在按着没有」（日志据此决定要不要解释）。 */
        boolean needsKeyboard() {
            return !this.keys.isEmpty();
        }

        /** 绑定的键盘键名单，只给日志用。 */
        String describeKeys() {
            if (this.keys.isEmpty()) {
                return "无";
            }
            StringBuilder text = new StringBuilder();
            for (Key key : this.keys) {
                if (text.length() > 0) {
                    text.append('+');
                }
                text.append(key.name() == null || key.name().isEmpty()
                        ? "码" + key.code() : key.name());
            }
            return text.toString();
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
        List<Key> keyboard = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            int code = keys.get(i);
            if (isMouseKey(code)) {
                mouseCode = code;
                mouseCount++;
                continue;
            }
            String name = names.get(i);
            Modifier modifier = modifierOf(name);
            if (modifier == null) {
                // 🔴 这一轮：不再是错误。字母 / 数字 / F 键一律进「要按着的键」表，
                //    由 FillKeyState 的按键账本回答「现在按着没有」。
                keyboard.add(new Key(code, name));
                continue;
            }
            switch (modifier) {
                case SHIFT -> shift = true;
                case CONTROL -> control = true;
                case ALT -> alt = true;
            }
        }
        if (mouseCount > 1) {
            return problem("绑了 " + mouseCount + " 个鼠标键（一个指针按不住两个）");
        }
        return new Combo(mouseCode, mouseCount == 1, shift, control, alt, keyboard, null);
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
     * 绑定的<b>非修饰键</b>是不是都按着 —— 码值或键名任一对上就算。
     *
     * <p>没绑这类键（绝大多数配置）⇒ 恒 true，一个集合都不用查。</p>
     */
    static boolean keysHeld(Combo combo, Set<Integer> heldCodes, Set<String> heldNames) {
        if (combo == null || !combo.usable()) {
            return false;
        }
        if (combo.keys().isEmpty()) {
            return true;
        }
        for (Key key : combo.keys()) {
            boolean byCode = heldCodes != null && heldCodes.contains(key.code());
            boolean byName = heldNames != null && key.name() != null
                    && heldNames.contains(normalizeName(key.name()));
            if (!byCode && !byName) {
                return false;
            }
        }
        return true;
    }

    /** 这一下点击算不算「取满组合键」，<b>并且</b>绑定的键盘键都没按着 ⇒ false。 */
    static boolean matches(Combo combo, int mouseButton, boolean shift, boolean control, boolean alt,
                           Set<Integer> heldCodes, Set<String> heldNames) {
        if (!matchesMouse(combo, mouseButton, shift, control, alt)) {
            return false;
        }
        return keysHeld(combo, heldCodes, heldNames);
    }

    /**
     * 不看键盘账本的那一半判据（鼠标键 + 修饰键）。
     *
     * <p>绑了键盘键的组合键<b>不能</b>只用这一个 —— 但它独立存在有价值：
     * 判据日志要靠它区分「这一击错在鼠标/修饰键」还是「错在键盘键没按」。</p>
     */
    static boolean matchesMouse(Combo combo, int mouseButton, boolean shift, boolean control, boolean alt) {
        if (combo == null || !combo.usable()) {
            return false;
        }
        // 绑了鼠标键 ⇒ 逐位相等；没绑 ⇒ 玩家点了就算（形态 3，见类注释）
        if (combo.hasMouse() && combo.mouseCode() != mouseButton - MOUSE_KEY_OFFSET) {
            return false;
        }
        return modifiersMatch(combo, shift, control, alt);
    }

    /**
     * 这一下点击算不算「取满组合键」——<b>不带键盘账本的旧签名</b>。
     *
     * <p>等价于「一个键盘键都没按着」。只在两种地方用到：不需要键盘键的组合键
     * （形态 1）、以及<b>单测</b>里刻意验「绑了键盘键但没人按 ⇒ 不算」。</p>
     *
     * <p>鼠标键必须<b>逐位相等</b>（绑了的话）：{@code 绑定键码 == 原版按钮号 - 100}。
     * 0.4.5 曾在这里挂过一条「差 ≤2 也算」的容错回退，结果是绑左键、右键生效；
     * 详见类注释 0.4.5 事故段。<b>不许再加任何形式的按钮号放宽</b>。</p>
     *
     * @param mouseButton 原版 {@code MouseButtonEvent#button()}（26.3 是 1 基，其余 0 基）
     * @param shift       {@code event.hasShiftDown()}
     * @param control     {@code event.hasControlDown()}
     * @param alt         {@code event.hasAltDown()}
     */
    static boolean matches(Combo combo, int mouseButton, boolean shift, boolean control, boolean alt) {
        return matches(combo, mouseButton, shift, control, alt, Set.of(), Set.of());
    }

    /**
     * 键名归一化：两边的写法折成同一个串再比。
     *
     * <ul>
     *   <li>malilib：{@code LEFT_ALT} / {@code KEY_A} / {@code KEY_1}</li>
     *   <li>原版 {@code InputConstants}：{@code key.keyboard.left.alt} /
     *       {@code key.keyboard.a} / {@code key.keyboard.1}</li>
     * </ul>
     * <p>去掉 {@code key.keyboard.} 前缀与 {@code key_} 前缀、小写、{@code .} 换成
     * {@code _} 之后两边都是 {@code left_alt} / {@code a} / {@code 1}。</p>
     */
    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String text = name.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("key.keyboard.")) {
            text = text.substring("key.keyboard.".length());
        } else if (text.startsWith("key.mouse.")) {
            text = text.substring("key.mouse.".length());
        }
        if (text.startsWith("key_")) {
            text = text.substring("key_".length());
        }
        return text.replace('.', '_');
    }

    private static Combo problem(String text) {
        return new Combo(0, false, false, false, false, List.of(), text);
    }
}
