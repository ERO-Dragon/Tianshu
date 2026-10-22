package com.rheinmetal.tianshu.function.auxilium.core.delegate;

/**
 * 委派投递的出口：语音切片从这里取"该播报的句柄"（设计 §6.2）。
 *
 * <p>本片只把句柄交出去（默认实现写日志），<b>不接 TTS</b>；接上以后 {@code mode} 决定立即出声、排队或
 * 只追加。投递本身（① 登记 + ② {@code <event>} 追加）与这个出口无关，永远先发生（§6.1）。
 */
@FunctionalInterface
public interface AXDelegateDeliverySink {

    AXDelegateDeliverySink NOOP = delivery -> {
    };

    void publish(AXDelegateDelivery delivery);
}
