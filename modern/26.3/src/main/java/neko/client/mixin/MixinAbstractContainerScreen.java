package neko.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import neko.client.SearchClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给容器界面叠加「虚空仓库搜索框」。
 *
 * <h2>只叠加、不改动</h2>
 * <p>所有注入都是 TAIL / HEAD 吞键，不修改任何原版逻辑；搜索框只对
 * {@link ChestMenu}（大箱子家族，虚空仓库的界面就是一个 6 行大箱子）创建，
 * 其它容器界面（熔炉、酿造台……）一个字节都不碰。</p>
 *
 * <h2>🔴 键盘吞噬：为什么 keyPressed 必须在 HEAD 整个吞掉</h2>
 * <p>26.3 的 {@code EditBox.keyPressed} 只消费编辑键（方向键 / 退格 / 全选 /
 * 复制粘贴），<b>数字键与字母键从它手里 fall through</b>（javap -c 实证：
 * 方法体最后是识别键返回 1、否则返回 0）。而容器界面对数字键的语义是
 * {@code SWAP} —— 「点着仓库格按 1-9 把快捷栏整组存入仓库」。也就是说：
 * 如果只在搜索框聚焦时放行原版路由，玩家在框里打 {@code td}（铁锭），
 * 中途按下的每个数字都会把快捷栏那一格<b>真的存进虚空仓库</b>。</p>
 *
 * <p>所以聚焦期间的按键必须在 HEAD 全部拦下：先交给 {@code box.keyPressed}
 * 处理编辑键，然后无条件 {@code cancel} —— 编辑键已被处理，其余键本来就
 * 不该有任何游戏内语义。字母 / 汉字走的是 {@code charTyped}（CharacterEvent），
 * 与按键路由无关，仍由原版 children 路由送进聚焦的输入框。</p>
 *
 * <p>ESC 例外处理：聚焦时 ESC = 退出搜索（不关界面），再按一次才关界面 ——
 * 与创造模式物品栏的搜索框行为一致。</p>
 *
 * <h2>渲染为什么要手动接线</h2>
 * <p>26.x 的容器界面 {@code extractRenderState} <b>不调用 super</b>
 * （javap -c 实证：只走 extractContents / extractCarriedItem / extractTooltip），
 * Screen 的 renderables 出帧管线对容器界面不生效。所以这里在 TAIL 手动调
 * {@code box.extractRenderState(...)}，焦点光标、hint、选区全部由 EditBox 自己画。</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu>
        implements neko.client.SearchScreenAccess {

    @Shadow
    protected int leftPos;
    @Shadow
    protected int topPos;
    @Shadow
    protected int imageWidth;
    @Shadow
    protected int imageHeight;
    // 🔴 addRenderableWidget 不在这里 @Shadow：它声明在父类 Screen，而 @Shadow 只查
    // 目标类自己声明的成员（2026-11-01 真机崩溃实录）—— 父类成员走 AccessorScreen 的
    // @Invoker，见本类 onInit 里的调用。

    @Unique
    private static final int SEARCH_WIDTH = 140;
    @Unique
    private static final int SEARCH_HEIGHT = 16;

    /**
     * ESC 的扫描码（GLFW {@code GLFW_KEY_ESCAPE}）。
     *
     * <p>本工程编译类路径上没有 GLFW 头（loom 的非 remap 管线不自动带 lwjgl），
     * 而 GLFW 键码自 1.0 起从未变过 —— 256 是稳定常量，加依赖不值得。</p>
     */
    @Unique
    private static final int KEY_ESCAPE = 256;

    /** 本屏幕的搜索框；非大箱子类界面恒为 {@code null} */
    @Unique
    private EditBox voidwarehouseSearch$searchBox;

    // ------------------------------------------------------------------ 生命周期

    /**
     * init 尾声：大箱子类界面创建搜索框并探测服务端。
     * <b>框创建即隐藏</b>（{@code setVisible(false)}），服务端回目录才亮 ——
     * 普通大箱子永远不亮，界面上没有任何残迹。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void voidwarehouseSearch$onInit(CallbackInfo ci) {
        if (!((Object) this.getMenu() instanceof ChestMenu)) {
            return;
        }
        // 两个功能都关了 ⇒ 连框都不建、hello 也不发，界面上一个字节的残迹都没有
        if (!neko.client.SearchConfig.anyFeatureEnabled()) {
            return;
        }
        EditBox box = new EditBox(Minecraft.getInstance().font,
                this.leftPos + (this.imageWidth - SEARCH_WIDTH) / 2,
                Math.max(2, this.topPos - SEARCH_HEIGHT - 2),
                SEARCH_WIDTH, SEARCH_HEIGHT,
                Component.literal("虚空仓库搜索"));
        box.setMaxLength(64);
        box.setVisible(false);
        box.setHint(Component.literal("搜索（中文/拼音/ID）"));
        box.setResponder(SearchClientState::onQueryChanged);
        // 加入 children（事件路由）+ renderables（narration）；渲染是我们自己出帧的
        ((AccessorScreen) (Object) this).voidwarehouseSearch$invokeAddRenderableWidget(box);
        this.voidwarehouseSearch$searchBox = box;
        SearchClientState.bind(box, this);
    }

    /**
     * 开关变化时就地刷新这个界面（{@link neko.client.SearchScreenAccess} 的实现）。
     *
     * <p>三种情况：</p>
     * <ul>
     *   <li><b>刚关掉搜索</b>：先撤焦点（否则字母还会被 {@code charTyped} 送进看不见的框里），
     *       再清输入、再补一条「空集合 = 清除过滤」——顺序不能反，见
     *       {@link neko.client.SearchClientState#exitSearch()};</li>
     *   <li><b>刚打开搜索</b>：仓库界面立刻亮框（普通大箱子仍然不亮，靠 {@code warehouse} 判）；</li>
     *   <li>开关没动（例如只切了取满）：什么都不做。</li>
     * </ul>
     */
    @Unique
    @Override
    public void vwsearch$applyFeatureState() {
        EditBox box = this.voidwarehouseSearch$searchBox;
        if (box == null) {
            return;
        }
        boolean on = neko.client.SearchConfig.isSearchBoxEnabled();
        if (!on) {
            this.voidwarehouseSearch$setSearchFocused(false);
            if (!box.getValue().isEmpty()) {
                box.setValue("");
            }
            neko.client.SearchClientState.exitSearch();
        }
        box.setVisible(on && neko.client.SearchClientState.isWarehouse());
    }

    /** 屏幕关闭：清客户端状态（服务端的过滤跟着菜单消失，不需要清理包） */
    @Inject(method = "removed", at = @At("TAIL"))
    private void voidwarehouseSearch$onRemoved(CallbackInfo ci) {
        this.voidwarehouseSearch$searchBox = null;
        SearchClientState.clear();
    }

    /** 每 tick 推进 apply 的尾随防抖 */
    @Inject(method = "containerTick", at = @At("TAIL"))
    private void voidwarehouseSearch$onTick(CallbackInfo ci) {
        SearchClientState.tick();
    }

    // ------------------------------------------------------------------ 键盘

    /**
     * 搜索框聚焦时在 HEAD 吞掉全部按键（理由见类注释）。
     * ESC = 退出搜索框而不是关界面。
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void voidwarehouseSearch$onKeyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        EditBox box = this.voidwarehouseSearch$searchBox;
        if (box == null || !box.isFocused()) {
            return;
        }
        if (keyEvent.key() == KEY_ESCAPE) {
            this.voidwarehouseSearch$setSearchFocused(false);
            cir.setReturnValue(true);
            return;
        }
        // 编辑键交给输入框处理；其余键到此为止 —— 绝不能落到容器的
        // checkHotbarKeyPressed（数字键 = 把快捷栏整组存进仓库）
        box.keyPressed(keyEvent);
        cir.setReturnValue(true);
    }

    @Unique
    private void voidwarehouseSearch$setSearchFocused(boolean focused) {
        EditBox box = this.voidwarehouseSearch$searchBox;
        if (box == null) {
            return;
        }
        ((ContainerEventHandler) (Object) this).setFocused(focused ? box : null);
        if (box.isFocused() != focused) {
            box.setFocused(focused);
        }
    }

    // ------------------------------------------------------------------ 鼠标

    /** 内容格上界：前 5 行 × 9 列（= 服务端 {@code VoidWarehouseData.NAV_START}） */
    @Unique
    private static final int CONTENT_SLOTS = 45;

    /*
     * 🔴 这里原本有一个 LEFT_BUTTON 常量（26.3=1，其余版本=0），0.4.3 起**删掉了**：
     * 「哪一下点击算取满」现在由玩家绑定的组合键决定，本类不再认识「左键」这个具体按钮。
     * 顺带说明那个版本差异去了哪：它在 SearchConfig#isFillComboHeld 里被消掉了 ——
     * 那边算的是 `原版按钮号 - 100`，而 malilib 的 BUTTON_1 在 26.3 恰好是 -99、
     * 在其余版本恰好是 -100，两边各自都对齐，所以**不需要按版本分叉**。
     * （26.3 的 button() 是 1 基、26.2 及更早是 0 基；26.3 的 MouseHandler 里
     *  1=左、2=中、3=右，别拿 GLFW 的 0/1/2 去套。）
     */

    /**
     * 「取满手势」：按住玩家绑定的组合键 + 点某一格，把这一格的物品
     * <b>取出到背包放不下为止</b>（常规模式纯散货、潜影盒模式给满盒 —— 模式与容量
     * 全由服务端现算）。
     *
     * <h2>组合键从哪来</h2>
     * <p>{@code config/neko-client.json} 的 {@code keyFillCombo}，0.4.7 起默认
     * {@code LEFT_CONTROL,LEFT_ALT,BUTTON_2}（= Ctrl+Alt+右键；左键绑定同样受支持，
     * 把鼠标键改成 {@code BUTTON_1} 即可）。
     * 判据在 {@link neko.client.SearchConfig#isFillComboHeld(int, boolean, boolean, boolean)}，
     * <b>本类不认识 Ctrl / Alt / 左键里的任何一个</b> —— 玩家绑什么就是什么，
     * 本类只负责把「实际点下的按钮号」+「这一刻的修饰键掩码」递过去。</p>
     *
     * <h2>为什么必须在 HEAD 拦下并 cancel</h2>
     * <p>不 cancel 的话，服务端先按普通左键取 1 个、再收到 fill_request 取一整批 ——
     * 玩家点一下会多拿 1 个（两笔都扣账，但「点一次多 1 个」不是任何一条语义）。</p>
     *
     * <h2>为什么只有发出去才 cancel</h2>
     * <p>对端没这条通道（旧服务端 / 纯原版服）时 {@code sendFill} 返回 false，
     * 这里原样放行 —— 点击退回「取 1 个」的原版语义，装了旧客户端的玩家
     * 感知不到任何异常，与搜索框的降级策略一致。</p>
     *
     * <h2>槽位从点击坐标现算，不读 hoveredSlot</h2>
     * <p>26.3 的 {@code hoveredSlot} 是渲染管线 {@code extractRenderState} 里写的
     * （javap 实证），而原版 {@code mouseClicked} 自己是把
     * {@code getHoveredSlot(event.x(), event.y())} 算进一个<b>局部变量</b>的。
     * 旧版这里读字段再用 {@code event.x()} 复校一次严格 16×16，既可能读到上一帧的
     * 旧格，又漏掉了原版命中框四周各 1px 的宽容 —— 两处都只会表现为「按了没反应」。
     * 现在照原版 {@code isHovering} 的公式遍历菜单槽位，一次算准。</p>
     *
     * <h2>为什么目录没到也把请求发出去</h2>
     * <p>服务端 {@code FillNetwork#handleFill} 才是权威：它自己会验
     * {@code voidWarehouse} 规则 + {@code containerMenu instanceof VoidWarehouseMenu}。
     * 客户端的 {@code warehouse} 只是「搜索框亮不亮」的视图标志，把它当作动作通道的
     * 硬门禁，等于让取满依赖一次可有可无的视图握手 —— 目录一丢，功能就静默消失。
     * 目录没到时改成：补发一次 hello、请求照发、但<b>不吞原版点击</b>（退回普通取 1 个），
     * 目录到了再恢复「点一下取满」的完整语义。</p>
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void voidwarehouseSearch$onMouseClickedFill(MouseButtonEvent event, boolean doubleClick,
                                                        CallbackInfoReturnable<Boolean> cir) {
        // 开关关着 ⇒ 完全退回原版语义（不 cancel、不发包），装了这个 mod 的玩家
        // 也感知不到自己装了它
        if (!neko.client.SearchConfig.isFillGestureEnabled()) {
            return;
        }
        // 🔴 递过去的是「点击那一瞬间按着哪些修饰键」，不是判据本身。
        // 判据（绑了哪些键、点的是不是那一个鼠标键、要不要 Shift/Ctrl/Alt）
        // 全在 SearchConfig 那边，0.4.6 起严格匹配、零回退 ——
        // 本类一旦写出 `hasControlDown() && hasAltDown()` 就又变回 0.4.2 的写死形态了。
        if (!neko.client.SearchConfig.isFillComboHeld(event.button(),
                event.hasShiftDown(), event.hasControlDown(), event.hasAltDown())) {
            return;
        }
        if (!((Object) this.getMenu() instanceof ChestMenu)) {
            neko.client.SearchConfig.audit("取满中止：菜单不是 ChestMenu（{}）",
                    this.getMenu().getClass().getName());
            return;
        }
        boolean warehouse = SearchClientState.isWarehouse();
        if (!warehouse) {
            neko.client.SearchConfig.audit("取满降级：目录未到 ⇒ 补发 hello、请求照发、不吞这次原版点击");
            SearchClientState.refreshCatalog();
        }
        Slot slot = voidwarehouseSearch$slotAt(event);
        if (slot == null) {
            // 🔴 0.4.7：只写「没落在 0..45」玩家看不懂 —— 0.4.6 真机上三次左键都落在
            // 玩家背包（relY≈146/161/216），日志却只说「不在内容格」，于是被当成
            // 「左键失效」。这里把界面原点、相对坐标和区域名一起打出来，一看就能对照界面。
            double relX = event.x() - this.leftPos;
            double relY = event.y() - this.topPos;
            neko.client.SearchConfig.audit(
                    "取满中止：点击 ({}, {}) 不在内容格（界面原点=({}, {}) 相对=({}, {}) 区域={}）"
                            + " —— 取满只认前 {} 格（前 5 行内容区）",
                    event.x(), event.y(), this.leftPos, this.topPos,
                    relX, relY, voidwarehouseSearch$regionOf(relX, relY), CONTENT_SLOTS);
            return;
        }
        if (slot.getItem().isEmpty()) {
            neko.client.SearchConfig.audit("取满中止：第 {} 格是空的", slot.index);
            return;
        }
        if (!SearchClientState.sendFill(slot.index)) {
            return;
        }
        neko.client.SearchConfig.audit("取满链路：slot={} 目录={} ⇒ {}原版点击",
                slot.index, warehouse ? "已到" : "未到", warehouse ? "吞掉" : "放行");
        if (!warehouse) {
            return;
        }
        // 原版点击被吞掉，它的「点框外退出搜索」也不会执行 —— 这里补上，
        // 否则键盘焦点会留在搜索框里，玩家以为自己在取货、其实在打字
        if (this.voidwarehouseSearch$searchBox != null && this.voidwarehouseSearch$searchBox.isFocused()) {
            this.voidwarehouseSearch$setSearchFocused(false);
        }
        cir.setReturnValue(true);
    }

    /**
     * 照原版 {@code isHovering} 的公式，从点击事件坐标现算落在哪个槽位。
     * 原版命中框是 {@code x-1 .. x+16+1}（四周各宽容 1px），不是严格的 16×16。
     * 只看前 {@link #CONTENT_SLOTS} 格内容区：导航行与玩家背包不是取满的目标。
     */
    @Unique
    private Slot voidwarehouseSearch$slotAt(MouseButtonEvent event) {
        double relX = event.x() - this.leftPos;
        double relY = event.y() - this.topPos;
        for (Slot candidate : this.getMenu().slots) {
            if (candidate.index < 0 || candidate.index >= CONTENT_SLOTS) {
                continue;
            }
            if (relX >= candidate.x - 1 && relX < candidate.x + 17
                    && relY >= candidate.y - 1 && relY < candidate.y + 17) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 把点击的相对坐标翻译成「能对照界面」的区域名，只给「没落在内容格」那条日志用。
     *
     * <p>坐标全部相对界面左上角（{@link #leftPos} / {@link #topPos}），数值取自原版
     * 大箱子布局：内容 5 行在 y=18..106、第 6 行导航在 y=108..124、玩家背包在
     * y=140..192、快捷栏在 y=198..214，界面高 222。服务端的
     * {@code VoidWarehouseData.CONTENT_ROWS = 5} / {@code NAV_START = 45} 与此一致。</p>
     */
    @Unique
    private String voidwarehouseSearch$regionOf(double relX, double relY) {
        if (relX < 0 || relX >= this.imageWidth) {
            return "界面左右之外（边框外）";
        }
        if (relY < 0) {
            return "界面上方（搜索框 / 边框）";
        }
        if (relY < 17) {
            return "顶部边框";
        }
        if (relY < 107) {
            // 走到这里说明纵坐标在内容行上，但横坐标落在列与列之间的空隙
            return "内容行内、但不在任何一列上（列间空隙 / 左右边框）";
        }
        if (relY < 125) {
            return "第 6 行导航行（不是内容格）";
        }
        if (relY < 139) {
            return "容器与背包之间的空隙";
        }
        if (relY < 193) {
            return "玩家背包（不是仓库内容格）";
        }
        if (relY < 215) {
            return "快捷栏（不是仓库内容格）";
        }
        return "界面下方";
    }

    /**
     * 点击行为：<b>点框 = 聚焦 + 顺手刷新一次目录</b>（服务端限流之内）；
     * 点框外 = 退出搜索框（归还全部键盘语义给容器）。
     * 用 {@code @At("RETURN")} 而不是 TAIL：原版这个方法有多条返回路径。
     */
    @Inject(method = "mouseClicked", at = @At("RETURN"))
    private void voidwarehouseSearch$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
                                                    CallbackInfoReturnable<Boolean> cir) {
        EditBox box = this.voidwarehouseSearch$searchBox;
        if (box == null) {
            return;
        }
        boolean inside = event.x() >= box.getX() && event.x() < box.getX() + box.getWidth()
                && event.y() >= box.getY() && event.y() < box.getY() + box.getHeight();
        if (inside && box.isVisible()) {
            // 原版路由已经点过 setFocused(box)；这里再对齐一次（幂等），
            // 顺带自愈「容器 focused 字段 == box 但 widget 标志已 false」的脏状态
            this.voidwarehouseSearch$setSearchFocused(true);
            SearchClientState.onSearchBoxClicked();
        } else if (box.isFocused()) {
            // 点框外：无论刚才那次点击是否被原版消费（点了槽位、点了框外空白），
            // 都退出搜索 —— 否则用户以为在点界面，键盘其实还在往框里打字
            this.voidwarehouseSearch$setSearchFocused(false);
        }
    }

    // ------------------------------------------------------------------ 渲染

    /** 容器界面的出帧管线不经过 renderables（见类注释），在 TAIL 手动画搜索框 */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void voidwarehouseSearch$onExtractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                                          float partialTick, CallbackInfo ci) {
        EditBox box = this.voidwarehouseSearch$searchBox;
        if (box != null && box.isVisible()) {
            box.extractRenderState(extractor, mouseX, mouseY, partialTick);
        }
    }

    /** {@code AbstractContainerScreen#getMenu}（26.3 实证为 public） */
    @Shadow
    public abstract T getMenu();
}
