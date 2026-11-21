package neko.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 「键盘上现在按着哪些键」的<b>账本</b>：按下降记、松开销账、点击时对账。
 *
 * <h2>为什么需要它（不轮询键盘状态）</h2>
 * <p>「取满」要支持<b>单个键盘键</b>（例如只绑 {@code R}）。这件事原版没有现成的
 * 实时查询入口：26.3 的 {@code InputConstants.isKeyDown(int)} 把值当 SDL scancode
 * 去索引键盘状态缓冲区，而 malilib 传进去的是 SDLK（{@code LEFT_ALT = 1073742050}），
 * 越界不报错、读到 0 ⇒ <b>恒 false</b>（推导见 {@link FillCombo} 类注释）。
 * 所以不能「问键盘」，只能<b>记账</b>：{@code KeyboardHandler.keyPress} 的
 * {@code action == 0} 是松开、其余是按下 —— 五个版本的签名一致（javap 实证
 * 26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11 全是
 * {@code keyPress(long, int, KeyEvent)}），所以只需要一个混入。</p>
 *
 * <h2>账本用「码值 + 键名」双份记</h2>
 * <p>码值是两个模组之间的约定（malilib 存 {@code InputConstants.Key#getValue()}，
 * 我们收 {@code KeyEvent.key()}），正常情况下它们相等；万一哪天某一侧换了编码，
 * 键名这一份还能兜住（{@link FillCombo#normalizeName} 把
 * {@code LEFT_ALT} 与 {@code key.keyboard.left.alt} 折成同一个串）。
 * 判据那边只要任一对上就算「按着」。</p>
 *
 * <h2>🔴 松开事件丢了怎么办</h2>
 * <p>只在<b>一个屏幕关掉</b>时整份清空（{@code MixinAbstractContainerScreen#removed}）。
 * 这么做的理由：账本唯一会「脏」的时机是玩家按住键时切走窗口（操作系统只发失焦、
 * 不发 key-up），而那时候玩家手上一定开着某个容器界面 —— 关掉界面就等于
 * 「这次会话的按键状态从零开始」，比永久记住一个假「按着」安全得多。</p>
 *
 * <h2>线程</h2>
 * <p>写（混入）和读（点击判据）都在客户端主线程，所以这里<b>不加锁</b>。
 * 唯一的例外是 {@link #heldCodes()} / {@link #heldNames()} 返回的只读视图，
 * 它们只被主线程在读的那一刻遍历。</p>
 */
public final class FillKeyState {

    /** 按着的键码（malilib / {@code KeyEvent.key()} 的口径） */
    private static final Set<Integer> CODES = new LinkedHashSet<>();
    /** 按着的键名（{@link FillCombo#normalizeName} 归一化后） */
    private static final Set<String> NAMES = new LinkedHashSet<>();
    /** 诊断用：码值 → 人可读名字（顺序 = 按下的先后） */
    private static final Map<Integer, String> LABELS = new LinkedHashMap<>();

    /** 这个会话里到底有没有收到过任何一次按键（用于区分「没按」与「混入没挂上」）。 */
    private static boolean sawAnyPress;

    private FillKeyState() {
    }

    /**
     * 记一次按下。
     *
     * @param code 键码；{@code KeyEvent.key()}
     * @param name 键名（可为 null，那就只按码值对账）
     */
    public static void press(int code, String name) {
        sawAnyPress = true;
        CODES.add(code);
        String normalized = FillCombo.normalizeName(name);
        if (!normalized.isEmpty()) {
            NAMES.add(normalized);
        }
        LABELS.put(code, normalized.isEmpty() ? "码" + code : normalized);
    }

    /**
     * 销一次账（松开）。
     *
     * <p>码值一定删；键名只有在非空时才删 —— 否则 {@code release(code, null)} 会
     * 把「空串」当成一个键名留在集合里，进而让某些组合键永远显示「按着」。</p>
     */
    public static void release(int code, String name) {
        CODES.remove(code);
        String normalized = FillCombo.normalizeName(name);
        if (!normalized.isEmpty()) {
            NAMES.remove(normalized);
        }
        LABELS.remove(code);
    }

    /** 整份清空（界面关闭 / 换世界时用）。 */
    public static void clear() {
        CODES.clear();
        NAMES.clear();
        LABELS.clear();
    }

    /** 现在按着的键码（只读视图，别拿去改）。 */
    public static Set<Integer> heldCodes() {
        return Collections.unmodifiableSet(CODES);
    }

    /** 现在按着的键名（已归一化，只读视图）。 */
    public static Set<String> heldNames() {
        return Collections.unmodifiableSet(NAMES);
    }

    /** 这个会话里有没有收到过任何一次按键 —— 判「混入到底挂上没有」。 */
    public static boolean sawAnyPress() {
        return sawAnyPress;
    }

    /** 现在按着哪些键，只给日志用（空账本给一句人话，而不是空白）。 */
    public static String describe() {
        if (LABELS.isEmpty()) {
            return "（没有键按下）";
        }
        return String.join(" + ", LABELS.values());
    }
}
