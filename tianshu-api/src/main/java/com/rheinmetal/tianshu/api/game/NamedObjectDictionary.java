package com.rheinmetal.tianshu.api.game;

import java.util.List;
import java.util.Map;

/**
 * The game's names for its own objects, as a plain snapshot.
 *
 * <p>The platform reads the registry and the language table; a module indexes that snapshot for text
 * repair. Keeping it a map means no module has to know how a platform exposes a name.</p>
 */
@FunctionalInterface
public interface NamedObjectDictionary {

    /** Key is an object id, value is every name it answers to in the current language. */
    Map<String, List<String>> snapshot();
}
