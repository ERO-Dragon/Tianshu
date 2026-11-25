package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Reads the world state around an actor: dimension, biome, time, weather, difficulty, light.
 *
 * <p>Positioned by the actor rather than by coordinates: what a caller wants is "the world where I am", and a caller
 * that has coordinates already has the position of whatever it is looking at. A position-less variant would push the
 * "which position do I mean" question onto every module.</p>
 */
public interface WorldInfoPort {

    CompletableFuture<WorldInfo> info(ActorRef actor);
}