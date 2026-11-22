package com.racasmod.race;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro das raças. Só existem personagens de anime: veja AnimeRaces.java.
 * Para criar um novo, copie um bloco de lá e mude os valores.
 */
public final class Races {
    private static final Map<String, Race> MAP = new LinkedHashMap<>();

    private static void add(Race r) { MAP.put(r.id, r); }

    public static Race get(String id) { ensure(); return id == null ? null : MAP.get(id); }
    public static List<Race> all() { ensure(); return new ArrayList<>(MAP.values()); }
    public static List<String> ids() { ensure(); return new ArrayList<>(MAP.keySet()); }

    private static void ensure() {
        if (!MAP.isEmpty()) return;
        AnimeRaces.register(Races::add);
    }

    private Races() {}
}
