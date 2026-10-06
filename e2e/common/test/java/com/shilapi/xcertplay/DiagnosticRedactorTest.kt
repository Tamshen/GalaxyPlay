package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticRedactorTest {
    @Test fun savedDriveReportKeepsMediaPerformanceCounters() {
        val folder = Files.createTempDirectory("diplay-media-report").toFile()
        try {
            val audio = "audio stats audioType=media codec=AAC_LC rx=215 dropped=0 underruns=+3 queue=2 playing=true maxGapMs=420 sinceRxMs=10 maxWriteMs=22 decoderDroppedTotal=0 outputBuffersTotal=212 ended=true"
            val video = "Video: video stats rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000 recoveries=0 touch2frame avg=85ms max=110ms n=3 touchSendMax=1ms"
            SessionLogFile(folder.resolve("diplay.log")).use {
                it.reset("started")
                it.append(audio)
                it.append(video)
            }
            val report = folder.resolve("diplay.log").readText()
            assertTrue(report.contains(audio))
            assertTrue(report.contains(video))
        } finally { folder.deleteRecursively() }
    }
    @Test fun payloadValuesAreHiddenWithoutDroppingTechnicalContext() {
        for (line in listOf("hotspot passphrase=secret", "token=secret", "rx body={phone: 'secret'}",
            "wifi ssid=secret", "wireless name=secret with spaces", "serial=secret")) {
            val safe = DiagnosticRedactor.redact(line)!!
            assertFalse(safe.contains("secret"))
            assertTrue(safe.contains("[redacted]"))
            assertEquals(safe, DiagnosticRedactor.redact(safe))
        }
        for (line in listOf("TRACE IAP2 tx key", "PHONE packet", "ok\nsecret", "-----BEGIN PRIVATE KEY-----"))
            assertNull(line, DiagnosticRedactor.redact(line))
        assertEquals("certificate bytes=607", DiagnosticRedactor.redact("certificate bytes=607"))
    }

    @Test fun technicalNamesAndParametersSurviveSensitiveFieldMasking() {
        val technical = listOf(
            "name=c2.qti.hevc.decoder profile=2 level=153 width=1440 height=1920 error=0xfffffff4",
            "audio device=BUS00_MEDIA deviceId=5 usage=12 focus=44 sampleRate=48000 channels=2 underruns=3",
            "entry=PERM:android.permission.ACCESS_TOKEN granted=false appOp=ignored",
            "entry=PERM:android.permission.CALL_PHONE result=DENIED",
            "decoderName=OMX.qcom.video.decoder.hevc bypass=false serialExecutor=1 payloadBytes=420 maxGapMs=150",
            "firmware=1.2.3.4 api=30 physical=201x268mm",
        )
        technical.forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }
        val safe = DiagnosticRedactor.redact("stage=mfi token=secret status=failed code=403 elapsedMs=200")!!
        assertEquals("stage=mfi token=[redacted] status=failed code=403 elapsedMs=200", safe)
        val json = DiagnosticRedactor.redact("rx payload={\"nested\":{\"password\":\"secret\"}} codec=HEVC error=-12")!!
        assertEquals("rx payload=[redacted] codec=HEVC error=-12", json)
        assertEquals("peerName=[redacted] stage=connected", DiagnosticRedactor.redact("peerName=\"private phone\" stage=connected"))
        assertFalse(DiagnosticRedactor.redact("server=https://private.example/api path=/data/user/0/private code=401")!!.contains("private"))
    }

    @Test fun longTechnicalLinesHaveAnExplicitMarkerInsteadOfSilentTruncation() {
        val original = "codec config " + "width=1440 ".repeat(600)
        val safe = DiagnosticRedactor.redact(original)!!
        assertEquals(DiagnosticRedactor.MAX_LINE, safe.length)
        assertTrue(safe.endsWith(" [truncated]"))
    }

    @Test fun stateTransitionsSurviveWithoutAddressesOrIdentifiers() {
        val line = DiagnosticRedactor.redact("connected peer=C0:A6:00:29:58:0A ip=192.168.31.71 id=0123456789abcdef0123456789abcdef ipv6=fe80::1234:5678:abcd:9%p2p0")!!
        assertTrue(line.contains("connected"))
        assertFalse(line.contains("C0:A6")); assertFalse(line.contains("192.168")); assertFalse(line.contains("012345")); assertFalse(line.contains("fe80"))
        assertEquals(line, DiagnosticRedactor.redact(line))
        val peer = DiagnosticRedactor.redact("Microphone: start type=telephony codec=OPUS peer=192.168.49.1")!!
        assertEquals("Microphone: start type=telephony codec=OPUS peer=[ip]", peer)
        assertEquals(peer, DiagnosticRedactor.redact(peer))
    }
    @Test fun logRotationIsBoundedAndRedactionHappensBeforeDisk() {
        val folder = Files.createTempDirectory("diplay-log-test").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            log.append("password=secret")
            repeat(1600) { log.append("connection state " + "x".repeat(690)) }
            log.append("CarPlay connected")
            log.close()
            assertTrue(folder.resolve("diplay.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("previous.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("diplay.log").readText().contains("CarPlay connected"))
            assertFalse(folder.listFiles()!!.any { it.readText().contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun failuresSurviveLaterSuccessfulSessionsAndOldestHistoryExpires() {
        val folder = Files.createTempDirectory("diplay-history-test").toFile()
        try {
            repeat(10) { session ->
                SessionLogFile(folder.resolve("diplay.log")).use {
                    it.reset("session=$session")
                    it.append(if (session == 3) "Wi-Fi P2P create rejected code=0" else "CarPlay connected")
                    it.append("password=secret")
                }
            }
            // 报告还包含按需创建的专项日志，本场景只产生连接历史文件。
            val history = SessionLogFile.REPORT_NAMES.mapNotNull { folder.resolve(it).takeIf { file -> file.isFile }?.readText() }
            assertEquals(8, history.size)
            assertEquals(8, folder.listFiles()!!.size)
            assertTrue(history.first().contains("session=2"))
            assertTrue(history.last().contains("session=9"))
            assertTrue(history.any { it.contains("rejected code=0") })
            assertFalse(history.any { it.contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun safeWifiMetadataSurvivesWithoutWeakeningCredentialFilters() {
        val lines = listOf(
            "Wi-Fi P2P preflight wifiEnabled=true locationEnabled=false permissionGranted=true stationMHz=5180",
            "Wi-Fi P2P create mode=FIXED_2_GHZ frequencyMHz=2437",
            "Wi-Fi P2P create rejected code=0 reason=generic error",
            "Wi-Fi P2P ready mode=FIXED_2_GHZ band=2.4 GHz channel=6 frequencyMHz=2437",
            "Wi-Fi P2P channel requestedMHz=2437 actualMHz=2412 matched=false",
            "wireless hotspot backend=Wi-Fi P2P iface=p2p0 host=192.168.49.1 band=5 GHz channel=36 frequency=5180MHz",
        )
        for (line in lines) assertNotNull(line, DiagnosticRedactor.redact(line))
        assertFalse(DiagnosticRedactor.redact(lines.last())!!.contains("192.168.49.1"))
    }
}
