package com.mewbox.tv

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun searchHistory_serialization_roundtrip() {
        val initial = listOf("庆余年", "繁花", "抓娃娃")
        val serialized = json.encodeToString(initial)
        val deserialized = json.decodeFromString<List<String>>(serialized)
        assertEquals(initial, deserialized)
    }

    @Test
    fun searchHistory_empty_string_handling() {
        val emptyJson = ""
        val result = runCatching {
            if (emptyJson.isBlank()) emptyList<String>() else json.decodeFromString<List<String>>(emptyJson)
        }.getOrDefault(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun searchHistory_add_deduplicatesAndPrepends() {
        var list = listOf("庆余年", "繁花", "三体")
        val newKeyword = "繁花"
        list = (listOf(newKeyword) + list.filter { it != newKeyword }).take(20)
        assertEquals(listOf("繁花", "庆余年", "三体"), list)
    }

    @Test
    fun searchHistory_capsAt20() {
        var list = (1..25).map { "关键词_$it" }
        val newKeyword = "最新词"
        list = (listOf(newKeyword) + list.filter { it != newKeyword }).take(20)
        assertEquals(20, list.size)
        assertEquals("最新词", list.first())
    }

    @Test
    fun searchHistory_deleteItem() {
        val list = listOf("庆余年", "繁花", "三体")
        val updated = list.filter { it != "繁花" }
        assertEquals(listOf("庆余年", "三体"), updated)
    }
}

