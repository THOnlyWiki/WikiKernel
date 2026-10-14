package cc.thonly.wikikernel.common.plugin.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件依赖图：对「插件 id → 依赖的插件 id」做**稳定拓扑排序**，并识别缺失依赖与循环依赖。
 * <p>
 * core 用它决定两件事：
 * <ul>
 *     <li>启动顺序（{@code plugin.requires}，被依赖的插件先启动）；</li>
 *     <li>{@code <head>} 注入顺序（{@code plugin.clientRequires}，被依赖的插件前端资源先注入）。</li>
 * </ul>
 * 纯计算、无 Spring / 无 IO 依赖，便于单独测试；插件数量很小，算法按 O(V+E) 实现。
 * <p>
 * 术语：图的**节点**就是传入 map 的 key（= 已安装且未被白名单过滤掉的插件 id），
 * 依赖里出现的、不在节点集合中的 id 视为**缺失依赖**（未安装 / 被禁用 / 发现阶段就被跳过）。
 */
public final class PluginDependencyGraph {

    /**
     * 排序结果。
     *
     * @param order      可解析的插件 id，**依赖在前**（同层保持输入顺序，结果稳定）
     * @param cyclic     处于循环依赖上的插件 id（它们与其下游都无法排序）
     * @param blocked    依赖了循环依赖（自身不在环上）的插件 id
     * @param missing    插件 id → 图中不存在的依赖 id（缺失依赖）
     * @param cyclePaths 环上插件 id → 一条可读的环路（如 {@code [a, b, a]}），只用于日志
     */
    public record Result(List<String> order,
                         List<String> cyclic,
                         List<String> blocked,
                         Map<String, List<String>> missing,
                         Map<String, List<String>> cyclePaths) {

        /**
         * 全部节点（可解析的在前，环上与受环影响的按输入顺序追加）。
         * <p>
         * 客户端注入顺序用它：循环依赖只应打乱顺序、记 WARN，**不能让插件从页面上消失**。
         */
        public List<String> orderWithBrokenCycles() {
            List<String> all = new ArrayList<>(this.order);
            all.addAll(this.cyclic);
            all.addAll(this.blocked);
            return List.copyOf(all);
        }

        /** 某个节点无法排序的原因（可读文案）；能排序时返回 null。 */
        public String unresolvedReason(String id) {
            if (this.cyclic.contains(id)) {
                List<String> path = this.cyclePaths.get(id);
                return "循环依赖" + (path == null || path.isEmpty() ? "" : "（" + String.join(" → ", path) + "）");
            }
            if (this.blocked.contains(id)) {
                return "依赖了处于循环依赖中的插件";
            }
            return null;
        }
    }

    private PluginDependencyGraph() {
    }

    /**
     * 拓扑排序。
     *
     * @param dependencies 插件 id → 它依赖的插件 id 列表（声明顺序即输入顺序；key 集合 = 全部可选节点）
     */
    public static Result sort(Map<String, List<String>> dependencies) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(dependencies.keySet());

        for (String id : ids) {
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            List<String> absent = new ArrayList<>();
            for (String dependency : dependencies.getOrDefault(id, List.of())) {
                if (dependency.equals(id) || dependencies.containsKey(dependency)) {
                    resolved.add(dependency);
                } else {
                    absent.add(dependency);
                }
            }
            edges.put(id, List.copyOf(resolved));
            if (!absent.isEmpty()) {
                missing.put(id, List.copyOf(absent));
            }
            for (String dependency : resolved) {
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(id);
            }
        }

        // Kahn：入度为「还没启动的依赖数」；用 FIFO 队列保证「被依赖者先出」
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (String id : ids) {
            pending.put(id, edges.get(id).size());
        }
        Deque<String> queue = new ArrayDeque<>();
        for (String id : ids) {
            if (pending.get(id) == 0) {
                queue.add(id);
            }
        }
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                int left = pending.get(dependent) - 1;
                pending.put(dependent, left);
                if (left == 0) {
                    queue.add(dependent);
                }
            }
        }

        Set<String> remaining = new LinkedHashSet<>(ids);
        order.forEach(remaining::remove);
        Map<String, List<String>> cyclePaths = new LinkedHashMap<>();
        List<String> cyclic = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (String id : ids) {
            if (!remaining.contains(id)) {
                continue;
            }
            List<String> path = pathToSelf(id, edges, remaining);
            if (path != null) {
                cyclic.add(id);
                cyclePaths.put(id, path);
            } else {
                blocked.add(id);
            }
        }

        return new Result(List.copyOf(order), List.copyOf(cyclic), List.copyOf(blocked),
                Collections.unmodifiableMap(new LinkedHashMap<>(missing)),
                Collections.unmodifiableMap(new LinkedHashMap<>(cyclePaths)));
    }

    /** 在 {@code scope} 内找一条 {@code id → … → id} 的路径；找不到（不在环上）返回 null。 */
    private static List<String> pathToSelf(String id, Map<String, List<String>> edges, Set<String> scope) {
        Deque<String> path = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        if (walk(id, id, edges, scope, visited, path)) {
            return List.copyOf(path);
        }
        return null;
    }

    private static boolean walk(String current, String target, Map<String, List<String>> edges,
                                Set<String> scope, Set<String> visited, Deque<String> path) {
        path.addLast(current);
        for (String dependency : edges.getOrDefault(current, List.of())) {
            if (!scope.contains(dependency)) {
                continue;
            }
            if (dependency.equals(target)) {
                path.addLast(target);
                return true;
            }
            if (visited.add(dependency) && walk(dependency, target, edges, scope, visited, path)) {
                return true;
            }
        }
        path.removeLast();
        return false;
    }
}
