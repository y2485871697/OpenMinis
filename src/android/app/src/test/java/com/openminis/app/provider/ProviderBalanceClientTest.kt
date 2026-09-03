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
    fun acceptsBareNumericBalanceWithConfiguredKey() {
        assertEquals(
            "9.26",
            ProviderBalanceClient.extractDisplayValue("9.2600", "remaining"),
        )
    }

    @Test
    fun findsNamedBalanceInTopLevelArray() {
        assertEquals(
            "9.26",
            ProviderBalanceClient.extractDisplayValue(
                """[{"remaining":9.2600}]""",
                "remaining",
            ),
        )
    }

    @Test
    fun acceptsRootPathForBareBalance() {
        assertEquals(
            "9.26",
            ProviderBalanceClient.extractDisplayValue("9.2600", "$"),
        )
    }

    @Test
    fun reportsMissingKey() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderBalanceClient.extractDisplayValue("""{"balance":1}""", "account.balance")
        }
    }

    @Test
    fun appendsLeadingSlashEndpointToVersionedBaseUrl() {
        assertEquals(
            "https://example.com/v1/usage",
            ProviderBalanceClient.resolveUrl("https://example.com/v1", "/usage").toString(),
        )
    }

    @Test
    fun rejectsHtmlInsteadOfDisplayingDoctypeAsBalance() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderBalanceClient.extractDisplayValue("<!doctype html><html></html>", "remaining")
        }
    }

    @Test
    fun roundsLongBalanceToTwoDecimals() {
        assertEquals(
            "23.23",
            ProviderBalanceClient.extractDisplayValue("23.22954043", "remaining"),
        )
    }

    @Test
    fun padsBalanceToTwoDecimals() {
        assertEquals(
            "9.20",
            ProviderBalanceClient.extractDisplayValue("9.2", "remaining"),
        )
    }}
