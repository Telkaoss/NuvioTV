package com.nuvio.tv.domain.model

enum class NewItemsIndicatorStyle {
    OFF,
    BADGE,
    DOT,
    RING;

    fun next(): NewItemsIndicatorStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): NewItemsIndicatorStyle =
            entries.firstOrNull { it.name == value } ?: BADGE
    }
}
