package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/** Optional fragment rendered inside the existing web edit form when a revision check fails. */
public interface EditConflictUi extends Extension {
    String template();
}
