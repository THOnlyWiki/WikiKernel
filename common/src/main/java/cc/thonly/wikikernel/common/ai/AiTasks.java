package cc.thonly.wikikernel.common.ai;

import cc.thonly.wikikernel.common.extension.Viewer;

/** Named site tasks use site credentials, never an editor's personal credentials. */
public interface AiTasks {
    String complete(String scene, Viewer actor, String system, String input);
    boolean configured(String scene);
    default boolean allowed(String scene, Viewer actor) { return actor != null && actor.loggedIn(); }
    String model(String scene);
}
