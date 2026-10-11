package com.deadman.world;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

@Value
public class EventNotice
{
    public enum Type { CHEST, BREACH }
    public enum Combat { SINGLE, MULTI }

    private static final Pattern HEADER = Pattern.compile(
        "^A (breach|Deadman's Chest) has (?:spawned|appeared|opened|emerged) (?:at|in|near|on|inside|by) (.+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COMBAT = Pattern.compile("\\((Single|Multi)\\)", Pattern.CASE_INSENSITIVE);

    Type type;
    String place;
    Combat combat;

    public static Optional<EventNotice> read(String raw)
    {
        if (raw == null || raw.length() > 1024)
        {
            return Optional.empty();
        }
        String text = Text.removeTags(raw).replace('\u2019', '\'').replaceAll("\\s+", " ").trim();
        Matcher header = HEADER.matcher(text);
        if (!header.matches())
        {
            return Optional.empty();
        }
        Matcher combatMatch = COMBAT.matcher(header.group(2));
        Combat combat = combatMatch.find() ? Combat.valueOf(combatMatch.group(1).toUpperCase(java.util.Locale.ROOT)) : null;
        String place = header.group(2).split("!|\\.\\s|\\((?i:Single|Multi)\\)", 2)[0].replaceAll("[.!]+$", "").trim();
        if (place.isEmpty() || place.length() > 120)
        {
            return Optional.empty();
        }
        return Optional.of(new EventNotice(header.group(1).equalsIgnoreCase("breach") ? Type.BREACH : Type.CHEST, place, combat));
    }
}
