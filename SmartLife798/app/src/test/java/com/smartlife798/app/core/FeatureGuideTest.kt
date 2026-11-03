package com.smartlife798.app.core

import org.junit.Assert.*
import org.junit.Test

class FeatureGuideTest {
    @Test fun freshInstallHasOneCurrentCatalog() {
        assertEquals(FeatureGuide.topics, FeatureGuide.pending(emptySet()))
        assertEquals(FeatureGuide.topics.size, FeatureGuide.topics.map { it.key }.toSet().size)
    }
    @Test fun crossingVersionsMergesOnlyUnseenRevisions() {
        val catalog = listOf(
            GuideTopic("old:1", "old", "", ""), GuideTopic("new-a:1", "a", "", ""),
            GuideTopic("new-b:2", "b", "", ""), GuideTopic("new-a:1", "duplicate", "", ""),
        )
        assertEquals(listOf("new-a:1", "new-b:2"), FeatureGuide.pending(setOf("old:1", "new-b:1", "retired:1"), catalog).map { it.key })
        assertTrue(FeatureGuide.pending(catalog.map { it.key }.toSet(), catalog).isEmpty())
    }
    @Test fun existingUsersNeverReplayTheBasicCard() {
        val pending = FeatureGuide.pending(emptySet(), includeBasics = false)
        assertFalse(pending.any { it.basic })
        assertEquals(FeatureGuide.topics.filterNot { it.basic }, pending)
    }
    @Test fun firstUseCombinesOneBasicAndCurrentFeatures() {
        val fresh = FeatureGuide.pending(emptySet())
        assertEquals(1, fresh.count { it.basic })
        assertTrue(fresh.first().basic)
    }
    @Test fun partialReadingResumesAtUnreadFeatures() {
        val firstTwo = FeatureGuide.topics.take(2).map { it.key }.toSet()
        assertEquals(FeatureGuide.topics.drop(2), FeatureGuide.pending(firstTwo, includeBasics = false))
    }
    @Test fun crossVersionBatchContainsOnlyLatestUnseenFeatures() {
        val future = FeatureGuide.topics + listOf(GuideTopic("new:1", "", "", ""), GuideTopic("new:2", "", "", ""))
        assertEquals(listOf("new:2"), FeatureGuide.pending(FeatureGuide.topics.map { it.key }.toSet(), future, includeBasics = false).map { it.key })
    }
    @Test fun downgradeDoesNotReshowReadContent() {
        assertTrue(FeatureGuide.pending(setOf("a:2"), listOf(GuideTopic("a:1", "", "", ""))).isEmpty())
    }
}
