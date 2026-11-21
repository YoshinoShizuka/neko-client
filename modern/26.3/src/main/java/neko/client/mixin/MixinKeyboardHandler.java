package neko.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import neko.client.FillKeyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「键盘上按着哪些键」记进 {@link FillKeyState} —— 「取满」支持单个键盘键的地基。
 *
 * <h2>为什么钉在 {@code KeyboardHandler.keyPress}</h2>
 * <p>这是客户端<b>唯一</b>一处「按下与松开都会经过、且不带任何界面前提」的入口：</p>
 * <ul>
 *   <li>五个版本的签名完全一致（javap 实证）：
 *       {@code keyPress(long window, int action, KeyEvent event)} ——
 *       26.3 是 {@code public}，26.2 / 26.1.2 / 26.1.1 / 1.21.11 是 {@code private}，
 *       混入对 private 方法同样有效；</li>
 *   <li>{@code action == 0} ⇒ 松开，其余（{@code 1} 按下、{@code 2} 或 {@code -1} 自动重复）
 *       ⇒ 按下。26.3 的 {@code keyPress} 里写的是 {@code action == 1 || action == -1}
 *       才转发 {@code Screen#keyPressed}、{@code action == 0} 才转发
 *       {@code Screen#keyReleased}（javap -c 实证），所以这条判据与它的语义一致；</li>
 *   <li>不经过 {@code Screen}：26.3 的 {@code Screen} <b>没有</b> {@code keyReleased}
 *       （只有 1.21.11 有），走界面就等于「有的版本记不到松开」。</li>
 * </ul>
 *
 * <h2>为什么 {@code require = 0}</h2>
 * <p>这是<b>客户端体验</b>的一部分：混入没挂上时最坏的结果应该是「绑键盘键的手势不生效」，
 * 而不是整个游戏起不来。挂了没有由 {@link FillKeyState#sawAnyPress()} 回答 ——
 * 玩家绑了键盘键却一次按键都没记到时，判据日志会直接把
 * 「按键跟踪没挂上」写出来（{@code SearchConfig#isFillComboHeld}）。</p>
 *
 * <h2>不吞键</h2>
 * <p>只 HEAD 记账、不 cancel、不改任何返回值 —— 原版与其它模组看到的
 * {@code keyPress} 一字未变。</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class MixinKeyboardHandler {

    @Inject(method = "keyPress", at = @At("HEAD"), require = 0)
    private void nekoClient$trackFillKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (event == null) {
            return;
        }
        int code = event.key();
        String name;
        try {
            // 只在按键这一瞬间做一次名字解析（不是每帧），成本可以忽略
            name = InputConstants.getKey(event).getName();
        } catch (RuntimeException unknownKey) {
            // 解析不出名字不是错：码值那一路照样能对账
            name = null;
        }
        if (action == 0) {
            FillKeyState.release(code, name);
        } else {
            FillKeyState.press(code, name);
        }
    }
}
