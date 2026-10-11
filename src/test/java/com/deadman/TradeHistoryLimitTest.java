package com.deadman;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import static org.junit.Assert.*;

public class TradeHistoryLimitTest
{
	@Test public void configExposesFiveSavedChoicesWithThreeMonthDefault() throws Exception
	{
		Method method = Arrays.stream(DeadmanConfig.class.getMethods())
			.filter(m -> m.getName().equals("tradeHistoryLimit")).findFirst().orElse(null);
		assertNotNull("Trade history limit must be a native saved config item", method);
		assertEquals("tradeHistoryLimit", method.getAnnotation(ConfigItem.class).keyName());
		assertTrue(method.getReturnType().isEnum());
		Object[] choices = method.getReturnType().getEnumConstants();
		assertArrayEquals(new String[]{"1 week", "2 weeks", "1 month", "2 months", "3 months"},
			Arrays.stream(choices).map(Object::toString).toArray(String[]::new));
		assertEquals(choices[4], method.invoke(new DeadmanConfig() {}));
	}

	@Test public void cutoffsUseUtcCalendarWeeksAndMonths() throws Exception
	{
		Method method = Arrays.stream(DeadmanConfig.class.getMethods())
			.filter(m -> m.getName().equals("tradeHistoryLimit")).findFirst().orElse(null);
		assertNotNull("History cutoff must follow the selected limit", method);
		Object[] choices = method.getReturnType().getEnumConstants();
		String[] expected = {"2026-03-24T12:00:00Z", "2026-03-17T12:00:00Z", "2026-02-28T12:00:00Z",
			"2026-01-31T12:00:00Z", "2025-12-31T12:00:00Z"};
		for (int i = 0; i < choices.length; i++)
		{
			assertEquals(Instant.parse(expected[i]).toEpochMilli(), method.getReturnType()
				.getMethod("cutoff", Instant.class).invoke(choices[i], Instant.parse("2026-03-31T12:00:00Z")));
		}
	}
}
