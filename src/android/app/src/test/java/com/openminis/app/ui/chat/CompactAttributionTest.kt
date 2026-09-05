package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompactAttributionTest {
    @Test fun providerPrecedesModelWithMiddleDot() {
        assertEquals("Provider\u00b7Model", compactAttributionSubtitle("Model (Provider)"))
    }
    @Test fun missingHistoricalProviderIsNotInvented() {
        assertEquals("Model", compactAttributionSubtitle("Model"))
        assertEquals("", compactAttributionSubtitle("  "))
    }
    @Test fun splitCompactionPreservesEverySuccessfulModel() {
        assertEquals("A\u00b7First / B\u00b7Second", compactAttributionSubtitle("First (A) / Second (B)"))
    }
    @Test fun providerNamesCanContainSlashSeparators() {
        assertEquals("A / B\u00b7Model", compactAttributionSubtitle("Model (A / B)"))
    }
    @Test fun overlappingSeparatorsDoNotCrash() {
        assertEquals("/", compactAttributionSubtitle(" / / "))
        assertEquals("A\u00b7First / B\u00b7Second", compactAttributionSubtitle("First (A) /  / Second (B)"))
    }
    @Test fun parenthesesInProviderAndModelArePreserved() {
        assertEquals("Server (custom)\u00b7Model (preview)", compactAttributionSubtitle("Model (preview) (Server (custom))"))
    }
    @Test fun incompleteOldLabelsAreKeptWithoutGuessing() {
        assertEquals("Model (Server", compactAttributionSubtitle("Model (Server"))
    }
    @Test fun currentModelLabelRoundTripsIntoTheRequestedPresentation() {
        val stored = compactModelDisplayLabel("Model", "id", "Server", "openai")
        assertEquals("Server\u00b7Model", compactAttributionSubtitle(stored))
    }
    @Test fun newAndReloadedDividersKeepMetadataOutOfTheirVisibleText() {
        fun source(name: String) = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { File(it, "com/openminis/app/ui/chat/$name.kt") }.first { it.isFile }.readText()
        val vm = source("ChatViewModel")
        assertTrue(vm.contains("detailLabel = compactModelLabel"))
        assertTrue(vm.contains("toolTitle = compactSummaryModel(markerForDivider.summary).orEmpty()"))
        val ui = source("ChatMiscViews")
        assertTrue(ui.contains("text = dividerText"))
        assertTrue(ui.contains("subtitle = modelAttribution"))
        assertTrue(ui.contains("AnnotatedString(summary)"))
        assertTrue(source("StandardChatSheet").contains("color = ChatColors.secondaryText"))
    }
}
