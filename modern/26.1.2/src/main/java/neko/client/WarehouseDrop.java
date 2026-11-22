package neko.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import neko.client.fill.QuickClientScreenAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「在虚空仓库界面对着某一格按丢弃键 → 物品从仓库掉到地上」。
 *
 * <h2>它不是客户端自己生成掉落物</h2>
 * <p>客户端只发一条<b>原版语义</b>的 {@code THROW} 点击（button 0 = 1 个，
 * 1 = 整格）。真正的扣库存与生成掉落物都在服务端：{@code VoidWarehouseMenu#clicked}
 * 的 THROW 分支会从 {@code VoidWarehouseData} 扣账，再按每堆 ≤ maxStack 分批
 * {@code drop}。这样三件事天然一致：</p>
 * <ul>
 *   <li>界面上的数量减少（服务端 {@code syncAllViewers} 重发 54 格真值）；</li>
 *   <li>地上的掉落物（服务端生成实体，客户端预测不生成）；</li>
 *   <li>仓库存档（与左键 / 右键 / Shift 走同一个 {@code withdraw} 扣账）。</li>
 * </ul>
 *
 * <h2>为什么是 {@code handleContainerInput} 而不是 {@code player.drop}</h2>
 * <p>QuickCraft 的快速丢弃就是这么做的，而且只有它能同时满足「丢仓库格」与
 * 「客户端预测正确」：{@code player.drop} 丢的是<b>玩家背包里</b>的东西，
 * 对仓库格没有意义；直接构造 {@code ServerboundPlayerActionPacket} 也绕不开
 * 服务端的槽位校验。{@code THROW} 点击则是原版协议里唯一「指定槽位丢地」的输入。</p>
 *
 * <h2>三个前置门禁</h2>
 * <ol>
 *   <li>{@link SearchClientState#isWarehouse()}：只对虚空仓库生效，
 *       普通箱子 / 熔炉里按同一个键一律不管；</li>
 *   <li>{@link SearchClientState#isSearchFocused()}：搜索框正在输入时让路，
 *       否则打字里的字母会被当成快捷键；</li>
 *   <li>{@link WarehouseDropModel#isWarehouseContentSlot}：只丢前 45 格，
 *       导航行与玩家背包都碰不到。</li>
 * </ol>
 */
public final class WarehouseDrop {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    private WarehouseDrop() {
    }

    /**
     * 容器界面的 {@code keyPressed} 入口：<b>Shift+Q</b> 时执行整格丢弃并吞掉按键。
     *
     * <h2>为什么走界面按键而不是 malilib 热键</h2>
     * <p>0.5.9 首版把 Shift+Q 注册成 {@code ConfigHotkey}，真机上回调一次都没进
     * （日志里连一条 {@code [虚空仓库丢弃]} 都没有），而原版 Q 照样把物品丢了 1 个 ——
     * 说明按键到了界面、只是 malilib 那条链没派发。原版自己就是在
     * {@code AbstractContainerScreen#keyPressed} 里读 {@code options.keyDrop}，
     * 我们挂在同一个方法上，拿到的键、修饰键、吞键时机都最可靠。</p>
     *
     * @return {@code true} = 已接管，调用方必须 {@code cancel}（否则原版会再丢 1 个）
     */
    public static boolean handleKey(KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (!WarehouseDropModel.isDropKey(event.key(), event.hasShiftDown(), event.hasControlDown())) {
            return false;
        }
        if (!SearchClientState.isWarehouse()) {
            return false;
        }
        drop(Minecraft.getInstance());
        // 即使这一下没丢出去（悬停不在内容格），也要吞掉：原版 Q 会丢 1 个，
        // 那不是玩家按 Shift+Q 想要的结果。
        return true;
    }

    /**
     * 对着鼠标悬停的仓库格发一次<b>整格</b>丢弃（Shift+Q）。
     *
     * <p>「丢 1 个」与「丢一组」走<b>原版</b>的 Q / Ctrl+Q，不在这里重复实现 ——
     * 26.3 的 {@code AbstractContainerScreen#keyPressed} 自己就会对悬停槽发
     * {@code THROW}（Q → button 0，Ctrl+Q → button 1）。我们再注册同名热键只会
     * 造成「我们发一次 + 原版发一次」的双发，0.5.9 首版被玩家报「按一下丢了一整格」
     * 就是这条路径。</p>
     *
     * @return {@code true} = 请求已发出（不代表服务端一定扣了账，看界面数量变化）
     */
    public static boolean drop(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null) {
            return false;
        }
        if (!SearchClientState.isWarehouse()) {
            return false;
        }
        if (SearchClientState.isSearchFocused()) {
            return false;
        }
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        if (!(screen instanceof SearchScreenAccess geometry)) {
            return false;
        }
        if (screen.getMenu() == null) {
            return false;
        }

        int mouseX = (int) client.mouseHandler.getScaledXPos(client.getWindow());
        int mouseY = (int) client.mouseHandler.getScaledYPos(client.getWindow());
        int guiLeft = geometry.vwsearch$getGuiLeft();
        int guiTop = geometry.vwsearch$getGuiTop();

        Slot hovered = null;
        for (Slot slot : screen.getMenu().slots) {
            if (WarehouseDropModel.isMouseOver(slot.x, slot.y, mouseX, mouseY, guiLeft, guiTop)) {
                hovered = slot;
                break;
            }
        }
        if (hovered == null) {
            SearchConfig.trace("[虚空仓库丢弃] 鼠标不在任何格子上（{},{})", mouseX, mouseY);
            return false;
        }
        if (!WarehouseDropModel.isWarehouseContentSlot(hovered.index, screen.getMenu().slots.size())) {
            SearchConfig.trace("[虚空仓库丢弃] 第 {} 格不是仓库内容格（导航行 / 玩家背包不参与丢弃）",
                    hovered.index);
            return false;
        }
        boolean visible = hovered.isActive() && hovered.x >= 0 && hovered.y >= 0;
        if (!WarehouseDropModel.canDrop(hovered.hasItem(), hovered.mayPickup(client.player), visible)) {
            SearchConfig.trace("[虚空仓库丢弃] 第 {} 格不可丢（空 / 不可拿 / 不可见）", hovered.index);
            return false;
        }

        int button = WarehouseDropModel.BUTTON_WHOLE_SLOT;
        try {
            client.gameMode.handleContainerInput(
                    screen.getMenu().containerId,
                    hovered.index,
                    button,
                    ContainerInput.THROW,
                    client.player);
        } catch (RuntimeException failed) {
            LOGGER.warn("[虚空仓库丢弃] 发包失败：{}", failed.toString());
            return false;
        }
        SearchConfig.audit("[虚空仓库丢弃] 已发出 slot={} button={} item={}",
                hovered.index, button, hovered.getItem().getItem());
        return true;
    }
}
