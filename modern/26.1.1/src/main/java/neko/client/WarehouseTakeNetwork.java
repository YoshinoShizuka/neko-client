package neko.client;

import fi.dy.masa.malilib.util.InfoUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import neko.voidwarehouse.restock.PrinterTakePayload;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 「热键 + 手持物 → 从虚空仓库取货到背包」的客户端收发管家。
 *
 * <h2>为什么不用仓库界面那条 {@code fill_request}</h2>
 * <p>{@code fill_request} 必须开着仓库界面、按 slotId 取，而这条要的是
 * <b>任何时刻手持某物按一下就能补货</b>（典型场景：quickcraft 填容器时背包缺料），
 * 服务端 {@code printer_take} 正好是「按物品 id 要一组、塞进背包」的通道，
 * 不需要开界面，也不要求客户端读得到仓库库存（数据全在服务端）。</p>
 *
 * <h2>一次请求就够</h2>
 * <p>服务端 {@code PrinterTakeService#take} 内部是
 * {@code withdraw(sample, Integer.MAX_VALUE)} —— 一次把仓库里这个物品能给的都取出来，
 * 背包只塞得下部分就把剩下的还回仓库。所以「取到放不下为止」是<b>服务端一次做完</b>的，
 * 客户端不需要循环发请求。</p>
 *
 * <h2>🔴 成功要取交集</h2>
 * <p>{@code OK} 只代表服务端发了；专用服务器上还必须确认背包里该物品真的变多。
 * 判据在 {@link WarehouseTakeModel#delivered}（纯类、可单测）。</p>
 *
 * <h2>收发两端都要自己注册</h2>
 * <p>专用服务器上客户端进程的 {@code SERVERBOUND_PLAY} 表是独立的，不自己注册的话
 * 发包会退化成 {@code DiscardedPayload} —— 不崩、{@code canSend} 还显示支持，
 * 但数据整包丢，是最难查的一种失效。单人同进程时服务端模组已经注册过，
 * 这里用「撞名吞掉」保持幂等；归属对不对由 {@link PayloadChannels} 核对。</p>
 */
public final class WarehouseTakeNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /** 同一时刻只允许一个请求在飞（与 printer 一致） */
    private static volatile int activeNonce = 0;
    private static volatile int nextNonce = 1;
    private static volatile int latestStatus = WarehouseTakeModel.STATUS_NO_REPLY;
    /** 发包前背包里该物品的件数，用于成功判据 */
    private static volatile int heldBefore = 0;
    private static volatile String activeItemId = null;
    private static volatile long sentAt = 0L;
    /** 撞 THROTTLED 时的重试次数（最多 2 次，避免无限退避） */
    private static volatile int retries = 0;
    private static volatile boolean initialized = false;
    /** 谁在等这次回执：手持热键为 null；投影填充的补料链会传自己的回调 */
    private static volatile Consumer<Boolean> completion = null;
    /** 填充补料是后台行为，不抢玩家的动作栏（失败由填充链路自己报） */
    private static volatile boolean silentRequest = false;

    private WarehouseTakeNetwork() {
    }

    /** 客户端初始化时调一次；registry 由入口按版本传入（26.x / 1.21.11 方法名不同） */
    public static void init(PayloadTypeRegistry<?> c2s, PayloadTypeRegistry<?> s2c) {
        if (initialized) {
            return;
        }
        PayloadChannels.register(c2s, "printer_take",
                PrinterTakePayload.TYPE, PrinterTakePayload.CODEC);
        PayloadChannels.register(s2c, "printer_take_result",
                PrinterTakeResultPayload.TYPE, PrinterTakeResultPayload.CODEC);
        try {
            ClientPlayNetworking.registerGlobalReceiver(
                    PrinterTakeResultPayload.TYPE, WarehouseTakeNetwork::receive);
        } catch (RuntimeException duplicate) {
            LOGGER.warn("[虚空仓库补货] 回执接收端已注册过：{}", duplicate.toString());
        }
        ClientTickEvents.END_CLIENT_TICK.register(WarehouseTakeNetwork::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forget());
        initialized = true;
    }

    /** 对端认不认这条取货通道（旧服务端 / 纯原版服 ⇒ false） */
    public static boolean isSupported() {
        if (!initialized) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(PrinterTakePayload.TYPE)
                    && PayloadChannels.usable(PrinterTakePayload.TYPE);
        } catch (RuntimeException noConnection) {
            return false;
        }
    }

    /**
     * 从手持物发一次取货请求。
     *
     * @return {@code true} = 请求已发出（不代表取到货，结果看回执）
     */
    public static boolean begin(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty()) {
            InfoUtils.printActionbarMessage("message.neko_client.warehouse_take_no_item");
            return false;
        }
        return beginForItem(client, held, false, null);
    }

    /**
     * 指定物品取货（0.5.4 投影填充的缺料续填用）。
     *
     * <p>与 {@link #begin(Minecraft)} 共用同一条 {@code printer_take} 通道和成功判据，
     * 只是不读手持物、不刷动作栏，并在真正「取到了」时回调调用方。<b>回调在回执
     * 已结算、状态已清空之后才调用</b>，因此回调里可以立刻发下一个请求。</p>
     *
     * @return {@code true} = 请求已发出；false 时<b>不</b>回调（调用方看返回值即可）
     */
    public static boolean beginForItem(Minecraft client, ItemStack sample,
                                       boolean silent, Consumer<Boolean> onResult) {
        if (client == null || client.player == null || sample == null || sample.isEmpty()) {
            return false;
        }
        if (!isSupported()) {
            if (!silent) {
                InfoUtils.printActionbarMessage("message.neko_client.warehouse_take_unsupported");
            }
            return false;
        }
        if (activeNonce != 0) {
            if (!silent) {
                InfoUtils.printActionbarMessage("message.neko_client.warehouse_take_busy");
            }
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(sample.getItem()).toString();
        int nonce = WarehouseTakeModel.nextNonce(nextNonce);
        nextNonce = nonce;

        heldBefore = countInInventory(client, sample);
        activeItemId = id;
        activeNonce = nonce;
        latestStatus = WarehouseTakeModel.STATUS_NO_REPLY;
        completion = onResult;
        silentRequest = silent;
        sentAt = System.currentTimeMillis();
        retries = 0;
        try {
            ClientPlayNetworking.send(new PrinterTakePayload(nonce, List.of(id)));
        } catch (RuntimeException failed) {
            forget();
            LOGGER.warn("[虚空仓库补货] 发包失败：{}", failed.toString());
            if (!silent) {
                InfoUtils.printActionbarMessage("message.neko_client.warehouse_take_failed");
            }
            return false;
        }
        return true;
    }

    private static void finishRequest(Consumer<Boolean> callback, boolean result) {
        if (callback != null) {
            callback.accept(result);
        }
    }

    private static void receive(PrinterTakeResultPayload payload, ClientPlayNetworking.Context context) {
        if (payload.nonce() <= 0 || payload.nonce() != activeNonce) {
            return;
        }
        latestStatus = payload.status();
    }

    /** 每 tick 推进回执 / 超时 / 退避重试；未激活时是一个很快的 return */
    private static void tick(Minecraft client) {
        int nonce = activeNonce;
        if (nonce == 0) {
            return;
        }
        int status = latestStatus;
        if (status == WarehouseTakeModel.STATUS_NO_REPLY) {
            if (System.currentTimeMillis() - sentAt > WarehouseTakeModel.REPLY_TIMEOUT_MS) {
                if (!silentRequest) {
                    InfoUtils.printActionbarMessage("message.neko_client.warehouse_take_no_reply");
                }
                LOGGER.warn("[虚空仓库补货] nonce={} 超时无回执（{}）", nonce, activeItemId);
                Consumer<Boolean> callback = completion;
                forget();
                finishRequest(callback, false);
            }
            return;
        }
        if (WarehouseTakeModel.shouldRetry(status) && retries < 2) {
            retries++;
            resend(client, nonce);
            return;
        }
        // 成功判据取交集：服务端 OK + 背包真的变多
        boolean delivered = WarehouseTakeModel.delivered(status, heldBefore,
                countInInventory(client, stackOf(activeItemId)));
        if (!delivered && !silentRequest) {
            String key = WarehouseTakeModel.hintKey(status);
            if (key != null) {
                InfoUtils.printActionbarMessage(key);
            }
            if (status == 4) {
                LOGGER.warn("[虚空仓库补货] nonce={} 重试 2 次仍撞限流", nonce);
            }
        }
        // 🔴 先清空本次状态再回调：回调里常常立刻发下一个补料请求
        Consumer<Boolean> callback = completion;
        forget();
        finishRequest(callback, delivered);
    }

    /** 撞限流后退避重发（same nonce，回执配对不变） */
    private static void resend(Minecraft client, int nonce) {
        try {
            ClientPlayNetworking.send(new PrinterTakePayload(nonce, List.of(activeItemId)));
            heldBefore = countInInventory(client, stackOf(activeItemId));
            sentAt = System.currentTimeMillis();
            latestStatus = WarehouseTakeModel.STATUS_NO_REPLY;
        } catch (RuntimeException failed) {
            forget();
        }
    }

    private static ItemStack stackOf(String id) {
        if (id == null) {
            return ItemStack.EMPTY;
        }
        var key = net.minecraft.resources.Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(key));
    }

    /** 数主背包 36 格里该物品的件数（与服务端 PrinterTakeService 的口径一致） */
    private static int countInInventory(Minecraft client, ItemStack sample) {
        if (client == null || client.player == null || sample.isEmpty()) {
            return 0;
        }
        var inventory = client.player.getInventory();
        int count = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, sample)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 作废当前请求（换世界 / 断线 / 收尾）；填充补料链取消时只作废自己的请求 */
    public static void forget() {
        activeNonce = 0;
        latestStatus = WarehouseTakeModel.STATUS_NO_REPLY;
        activeItemId = null;
        heldBefore = 0;
        retries = 0;
        completion = null;
        silentRequest = false;
    }

    public static void cancelFillRequest() {
        if (completion != null) {
            forget();
        }
    }
}
