package neko.client.fill;

/**
 * 连续填充热键的「已启用」状态——<b>纯逻辑</b>，不引用 Minecraft / malilib。
 *
 * <h2>为什么是开关而不是「按住」</h2>
 * <p>0.5.4 初版只有两条判据：热键本身（{@code isKeybindHeld()}）或与
 * {@code keyUse} 完全一致的绑定。前者在 26.x 的 malilib 上不可靠（见
 * {@link neko.client.FillCombo} 的事故记录），后者意味着玩家把热键绑成
 * {@code KP_9} 之类任意键时<b>永远不生效</b>——玩家实测就是这种情况。
 * 这里改由 malilib 的按键回调驱动：按一下启用、再按一下关闭。回调走的是
 * malilib 自己的事件派发（其它热键都在用），不碰任何「键现在按着没有」的坏 API。</p>
 */
public final class ContinuousFillControl {
    private static boolean armed;

    private ContinuousFillControl() { }

    /** 按一下热键：切换启用状态，返回切换后的值 */
    public static boolean toggle() {
        armed = !armed;
        return armed;
    }

    public static boolean isArmed() {
        return armed;
    }

    /** 填充结束 / 规则关闭 / 断线时复位，避免下次进服一右键就触发 */
    public static void disarm() {
        armed = false;
    }
}
