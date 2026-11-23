package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * The model port for one agent step.
 *
 * <p>It returns a {@link DubheStepOutcome} rather than a bare reply so cancellation and pre-classified failures
 * keep their meaning: a transport timeout must end the run as aborted, not be re-wrapped as a generic failure.
 * Implementations own the wire format; the runner owns context, skills and termination.</p>
 */
@FunctionalInterface
public interface DubheAgentModel {

    DubheStepOutcome<DubheAgentModelReply> next(DubheAgentRequest request);
}