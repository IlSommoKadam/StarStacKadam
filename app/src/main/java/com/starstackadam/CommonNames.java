package com.starstackadam;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Nome comune associato alla sigla di catalogo.
 * Le voci Messier seguono il nome comune italiano di
 * <a href="https://www.grag.org/catalogo-messier-con-il-cercatore-telrad/">GrAG</a>
 * e dell'<a href="http://astrolink.mclink.it/messier/data2.html">indice astrolink</a>.
 * NGC, IC e Sh2 sono i nomi comuni usati in italiano per i bersagli più fotografati.
 */
public final class CommonNames {
    /** Chiave normalizzata ({@code m31}, {@code ngc7000}, {@code vega}) → nome comune. */
    public static final Map<String, String> COMMON_NAME;

    private static final Set<String> STAR_KEYS;

    static {
        Map<String, String> names = new HashMap<>();
        Set<String> stars = new HashSet<>();

        add(names, stars, false, "Nebulosa del Granchio", "M 1", "NGC 1952");
        add(names, stars, false, "Ammasso della Farfalla", "M 6", "NGC 6405");
        add(names, stars, false, "Ammasso di Tolomeo", "M 7", "NGC 6475");
        add(names, stars, false, "Nebulosa Laguna", "M 8", "NGC 6523", "Laguna");
        add(names, stars, false, "Ammasso dell'Anitra Selvatica", "M 11", "NGC 6705");
        add(names, stars, false, "Grande Ammasso di Ercole", "M 13", "NGC 6205");
        add(names, stars, false, "Nebulosa Aquila", "M 16", "NGC 6611");
        add(names, stars, false, "Nebulosa Omega", "M 17", "NGC 6618");
        add(names, stars, false, "Nebulosa Trifida", "M 20", "NGC 6514");
        add(names, stars, false, "Piccola Nube del Sagittario", "M 24");
        add(names, stars, false, "Nebulosa Manubrio", "M 27", "NGC 6853");
        add(names, stars, false, "Galassia di Andromeda", "M 31", "NGC 224", "Andromeda");
        add(names, stars, false, "Galassia del Triangolo", "M 33", "NGC 598");
        add(names, stars, false, "Ammasso Piccolo Alveare", "M 41", "NGC 2287");
        add(names, stars, false, "Nebulosa di Orione", "M 42", "NGC 1976", "Orione");
        add(names, stars, false, "Nebulosa di De Mairan", "M 43", "NGC 1982");
        add(names, stars, false, "Presepe", "M 44", "NGC 2632");
        add(names, stars, false, "Pleiadi", "M 45");
        add(names, stars, false, "Galassia Vortice", "M 51", "NGC 5194");
        add(names, stars, false, "Galassia Girasole", "M 63", "NGC 5055");
        add(names, stars, false, "Galassia Occhio Nero", "M 64", "NGC 4826");
        add(names, stars, false, "Nebulosa Piccola Campana Muta", "M 76", "NGC 650");
        add(names, stars, false, "Galassia Fantasma", "M 74", "NGC 628");
        add(names, stars, false, "Galassia di Bode", "M 81", "NGC 3031");
        add(names, stars, false, "Galassia Sigaro", "M 82", "NGC 3034");
        add(names, stars, false, "Galassia Girandola del Sud", "M 83", "NGC 5236");
        add(names, stars, false, "Galassia Virgo A", "M 87", "NGC 4486");
        add(names, stars, false, "Nebulosa Gufo", "M 97", "NGC 3587");
        add(names, stars, false, "Galassia Girandola", "M 101", "NGC 5457");
        add(names, stars, false, "Galassia Fuso", "M 102", "NGC 5866");
        add(names, stars, false, "Galassia Sombrero", "M 104", "NGC 4594");

        add(names, stars, false, "Galassia dello Scultore", "NGC 253");
        add(names, stars, false, "Galassia dell'Ago", "NGC 4565");
        add(names, stars, false, "Galassia Balena", "NGC 4631");
        add(names, stars, false, "Nebulosa Pacman", "NGC 281");
        add(names, stars, false, "Nebulosa California", "NGC 1499");
        add(names, stars, false, "Nebulosa Rosetta", "NGC 2237");
        add(names, stars, false, "Nebulosa Cono", "NGC 2264");
        add(names, stars, false, "Nebulosa Crescente", "NGC 6888");
        add(names, stars, false, "Nebulosa Velo", "NGC 6960", "NGC 6992");
        add(names, stars, false, "Nebulosa Nord America", "NGC 7000");
        add(names, stars, false, "Nebulosa Iris", "NGC 7023");
        add(names, stars, false, "Nebulosa Elica", "NGC 7293");
        add(names, stars, false, "Nebulosa Testa di Cavallo", "IC 434");
        add(names, stars, false, "Nebulosa Proboscide d'Elefante", "IC 1396");
        add(names, stars, false, "Nebulosa Cuore", "IC 1805");
        add(names, stars, false, "Nebulosa Anima", "IC 1848");
        add(names, stars, false, "Nebulosa Grotta", "Sh2-155");

        add(names, stars, true, "Vega", "Vega");
        add(names, stars, true, "Sirio", "Sirio", "Sirius");
        add(names, stars, true, "Betelgeuse", "Betelgeuse");
        add(names, stars, true, "Rigel", "Rigel");
        add(names, stars, true, "Aldebaran", "Aldebaran");
        add(names, stars, true, "Capella", "Capella");
        add(names, stars, true, "Procione", "Procione", "Procyon");
        add(names, stars, true, "Arturo", "Arturo", "Arcturus");
        add(names, stars, true, "Spica", "Spica");
        add(names, stars, true, "Antares", "Antares");
        add(names, stars, true, "Stella Polare", "Polare", "Stella Polare");
        add(names, stars, true, "Altair", "Altair");
        add(names, stars, true, "Deneb", "Deneb");
        add(names, stars, true, "Polluce", "Polluce", "Pollux");
        add(names, stars, true, "Castore", "Castore", "Castor");

        COMMON_NAME = Collections.unmodifiableMap(names);
        STAR_KEYS = Collections.unmodifiableSet(stars);
    }

