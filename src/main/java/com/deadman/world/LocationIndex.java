package com.deadman.world;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

@Slf4j
public final class LocationIndex
{
    @Value
    public static class Anchor
    {
        String label;
        int x;
        int y;
        int plane;
        int labelSize;
    }

    private static final Set<String> IGNORE = new HashSet<>(Arrays.asList(
        "a", "an", "the", "of", "at", "near", "in", "on", "by", "inside", "kingdom", "province"));
    private static final LocationEstimate UNKNOWN = new LocationEstimate(null, null, LocationEstimate.Precision.UNKNOWN);
    private static final Set<String> DIRECTIONS = new HashSet<>(Arrays.asList("north", "northern", "south", "southern",
        "east", "eastern", "west", "western", "northeast", "northeastern", "northwest", "northwestern",
        "southeast", "southeastern", "southwest", "southwestern"));
    private final List<Anchor> anchors = new ArrayList<>();
    private final Map<String, Set<String>> names = new HashMap<>();
    private final Map<Anchor, String> keys = new HashMap<>();
    private Map<String, LocationEstimate> overrides = Collections.emptyMap();

    public LocationIndex(Collection<Anchor> data)
    {
        for (Anchor anchor : data)
        {
            if (anchor != null && anchor.label != null && !key(anchor.label).isEmpty()
                && valid(anchor.x + 1, anchor.y + 1, anchor.plane))
            {
                anchors.add(anchor);
                String name = key(anchor.label);
                names.computeIfAbsent(name, ignored -> new HashSet<>(Arrays.asList(name.split(" "))));
                keys.put(anchor, name);
            }
        }
    }

    public static LocationIndex bundled(Gson gson)
    {
        try (InputStream input = LocationIndex.class.getResourceAsStream("landmarks.json"))
        {
            if (input != null)
            {
                Anchor[] records = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Anchor[].class);
                if (records != null)
                {
                    return new LocationIndex(Arrays.asList(records));
                }
            }
            log.warn("Deadman map landmark catalog is missing");
        }
        catch (Exception ex)
        {
            log.warn("Unable to read Deadman map landmarks; announcements will remain unmapped", ex);
        }
        return new LocationIndex(Collections.emptyList());
    }

    public int size()
    {
        return anchors.size();
    }

    public void setOverrides(String text)
    {
        Map<String, LocationEstimate> replacement = new HashMap<>();
        if (text != null && text.length() <= 64000)
        {
            for (String line : text.split("\\R", 501))
            {
                if (replacement.size() >= 500)
                {
                    break;
                }
                String[] fields = line.split("\\|", -1);
                if (fields.length != 2 && fields.length != 3)
                {
                    continue;
                }
                String name = key(fields[0]);
                WorldPoint point = coordinate(fields[1]);
                WorldPoint entrance = fields.length == 3 ? coordinate(fields[2]) : null;
                if (!name.isEmpty() && name.length() <= 120 && point != null && (fields.length == 2 || entrance != null))
                {
                    replacement.put(name, new LocationEstimate(point, entrance, LocationEstimate.Precision.CUSTOM));
                }
            }
        }
        overrides = replacement;
    }

    public LocationEstimate find(String place)
    {
        if (place == null || place.length() > 240)
        {
            return UNKNOWN;
        }
        String query = key(place);
        LocationEstimate custom = overrides.get(query);
        if (custom != null)
        {
            return custom;
        }
        Set<String> words = new HashSet<>(Arrays.asList(query.split(" ")));
        Anchor best = null;
        int score = 0;
        boolean ambiguous = false;
        boolean exact = false;
        for (Anchor anchor : anchors)
        {
            String name = keys.get(anchor);
            Set<String> labelWords = names.get(name);
            if (!words.containsAll(labelWords))
            {
                continue;
            }
            Set<String> qualifiers = new HashSet<>(words);
            qualifiers.removeAll(labelWords);
            if (!DIRECTIONS.containsAll(qualifiers))
            {
                continue;
            }
            boolean sameName = name.equals(query);
            int rank = labelWords.size() + (sameName ? 100 : 0);
            if (rank > score)
            {
                best = anchor;
                score = rank;
                exact = sameName;
                ambiguous = false;
            }
            else if (rank == score && best != null && (best.x != anchor.x || best.y != anchor.y || best.plane != anchor.plane))
            {
                ambiguous = true;
            }
        }
        if (best == null || ambiguous)
        {
            return UNKNOWN;
        }
        // A compass word within the winning label (e.g. East Harbor) is part of its name.
        words.removeAll(names.get(keys.get(best)));
        int directions = 0;
        for (String word : words)
        {
            switch (word)
            {
                case "north": case "northern": directions |= 1; break;
                case "south": case "southern": directions |= 2; break;
                case "east": case "eastern": directions |= 4; break;
                case "west": case "western": directions |= 8; break;
                case "northeast": case "northeastern": directions |= 5; break;
                case "northwest": case "northwestern": directions |= 9; break;
                case "southeast": case "southeastern": directions |= 6; break;
                case "southwest": case "southwestern": directions |= 10; break;
                default: break;
            }
        }
        if ((directions & 3) == 3 || (directions & 12) == 12)
        {
            return UNKNOWN;
        }
        int dy = (directions & 1) != 0 ? 1 : (directions & 2) != 0 ? -1 : 0;
        int dx = (directions & 4) != 0 ? 1 : (directions & 8) != 0 ? -1 : 0;
        boolean region = best.labelSize == 2;
        int shift = region ? 192 : 40;
        int x = best.x + 1 + (exact ? 0 : dx * shift);
        int y = best.y + 1 + (exact ? 0 : dy * shift);
        if (!valid(x, y, best.plane))
        {
            return UNKNOWN;
        }
        LocationEstimate.Precision precision = region ? LocationEstimate.Precision.REGION
            : !exact && (dx != 0 || dy != 0) ? LocationEstimate.Precision.DIRECTION : LocationEstimate.Precision.MAP_LABEL;
        return new LocationEstimate(new WorldPoint(x, y, best.plane), null, precision);
    }

    static String key(String input)
    {
        String clean = input.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "")
            .replace('\u2019', '\'').toLowerCase(Locale.ROOT).replace("'", "")
            .replaceAll("[^a-z0-9]+", " ").trim();
        List<String> result = new ArrayList<>();
        for (String word : clean.split(" +"))
        {
            if (!IGNORE.contains(word) && !word.isEmpty())
            {
                // Asgarnian/Kandarian-style adjectives refer to the corresponding region label.
                result.add(word.endsWith("ian") ? word.substring(0, word.length() - 1) : word);
            }
        }
        return String.join(" ", result);
    }

    private static WorldPoint coordinate(String value)
    {
        String[] axes = value.trim().split(",", -1);
        if (axes.length != 3)
        {
            return null;
        }
        try
        {
            int x = Integer.parseInt(axes[0].trim());
            int y = Integer.parseInt(axes[1].trim());
            int plane = Integer.parseInt(axes[2].trim());
            return valid(x, y, plane) ? new WorldPoint(x, y, plane) : null;
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    private static boolean valid(int x, int y, int plane)
    {
        return x >= 0 && x < 16384 && y >= 0 && y < 16384 && plane >= 0 && plane <= 3;
    }
}
