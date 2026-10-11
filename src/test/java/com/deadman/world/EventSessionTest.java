package com.deadman.world;

import com.deadman.DeadmanConfig;
import com.google.gson.Gson;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventSessionTest
{
    private static final Instant START = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    public void announcementParsingIsAnchoredAndKeepsCombat()
    {
        EventNotice n = EventNotice.read("<col=ffff00>A breach has spawned near Castle Wars! Powerful monsters now roam the area (Single).</col>").get();
        assertEquals(EventNotice.Type.BREACH, n.getType());
        assertEquals("Castle Wars", n.getPlace());
        assertEquals(EventNotice.Combat.SINGLE, n.getCombat());
        assertFalse(EventNotice.read("Someone says A breach has spawned near Castle Wars!").isPresent());
        assertFalse(EventNotice.read("A breach has spawned at !").isPresent());
        assertFalse(EventNotice.read(null).isPresent());
    }

    @Test
    public void combatInShortMessageAndCurlyApostropheAreAccepted()
    {
        assertEquals(EventNotice.Combat.MULTI, EventNotice.read("A breach has appeared in Seers' Village (Multi).").get().getCombat());
        assertEquals(EventNotice.Type.CHEST, EventNotice.read("A Deadman\u2019s Chest has spawned at Catherby!").get().getType());
    }

    @Test
    public void exactOverrideBeatsLandmarkAndSupportsEntrance()
    {
        LocationIndex index = fixture();
        index.setOverrides("north of Clock Tower | 3020,3020,0\nUnderground test | 3000,9500,0 | 3001,3002,0");
        LocationEstimate match = index.find("North of the Clock Tower");
        assertEquals(LocationEstimate.Precision.CUSTOM, match.getPrecision());
        assertEquals(new WorldPoint(3020,3020,0), match.getPoint());
        assertEquals(new WorldPoint(3001,3002,0), index.find("Underground test").navigationFrom(new WorldPoint(3000,3000,0)));
    }

    @Test
    public void directionsMoveAnchorButDoNotInventUnknownPlaces()
    {
        LocationIndex index = fixture();
        assertEquals(new WorldPoint(3001,3041,0), index.find("north of Clock Tower").getPoint());
        assertEquals(LocationEstimate.Precision.DIRECTION, index.find("north of Clock Tower").getPrecision());
        assertEquals(LocationEstimate.Precision.REGION, index.find("Southern Kingdom of Testland").getPrecision());
        assertNull(index.find("definitely nowhere 999").getPoint());
    }

    @Test
    public void ambiguousNamesRequireAnOverride()
    {
        LocationIndex index = new LocationIndex(Arrays.asList(
            new LocationIndex.Anchor("Twin Temple", 1000, 1000, 0, 0),
            new LocationIndex.Anchor("Twin Temple", 2000, 2000, 0, 0)));
        assertNull(index.find("Twin Temple").getPoint());
        index.setOverrides("Twin Temple | 1002,1002,0");
        assertEquals(new WorldPoint(1002,1002,0), index.find("Twin Temple").getPoint());
    }

    @Test
    public void compassWordInALandmarkNameDoesNotChangeTheDirection()
    {
        LocationIndex index = new LocationIndex(Collections.singletonList(
            new LocationIndex.Anchor("East Harbor", 3000, 3000, 0, 0)));
        assertEquals(new WorldPoint(3001,3041,0), index.find("North of East Harbor").getPoint());
    }

    @Test
    public void contradictoryDirectionsStayUnmapped()
    {
        assertNull(fixture().find("north and south of Clock Tower").getPoint());
    }

    @Test
    public void unresolvedDungeonQualifiersDoNotUseSurfaceTowns()
    {
        LocationIndex index = LocationIndex.bundled(new Gson());
        assertNull(index.find("Taverley Dungeon").getPoint());
        assertNull(index.find("Varrock Sewers").getPoint());
        assertNotNull(index.find("Taverley").getPoint());
    }

    @Test
    public void badOverridesAreIgnoredAndRemovedOverridesDoNotLinger()
    {
        LocationIndex index = fixture();
        index.setOverrides("Missing | -1,10,0\nMissing | 100,100,9\nMissing | nope\nValid | 200,200,0");
        assertNull(index.find("Missing").getPoint());
        assertEquals(new WorldPoint(200,200,0), index.find("Valid").getPoint());
        index.setOverrides("");
        assertNull(index.find("Valid").getPoint());
    }

    @Test
    public void cacheDerivedCatalogLoadsRealMapAnchors()
    {
        LocationIndex index = LocationIndex.bundled(new Gson());
        assertEquals(new WorldPoint(2855,3565,0), index.find("Warriors' Guild").getPoint());
        assertEquals(LocationEstimate.Precision.REGION, index.find("Kingdom of Asgarnia").getPrecision());
        assertTrue(index.size() > 1000);
    }

    @Test
    public void worldAndChatFiltersPreventFalseEvents()
    {
        EventSession session = new EventSession(new DeadmanConfig(){}, fixture());
        session.session(GameState.LOGGED_IN, 301);
        chest(session, START);
        assertTrue(session.rows().isEmpty());
        session.session(GameState.LOGGED_IN, 345);
        session.message(ChatMessageType.PUBLICCHAT, "A breach has spawned at Clock Tower!", START);
        assertTrue(session.rows().isEmpty());
        chest(session, START);
        assertEquals(1, session.rows().size());
    }

    @Test
    public void duplicateReportsCannotRestartTheCountdown()
    {
        EventSession session = active();
        chest(session, START);
        chest(session, START.plusSeconds(20));
        assertEquals(1, session.rows().size());
        assertEquals(START.plusSeconds(600), session.rows().get(0).getExpires());
    }

    @Test
    public void lateDuplicateDoesNotExtendAnActiveChest()
    {
        EventSession session = active();
        chest(session, START);
        chest(session, START.plusSeconds(550));
        assertEquals(START.plusSeconds(600), session.rows().get(0).getExpires());
    }

    @Test
    public void supersededChestCannotReturnThroughADuplicateBroadcast()
    {
        EventSession session = active();
        chest(session, START);
        session.message(ChatMessageType.BROADCAST, "A Deadman's Chest has spawned at second place!", START.plusSeconds(10));
        chest(session, START.plusSeconds(20));
        assertEquals("second place", session.rows().get(0).getPlace());
        assertEquals(START.plusSeconds(610), session.rows().get(0).getExpires());
    }

    @Test
    public void minimapToggleLeavesMapMarkersAvailable()
    {
        EventSession session = new EventSession(new DeadmanConfig(){
            @Override public boolean eventMinimapArrows(){return false;}
        }, fixture());
        session.session(GameState.LOGGED_IN,345);
        chest(session, START);
        assertEquals(1, session.markers().size());
        assertTrue(session.minimap().isEmpty());
    }

    @Test
    public void unrelatedChatAtExpiryClearsPresentationToo()
    {
        EventSession session = active();
        chest(session, START);
        session.message(ChatMessageType.GAMEMESSAGE, "You drink a potion.", START.plusSeconds(600));
        session.tick(START.plusSeconds(601));
        assertTrue(session.rows().isEmpty());
        assertTrue(session.markers().isEmpty());
    }

    @Test
    public void loadingKeepsStateButHopDiscardsIt()
    {
        EventSession session = active();
        chest(session, START);
        session.session(GameState.LOADING, 345);
        assertTrue(session.markers().isEmpty());
        session.session(GameState.LOGGED_IN, 345);
        assertEquals(1, session.rows().size());
        session.session(GameState.HOPPING, 345);
        session.session(GameState.LOGGED_IN, 345);
        assertTrue(session.rows().isEmpty());
    }

    @Test
    public void stoppedSessionIgnoresLateMessages()
    {
        EventSession session = active();
        chest(session, START);
        session.stop();
        session.session(GameState.LOGGED_IN, 345);
        chest(session, START.plusSeconds(1));
        assertTrue(session.rows().isEmpty());
        assertTrue(session.markers().isEmpty());
    }

    @Test
    public void markerSettingsDoNotDiscardEventList()
    {
        AtomicBoolean enabled = new AtomicBoolean(true);
        EventSession session = new EventSession(new DeadmanConfig(){
            @Override public boolean chestMapMarkers(){return enabled.get();}
        }, fixture());
        session.session(GameState.LOGGED_IN,345);
        chest(session, START);
        assertEquals(1, session.markers().size());
        enabled.set(false);
        session.configure();
        assertTrue(session.markers().isEmpty());
        assertEquals(1, session.rows().size());
        enabled.set(true);
        session.configure();
        assertEquals(1, session.markers().size());
    }

    @Test
    public void updatedOverridesRemapAnAlreadyAnnouncedEvent()
    {
        AtomicBoolean override = new AtomicBoolean(false);
        EventSession session = new EventSession(new DeadmanConfig(){
            @Override public String eventLocationOverrides(){return override.get() ? "Clock Tower | 1111,2222,0" : "";}
        }, fixture());
        session.session(GameState.LOGGED_IN,345);
        chest(session, START);
        override.set(true);
        session.configure();
        assertEquals(new WorldPoint(1111,2222,0), session.rows().get(0).getLocation().getPoint());
    }

    @Test
    public void breachListIsBoundedAndExpires()
    {
        EventSession session = active();
        for(int i=0;i<40;i++){
            session.message(ChatMessageType.BROADCAST,"A breach has spawned at place "+i+"!",START);
        }
        assertTrue(session.rows().size() <= 24);
        session.tick(START.plusSeconds(2700));
        assertTrue(session.rows().isEmpty());
    }

    static LocationIndex fixture()
    {
        return new LocationIndex(Arrays.asList(
            new LocationIndex.Anchor("Clock Tower",3000,3000,0,0),
            new LocationIndex.Anchor("Kingdom of Testland",3200,3400,0,2)));
    }

    private static EventSession active()
    {
        EventSession session = new EventSession(new DeadmanConfig(){}, fixture());
        session.session(GameState.LOGGED_IN,345);
        return session;
    }

    private static void chest(EventSession session, Instant at)
    {
        session.message(ChatMessageType.BROADCAST,"A Deadman's Chest has spawned at Clock Tower!",at);
    }
}

