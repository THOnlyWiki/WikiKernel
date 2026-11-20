package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.extension.ActivityProvider.Activity;
import java.util.List;

public interface ActivityDirectory {
    List<Activity> activities(int year);
    default List<String> regions() { return List.of(); }
}
