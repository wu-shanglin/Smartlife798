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
    @Test fun manyNewTopicsDoNotLengthenFirstUse() {
        val future = FeatureGuide.topics + (1..40).map { GuideTopic("future-$it:1", "", "", "") }
        assertEquals(4, FeatureGuide.basics(future).size)
        assertEquals(listOf("a:2"), FeatureGuide.pending(emptySet(), listOf(GuideTopic("a:1", "", "", ""), GuideTopic("a:2", "", "", ""))).map { it.key })
        assertEquals(40, FeatureGuide.pending(FeatureGuide.topics.map { it.key }.toSet(), future).size)
    }
    @Test fun downgradeDoesNotReshowReadContent() {
        assertTrue(FeatureGuide.pending(setOf("a:2"), listOf(GuideTopic("a:1", "", "", ""))).isEmpty())
    }
}
