package com.starstackadam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sceglie quali file entrare in preview o nello stack.
 * I {@code *-output.jpg} di Vespera sono già medie progressive: la live ne
 * mostra solo l'ultimo; lo share usa le pose singole.
 */
final class FrameSelect {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private FrameSelect() {}

    static boolean isDerivedName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("stack") || n.contains("master") || n.contains("final")
                || n.contains("preview") || n.contains("output");
    }

    /** Ultimo output/stack Vespera; se manca, ultima posa singola. */
    static List<String> forPreview(List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        List<String> lights = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        split(paths, lights, derived);
        if (!derived.isEmpty()) {
            derived.sort(NATURAL);
            return List.of(derived.get(derived.size() - 1));
        }
        if (lights.isEmpty()) return List.of();
        lights.sort(NATURAL);
        return List.of(lights.get(lights.size() - 1));
    }

    /** Pose singole per lo stack; se ci sono solo output, resta l'ultimo. */
    static List<String> forStack(List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        List<String> lights = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        split(paths, lights, derived);
        if (!lights.isEmpty()) {
            lights.sort(NATURAL);
            return lights;
        }
        if (derived.isEmpty()) return List.of();
        derived.sort(NATURAL);
        return List.of(derived.get(derived.size() - 1));
    }

    /** Ultimo path derived, oppure vuoto. */
    static String lastDerived(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        List<String> derived = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            if (isDerivedName(baseName(path))) derived.add(path);
        }
        if (derived.isEmpty()) return "";
        derived.sort(NATURAL);
        return derived.get(derived.size() - 1);
    }

    static String note(List<String> original, List<String> selected) {
        if (original == null || selected == null) return "";
        if (selected.size() == original.size()) return "";
        if (selected.size() == 1 && original.size() > 1) {
            String name = baseName(selected.get(0));
            if (isDerivedName(name)) {
                return "Solo l'ultimo output Vespera (già stackato; le medie progressive non si rimediano)";
            }
            return "Anteprima sull'ultima posa";
        }
        if (selected.size() < original.size()) {
            return "Pose singole: " + selected.size() + " (esclusi output/stack)";
        }
        return "";
    }

    private static void split(List<String> paths, List<String> lights, List<String> derived) {
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            if (isDerivedName(baseName(path))) derived.add(path);
            else lights.add(path);
        }
    }

    private static final Comparator<String> NATURAL = (a, b) -> {
        String na = baseName(a);
        String nb = baseName(b);
        Matcher ma = NUMBER.matcher(na);
        Matcher mb = NUMBER.matcher(nb);
        int ia = 0;
        int ib = 0;
        while (ma.find(ia) && mb.find(ib)) {
            int before = na.substring(ia, ma.start()).compareToIgnoreCase(nb.substring(ib, mb.start()));
            if (before != 0) return before;
            try {
                long da = Long.parseLong(ma.group(1));
                long db = Long.parseLong(mb.group(1));
                if (da != db) return Long.compare(da, db);
            } catch (NumberFormatException ignored) {
                int cmp = ma.group(1).compareTo(mb.group(1));
                if (cmp != 0) return cmp;
            }
            ia = ma.end();
            ib = mb.end();
        }
        return na.substring(ia).compareToIgnoreCase(nb.substring(ib));
    };

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
