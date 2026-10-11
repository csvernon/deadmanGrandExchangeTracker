package com.deadman;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public enum TradeHistoryLimit
{
	ONE_WEEK("1 week", 1, ChronoUnit.WEEKS),
	TWO_WEEKS("2 weeks", 2, ChronoUnit.WEEKS),
	ONE_MONTH("1 month", 1, ChronoUnit.MONTHS),
	TWO_MONTHS("2 months", 2, ChronoUnit.MONTHS),
	THREE_MONTHS("3 months", 3, ChronoUnit.MONTHS);

	private final String label;
	private final int amount;
	private final ChronoUnit unit;

	TradeHistoryLimit(String label, int amount, ChronoUnit unit)
	{
		this.label = label;
		this.amount = amount;
		this.unit = unit;
	}

	public long cutoff(Instant now)
	{
		return now.atZone(ZoneOffset.UTC).minus(amount, unit).toInstant().toEpochMilli();
	}

	@Override public String toString()
	{
		return label;
	}
}
