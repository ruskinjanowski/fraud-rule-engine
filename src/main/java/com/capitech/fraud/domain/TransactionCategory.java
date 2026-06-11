package com.capitech.fraud.domain;

/**
 * The category assigned to an incoming transaction event by the upstream system.
 * The brief deals in <em>categorized</em> transaction events; this is that categorization.
 */
public enum TransactionCategory {
	POS,
	ATM,
	ONLINE,
	TRANSFER,
	DEBIT_ORDER,
	OTHER
}
