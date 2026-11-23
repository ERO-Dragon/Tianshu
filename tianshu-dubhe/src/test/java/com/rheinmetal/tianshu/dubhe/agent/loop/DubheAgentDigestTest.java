package com.rheinmetal.tianshu.dubhe.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheAgentDigestTest {

    @Test
    void theDigestRendersAsLanguageNeutralJson() {
        DubheAgentDigest digest = new DubheAgentDigest(
                "暮色森林怎么进",
                List.of("查了图鉴"),
                List.of("需要头骨"),
                List.of("不能用传送门"),
                List.of("钥匙在哪"),
                "查钥匙来源");

        String json = digest.toJson();

        assertTrue(json.contains("\"goal\":\"暮色森林怎么进\""), json);
        assertTrue(json.contains("\"ruledOut\""), json);
        assertTrue(json.contains("\"next\":\"查钥匙来源\""), json);
    }

    @Test
    void emptyListsAreStillRendered() {
        String json = new DubheAgentDigest("问题", null, null, null, null, null).toJson();

        assertEquals("{\"goal\":\"问题\",\"done\":[],\"facts\":[],\"ruledOut\":[],\"open\":[],\"next\":\"\"}", json);
    }
}