    private CommonNames() {}

    /** Nome comune della sigla registrata in cartella. Vuoto se non è in mappa. */
    public static String of(String registered) {
        if (registered == null || registered.isBlank()) return "";
        String cat = ObjectCatalog.catalogKey(registered);
        if (!cat.isEmpty()) {
            String hit = COMMON_NAME.get(cat);
            if (hit != null) return hit;
        }
        String folded = ObjectCatalog.key(registered);
        String hit = COMMON_NAME.get(folded);
        return hit == null ? "" : hit;
    }

    /** Tipo ricavato dal nome comune, oppure null se la sigla non è in mappa. */
    public static SkyObject.Kind kindFor(String registered) {
        if (registered == null || registered.isBlank()) return null;
        String cat = ObjectCatalog.catalogKey(registered);
        String folded = ObjectCatalog.key(registered);
        if (STAR_KEYS.contains(cat) || STAR_KEYS.contains(folded)) return SkyObject.Kind.STAR;
        if (of(registered).isEmpty()) return null;
        return ObjectCatalog.kindFromText(of(registered));
    }

    private static void add(
            Map<String, String> names,
            Set<String> stars,
            boolean star,
            String common,
            String... aliases) {
        for (String alias : aliases) {
            String cat = ObjectCatalog.catalogKey(alias);
            if (!cat.isEmpty()) {
                names.put(cat, common);
                if (star) stars.add(cat);
            }
            String folded = ObjectCatalog.key(alias);
            if (!folded.isEmpty()) {
                names.put(folded, common);
                if (star) stars.add(folded);
            }
        }
    }
}
