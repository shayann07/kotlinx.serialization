/*
 * Copyright 2017-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.features

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.test.checkDecodingException
import kotlinx.serialization.test.checkEncodingException
import kotlinx.serialization.test.isJs
import kotlinx.serialization.test.isWasm
import kotlin.test.*

class PolymorphicErrorMessagesTest : JsonTestBase() {
    @Serializable
    class DummyData(@Polymorphic val a: Any)

    @Serializable
    class Holder(val d: DummyData)

    @Test
    fun testNotRegisteredMessage() = parametrizedTest { mode ->
        val input = """{"d":{"a":{"type":"my.Class", "value":42}}}"""
        checkDecodingException(mode, {
            default.decodeFromString<Holder>(input, mode)
        }) {
            message("Serializer for subclass 'my.Class' is not found in the polymorphic scope of 'Any'")
            if (mode != JsonTestingMode.TREE) path("$.d.a") else path("$.a") // #3170
            hint("Check if class with serial name 'my.Class' exists and serializer is registered in a corresponding SerializersModule.")
            // ReaderJsonLexer.peekLeadingMatchingValue is not implemented (#2626), so first-key optimization is not working for non-streaming yet.
            if (mode == JsonTestingMode.STREAMING) {
                offset(10)
                input(input)
            } else {
                input("{\"type\":\"my.Class\",\"value\":42}")
            }
        }
    }

    @Test
    fun testDiscriminatorMissingNoDefaultMessage() = parametrizedTest { mode ->
        val input = """{"d":{"a":{"value":42}}}"""
        checkDecodingException(mode, {
            default.decodeFromString<Holder>(input, mode)
        }) {
            // Always slow path when discriminator is missing, so no position
            message("Class discriminator was missing and no default serializers were registered in the polymorphic scope of 'Any'")
            if (mode != JsonTestingMode.TREE) path("$.d.a") else path("$.a") // #3170
            input("{\"value\":42}")
        }
    }

    @Test
    fun testClassDiscriminatorIsNull() = parametrizedTest { mode ->
        val input = """{"d":{"a":{"type":null, "value":42}}}"""
        checkDecodingException(mode, {
            default.decodeFromString<Holder>(input, mode)
        }) {
            // Always slow path when discriminator is missing, so no position
            message("Class discriminator was missing and no default serializers were registered in the polymorphic scope of 'Any'")
            if (mode != JsonTestingMode.TREE) path("$.d.a") else path("$.a") // #3170
            input("{\"type\":null,\"value\":42}")
        }
    }

    @Test
    fun testUnknownSubclassDuringEncoding() = parametrizedTest { mode ->
        val input = Holder(DummyData(StringData("x")))
        val subclassName = if (isJs() || isWasm()) "StringData" else "kotlinx.serialization.StringData"
        checkEncodingException(mode, {
            default.encodeToString(Holder.serializer(), input)
        }) {
            message("Serializer for subclass '$subclassName' is not found in the polymorphic scope of 'Any'")
            hint("Check if class with serial name '$subclassName' exists and serializer is registered in a corresponding SerializersModule.")
            serialName(subclassName)
        }
    }

    class UnregisteredData(val value: String)

    @Test
    fun testUnregisteredSubclassReportsQualifiedName() = parametrizedTest { mode ->
        val input = Holder(DummyData(UnregisteredData("x")))
        val subclassName = if (isJs() || isWasm()) {
            "UnregisteredData"
        } else {
            "kotlinx.serialization.features.PolymorphicErrorMessagesTest.UnregisteredData"
        }
        checkEncodingException(mode, {
            default.encodeToString(Holder.serializer(), input)
        }) {
            message("Serializer for subclass '$subclassName' is not found in the polymorphic scope of 'Any'")
            hint("Check if class with serial name '$subclassName' exists and serializer is registered in a corresponding SerializersModule.")
            serialName(subclassName)
        }
    }

    @Test
    fun testJsonContentPolymorphicSerializerMessageContainsQualifiedName() {
        val serializer = object : JsonContentPolymorphicSerializer<Any>(Any::class) {
            override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Any> =
                throw UnsupportedOperationException()
        }
        val subclassName = if (isJs() || isWasm()) {
            "UnregisteredData"
        } else {
            "kotlinx.serialization.features.PolymorphicErrorMessagesTest.UnregisteredData"
        }
        val exception = assertFailsWith<SerializationException> {
            default.encodeToString(serializer, UnregisteredData("x"))
        }
        val expected = "Class '$subclassName' is not registered for polymorphic serialization in the scope of 'Any'.\n" +
            "Mark the base class as 'sealed' or register the serializer explicitly."
        assertEquals(expected, exception.message)
    }

    sealed class LoadingState<out T : Any> {
        data class Ready<T : Any>(val result: String) : LoadingState<T>()
    }

    @Test
    fun testLoadingStateReadyMessageContainsQualifiedName() = parametrizedTest { mode ->
        val subclassName = if (isJs() || isWasm()) {
            "Ready"
        } else {
            "kotlinx.serialization.features.PolymorphicErrorMessagesTest.LoadingState.Ready"
        }
        checkEncodingException(mode, {
            default.encodeToString<LoadingState<*>>(PolymorphicSerializer(LoadingState::class), LoadingState.Ready<String>("ok"))
        }) {
            message("Serializer for subclass '$subclassName' is not found in the polymorphic scope of 'LoadingState'")
            hint("Check if class with serial name '$subclassName' exists and serializer is registered in a corresponding SerializersModule.\n" +
                "To be registered automatically, class '$subclassName' has to be '@Serializable', and the base class 'LoadingState' has to be sealed and '@Serializable'.")
            serialName(subclassName)
        }
    }

    @Test
    fun testLocalClassDuringEncoding() = parametrizedTest { mode ->
        class LocalData(val value: String)
        val input = Holder(DummyData(LocalData("x")))
        checkEncodingException(mode, {
            default.encodeToString(Holder.serializer(), input)
        }) {
            message("Serializer for subclass 'LocalData' is not found in the polymorphic scope of 'Any'")
            hint("Check if class with serial name 'LocalData' exists and serializer is registered in a corresponding SerializersModule.")
            serialName("LocalData")
        }
    }

    @Test
    fun testAnonymousObjectDuringEncoding() = parametrizedTest { mode ->
        val anonymous = object : Any() {}
        val input = Holder(DummyData(anonymous))
        val subclassName = anonymous::class.toString()
        checkEncodingException(mode, {
            default.encodeToString(Holder.serializer(), input)
        }) {
            message("Serializer for subclass '$subclassName' is not found in the polymorphic scope of 'Any'")
            hint("Check if class with serial name '$subclassName' exists and serializer is registered in a corresponding SerializersModule.")
            serialName(subclassName)
        }
    }

    @Test
    fun testJsonContentPolymorphicSerializerWithLoadingStateReady() {
        val serializer = object : JsonContentPolymorphicSerializer<LoadingState<*>>(LoadingState::class) {
            override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LoadingState<*>> =
                throw UnsupportedOperationException()
        }
        val subclassName = if (isJs() || isWasm()) {
            "Ready"
        } else {
            "kotlinx.serialization.features.PolymorphicErrorMessagesTest.LoadingState.Ready"
        }
        val exception = assertFailsWith<SerializationException> {
            default.encodeToString(serializer, LoadingState.Ready<String>("ok"))
        }
        val expected = "Class '$subclassName' is not registered for polymorphic serialization in the scope of 'LoadingState'.\n" +
            "Mark the base class as 'sealed' or register the serializer explicitly."
        assertEquals(expected, exception.message)
    }
}
