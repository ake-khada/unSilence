package com.unsilence.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relay_trust_scores")
data class RelayTrustScoreEntity(
    @PrimaryKey
    @ColumnInfo(name = "relay_url")
    val relayUrl: String,

    @ColumnInfo(name = "score")
    val score: Int,

    @ColumnInfo(name = "reliability")
    val reliability: Int,

    @ColumnInfo(name = "quality")
    val quality: Int,

    @ColumnInfo(name = "accessibility")
    val accessibility: Int,

    @ColumnInfo(name = "confidence")
    val confidence: String,

    @ColumnInfo(name = "observations")
    val observations: Int,

    @ColumnInfo(name = "policy")
    val policy: String? = null,

    @ColumnInfo(name = "country_code")
    val countryCode: String? = null,

    @ColumnInfo(name = "operator_verified")
    val operatorVerified: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
