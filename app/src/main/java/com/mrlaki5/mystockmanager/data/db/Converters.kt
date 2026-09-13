package com.mrlaki5.mystockmanager.data.db

import androidx.room.TypeConverter
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {

    @TypeConverter
    fun keywordsToJson(keywords: List<String>): String =
        json.encodeToString(serializer, keywords)

    @TypeConverter
    fun keywordsFromJson(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())

    @TypeConverter
    fun stateToName(state: ImageState): String = state.name

    /** Unknown names decay to IMPORTED rather than crashing on a downgraded install. */
    @TypeConverter
    fun stateFromName(value: String): ImageState =
        runCatching { ImageState.valueOf(value) }.getOrDefault(ImageState.IMPORTED)

    private companion object {
        val json = Json
        val serializer = ListSerializer(String.serializer())
    }
}
