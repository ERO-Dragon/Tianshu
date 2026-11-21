package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateRegistry;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePendingWorkPromptContributorTest {

    private static final DubheScope SCOPE =
            new DubheScope("player-1", "overworld", "玩家", DubheScopeKind.LOCAL_WORLD, true);

    @Test
    void rendersEachInFlightQueryWithItsWaitedSeconds() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.open(SCOPE, "session-1", "暮色森林怎么进", 1_000L);
        registry.open(SCOPE, "session-1", "下界合金怎么造", 2_000L);

        String section = contribute(registry, 7_000L, "查到了吗");

        assertTrue(section.contains("<pending_work>"), section);
        assertTrue(section.contains("暮色森林怎么进（已等 6 秒）"), section);
        assertTrue(section.contains("下界合金怎么造（已等 5 秒）"), section);
    }

    @Test
    void contributesNothingWhenNoDelegationIsOpen() {
        String section = contribute(new DubheDelegateRegistry(), 7_000L, "查到了吗");

        assertFalse(section.contains("<pending_work>"), section);
    }

    @Test
    void capsTheBlockAtThreeLinesAndSaysHowManyAreHidden() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        for (int index = 0; index < 5; index++) {
            registry.open(SCOPE, "session-1", "问题" + index, 1_000L + index);
        }

        String section = contribute(registry, 9_000L, "还在查吗");

        assertTrue(section.contains("另有 2 件在办"), section);
        assertFalse(section.contains("问题3"), section);
    }

    @Test
    void staysSilentWhenTheInputIsAboutSomethingElse() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.open(SCOPE, "session-1", "暮色森林怎么进", 1_000L);

        String section = contribute(registry, 7_000L, "帮我看看背包");

        assertFalse(section.contains("<pending_work>"), section);
    }

    @Test
    void aContinuationOfThePendingQuestionAlsoQualifies() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.open(SCOPE, "session-1", "暮色森林怎么进", 1_000L);

        String section = contribute(registry, 7_000L, "暮色森林那个查得怎么样了");

        assertTrue(section.contains("<pending_work>"), section);
    }

    private static String contribute(DubheDelegateRegistry registry, long nowMillis, String userText) {
        DubhePendingWorkPromptContributor contributor =
                new DubhePendingWorkPromptContributor(registry, () -> nowMillis);
        DubhePromptBuildContext context = new DubhePromptBuildContext(
                new DubheRequest("DUBHE.request", userText, ""),
                new DubheContextSnapshot(SCOPE, null, null, List.of(), ""),
                null,
                DubhePromptLanguage.ZH_CN,
                null);
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();
        contributor.contribute(context, builder);
        return builder.build().messages().stream().map(message -> message.content()).collect(Collectors.joining("\n"));
    }
}