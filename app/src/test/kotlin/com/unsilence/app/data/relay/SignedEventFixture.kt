package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.NostrEvent
import kotlinx.serialization.encodeToString

/**
 * Public relay event with a canonical id and BIP-340 signature. Keeping one
 * shared wire fixture prevents the ingest and embedded-event crypto tests from
 * accidentally exercising different canonical serializations.
 */
internal object SignedEventFixture {
    const val ID = "37395329e974410c1bc8558af4abb47a5197c1276498e9d06b9c99ed589d40a3"
    const val PUBKEY = "d735231e8eeb2d49becea0ebadf7cde4f81807ddc6d7389890f5b2067e099183"
    const val SIGNATURE =
        "05d5061afd7f3bf7c713c15a6c8c6aa266a97c6c4ad6db10c343aaa4d7bcb2d" +
            "682349c1d94567ed15ad34cae9334c9e049a0a52135b065a5f044dc975da08981"
    const val CREATED_AT = 1_786_104_801L
    const val KIND = 1
    const val CONTENT =
        "【DynamoDBに追加されたベクトル検索を、Streamsでベクトル化してStrands Agentsから使ってみた】\n" +
            "DynamoDBに新しく追加されたネイティブベクトル検索機能をStrands Agentsから試してみました！\n" +
            "https://dev.classmethod.jp/articles/dynamo-vector-strands/"

    val TAGS = listOf(
        listOf("r", "https://dev.classmethod.jp/articles/dynamo-vector-strands/"),
        listOf(
            "proxy",
            "https://dev.classmethod.jp/feed/#https://dev.classmethod.jp/articles/dynamo-vector-strands/",
            "rss",
        ),
    )

    fun dto(
        pubkey: String = PUBKEY,
        signature: String = SIGNATURE,
    ): EventDto = EventDto(
        id = ID,
        pubkey = pubkey,
        kind = KIND,
        content = CONTENT,
        createdAt = CREATED_AT,
        tags = TAGS,
        sig = signature,
    )

    fun wireJson(
        pubkey: String = PUBKEY,
        signature: String = SIGNATURE,
    ): String = NostrJson.encodeToString(dto(pubkey, signature))

    fun event(
        pubkey: String = PUBKEY,
        signature: String = SIGNATURE,
    ): NostrEvent = dto(pubkey, signature).toNostrEvent("wss://nos.lol")
}
