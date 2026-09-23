package ink.jvm.chatter

import ink.jvm.chatter.data.BotFrame
import ink.jvm.chatter.data.CallCaption
import ink.jvm.chatter.data.CallInvite
import ink.jvm.chatter.data.CallMedia
import ink.jvm.chatter.data.Hello
import ink.jvm.chatter.data.ChatMessage
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.Frame
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.MediaInfo
import ink.jvm.chatter.data.MsgNew
import ink.jvm.chatter.data.MsgSend
import ink.jvm.chatter.data.PushNotice
import ink.jvm.chatter.data.ProtoJson
import ink.jvm.chatter.data.ReleaseInfo
import ink.jvm.chatter.data.Sync
import ink.jvm.chatter.data.toJson
import ink.jvm.chatter.data.toLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun frames_round_trip_with_t_discriminator() {
        val json = MsgSend("abcdefgh", "text", "hi", replyTo = "q1").toJson()
        assertTrue(json.contains("\"t\":\"msg.send\""))
        assertFalse("defaults are not encoded", json.contains("mediaId"))
        val back = ProtoJson.decodeFromString(Frame.serializer(), json) as MsgSend
        assertEquals("hi", back.text)
        assertEquals("q1", back.replyTo)
    }

    @Test
    fun unknown_fields_are_ignored() {
        val f = ProtoJson.decodeFromString(Frame.serializer(), """{"t":"sync","since":5,"future":1}""") as Sync
        assertEquals(5L, f.since)
    }

    @Test
    fun assistant_call_invite_and_caption_round_trip() {
        val bot = CallInvite("c1", false, bot = true).toJson()
        assertTrue(bot.contains("\"t\":\"call.invite\""))
        assertTrue(bot.contains("\"bot\":true"))
        assertFalse(CallInvite("c2", true).toJson().contains("bot"))
        val cap = ProtoJson.decodeFromString(
            Frame.serializer(),
            """{"t":"call.caption","callId":"c","who":"assistant","text":"我在","state":"final","phase":"speaking","ts":9}""",
        ) as CallCaption
        assertEquals("我在", cap.text)
        assertEquals("assistant", cap.who)
        assertEquals("speaking", cap.phase)
        assertEquals(9L, cap.ts)
    }

    @Test
    fun call_media_defaults_audio_true() {
        val f = ProtoJson.decodeFromString(Frame.serializer(), """{"t":"call.media","callId":"c","video":false}""") as CallMedia
        assertTrue(f.audio)
        assertFalse(f.video)
    }

    @Test
    fun msg_new_maps_to_local_sent() {
        val m = ChatMessage(7, "id1", 2, "video", null, MediaInfo("h", "video/mp4", 10, 640, 360, 4000, "t"), 1000)
        val local = (ProtoJson.decodeFromString(Frame.serializer(), MsgNew(m).toJson()) as MsgNew).msg.toLocal()
        assertEquals(LocalMessage.SENT, local.status)
        assertEquals(4000, local.media?.durationMs)
        assertFalse(local.isControl)
    }

    @Test
    fun control_kinds_are_hidden_and_previewed() {
        assertTrue(LocalMessage("x", 1, 1, "react", "a|❤️|1", null, 0, LocalMessage.SENT).isControl)
        val v = LocalMessage("x", 1, 1, "video", null, null, 0, LocalMessage.SENT)
        assertEquals("[视频]", ChatRepository.previewOf(v))
        val long = LocalMessage("y", 1, 1, "text", "a".repeat(200), null, 0, LocalMessage.SENT)
        assertEquals(121, ChatRepository.previewOf(long).length)
    }

    @Test
    fun offline_notice_is_plain_and_omitted_when_empty() {
        val json = MsgSend("abcdefgh", "text", "cipher", notice = "你好").toJson()
        assertTrue(json.contains("\"notice\":\"你好\""))
        assertFalse(MsgSend("abcdefgh", "text", "cipher").toJson().contains("notice"))
        assertEquals("你好", PushNotice.of("text", "你好"))
        assertEquals("[图片]", PushNotice.of("image", null))
        assertEquals("[图片] 说明", PushNotice.of("image", "说明"))
        assertEquals("阅后即焚", PushNotice.of("image", "说明", once = true))
        assertEquals(null, PushNotice.of("react", "a|❤️|1"))
        val pref = ProtoJson.encodeToString(ink.jvm.chatter.data.PushPref.serializer(), ink.jvm.chatter.data.PushPref(provider = "meow", secret = "nick", intervalSec = 60))
        assertTrue(pref.contains("\"intervalSec\":60"))
    }

    @Test
    fun assistant_frames_and_to_field() {
        val json = MsgSend("abcdefgh", "text", "hi", to = "bot").toJson()
        assertTrue(json.contains("\"to\":\"bot\""))
        val bot = ProtoJson.decodeFromString(Frame.serializer(), """{"t":"bot","id":0,"name":"小助","online":true}""") as BotFrame
        assertEquals("小助", bot.name)
        assertTrue(bot.online)
        val hello = ProtoJson.decodeFromString(Frame.serializer(), """{"t":"hello","connId":"c","version":"1","serverTs":1,"user":{"id":1,"name":"a"},"lastSeq":0,"bot":{"id":0,"name":"助手","online":false}}""") as Hello
        assertEquals("助手", hello.bot?.name)
        val old = ProtoJson.decodeFromString(Frame.serializer(), """{"t":"hello","connId":"c","version":"1","serverTs":1,"user":{"id":1,"name":"a"},"lastSeq":0}""") as Hello
        assertEquals(null, old.bot)
        val m = ChatMessage(7, "id1", 0, "text", "hello", null, 1000).toLocal()
        assertTrue(m.fromBot)
        assertFalse(m.toBot)
        val mine = ChatMessage(8, "id2", 1, "text", "hi", null, 1000, to = "bot").toLocal()
        assertTrue(mine.toBot)
    }

    @Test
    fun release_manifest_parses() {
        val r = ProtoJson.decodeFromString(ReleaseInfo.serializer(), """{"versionCode":14,"versionName":"0.9.0","url":"https://x/a.apk","size":1,"notes":"n"}""")
        assertEquals(14, r.versionCode)
    }
}
