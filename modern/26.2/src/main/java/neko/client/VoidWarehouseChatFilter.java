package neko.client;

/**
 * 「静音虚空仓库聊天提示」的<b>纯判据</b>：只回答一段系统聊天文本是不是虚空仓库说的。
 *
 * <h2>为什么不直接写在 mixin 里</h2>
 * <p>0.4.3 的取满判据已经吃过一次亏：把判据糊在事件回调里，单测碰不到，
 * 于是「绑左键却右键生效」这种语义错误全靠真机日志才抓得到。这里同样把
 * <b>判据本体</b>拆成不引用任何 Minecraft / malilib 类型的静态类，JUnit 能直接跑，
 * mixin 只负责「开关开着 + 判据命中 ⇒ cancel 这一包」。</p>
 *
 * <h2>三条特征从哪来</h2>
 * <ol>
 *   <li>{@code [虚空仓库] } —— carpet-Neko-Addition 的
 *       {@code VoidWarehouseText.INFO_PREFIX}，所有主提示的统一前缀；</li>
 *   <li>{@code [Void Warehouse] } —— 同类的 {@code INFO_PREFIX_EN}，
 *       英文客户端走这一条；</li>
 *   <li>{@code └ } —— 配方本「还能这样合 / 容量已满」的<b>续行</b>。
 *       它们是独立的 {@code sendSystemMessage} 调用，本体只有
 *       {@code VoidWarehouseText.body("└ ")}，<b>不带前缀</b>。
 *       少了这一条，开着静音时仍会漏出半截续行 —— 那就不是「全部静音」了。</li>
 * </ol>
 *
 * <h2>🔴 为什么<b>只认开头</b>，不认「包含」</h2>
 * <p>玩家在普通聊天里引用一句「[虚空仓库] 库存不足」是很常见的事；
 * 若用 {@code contains}，静音会把别人的正常聊天一起吃掉，而且找不到原因。
 * 服务端自己发的提示永远是<b>整条消息的第一个可见字符</b>就是这个前缀
 * （带样式时 {@code Component#getString()} 已经把样式码去掉了；这里仍多剥一层
 * 前导的 {@code §x} 与空白，防止将来有人改成字面量颜色码）。</p>
 *
 * <h2>已知取舍</h2>
 * <p>别的模组若也用 {@code └ } 开头发消息，会被一起静音。这是「全部静音」
 * 的代价；前缀两条是精确的，续行这一条只能靠特征。若以后要收紧，可以改成
 * 「上一条刚静音了主提示」的状态机，但那会让纯判据带上时序，先不做。</p>
 */
public final class VoidWarehouseChatFilter {

    /** 中文主提示前缀（服务端 {@code VoidWarehouseText.INFO_PREFIX} 的可见部分，含尾部空格） */
    private static final String PREFIX_ZH = "[虚空仓库] ";
    /** 英文主提示前缀（服务端 {@code VoidWarehouseText.INFO_PREFIX_EN} 的可见部分，含尾部空格） */
    private static final String PREFIX_EN = "[Void Warehouse] ";
    /** 配方本续行开头（服务端 {@code VoidWarehouseText.body("└ ")}，含尾部空格） */
    private static final String CONTINUATION = "└ ";

    private VoidWarehouseChatFilter() {
    }

    /**
     * 这一条系统聊天是不是虚空仓库的提示。
     *
     * @param text {@code ClientboundSystemChatPacket#content().getString()}，可为 {@code null}
     * @return 三条特征之一命中（且位于开头）⇒ {@code true}
     */
    public static boolean shouldSilence(String text) {
        if (text == null) {
            return false;
        }
        String visible = stripLeadingDecorations(text);
        return visible.startsWith(PREFIX_ZH)
                || visible.startsWith(PREFIX_EN)
                || visible.startsWith(CONTINUATION);
    }

    /**
     * 剥掉开头的空白与 {@code §x} 样式码。
     *
     * <p>原版 {@code Component#getString()} 本身不带样式码，这一层是防御性的：
     * 万一某条提示被改成字面量颜色码，判据不能因此静默失效 —— 这正是 0.4.x
     * 「功能看起来没坏，其实一次都没命中」那类事故的形状。</p>
     */
    private static String stripLeadingDecorations(String text) {
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '§' && index + 1 < text.length()) {
                index += 2;
                continue;
            }
            break;
        }
        return text.substring(index);
    }
}
