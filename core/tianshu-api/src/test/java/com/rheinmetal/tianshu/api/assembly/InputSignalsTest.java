package com.rheinmetal.tianshu.api.assembly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bus inverts the old direction: the platform publishes, modules subscribe, and publishing to nobody is
 * a normal case rather than an error.
 *
 * <p>The two properties that matter operationally are both here: a closed subscription stops receiving, and
 * one signal never reaches a subscriber of another.</p>
 */
class InputSignalsTest {

    @AfterEach
    void reset() {
        InputSignals.clear();
    }

    @Test
    void aSubscriberReceivesTheSignalItAskedFor() {
        List<String> received = new ArrayList<>();
        InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, received::add);

        InputSignals.publish(InputSignals.TEXT_SUBMITTED, "你好");

        assertEquals(List.of("你好"), received);
    }

    @Test
    void publishingWithNoSubscriberIsSilent() {
        InputSignals.publish(InputSignals.PUSH_TO_TALK_DOWN, "");
    }

    @Test
    void aSubscriptionOnlyReceivesItsOwnSignal() {
        List<String> received = new ArrayList<>();
        InputSignals.subscribe(InputSignals.PUSH_TO_TALK_DOWN, received::add);

        InputSignals.publish(InputSignals.TEXT_SUBMITTED, "not for me");

        assertTrue(received.isEmpty());
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        List<String> received = new ArrayList<>();
        InputSignals.Subscription subscription =
                InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, received::add);

        subscription.close();
        InputSignals.publish(InputSignals.TEXT_SUBMITTED, "ignored");

        assertTrue(received.isEmpty(), "a stopped module must not react to input");
    }

    @Test
    void theBusReportsWhetherAnyoneIsListening() {
        assertTrue(!InputSignals.hasSubscribers(InputSignals.TEXT_SUBMITTED),
                "nothing is subscribed before a module attaches");

        InputSignals.Subscription subscription =
                InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, text -> {
                });

        assertTrue(InputSignals.hasSubscribers(InputSignals.TEXT_SUBMITTED),
                "the platform may open the input bar only when someone will answer it");

        subscription.close();

        assertFalse(InputSignals.hasSubscribers(InputSignals.TEXT_SUBMITTED));
    }

    @Test
    void aSignalWithoutTextArrivesAsAnEmptyString() {
        List<String> received = new ArrayList<>();
        InputSignals.subscribe(InputSignals.PUSH_TO_TALK_UP, received::add);

        InputSignals.publish(InputSignals.PUSH_TO_TALK_UP, null);

        assertEquals(List.of(""), received);
    }
}
