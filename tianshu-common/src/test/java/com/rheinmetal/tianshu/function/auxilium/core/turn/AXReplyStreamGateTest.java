package com.rheinmetal.tianshu.function.auxilium.core.turn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式闸（设计 §4.1）：模型分片到达时，哪些字符现在就能念。
 *
 * <p>闸门存在的理由只有一个——标签可能被切成任意两半（{@code <del} / {@code egate>}）。所以这里的用例都是
 * "给定分片序列，断言最终发声内容"，而不是断言内部状态：压住与放行的差别只有玩家能不能听见。</p>
 */
class AXReplyStreamGateTest {

    @Test
    void plainTextGoesOutChunkByChunk() {
        AXReplyStreamGate gate = new AXReplyStreamGate();

        assertEquals("铁砧", gate.accept("铁砧"));
        assertEquals("可以", gate.accept("可以"));
        assertEquals("", gate.flush());
        assertEquals("铁砧可以", gate.emitted());
    }

    @Test
    void anOpenTagSplitAcrossChunksIsNeverSpoken() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        List<String> spoken = feed(gate, "这个问题让我去查一下。<del", "egate>检索如何进入暮色森林</del", "egate>");

        assertEquals(List.of("这个问题让我去查一下。"), spoken, "标签与标签内容一个字都不能出去");
        assertEquals("", gate.flush());
    }

    @Test
    void aSuspiciousPrefixThatTurnsOutToBeOrdinaryTextIsReleasedUnchanged() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        List<String> spoken = feed(gate, "按 <del", "ete 键");

        assertEquals("按 <delete 键", String.join("", spoken), "压住的字符必须原样放出来，不能被吞掉");
        assertEquals("", gate.flush());
    }

    @Test
    void aCloseTagSplitAcrossChunksIsNotSpokenAndTheTextAfterItIs() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        List<String> spoken = feed(gate, "让我查一下。<delegate>如何进入暮色森林</del", "egate>稍等。");

        assertEquals("让我查一下。稍等。", String.join("", spoken), "闭标签跨片也要吞掉，后面的正文照念");
        assertEquals("", gate.flush());
    }

    @Test
    void anUnclosedTagReleasesItsBodyWhenTheStreamEnds() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        List<String> spoken = feed(gate, "让我查一下。<del", "egate>如何进入暮色森林");

        assertEquals("让我查一下。", String.join("", spoken), "只有开标签时，先只放出标签之前的正文");
        assertEquals("如何进入暮色森林", gate.flush(), "流结束时按规则 4 把标签内容当普通文本交回");
    }

    @Test
    void aTrailingTagPrefixAtTheEndIsReleasedAsPlainText() {
        AXReplyStreamGate gate = new AXReplyStreamGate();

        assertEquals("这句话没说完", gate.accept("这句话没说完<del"), "只有字面文本放出，<del 被压住");
        assertEquals("<del", gate.flush(), "流结束才判明那只是普通字符");
    }

    @Test
    void aSecondTagBlockIsSwallowedAsWell() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        List<String> spoken = feed(gate, "前段<delegate>第一个</delegate>中段", "<delegate>第二个</delegate>后段");

        assertEquals("前段中段后段", String.join("", spoken));
    }

    @Test
    void theGateAndTheParserAgreeOnWhatIsSpoken() {
        for (String raw : new String[]{
                "",
                "   ",
                "铁砧可以修复工具。",
                "让我查一下。<delegate>如何进入暮色森林</delegate>",
                "前段<delegate>问题</delegate>后段",
                "前段<delegate></delegate>后段",
                "前段<delegate>  </delegate>后段",
                "前段<delegate>没有闭合",
                "前段</delegate>后段",
                "多标签<delegate>a</delegate>中间<delegate>b</delegate>尾巴",
                "<delegate>只有标签</delegate>"
        }) {
            AXReplyStreamGate gate = new AXReplyStreamGate();
            gate.accept(raw);
            gate.flush();

            assertEquals(AXReplyContract.parse(raw).spoken(), gate.emitted().strip(),
                    "解析器与流式闸对同一段输出必须给出同一个答句：" + raw);
        }
    }

    @Test
    void everySingleSplitPointProducesTheSameSpeech() {
        String raw = "让我查一下。<delegate>如何进入暮色森林</delegate>稍等。";
        String expected = AXReplyContract.parse(raw).spoken();

        for (int split = 0; split <= raw.length(); split++) {
            AXReplyStreamGate gate = new AXReplyStreamGate();
            List<String> spoken = feed(gate, raw.substring(0, split), raw.substring(split));

            assertEquals(expected, String.join("", spoken), "在第 " + split + " 个字符处切开时发声内容变了");
        }
    }

    private static List<String> feed(AXReplyStreamGate gate, String... chunks) {
        List<String> spoken = new ArrayList<>();
        for (String chunk : chunks) {
            String speakable = gate.accept(chunk);
            if (!speakable.isEmpty()) {
                spoken.add(speakable);
            }
        }
        return spoken;
    }

    @Test
    void nothingIsEverSpokenFromInsideAPairedTag() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        StringBuilder everything = new StringBuilder();

        for (String chunk : new String[]{"助手<deleg", "ate>机密问题</dele", "gate>结束"}) {
            everything.append(gate.accept(chunk));
        }
        everything.append(gate.flush());

        assertTrue(!everything.toString().contains("delegate"), "标记不得出现：" + everything);
        assertTrue(!everything.toString().contains("机密问题"), "标签内容不得出现：" + everything);
        assertEquals("助手结束", everything.toString());
    }
}
