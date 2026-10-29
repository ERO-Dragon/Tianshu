package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 辅星开机时发给映迹的那条"还没准备好"。
 *
 * <p>只钉两件事：打开一次收起一次、没打开过的收敛不发包；以及装配失败时它也必须被收起——
 * 装配抛异常的模块不会进"已准备"列表，停机时没人替它收尾，只能靠这里收干净。</p>
 */
class AXStartupActivityTest {
    @Test
    void theLoadingNoticeIsOpenedAndClosedExactlyOnce() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        List<PresenceActivityPayload> activities = captureActivities(runtime);
        AXProtocolAdapter adapter = new AXProtocolAdapter(runtime);

        adapter.publishLoadingActivity(true);
        adapter.publishLoadingActivity(false);
        // 没打开过的收敛不发包：干净启动不该产生一对空的结束事件。
        adapter.publishLoadingActivity(false);

        await(() -> activities.size() >= 2, "准备中与已就绪两条活动");
        assertEquals(2, activities.size());
        assertEquals(PresenceActivityType.LOADING, activities.get(0).activityType());
        assertEquals(PresenceActivityAction.STARTED, activities.get(0).action());
        assertEquals("ax.module.load", activities.get(0).activityId());
        assertEquals(PresenceActivityType.LOADING, activities.get(1).activityType());
        assertEquals(PresenceActivityAction.ENDED, activities.get(1).action());
    }

    /**
     * 装配失败：收起"准备中"，只留一条观察用的模块状态。
     *
     * <p>不发映迹的 {@code ERROR}——那句文案讲的是密钥/服务地址/向量模型，辅星装配失败与它们无关，
     * 而玩家对此也无从操作。</p>
     */
    @Test
    void aFailedPrepareClosesTheLoadingNoticeAndReportsOnlyAModuleStatus() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run);
        List<PresenceActivityPayload> activities = captureActivities(runtime);
        // 存储配置缺失 → 装配在构造布局时抛 IllegalArgumentException，正好是一次"没起来"。
        AXModule module = new AXModule(
                new FakeGameEnvironment(),
                null,
                runtime,
                null,
                null,
                () -> "天枢",
                AXRuntimePolicy.defaults(),
                AuxiliumOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );
        ModuleRuntimeContext context = new ModuleRuntimeContext(
                runtime,
                new ModuleServiceRegistry(),
                new TriggerRegistry(),
                new ModuleRuntimeState()
        );

        assertThrows(IllegalArgumentException.class, () -> module.prepare(context));

        await(() -> activities.size() >= 2, "准备中与收起两条活动");
        assertEquals(PresenceActivityType.LOADING, activities.get(0).activityType());
        assertEquals(PresenceActivityAction.STARTED, activities.get(0).action());
        assertEquals(PresenceActivityAction.ENDED, activities.get(1).action());
        // 状态走的是另一个话题，落地时刻与活动无关：单独等它。
        await(
                () -> runtime.moduleStatusCache().latest(AXModule.MODULE_ID, ModuleStatuses.TYPE_LIFECYCLE).isPresent(),
                "装配失败的模块状态"
        );
        Optional<ModuleStatus> failed = runtime.moduleStatusCache().latest(AXModule.MODULE_ID, ModuleStatuses.TYPE_LIFECYCLE);
        assertTrue(failed.isPresent(), "装配失败要留下一条模块状态");
        assertEquals("tianshu.runtime.module.ax.startup_failed", failed.get().messageKey());
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        // Presence owns its topics now; the kernel no longer registers them.
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
        List<PresenceActivityPayload> activities = Collections.synchronizedList(new ArrayList<>());
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.activity-test",
                        List.of(),
                        defaults.threadPolicy(),
                        defaults.cancellationScope(),
                        defaults.failurePolicy(),
                        defaults.deliveryPolicy(),
                        defaults.cancellable(),
                        defaults.supportsStreaming(),
                        defaults.maxConcurrency(),
                        defaults.queueCapacity()
                ),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.ACTIVITY,
                        PresencePayloadTypes.ACTIVITY,
                        PresenceActivityPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> activities.add((PresenceActivityPayload) envelope.payload())
        );
        return activities;
    }

    /** 投递在别的线程上发生：等它落地，不拿"立刻"当断言。 */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("没有等到" + what);
    }
}
