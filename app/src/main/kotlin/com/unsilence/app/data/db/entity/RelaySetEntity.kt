package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relay_sets")
data class RelaySetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** JSON array of relay WSS URLs */
    @ColumnInfo(name = "relay_urls")
    val relayUrls: String,

    /** Currently selected feed */
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    /** Global + Follows — editable but not deletable */
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,

    /** Serialised FeedFilter (null = use defaults) */
    @ColumnInfo(name = "filter_json")
    val filterJson: String? = null,
)
