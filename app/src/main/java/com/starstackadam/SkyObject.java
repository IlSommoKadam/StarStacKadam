package com.starstackadam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Oggetto salvato sul disco: sigla di cartella, nome comune, date, pose. */
public final class SkyObject {
    public enum Kind {
        GALAXY, STAR, NEBULA, OTHER
    }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ITALY);

    public final Kind kind;
    /** Sigla come sta nel nome della cartella (M31, NGC 7000, …). */
    public final String registeredName;
    public final String scientificName;
    /** Nome comune, dalla mappa {@link CommonNames#COMMON_NAME}. */
    public final String publicName;
    public final String folderPath;
    /** Pose JPEG singole per lo stack condivisibile. */
    public final List<String> imagePaths;
    /** Ultimo {@code *-output.jpg} / stack Vespera, per la visione live. */
    public final String previewPath;
    /** Epoch millis dell'ultimo output Vespera (LIST/MDTM). 0 se sconosciuto. */
    public final long previewModifiedMillis;
    /** Pose FITS accanto ai JPEG. Lo stack condivisibile le usa solo con l'opzione avanzata. */
    public final List<String> fitsPaths;
    /** Date di acquisizione {@code yyyy-MM-dd}, in ordine. */
    public final List<String> acquisitionDates;
    /**
     * False finché pose, FITS e anteprima non sono stati letti.
     * L'elenco iniziale usa solo il nome della cartella.
     */
    public final boolean detailsReady;
    /** Cartelle sessione ancora da elencare al tocco. */
    public final List<String> pendingDirs;

    public SkyObject(
            Kind kind,
            String registeredName,
            String scientificName,
            String publicName,
            String folderPath,
            List<String> imagePaths,
            List<String> fitsPaths,
            List<String> acquisitionDates) {
        this(kind, registeredName, scientificName, publicName, folderPath,
                imagePaths, "", 0L, fitsPaths, acquisitionDates);
    }

    public SkyObject(
            Kind kind,
            String registeredName,
            String scientificName,
            String publicName,
            String folderPath,
            List<String> imagePaths,
            String previewPath,
            List<String> fitsPaths,
            List<String> acquisitionDates) {
        this(kind, registeredName, scientificName, publicName, folderPath,
                imagePaths, previewPath, 0L, fitsPaths, acquisitionDates);
    }

    public SkyObject(
            Kind kind,
            String registeredName,
            String scientificName,
            String publicName,
            String folderPath,
            List<String> imagePaths,
            String previewPath,
            long previewModifiedMillis,
            List<String> fitsPaths,
            List<String> acquisitionDates) {
        this(kind, registeredName, scientificName, publicName, folderPath,
                imagePaths, previewPath, previewModifiedMillis, fitsPaths, acquisitionDates,
                true, List.of());
    }

    public SkyObject(
            Kind kind,
            String registeredName,
            String scientificName,
            String publicName,
            String folderPath,
            List<String> imagePaths,
            String previewPath,
            long previewModifiedMillis,
            List<String> fitsPaths,
            List<String> acquisitionDates,
            boolean detailsReady,
            List<String> pendingDirs) {
        this.kind = kind == null ? Kind.OTHER : kind;
        this.registeredName = registeredName == null ? "" : registeredName.trim();
        this.scientificName = scientificName == null ? "" : scientificName.trim();
        this.publicName = publicName == null ? "" : publicName.trim();
        this.folderPath = folderPath == null ? "/" : folderPath;
        this.imagePaths = Collections.unmodifiableList(
                new ArrayList<>(imagePaths == null ? List.of() : imagePaths));
        this.previewPath = previewPath == null ? "" : previewPath.trim();
        this.previewModifiedMillis = previewModifiedMillis > 0 ? previewModifiedMillis : 0L;
        this.fitsPaths = Collections.unmodifiableList(
                new ArrayList<>(fitsPaths == null ? List.of() : fitsPaths));
        List<String> dates = new ArrayList<>();
        if (acquisitionDates != null) {
            for (String date : acquisitionDates) {
                if (date != null && !date.isBlank() && !dates.contains(date)) dates.add(date);
            }
        }
        this.acquisitionDates = Collections.unmodifiableList(dates);
        this.detailsReady = detailsReady;
        this.pendingDirs = Collections.unmodifiableList(
                new ArrayList<>(pendingDirs == null ? List.of() : pendingDirs));
    }

    /** Data/ora dell'ultimo output Vespera, oppure vuoto. */
    public String previewWhenLabel() {
        return formatWhen(previewModifiedMillis);
    }

    static String formatWhen(long millis) {
        if (millis <= 0L) return "";
        return WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public int frameCount() {
        return imagePaths.size();
    }

    public int fitsCount() {
        return fitsPaths.size();
    }

    public boolean hasPreview() {
        return !previewPath.isEmpty();
    }

    /** Etichetta corta: {@code preview 3 ott 2026 21:15} oppure {@code preview Vespera}. */
    public String previewLabel() {
        if (!hasPreview()) return "";
        String when = previewWhenLabel();
        return when.isEmpty() ? "preview Vespera" : "preview " + when;
    }

    /** Path per la visione live: output Vespera, altrimenti ultima posa. */
    public String lastPreviewPath() {
        if (!previewPath.isEmpty()) return previewPath;
        if (imagePaths.isEmpty()) return "";
        List<String> preview = FrameSelect.forPreview(imagePaths);
        return preview.isEmpty() ? "" : preview.get(0);
    }

    public String datesLabel() {
        if (acquisitionDates.isEmpty()) return "";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALY);
        StringBuilder sb = new StringBuilder();
        for (String iso : acquisitionDates) {
            if (sb.length() > 0) sb.append(", ");
            try {
                sb.append(LocalDate.parse(iso).format(fmt));
            } catch (DateTimeParseException e) {
                sb.append(iso);
            }
        }
        return sb.toString();
    }

    public String kindLabel() {
        return switch (kind) {
            case GALAXY -> "Galassia";
            case STAR -> "Stella";
            case NEBULA -> "Nebulosa";
            case OTHER -> "Altro";
        };
    }
}
