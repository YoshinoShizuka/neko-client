package neko.voidwarehouse.search;

/**
 * 「让当前开着的界面就地刷新」的窄接口，由 {@code MixinAbstractContainerScreen} 实现。
 *
 * <h2>为什么需要它</h2>
 * <p>开关是全局的（{@link SearchConfig}），但真正要跟着变的是<b>某一个正在显示的
 * 容器界面</b>：搜索框要不要亮、键盘焦点要不要放掉、服务端那边挂着的过滤要不要撤掉。
 * 这三件事都只有那个屏幕自己知道，而热键的处理点（{@code ClientTickEvents}）
 * 手里只有一个 {@code Screen} 引用。</p>
 *
 * <h2>为什么不用 Minecraft 的「当前屏幕」</h2>
 * <p>「撤焦点」得调 {@code ContainerEventHandler#setFocused}（容器的事件路由字段），
 * 那个方法只有混进 {@code AbstractContainerScreen} 的代码才拿得到。所以让 mixin
 * 实现本接口，{@code SearchClientState.bind(...)} 时把 {@code this} 存下来，
 * 热键切换时直接调 —— <b>完全不碰 {@code Minecraft#screen}</b>。</p>
 *
 * <p>🔴 这不是洁癖：{@code Minecraft#screen} 在 26.x 内部搬过一次家
 * （26.1.1 / 26.1.2 是公开字段 {@code screen}，26.2 / 26.3 变成
 * {@code minecraft.gui.screen() -> Gui#screen()}，而 {@code Gui#screen()} 在
 * 26.1.x 上根本不存在）。能绕开就绕开，五个版本就能共用一份源码。</p>
 *
 * <p>方法名带 {@code vwsearch$} 前缀：mixin 注入的成员一律用项目前缀命名，
 * 避免和目标类或其它模组撞名（与 mixin 里的 {@code @Unique} 成员同一套约定）。</p>
 */
public interface SearchScreenAccess {

    /** 按当前配置刷新这个界面：搜索框显隐、焦点、服务端过滤 */
    void vwsearch$applyFeatureState();
}
