package app.haanf.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTest {
    private val tool = ToolSpec(
        "speak",
        "Say something",
        buildJsonObject { putJsonObject("message") { put("type", "string") } },
    )
    private val toolCallReply = """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,
        "tool_calls":[{"id":"c1","type":"function","function":{"name":"speak","arguments":"{\"message\":\"Hi\"}"}}]}}],
        "usage":{"prompt_tokens":10,"completion_tokens":4}}"""
    private val textReply = """{"choices":[{"finish_reason":"stop","message":{"content":"Done"}}]}"""

    private fun client(server: LocalServer) = OpenAiCompatibleClient(server.url, "test-key", "test-model")

    @Test
    fun `sends the request in Chat Completions form with the key`() = runBlocking {
        LocalServer(replies = listOf(textReply)).use { server ->
            client(server).send(LlmRequest("Be brief", listOf(LlmItem.User("Hello")), listOf(tool)))
            val sent = server.received.single()
            assertEquals("/chat/completions", sent.path)
            assertEquals("Bearer test-key", sent.headers["authorization"])
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
            val messages = body.getValue("messages").jsonArray
            assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("Hello", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
            val function = body.getValue("tools").jsonArray[0].jsonObject.getValue("function").jsonObject
            assertEquals("speak", function["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `reads tool calls and usage`() = runBlocking {
        LocalServer(replies = listOf(toolCallReply)).use { server ->
            val reply = client(server).send(LlmRequest("s", listOf(LlmItem.User("u"))))
            assertNull(reply.text)
            assertEquals("speak", reply.toolCalls.single().name)
            assertEquals("Hi", reply.toolCalls.single().input["message"]?.jsonPrimitive?.content)
            assertEquals(10L, reply.inputTokens)
            assertEquals(4L, reply.outputTokens)
        }
    }

    @Test
    fun `the tool loop returns results and stops when the model stops`() = runBlocking {
        LocalServer(replies = listOf(toolCallReply, textReply)).use { server ->
            val calls = mutableListOf<String>()
            val reply = runToolLoop(client(server), LlmRequest("s", listOf(LlmItem.User("u")), listOf(tool))) {
                calls += it.name
                ToolOutcome(it.id, "ok")
            }
            assertEquals("Done", reply.text)
            assertEquals(listOf("speak"), calls)
            val second = Json.parseToJsonElement(server.received[1].body).jsonObject.getValue("messages").jsonArray
            assertEquals("tool", second.last().jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("c1", second.last().jsonObject["tool_call_id"]?.jsonPrimitive?.content)
            assertEquals("assistant", second[2].jsonObject["role"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `the tool loop ends without another call once done`() = runBlocking {
        LocalServer(replies = listOf(toolCallReply, textReply)).use { server ->
            var done = false
            runToolLoop(client(server), LlmRequest("s", listOf(LlmItem.User("u")), listOf(tool)), isDone = { done }) {
                done = true
                ToolOutcome(it.id, "ok")
            }
            assertEquals(1, server.received.size)
        }
    }

    @Test
    fun `the tool loop stops at its round limit`() = runBlocking {
        LocalServer(replies = listOf(toolCallReply)).use { server ->
            runToolLoop(client(server), LlmRequest("s", listOf(LlmItem.User("u"))), maxRounds = 3) { ToolOutcome(it.id, "ok") }
            assertEquals(3, server.received.size)
        }
    }

    @Test
    fun `explains a rejected key`() {
        LocalServer(status = 401, replies = listOf("""{"error":{"message":"no"}}""")).use { server ->
            val error = assertThrows(LlmException::class.java) {
                runBlocking { client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))) }
            }
            assertEquals(LlmFailure.BAD_KEY, error.failure)
        }
    }

    @Test
    fun `explains running out of credit`() {
        LocalServer(status = 402, replies = listOf("{}")).use { server ->
            val error = assertThrows(LlmException::class.java) {
                runBlocking { client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))) }
            }
            assertEquals(LlmFailure.NO_CREDIT, error.failure)
        }
    }

    @Test
    fun `catches an error OpenRouter sends with a 200 status`() {
        LocalServer(replies = listOf("""{"error":{"code":429,"message":"slow down"}}""")).use { server ->
            val error = assertThrows(LlmException::class.java) {
                runBlocking { client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))) }
            }
            assertEquals(LlmFailure.RATE_LIMITED, error.failure)
        }
    }

    @Test
    fun `refuses to trust a reply it cannot read`() {
        LocalServer(replies = listOf("not json")).use { server ->
            val error = assertThrows(LlmException::class.java) {
                runBlocking { client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))) }
            }
            assertEquals(LlmFailure.BAD_RESPONSE, error.failure)
        }
    }

    @Test
    fun `reports a connection failure as a network problem`() {
        val client = OpenAiCompatibleClient("http://127.0.0.1:1", "k", "m")
        val error = assertThrows(LlmException::class.java) {
            runBlocking { client.send(LlmRequest("s", listOf(LlmItem.User("u")))) }
        }
        assertTrue(error.failure == LlmFailure.NETWORK)
    }
}
