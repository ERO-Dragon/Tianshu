package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * The model port for one agent step.
 *
 * <p>Implementations own the wire format (task prompt, skill directory rendering, tool-call parsing); the runner
 * owns context, skills and termination. That split is what lets the runner be tested without a model.</p>
 */
@FunctionalInterface
public interface DubheAgentModel {

    DubheAgentModelReply next(DubheAgentRequest request);
}