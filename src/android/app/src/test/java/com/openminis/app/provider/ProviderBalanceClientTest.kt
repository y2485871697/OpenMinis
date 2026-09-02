package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderBalanceClientTest {
    @Test
    fun extractsNestedArrayBalance() {
        val json = """{"balance_infos":[{"total_balance":9.2600}]}"""
        assertEquals(
            "9.26",
            ProviderBalanceClient.extractDisplayValue(
                json,
                "balance_infos[0].total_balance",
            ),
        )
    }

    @Test
    fun preservesStringBalance() {
        val json = """{"account":{"balance":"$12.40"}}"""
        assertEquals(
            "$12.40",
            ProviderBalanceClient.extractDisplayValue(json, "account.balance"),
        )
    }

    @Test
    fun reportsMissingKey() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderBalanceClient.extractDisplayValue("""{"balance":1}""", "account.balance")
        }
    }
}