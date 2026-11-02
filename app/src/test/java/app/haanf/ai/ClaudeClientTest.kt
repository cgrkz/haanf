package app.haanf.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientTest {
    private val tool = ToolSpec(
        "speak",
        "Say something",
        buildJsonObject { putJsonObject("message") { put("type", "string") } },
    )
    private val toolReply = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
        "content":[{"type":"thinking","thinking":"","signature":"sig123"},
                   {"type":"tool_use","id":"tu_1","name":"speak","input":{"message":"Hi"}}],
        "stop_reason":"tool_use","stop_sequence":null,
        "usage":{"input_tokens":20,"output_tokens":7}}"""
    private val textReply = """{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
        "content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn","stop_sequence":null,
        "usage":{"input_tokens":30,"output_tokens":2}}"""

    private fun client(server: LocalServer, model: String = "claude-opus-5-5") =
        ClaudeClient("sk-ant-api03-fake", model, baseUrl = server.url)

    private fun body(received: LocalServer.Received) = Json.parseToJsonElement(received.body).jsonObject

    @Test
    fun `sends strict tools, low effort and the refusal fallback`() = runBlocking {
        LocalServer(replies = listOf(textReply)).use { server ->
            client(server).send(LlmRequest("Be brief", listOf(LlmItem.User("Hello")), listOf(tool)))
            val sent = server.received.single()
            assertEquals("sk-ant-api03-fake", sent.headers["x-api-key"])
            assertNull(sent.headers["authorization"])
            assertTrue(sent.headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
            val json = body(sent)
            assertEquals("claude-opus-5-5", json["model"]?.jsonPrimitive?.content)
            assertEquals("Be brief", json["system"]?.jsonPrimitive?.content)
            assertEquals("default", json["fallbacks"]?.jsonPrimitive?.content)
            assertEquals("low", json.getValue("output_config").jsonObject["effort"]?.jsonPrimitive?.content)
            val sentTool = json.getValue("tools").jsonArray[0].jsonObject
            assertTrue(sentTool["strict"]!!.jsonPrimitive.boolean)
            assertFalse(sentTool.getValue("input_schema").jsonObject["additionalProperties"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `a model without the fallback does not ask for it`() = runBlocking {
        LocalServer(replies = listOf(textReply)).use { server ->
            client(server, "claude-haiku-5-5").send(LlmRequest("s", listOf(LlmItem.User("u"))))
            assertNull(body(server.received.single())["fallbacks"])
        }
    }

    @Test
    fun `reads tool calls and text`() = runBlocking {
        LocalServer(replies = listOf(toolReply)).use { server ->
            val reply = client(server).send(LlmRequest("s", listOf(LlmItem.User("u"))))
            assertEquals("speak", reply.toolCalls.single().name)
            assertEquals("Hi", reply.toolCalls.single().input["message"]?.jsonPrimitive?.content)
            assertEquals(20L, reply.inputTokens)
        }
    }

    @Test
    fun `a tool turn sends the thinking back unchanged`() = runBlocking {
        LocalServer(replies = listOf(toolReply, textReply)).use { server ->
            val reply = runToolLoop(client(server), LlmRequest("s", listOf(LlmItem.User("u")), listOf(tool))) {
                ToolOutcome(it.id, "ok")
            }
            assertEquals("Done", reply.text)
            val messages = body(server.received[1]).getValue("messages").jsonArray
            val assistant = messages[1].jsonObject.getValue("content").jsonArray
            assertEquals("thinking", assistant[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("sig123", assistant[0].jsonObject["signature"]?.jsonPrimitive?.content)
            val results = messages[2].jsonObject.getValue("content").jsonArray
            assertEquals("tool_result", results[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("tu_1", results[0].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `notices a refusal`() = runBlocking {
        val refused = textReply.replace("end_turn", "refusal")
        LocalServer(replies = listOf(refused)).use { server ->
            assertTrue(client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))).refused)
        }
    }

    @Test
    fun `explains a rejected key`() {
        val reply = """{"type":"error","error":{"type":"authentication_error","message":"no"}}"""
        LocalServer(status = 401, replies = listOf(reply)).use { server ->
            val error = assertThrows(LlmException::class.java) {
                runBlocking { client(server).send(LlmRequest("s", listOf(LlmItem.User("u")))) }
            }
            assertEquals(LlmFailure.BAD_KEY, error.failure)
        }
    }
}
