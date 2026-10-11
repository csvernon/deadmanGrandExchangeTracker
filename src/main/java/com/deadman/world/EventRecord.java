package com.deadman.world;

import java.time.Instant;
import lombok.Value;

@Value
public class EventRecord
{
    EventNotice.Type type;
    String place;
    EventNotice.Combat combat;
    Instant started;
    Instant expires;
    LocationEstimate location;
}
