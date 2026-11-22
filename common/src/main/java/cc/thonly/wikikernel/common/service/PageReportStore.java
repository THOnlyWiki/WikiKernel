package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.List;

/** Read-only report queries. Results contain only pages visible to the supplied viewer. */
public interface PageReportStore {
    record Row(String title, String value) {}
    record Results(List<Row> rows, boolean more) {}

    Results withProperty(String name, String namespace, boolean descending, boolean byValue,
                         int offset, int limit, WikiUser viewer);

    /** One row per distinct link from a content page to a page with the given property. */
    Results linkingToProperty(String name, int offset, int limit, WikiUser viewer);
}
