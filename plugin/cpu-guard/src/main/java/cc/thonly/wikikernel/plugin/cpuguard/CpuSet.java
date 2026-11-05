package cc.thonly.wikikernel.plugin.cpuguard;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Linux CPU 列表（例如 0-3,6）：仅接受有界数字范围，不交给 shell 解释。 */
final class CpuSet {
    private CpuSet() {}

    static List<Integer> parse(String text) {
        TreeSet<Integer> result = new TreeSet<>();
        if (text == null || text.isBlank()) return List.of();
        for (String token : text.trim().split(",", -1)) {
            String[] range = token.trim().split("-", -1);
            if (range.length > 2 || !range[0].matches("[0-9]{1,5}")
                    || (range.length == 2 && !range[1].matches("[0-9]{1,5}"))) {
                throw new IllegalArgumentException("Invalid CPU list");
            }
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            if (first > last || last >= 4096) throw new IllegalArgumentException("CPU range out of bounds");
            for (int cpu = first; cpu <= last; cpu++) result.add(cpu);
        }
        return List.copyOf(result);
    }

    static String first(List<Integer> available, int count) {
        return available.stream().limit(Math.max(0, count)).map(String::valueOf).collect(Collectors.joining(","));
    }
}
