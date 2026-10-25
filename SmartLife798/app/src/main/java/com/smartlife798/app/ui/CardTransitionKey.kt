package com.smartlife798.app.ui

/** Identifies the actual launch surface, not just the device shown in several lists. */
enum class CardDestination { DEVICE, SCAN, MANUAL, WALLET }

enum class CardOrigin(val destination: CardDestination, val cornerDp: Int) {
    HOME_FAVORITE(CardDestination.DEVICE, 18),
    HOME_RECENT(CardDestination.DEVICE, 16),
    HOME_ACTIVE(CardDestination.DEVICE, 20),
    DEVICES(CardDestination.DEVICE, 24),
    ORDERS_ACTIVE(CardDestination.DEVICE, 16),
    HOME_SCAN(CardDestination.SCAN, 32),
    NAV_SCAN(CardDestination.SCAN, 27),
    SCAN_MANUAL(CardDestination.MANUAL, 50),
    WALLET(CardDestination.WALLET, 12),
}

enum class CardPart { CONTAINER, TITLE, AMOUNT }

data class CardTransitionKey(
    val entryId: String,
    val origin: CardOrigin,
    val itemId: String,
    val part: CardPart = CardPart.CONTAINER,
)

data class CardSource(val entryId: String, val origin: CardOrigin) {
    fun isCurrent(currentEntryId: String?): Boolean = entryId.isNotBlank() && entryId == currentEntryId

    fun key(itemId: String = ""): CardTransitionKey? =
        if (entryId.isBlank() || (origin.destination == CardDestination.DEVICE && itemId.isBlank())) null
        else CardTransitionKey(entryId, origin, itemId)

    companion object {
        fun decode(entryId: String?, origin: String?, destination: CardDestination): CardSource? {
            if (entryId.isNullOrBlank()) return null
            val kind = CardOrigin.entries.firstOrNull { it.name == origin && it.destination == destination }
                ?: return null
            return CardSource(entryId, kind)
        }
    }
}
