package neko.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;

/**
 * 客户端提示的<b>虚空仓库统一格式</b>：与服务端 {@code VoidWarehouseText#info} 同一套前缀和配色。
 *
 * <h2>为什么客户端要自己造一遍</h2>
 * <p>服务端那个类在 carpet 模组里，客户端不能编译依赖它（两边是独立 jar，而且
 * 客户端在没有服务端的纯原版服上也要能显示）。这里按同一套常量复制一份：
 * 前缀亮金 {@code #FFD700}、正文浅灰 {@code #AAAAAA}、关键词亮绿 {@code #55FF55}，
 * 全部显式关掉粗体和斜体 —— 原版合样式时会把没设的字段沿用下去，不写 false 会被顶掉。</p>
 *
 * <h2>前缀按客户端语言切换</h2>
 * <p>读 {@code client.options.languageCode}（五版本 javap 核对都是 public String），
 * 中文玩家看到 {@code [虚空仓库] }，英文玩家看到 {@code [Void Warehouse] }，
 * 与服务端按接收玩家语言选前缀的语义一致。</p>
 */
public final class WarehouseText {
    private static final int PREFIX_RGB = 0xFFD700;
    private static final int BODY_RGB = 0xAAAAAA;
    private static final int KEY_RGB = 0x55FF55;
    private static final String PREFIX_ZH = "[虚空仓库] ";
    private static final String PREFIX_EN = "[Void Warehouse] ";

    private WarehouseText() { }

    /** 整条提示：亮金前缀 + 浅灰正文 */
    public static MutableComponent info(Minecraft client, Component body) {
        MutableComponent styledBody = body.copy().setStyle(
                Style.EMPTY.withColor(BODY_RGB).withItalic(false).withBold(false));
        return Component.literal(isEnglish(client) ? PREFIX_EN : PREFIX_ZH)
                .withStyle(Style.EMPTY.withColor(PREFIX_RGB).withItalic(false).withBold(false))
                .append(styledBody);
    }

    /** 正文片段：浅灰、正体 */
    public static MutableComponent body(String text) {
        return Component.literal(text).setStyle(
                Style.EMPTY.withColor(BODY_RGB).withItalic(false).withBold(false));
    }

    /** 关键词：亮绿、正体（容器名、数量这类玩家真正要读的词） */
    public static MutableComponent key(String text) {
        return Component.literal(text).setStyle(
                Style.EMPTY.withColor(KEY_RGB).withItalic(false).withBold(false));
    }

    /** 关键词组件版：保留原本的可翻译组件，只覆盖样式 */
    public static MutableComponent key(Component text) {
        return text.copy().setStyle(
                Style.EMPTY.withColor(KEY_RGB).withItalic(false).withBold(false));
    }

    /** 客户端当前语言是否为英文（与服务端 {@code VoidWarehouseText#isEnglish} 同一口径） */
    public static boolean isEnglish(Minecraft client) {
        return isEnglishCode(client == null || client.options == null
                ? null : client.options.languageCode);
    }

    /** 纯判据：给语言码回答前缀该用中文还是英文（可单测且不碰 Minecraft 类型） */
    public static boolean isEnglishCode(String languageCode) {
        if (languageCode == null) {
            return false;
        }
        return languageCode.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
