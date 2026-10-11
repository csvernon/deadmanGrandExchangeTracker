package com.deadman.world;

import com.deadman.DeadmanConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;

/** Mutated on the client thread; immutable snapshots are safe for rendering. */
public final class EventSession
{
    private final DeadmanConfig config;
    private final LocationIndex locations;
    private final Map<String, EventRecord> events = new LinkedHashMap<>();
    private final Map<String, Instant> recent = new LinkedHashMap<>();
    private boolean active;
    private boolean stopped;
    private int world;
    private volatile List<EventRecord> rows = Collections.emptyList();
    private volatile List<EventRecord> markers = Collections.emptyList();
    private volatile List<EventRecord> minimap = Collections.emptyList();
    private long revision;

    public EventSession(DeadmanConfig config, LocationIndex locations)
    {
        this.config = config;
        this.locations = locations;
        locations.setOverrides(config.eventLocationOverrides());
    }

    public void session(GameState state, int nextWorld)
    {
        if (stopped)
        {
            return;
        }
        if (nextWorld != world || (state != GameState.LOGGED_IN && state != GameState.LOADING))
        {
            events.clear();
            recent.clear();
        }
        world = nextWorld;
        active = state == GameState.LOGGED_IN && world == 345;
        publish();
    }

    public void message(ChatMessageType channel, String text, Instant now)
    {
        if (stopped || !active)
        {
            return;
        }
        expire(now);
        if (channel == ChatMessageType.BROADCAST || channel == ChatMessageType.GAMEMESSAGE)
        {
            Optional<EventNotice> parsed = EventNotice.read(text);
            if (parsed.isPresent())
            {
                EventNotice notice = parsed.get();
                String key = notice.getType() + ":" + LocationIndex.key(notice.getPlace());
                if (!recent.containsKey(key))
                {
                    if (notice.getType() == EventNotice.Type.CHEST)
                    {
                        events.values().removeIf(event -> event.getType() == EventNotice.Type.CHEST);
                    }
                    Instant expires = now.plusSeconds(notice.getType() == EventNotice.Type.CHEST ? 600 : 2700);
                    recent.put(key, expires);
                    while (recent.size() > 128)
                    {
                        recent.remove(recent.keySet().iterator().next());
                    }
                    events.put(key, new EventRecord(notice.getType(), notice.getPlace(), notice.getCombat(), now,
                        expires, locations.find(notice.getPlace())));
                    while (events.values().stream().filter(event -> event.getType() == EventNotice.Type.BREACH).count() > 24)
                    {
                        String oldest = events.entrySet().stream().filter(entry -> entry.getValue().getType() == EventNotice.Type.BREACH)
                            .map(Map.Entry::getKey).findFirst().get();
                        events.remove(oldest);
                    }
                }
            }
        }
        publish();
    }

    public void tick(Instant now)
    {
        if (!stopped)
        {
            expire(now);
            publish();
        }
    }

    public void configure()
    {
        if (stopped)
        {
            return;
        }
        locations.setOverrides(config.eventLocationOverrides());
        events.replaceAll((key, event) -> new EventRecord(event.getType(), event.getPlace(), event.getCombat(),
            event.getStarted(), event.getExpires(), locations.find(event.getPlace())));
        publish();
    }

    public void stop()
    {
        stopped = true;
        active = false;
        events.clear();
        recent.clear();
        publish();
    }

    public List<EventRecord> rows() { return rows; }
    public List<EventRecord> markers() { return markers; }
    public List<EventRecord> minimap() { return minimap; }
    public long revision() { return revision; }

    private void expire(Instant now)
    {
        events.values().removeIf(event -> !now.isBefore(event.getExpires()));
        recent.values().removeIf(expires -> !now.isBefore(expires));
    }

    private void publish()
    {
        List<EventRecord> nextRows = active ? new ArrayList<>(events.values()) : Collections.emptyList();
        List<EventRecord> nextMarkers = new ArrayList<>();
        for (EventRecord event : nextRows)
        {
            if (event.getLocation().getPoint() != null && (event.getType() == EventNotice.Type.CHEST
                ? config.chestMapMarkers() : config.breachMapMarkers()))
            {
                nextMarkers.add(event);
            }
        }
        List<EventRecord> nextMinimap = config.eventMinimapArrows() ? nextMarkers : Collections.emptyList();
        if (!rows.equals(nextRows) || !markers.equals(nextMarkers) || !minimap.equals(nextMinimap))
        {
            rows = Collections.unmodifiableList(nextRows);
            markers = Collections.unmodifiableList(nextMarkers);
            minimap = Collections.unmodifiableList(nextMinimap);
            revision++;
        }
    }
}
