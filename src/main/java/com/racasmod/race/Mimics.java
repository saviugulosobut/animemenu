package com.racasmod.race;

import java.util.ArrayList;
import java.util.List;

/**
 * Sistema de "copiar poder" (era do Herobrine). Nenhuma raça usa mais, então só sobra
 * este atalho para o código antigo continuar compilando.
 */
public final class Mimics {
    public static Race get(String id) { return Races.get(id); }

    public static List<Race> all() {
        List<Race> out = new ArrayList<>();
        for (Race r : Races.all()) if (!r.mimic) out.add(r);
        return out;
    }

    private Mimics() {}
}
