package com.rheinmetal.tianshu.dubhe.agent.skill;

/**
 * Who may see a skill.
 *
 * <p>技能永远只属于本模块：代理读它、调用它，**任何技能都不注册成舟楫能力**（G47 修订：技能不对外，功能对外）。
 * 对外的只有具体功能：`dubhe:research` / `dubhe:relay`。因此这里只有一个可见性。</p>
 */
public enum DubheSkillVisibility {

    INTERNAL
}