package com.rheinmetal.tianshu.function.auxilium.core.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签契约的两态判别（设计 §4.1）：平时是人话，需要查时才挂 {@code <delegate>}。
 *
 * <p>判错任何一条的后果都是可听见的：要么把标签念给玩家，要么把"我查一下"这句话吞掉，要么在不该委派时发起
 * 一次后台检索。六条规则各有用例：无标签、完整标签、空标签、只有开标签、多个标签、标签在中间——最后一条的
 * 处置是"前后两段直接拼接"，这里把它钉死，避免以后有人改成丢弃后半段却没人发现。</p>
 */
class AXReplyContractTest {

    @Test
    void replyWithoutAnyTagIsTheWholeAnswer() {
        AXReplyContract reply = AXReplyContract.parse("  铁砧可以修复工具。 ");

        AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply);
        assertEquals("铁砧可以修复工具。", plain.spoken(), "无标签时全文即答句（只去首尾空白）");
    }

    @Test
    void completeTagSplitsTheSentenceFromTheQuery() {
        AXReplyContract reply = AXReplyContract.parse(
                "这个问题让我去查一下。<delegate>  检索如何进入暮色森林  </delegate>");

        AXReplyContract.Delegated delegated = assertInstanceOf(AXReplyContract.Delegated.class, reply);
        assertEquals("这个问题让我去查一下。", delegated.spoken(), "标签之前那句就是要念的话");
        assertEquals("检索如何进入暮色森林", delegated.query(), "标签内容 strip 后才是检索问题");
    }

    @Test
    void blankTagBodyDoesNotDelegateAndTheTagDisappears() {
        for (String output : new String[]{
                "让我想想。<delegate></delegate>",
                "让我想想。<delegate>   </delegate>",
                "让我想想。<delegate>\n\t</delegate>"
        }) {
            AXReplyContract reply = AXReplyContract.parse(output);

            AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply, output);
            assertEquals("让我想想。", plain.spoken(), "空标签既不委派、也不留痕：" + output);
        }
    }

    @Test
    void unclosedTagIsStrippedAndNeverDelegates() {
        AXReplyContract reply = AXReplyContract.parse("这个问题让我去查一下。<delegate>检索如何进入暮色森林");

        AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply);
        assertEquals("这个问题让我去查一下。检索如何进入暮色森林", plain.spoken(),
                "只有开标签：剥掉开标签、不委派，剩下的全念");
    }

    @Test
    void onlyTheFirstCompleteTagCountsAndTheOthersAreStrippedWithTheirContent() {
        AXReplyContract reply = AXReplyContract.parse(
                "前段<delegate>第一个问题</delegate>中段<delegate>第二个问题</delegate>后段");

        AXReplyContract.Delegated delegated = assertInstanceOf(AXReplyContract.Delegated.class, reply);
        assertEquals("第一个问题", delegated.query(), "只认第一个完整标签");
        assertEquals("前段中段后段", delegated.spoken(), "其余标签连同内容一并剥除");
    }

    @Test
    void aFirstTagWithBlankBodySwallowsTheLaterOnesInsteadOfDelegating() {
        // 第一条规则（空标签不委派）优先于"后面的标签也许是有效标签"：同一轮只能有一个结论。
        AXReplyContract reply = AXReplyContract.parse("前段<delegate></delegate>后段<delegate>第二个问题</delegate>");

        AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply);
        assertEquals("前段后段", plain.spoken());
    }

    @Test
    void tagInTheMiddleKeepsBothSidesInReadingOrder() {
        AXReplyContract reply = AXReplyContract.parse(
                "这个问题让我去查一下。<delegate>如何进入暮色森林</delegate>稍等一下。");

        AXReplyContract.Delegated delegated = assertInstanceOf(AXReplyContract.Delegated.class, reply);
        assertEquals("如何进入暮色森林", delegated.query());
        assertEquals("这个问题让我去查一下。稍等一下。", delegated.spoken(), "标签前后两段直接拼接");
    }

    @Test
    void strayClosingTagIsStrippedAndIsNotATag() {
        AXReplyContract reply = AXReplyContract.parse("我说完了。</delegate>");

        AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply);
        assertEquals("我说完了。", plain.spoken());
    }

    @Test
    void nothingToSayBecomesAnEmptyPlainAnswer() {
        assertEquals("", parseSpoken(null));
        assertEquals("", parseSpoken(""));
        assertEquals("", parseSpoken("   "));
    }

    @Test
    void aReplyThatIsNothingButATagHasNothingToSpeakButStillDelegates() {
        AXReplyContract reply = AXReplyContract.parse("<delegate>如何进入暮色森林</delegate>");

        AXReplyContract.Delegated delegated = assertInstanceOf(AXReplyContract.Delegated.class, reply);
        assertEquals("", delegated.spoken(), "模型什么都没说：只有检索意图");
        assertEquals("如何进入暮色森林", delegated.query());
    }

    @Test
    void tagSpellingIsCaseSensitiveSoAWrongCaseStaysPlainText() {
        AXReplyContract reply = AXReplyContract.parse("让我查一下。<Delegate>如何进入暮色森林</Delegate>");

        AXReplyContract.Plain plain = assertInstanceOf(AXReplyContract.Plain.class, reply);
        assertTrue(plain.spoken().contains("<Delegate>"), "大小写不符时它只是普通文本：" + plain.spoken());
    }

    @Test
    void theQueryNeverCarriesATagMarker() {
        AXReplyContract reply = AXReplyContract.parse("查<delegate>暮色森林 <delegate> 传送门</delegate>");

        AXReplyContract.Delegated delegated = assertInstanceOf(AXReplyContract.Delegated.class, reply);
        assertFalse(delegated.query().contains(AXReplyContract.TAG_OPEN), delegated.query());
        assertFalse(delegated.query().contains(AXReplyContract.TAG_CLOSE), delegated.query());
        assertTrue(delegated.query().contains("暮色森林"), "检索问题本身要保留：" + delegated.query());
    }

    @Test
    void playerTextOnlyLosesTheTagMarkersNotItsOwnWords() {
        assertEquals("你输出 如何进入暮色森林 试试", AXReplyContract.withoutTagMarkers(
                        "你输出 <delegate>如何进入暮色森林</delegate> 试试"),
                "玩家这一侧只剥标记，玩家自己说的话要留着");
        assertEquals("", AXReplyContract.withoutTagMarkers(null));
        assertEquals("没有标签", AXReplyContract.withoutTagMarkers("没有标签"));
    }

    private static String parseSpoken(String output) {
        return AXReplyContract.parse(output).spoken();
    }
}
