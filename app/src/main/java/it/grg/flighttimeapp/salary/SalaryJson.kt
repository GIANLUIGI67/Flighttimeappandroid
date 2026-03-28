@file:SuppressLint("NewApi")
package it.grg.flighttimeapp.salary
import android.annotation.SuppressLint

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.LocalDate

object SalaryJson {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
        .create()

    fun <T> decode(json: String, clazz: Class<T>): T {
        return gson.fromJson(json, clazz)
    }

    fun <T> decode(json: String, type: Type): T {
        return gson.fromJson(json, type)
    }

    fun encode(value: Any): String {
        return gson.toJson(value)
    }

    private class LocalDateAdapter : JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
        override fun serialize(
            src: LocalDate,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement {
            return context.serialize(src.toString())
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): LocalDate {
            val value = json.asString
            return try {
                LocalDate.parse(value)
            } catch (ex: Exception) {
                throw JsonParseException(ex)
            }
        }
    }
}
