package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Author criteria shared by revision rows and operation logs. */
final class RecentChangeAuthors {
    enum Group { REGISTRATION, IDENTITY, AUTOMATION }

    enum Choice {
        ANONYMOUS(Group.REGISTRATION), REGISTERED(Group.REGISTRATION),
        NEWCOMER(Group.REGISTRATION), LEARNER(Group.REGISTRATION), EXPERIENCED(Group.REGISTRATION),
        OWN(Group.IDENTITY), OTHER(Group.IDENTITY),
        BOT(Group.AUTOMATION), HUMAN(Group.AUTOMATION);

        final Group group;
        Choice(Group group) { this.group = group; }
        String key() { return name().toLowerCase(Locale.ROOT); }
        boolean experience() { return this == NEWCOMER || this == LEARNER || this == EXPERIENCED; }
    }

    static Set<Choice> parse(String value, WikiUser viewer) {
        Set<String> keys = Set.copyOf(List.of((value == null ? "" : value).split(",")));
        Set<Choice> choices = EnumSet.noneOf(Choice.class);
        for (Choice choice : Choice.values()) {
            if (keys.contains(choice.key()) && (viewer != null || choice.group != Group.IDENTITY)) choices.add(choice);
        }
        return choices;
    }

    static Set<Choice> toggle(Set<Choice> choices, Choice choice) {
        Set<Choice> result = EnumSet.noneOf(Choice.class);
        result.addAll(choices);
        if (!result.add(choice)) result.remove(choice);
        return result;
    }

    static String encode(Set<Choice> choices) {
        return choices.stream().map(Choice::key).sorted().collect(Collectors.joining(","));
    }

    private final WikiUserMapper users;
    private final WikiRevisionMapper revisions;
    private final LocalDateTime now;
    private final WikiUser viewer;
    private final Set<Choice> selected;
    private final Set<Choice> marked;
    private final Map<Long, Choice> experience = new HashMap<>();

    RecentChangeAuthors(WikiUserMapper users, WikiRevisionMapper revisions, LocalDateTime now,
                        WikiUser viewer, Set<Choice> selected, Set<Choice> marked) {
        this.users = users;
        this.revisions = revisions;
        this.now = now;
        this.viewer = viewer;
        this.selected = selected;
        this.marked = marked;
    }

    void loadExperience(List<Long> actorIds) {
        if (selected.stream().noneMatch(Choice::experience) && marked.stream().noneMatch(Choice::experience)) return;
        List<Long> missing = actorIds.stream().filter(RecentChangeAuthors::registered)
                .distinct().filter(id -> !experience.containsKey(id)).toList();
        for (int start = 0; start < missing.size(); start += 400) {
            List<Long> batch = missing.subList(start, Math.min(start + 400, missing.size()));
            Map<Long, Long> counts = new HashMap<>();
            for (Map<String, Object> row : revisions.selectMaps(Wrappers.<WikiRevision>query()
                    .select("user_id", "COUNT(*) AS edit_count").in("user_id", batch)
                    .eq("deleted", false).groupBy("user_id"))) {
                DbRow data = new DbRow(row);
                counts.put(data.longValue("user_id", 0), data.longValue("edit_count", 0));
            }
            for (WikiUser user : users.selectList(Wrappers.<WikiUser>lambdaQuery()
                    .select(WikiUser::getId, WikiUser::getCreateAt).in(WikiUser::getId, batch))) {
                long edits = counts.getOrDefault(user.getId(), 0L);
                LocalDateTime created = user.getCreateAt();
                Choice level = Choice.LEARNER;
                if (edits < 10 || created != null && created.isAfter(now.minusDays(4))) level = Choice.NEWCOMER;
                else if (edits >= 500 && created != null && !created.isAfter(now.minusDays(30))) level = Choice.EXPERIENCED;
                experience.put(user.getId(), level);
            }
        }
    }

    boolean accepts(Long actor, boolean bot) {
        for (Group group : Group.values()) {
            boolean constrained = false;
            boolean matched = false;
            for (Choice choice : selected) {
                if (choice.group != group) continue;
                constrained = true;
                if (matches(choice, actor, bot)) matched = true;
            }
            if (constrained && !matched) return false;
        }
        return true;
    }

    boolean highlights(Long actor, boolean bot) {
        return marked.stream().anyMatch(choice -> matches(choice, actor, bot));
    }

    private boolean matches(Choice choice, Long actor, boolean bot) {
        return switch (choice) {
            case ANONYMOUS -> !registered(actor);
            case REGISTERED -> registered(actor);
            case NEWCOMER, LEARNER, EXPERIENCED -> registered(actor) && experience.get(actor) == choice;
            case OWN -> viewer != null && viewer.getId().equals(actor);
            case OTHER -> viewer != null && !viewer.getId().equals(actor);
            case BOT -> bot;
            case HUMAN -> !bot;
        };
    }

    static boolean registered(Long actor) {
        return actor != null && actor > 0;
    }
}
