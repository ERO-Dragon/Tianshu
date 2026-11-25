package com.rheinmetal.tianshu.lab;

/**
 * What the bench may observe about <em>this module's</em> life in the host, and the one call it may make.
 *
 * <p>An acceptance case runs inside the same module it is judging, so "the host assembled me" is not something it
 * can read from a registry: the host's status table belongs to the host. What it can read is what happened to it —
 * how many times each phase ran, in what order — and what it can do is send one command through the protocol centre
 * the way any module does. Both are handed over at start, so a case never reaches into the platform to invent a
 * second path beside the real one.</p>
 *
 * <p>Everything here is about the contract a module consumes; nothing exposes the host's internals.</p>
 */
public interface LabHostTrace {

    /** How many times the host has called {@code register} on this module. The contract says once. */
    int registerCalls();

    /**
     * Whether {@code register} ran to its end.
     *
     * <p>Entered is not the same as finished: the host records the attempt before invoking the module, so a module whose
     * register throws is still "entered". Without this flag the bench would report a broken registration as a healthy
     * one — which is exactly how the bench's own echo capability went unregistered without anyone noticing.</p>
     */
    boolean registerCompleted();

    /** How many times the host has called {@code start}. The contract says once, and only after register. */
    int startCalls();

    /** Whether {@code register} had already run when {@code start} was entered. */
    boolean registerBeforeStart();

    /** The last text the bench's own echo capability received. */
    String lastEcho();

    /** How many commands the echo capability has received, across every run of the bench in this session. */
    int echoDeliveries();

    /** Sends one command to the bench's own echo capability; the route is the protocol centre, not a direct call. */
    void echo(String text);

    /** How many commands the shared capability reached this module with. */
    int sharedDeliveries();

    /** How many commands the shared capability reached the peer module with. */
    int sharedPeerDeliveries();

    /** Sends one command to the capability both this module and its peer declared. */
    void submitShared(String text);
}