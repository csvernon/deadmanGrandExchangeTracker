package com.deadman;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeTrade
{
	private int itemId;
	private int quantitySold;
	private int totalQuantity;
	private int price;
	private int spent;
	private String state;
	private int slot;
	private boolean buy;
	private long timestamp;
	private int world;
}
