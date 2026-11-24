package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * Who may see a skill.
 *
 * <p>技能永远只属于本模块：代理读它、调用它，**任何技能都不注册成舟楫能力**。天枢不对外发布任何能力
 * （G66）：它是纯消费方。因此这里只有一个可见性。</p>
 */
public enum DubheSkillVisibility {

    INTERNAL
}